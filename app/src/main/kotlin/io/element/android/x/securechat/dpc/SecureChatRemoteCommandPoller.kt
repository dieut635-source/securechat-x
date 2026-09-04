/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Asks the homeserver whether this handset has been ordered wiped.
 *
 * **The handset asks; the server never pushes.** Push was considered and rejected on evidence: the
 * self-hosted ntfy on this deployment accepts anonymous publishes to any topic, so anyone who
 * guessed a topic name could erase somebody's phone. A wipe channel may not have that property.
 *
 * Asking instead means the request carries this session's own Matrix access token, over the
 * certificate-pinned homeserver, and the server answers only for the device that token belongs to.
 * No new key has to be minted or distributed, and a forged message achieves nothing: an attacker
 * would have to hold this device's token or own the server.
 *
 * **Residual risk, stated plainly:** owning the server means being able to wipe the fleet. Removing
 * that would need commands signed by a key the server does not hold, which is a key-management
 * system this project does not have yet. It is recorded as security debt rather than pretended away.
 */
@SingleIn(AppScope::class)
@Inject
class SecureChatRemoteCommandPoller(
    private val sessionStore: SessionStore,
    private val deviceOwner: SecureChatDeviceOwner,
    private val mdmService: MdmService,
    private val okHttpClient: OkHttpClient,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
) {
    fun start() {
        coroutineScope.launch {
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal suspend fun pollOnce() {
        // Nothing to obey on a handset this app does not manage: without device owner the wipe
        // would be refused anyway, and polling would only leak that the app is running.
        if (!deviceOwner.isDeviceOwner) return

        val sessions = runCatchingExceptions { sessionStore.getAllSessions() }.getOrElse {
            Timber.w(it, "Command poll: could not read sessions")
            return
        }
        for (session in sessions.filter { it.isTokenValid }) {
            when (val command = fetchCommand(session.homeserverUrl, session.accessToken)) {
                null -> Unit
                COMMAND_WIPE_DEVICE -> executeWipe(session.deviceId)
                // An order this build does not understand is not an order to improvise.
                else -> Timber.w("Command poll: ignoring unknown command '$command'")
            }
        }
    }

    private suspend fun fetchCommand(homeserverUrl: String, accessToken: String): String? {
        // Only the homeserver the administrator configured may give orders, and "configured" means
        // an exact match: scheme, host and port.
        //
        // Without it, any homeserver a user happened to sign into could erase a company handset.
        //
        // There is deliberately no separate https check here. MdmConfigParser already refuses a
        // homeserver_url that is not https, so an exact match inherits that guarantee from the one
        // place it is created and tested. Repeating it here made every test in this class pass for
        // the wrong reason: MockWebServer serves http, so all eight refused on the scheme and none
        // of them exercised the rule they claimed to. A duplicated guarantee that hides what the
        // tests actually prove is worse than a single one.
        val allowed = mdmService.config.value.homeserverUrl
        if (!sameOrigin(homeserverUrl, allowed)) {
            Timber.w("Command poll: refusing to take orders from a homeserver that is not the managed one")
            return null
        }
        val base = homeserverUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$base$COMMAND_PATH")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            runCatchingExceptions {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // A server that is down, or a token the server has revoked. Neither is an
                        // instruction to do anything, and neither is worth alarming about: this
                        // runs on a timer and will ask again.
                        return@use null
                    }
                    val body = response.body?.string().orEmpty()
                    // kotlinx.serialization, KHÔNG phải org.json: org.json trên classpath unit
                    // test của Android là stub và ném lỗi ở mọi lệnh gọi. Dùng nó ở đây làm MỌI
                    // test trong lớp này xanh vì lý do sai — chúng "từ chối" do ngoại lệ chứ
                    // không do quy tắc đang được kiểm. Lần thứ hai trong cùng một lớp.
                    val parsed = JSON.parseToJsonElement(body).jsonObject["command"]
                    parsed?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                }
            }.getOrElse {
                Timber.w(it, "Command poll: request failed")
                null
            }
        }
    }

    private fun executeWipe(deviceId: String) {
        val reason = "remote wipe ordered by the administrator for device $deviceId"
        // Straight through DeviceWipeAuthority, exactly like any other caller. The channel being
        // authenticated does not earn it a shortcut: arm, then spend, or nothing happens.
        val challenge = deviceOwner.armWipe(reason).getOrElse { throwable ->
            Timber.w(throwable, "Remote wipe refused before it began")
            return
        }
        deviceOwner.wipeDevice(challenge, reason)
            .onFailure { Timber.w(it, "Remote wipe refused") }
    }

    /** Scheme, host and port must all match. A host match alone would accept http on port 80. */
    private fun sameOrigin(a: String, b: String): Boolean = runCatchingExceptions {
        val left = java.net.URI(a.trimEnd('/'))
        val right = java.net.URI(b.trimEnd('/'))
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            left.port == right.port
    }.getOrDefault(false)

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        const val COMMAND_PATH = "/_securechat/command"
        const val COMMAND_WIPE_DEVICE = "wipe_device"

        /**
         * Five minutes. A wipe is ordered because a handset is lost or seized, so the delay that
         * matters is measured against how long it takes a person to act on it - not against
         * milliseconds. Polling harder would drain batteries across the fleet for no real gain.
         */
        const val POLL_INTERVAL_MS = 5 * 60 * 1000L
    }
}
