/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.element.android.features.call.impl.BuildConfig
import io.element.android.libraries.core.data.tryOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.net.URI

class WebViewWidgetMessageInterceptor(
    private val webView: WebView,
    private val onUrlLoaded: (String) -> Unit,
    private val onError: (String?) -> Unit,
) : WidgetMessageInterceptor {
    companion object {
        // Both bridge implementations expose the same JavaScript API, although only one is
        // registered for a given WebView.
        const val LISTENER_NAME = "elementX"
    }

    // It's important to have extra capacity here to make sure we don't drop any messages
    override val interceptedMessages = MutableSharedFlow<String>(extraBufferCapacity = 10)

    init {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(webView.context))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url != ABOUT_BLANK_URL && !isSecureChatCallDocumentUri(url)) {
                    view.stopLoading()
                    onError(UNTRUSTED_CALL_DOCUMENT_ERROR)
                    return
                }
                super.onPageStarted(view, url, favicon)

                // Due to https://github.com/element-hq/element-x-android/issues/4097
                // we need to supply a logging implementation that correctly includes
                // objects in log lines.
                view.evaluateJavascript(
                    """
                        function logFn(consoleLogFn, ...args) {
                            consoleLogFn(
                                args.map(
                                    a => typeof a === "string" ? a : JSON.stringify(a)
                                ).join(' ')
                            );
                        };
                        globalThis.console.debug = logFn.bind(null, console.debug);
                        globalThis.console.log = logFn.bind(null, console.log);
                        globalThis.console.info = logFn.bind(null, console.info);
                        globalThis.console.warn = logFn.bind(null, console.warn);
                        globalThis.console.error = logFn.bind(null, console.error);
                    """.trimIndent(),
                    null
                )

                // We inject this JS code when the page starts loading to attach a message listener to the window.
                // This listener will receive both messages:
                // - EC widget API -> Element X (message.data.api == "fromWidget")
                // - Element X -> EC widget API (message.data.api == "toWidget"), we should ignore these
                view.evaluateJavascript(
                    """
                        window.addEventListener('message', function(event) {
                            if (event.source !== window || event.origin !== '$SECURECHAT_CALL_ORIGIN') return;
                            const data = event.data;
                            if (data !== null && typeof data === 'object' &&
                                ((data.response && data.api == "toWidget") ||
                                (!data.response && data.api == "fromWidget"))) {
                                let json = JSON.stringify(data)
                                ${"console.log('message sent: ' + json);".takeIf { BuildConfig.DEBUG }}
                                $LISTENER_NAME.postMessage(json);
                            } else {
                                ${"console.log('message received (ignored): ' + JSON.stringify(data));".takeIf { BuildConfig.DEBUG }}
                            }
                        });
                    """.trimIndent(),
                    null
                )
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isSecureChatCallDocumentUri(url)) {
                    onUrlLoaded(url)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                return request.isForMainFrame && !isSecureChatCallDocumentUri(request.url.toString())
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean {
                return !isSecureChatCallDocumentUri(url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // No network for instance, transmit the error
                Timber.e("onReceivedError error: ${error?.errorCode} ${error?.description}")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == request?.url.toString()) {
                    onError(error?.description.toString())
                }

                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                Timber.e("onReceivedHttpError error: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase}")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == request?.url.toString()) {
                    onError(errorResponse?.statusCode.toString())
                }

                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Timber.e("onReceivedSslError error: ${error?.primaryError}")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == error?.url.toString()) {
                    onError(error?.toString())
                }

                super.onReceivedSslError(view, handler, error)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val assetResponse = assetLoader.shouldInterceptRequest(request.url)
                return when {
                    assetResponse != null -> assetResponse
                    isAllowedSecureChatCallSubresourceUri(request.url.toString()) -> null
                    else -> blockedCallNetworkResponse()
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String): WebResourceResponse? {
                val assetResponse = assetLoader.shouldInterceptRequest(url.toUri())
                return when {
                    assetResponse != null -> assetResponse
                    isAllowedSecureChatCallSubresourceUri(url) -> null
                    else -> blockedCallNetworkResponse()
                }
            }
        }

        // Prefer the origin-scoped WebMessageListener on WebViews that reliably support it.
        // Huawei WebView (Chromium < 119) reports WEB_MESSAGE_LISTENER as supported
        // but silently drops messages, so it still needs the legacy fallback below.
        // See: https://github.com/element-hq/element-x-android/issues/6632
        val webViewVersionName = WebViewCompat.getCurrentWebViewPackage(webView.context)?.versionName.orEmpty()
        Timber.d("Using WebView version: $webViewVersionName")
        val webViewVersionCode = webViewVersionName.split(".").firstOrNull()?.toIntOrNull() ?: 0

        if (webViewVersionCode >= 119 &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                LISTENER_NAME,
                setOf(SECURECHAT_CALL_ORIGIN),
                WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, _ ->
                    if (isMainFrame && isSecureChatCallOrigin(sourceOrigin.toString())) {
                        onMessageReceived(message.data)
                    }
                }
            )
        } else {
            // addJavascriptInterface has no frame-level origin enforcement. Keep it only as the
            // compatibility path for WebViews on which the origin-scoped channel is unavailable.
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun postMessage(json: String?) {
                    onMessageReceived(json)
                }
            }, LISTENER_NAME)
        }
    }

    override fun sendMessage(message: String) {
        webView.evaluateJavascript(secureChatCallPostMessageJavascript(message), null)
    }

    private fun onMessageReceived(json: String?) {
        // Here is where we would handle the messages from the WebView, passing them to the Rust SDK
        json?.let { interceptedMessages.tryEmit(it) }
    }
}

internal fun isSecureChatCallOrigin(uriString: String): Boolean {
    val uri = tryOrNull { URI(uriString) } ?: return false
    return uri.scheme == SECURECHAT_CALL_SCHEME &&
        uri.host == SECURECHAT_CALL_HOST &&
        uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == HTTPS_DEFAULT_PORT) &&
        uri.rawAuthority in SECURECHAT_CALL_AUTHORITIES
}

internal fun isSecureChatCallDocumentUri(uriString: String): Boolean {
    if (!isSecureChatCallOrigin(uriString)) return false
    return URI(uriString).rawPath == SECURECHAT_CALL_DOCUMENT_PATH
}

/**
 * The embedded call application may contact only the managed SecureChat deployment. Local
 * appassets are served before this policy is evaluated. All public CDNs, analytics endpoints,
 * captcha services, and future unreviewed call backends therefore fail closed.
 */
internal fun isAllowedSecureChatCallNetworkUri(uriString: String): Boolean {
    val uri = tryOrNull { URI(uriString) } ?: return false
    return uri.scheme == SECURECHAT_CALL_SCHEME &&
        uri.host == SECURECHAT_SERVER_HOST &&
        uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == HTTPS_DEFAULT_PORT) &&
        uri.rawAuthority in SECURECHAT_SERVER_AUTHORITIES
}

internal fun isAllowedSecureChatCallSubresourceUri(uriString: String): Boolean {
    if (isAllowedSecureChatCallNetworkUri(uriString)) return true
    val uri = tryOrNull { URI(uriString) } ?: return false
    return when (uri.scheme) {
        DATA_SCHEME -> uri.rawAuthority == null
        BLOB_SCHEME -> isSecureChatCallOrigin(uri.rawSchemeSpecificPart)
        else -> false
    }
}

internal fun secureChatCallPostMessageJavascript(message: String): String {
    val encodedMessage = Json.encodeToString(message)
    return "window.postMessage(JSON.parse($encodedMessage), '$SECURECHAT_CALL_ORIGIN')"
}

private const val SECURECHAT_CALL_SCHEME = "https"
private const val SECURECHAT_CALL_HOST = "appassets.androidplatform.net"
private const val SECURECHAT_SERVER_HOST = "chat.securechat.com.au"
private const val DATA_SCHEME = "data"
private const val BLOB_SCHEME = "blob"
private const val SECURECHAT_CALL_ORIGIN = "$SECURECHAT_CALL_SCHEME://$SECURECHAT_CALL_HOST"
private const val SECURECHAT_CALL_DOCUMENT_PATH = "/securechat-call/index.html"
private const val HTTPS_DEFAULT_PORT = 443
private const val ABOUT_BLANK_URL = "about:blank"
private const val UNTRUSTED_CALL_DOCUMENT_ERROR = "Blocked an untrusted call document."
private val SECURECHAT_CALL_AUTHORITIES = setOf(SECURECHAT_CALL_HOST, "$SECURECHAT_CALL_HOST:$HTTPS_DEFAULT_PORT")
private val SECURECHAT_SERVER_AUTHORITIES = setOf(SECURECHAT_SERVER_HOST, "$SECURECHAT_SERVER_HOST:$HTTPS_DEFAULT_PORT")

private fun blockedCallNetworkResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    Charsets.UTF_8.name(),
    403,
    "Forbidden",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0)),
)
