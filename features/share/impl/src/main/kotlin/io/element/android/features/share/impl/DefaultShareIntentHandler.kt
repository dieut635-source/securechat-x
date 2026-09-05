/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.impl

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.share.api.ShareIntentHandler
import io.element.android.features.share.api.UriToShare
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeAny
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeApplication
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeAudio
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeFile
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeImage
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeText
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.mdm.api.MdmService
import timber.log.Timber

@ContributesBinding(AppScope::class)
class DefaultShareIntentHandler(
    @ApplicationContext private val context: Context,
    private val mdmService: MdmService,
) : ShareIntentHandler {
    override fun handleIncomingShareIntent(
        intent: Intent,
    ): ShareIntentData? {
        if (!mdmService.config.value.allowFileSend) {
            val isPlainTextOnly = intent.action == Intent.ACTION_SEND &&
                intent.type == MimeTypes.PlainText &&
                !intent.hasExtra(Intent.EXTRA_STREAM) &&
                intent.clipData?.let { clipData ->
                    (0 until clipData.itemCount).none { clipData.getItemAt(it).uri != null }
                } != false
            if (!isPlainTextOnly) {
                // Enforce this before unmarshalling URI arrays, resolving providers, or looking up
                // MIME types. A rejected share must have no URI-related side effects.
                Timber.i("Incoming share ignored: sending files is disabled by the managed configuration")
                return null
            }
            // Return before resolveType(), which may contact a content provider when the sender
            // omitted an explicit MIME type.
            return handlePlainText(intent)
        }

        val type = intent.resolveType(context) ?: return null
        val uris = getIncomingUris(intent, type) ?: return null
        return when {
            uris.isEmpty() && type == MimeTypes.PlainText -> handlePlainText(intent)
            uris.isNotEmpty() ||
                type.isMimeTypeImage() ||
                type.isMimeTypeVideo() ||
                type.isMimeTypeAudio() ||
                type.isMimeTypeApplication() ||
                type.isMimeTypeFile() ||
                type.isMimeTypeText() ||
                type.isMimeTypeAny() -> {
                ShareIntentData.Uris(
                    text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotEmpty() },
                    uris = uris,
                )
            }
            else -> null
        }
    }

    private fun handlePlainText(intent: Intent): ShareIntentData.PlainText? {
        val content = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        return if (content?.isNotEmpty() == true) {
            ShareIntentData.PlainText(content)
        } else {
            null
        }
    }

    /**
     * Use this function to retrieve files which are shared from another application or internally
     * by using android.intent.action.SEND or android.intent.action.SEND_MULTIPLE actions.
     */
    private fun getIncomingUris(intent: Intent, fallbackMimeType: String): List<UriToShare>? {
        val uriList = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { uriList.add(it) }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { uriList.addAll(it) }
        }

        if (uriList.size > MAX_SHARED_URI_COUNT) {
            Timber.w("Incoming share ignored: too many URIs (${uriList.size})")
            return null
        }
        if (uriList.any { it.scheme != ContentResolver.SCHEME_CONTENT }) {
            Timber.w("Incoming share ignored: only content URIs are accepted")
            return null
        }
        val internalFileProviderAuthorities = setOf(
            "${context.packageName}.fileprovider",
            "${context.packageName}.notifications.fileprovider",
        )
        if (uriList.any { it.authority in internalFileProviderAuthorities }) {
            // An external app can forge the text of a URI owned by SecureChat without receiving a
            // grant. If SecureChat resolved that URI itself, its own provider privileges would turn
            // the app into a confused deputy and could expose private cached data.
            Timber.w("Incoming share ignored: SecureChat-owned FileProvider URI")
            return null
        }

        // The sending application grants SecureChat temporary read access through the incoming
        // intent. Never redistribute that grant or mutate the caller-owned intent.
        return uriList.map { uri ->
            // The value in fallbackMimeType can be wrong, especially if several uris were received
            // in the same intent (i.e. 'image/*'). We need to check the mime type of each uri.
            val mimeType = try {
                context.contentResolver.getType(uri) ?: fallbackMimeType
            } catch (exception: SecurityException) {
                Timber.w(exception, "Incoming share ignored: URI permission denied")
                return null
            }
            UriToShare(
                uri = uri,
                mimeType = mimeType,
            )
        }
    }

    private companion object {
        const val MAX_SHARED_URI_COUNT = 20
    }
}
