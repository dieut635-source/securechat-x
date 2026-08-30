/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.impl

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.share.api.UriToShare
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.mdm.test.FakeMdmService
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment

class DefaultShareIntentHandlerTest : RobolectricTest() {
    @Test
    fun `an image is shared as an uri`() {
        val uri = "content://sender/image.jpg".toUri()
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(
            aSendIntent(type = "image/jpeg", uri = uri)
        )
        assertThat(result).isEqualTo(
            ShareIntentData.Uris(
                text = null,
                uris = listOf(UriToShare(uri = uri, mimeType = "image/jpeg")),
            )
        )
    }

    @Test
    fun `an uri with an unlisted mime type is shared`() {
        val uri = "content://sender/logs.zip".toUri()
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(
            aSendIntent(type = "message/rfc822", uri = uri)
        )
        assertThat(result).isEqualTo(
            ShareIntentData.Uris(
                text = null,
                uris = listOf(UriToShare(uri = uri, mimeType = "message/rfc822")),
            )
        )
    }

    @Test
    fun `an uri with an unlisted mime type keeps the text as a caption`() {
        val uri = "content://sender/logs.zip".toUri()
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(
            aSendIntent(type = "message/rfc822", uri = uri).apply {
                putExtra(Intent.EXTRA_TEXT, "a caption")
            }
        )
        assertThat(result).isEqualTo(
            ShareIntentData.Uris(
                text = "a caption",
                uris = listOf(UriToShare(uri = uri, mimeType = "message/rfc822")),
            )
        )
    }

    @Test
    fun `several uris with an unlisted mime type are shared`() {
        val firstUri = "content://sender/first.zip".toUri()
        val secondUri = "content://sender/second.zip".toUri()
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "message/rfc822"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(firstUri, secondUri))
        }
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(intent)
        assertThat(result).isEqualTo(
            ShareIntentData.Uris(
                text = null,
                uris = listOf(
                    UriToShare(uri = firstUri, mimeType = "message/rfc822"),
                    UriToShare(uri = secondUri, mimeType = "message/rfc822"),
                ),
            )
        )
    }

    @Test
    fun `a plain text is shared as a text`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "a text")
        }
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(intent)
        assertThat(result).isEqualTo(ShareIntentData.PlainText("a text"))
    }

    @Test
    fun `an intent with neither uri nor text is not handled`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
        }
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(intent)
        assertThat(result).isNull()
    }

    @Test
    fun `an intent without mime type is not handled`() {
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(Intent(Intent.ACTION_SEND))
        assertThat(result).isNull()
    }

    @Test
    fun `an intent with a listed mime type and no uri is handled`() {
        listOf(
            MimeTypes.Jpeg,
            MimeTypes.Mp4,
            MimeTypes.Mp3,
            MimeTypes.Pdf,
            "file/binary",
            "text/html",
            MimeTypes.Any,
        ).forEach { type ->
            val result = createDefaultShareIntentHandler().handleIncomingShareIntent(
                Intent(Intent.ACTION_SEND).apply { this.type = type }
            )
            assertWithMessage("$type was not properly handled").that(result).isEqualTo(
                ShareIntentData.Uris(
                    text = null,
                    uris = emptyList(),
                )
            )
        }
    }

    @Test
    fun `a send multiple intent without uri is handled`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = MimeTypes.Jpeg
        }
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(intent)
        assertThat(result).isEqualTo(
            ShareIntentData.Uris(
                text = null,
                uris = emptyList(),
            )
        )
    }

    @Test
    fun `an uri is ignored when the intent is neither a send nor a send multiple`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = MimeTypes.Jpeg
            putExtra(Intent.EXTRA_STREAM, "content://sender/image.jpg".toUri())
        }
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(intent)
        assertThat(result).isEqualTo(
            ShareIntentData.Uris(
                text = null,
                uris = emptyList(),
            )
        )
    }

    @Test
    fun `an empty text is not kept as a caption`() {
        val uri = "content://sender/logs.zip".toUri()
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(
            aSendIntent(type = "message/rfc822", uri = uri).apply {
                putExtra(Intent.EXTRA_TEXT, "")
            }
        )
        assertThat(result).isEqualTo(
            ShareIntentData.Uris(
                text = null,
                uris = listOf(UriToShare(uri = uri, mimeType = "message/rfc822")),
            )
        )
    }

    @Test
    fun `a plain text intent without text is not handled`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MimeTypes.PlainText
        }
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(intent)
        assertThat(result).isNull()
    }

    @Test
    fun `a plain text intent with an empty text is not handled`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MimeTypes.PlainText
            putExtra(Intent.EXTRA_TEXT, "")
        }
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(intent)
        assertThat(result).isNull()
    }

    @Test
    fun `the mime type of the uri overrides the one of the intent`() {
        Robolectric.setupContentProvider(ZipContentProvider::class.java, "sender")
        val uri = "content://sender/logs.zip".toUri()
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(
            aSendIntent(type = MimeTypes.Any, uri = uri)
        )
        assertThat(result).isEqualTo(
            ShareIntentData.Uris(
                text = null,
                uris = listOf(UriToShare(uri = uri, mimeType = "application/zip")),
            )
        )
    }

    @Test
    fun `the incoming intent is not mutated or redistributed`() {
        val intent = aSendIntent(type = "message/rfc822", uri = "content://sender/logs.zip".toUri())
        createDefaultShareIntentHandler().handleIncomingShareIntent(intent)
        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.component).isNull()
    }

    @Test
    fun `non-content uris are refused`() {
        val result = createDefaultShareIntentHandler().handleIncomingShareIntent(
            aSendIntent(type = "message/rfc822", uri = "file:///data/local/tmp/private.txt".toUri())
        )
        assertThat(result).isNull()
    }

    @Test
    fun `SecureChat-owned FileProvider uris are refused before provider resolution`() {
        val packageName = RuntimeEnvironment.getApplication().packageName
        val authorities = listOf(
            "$packageName.fileprovider",
            "$packageName.notifications.fileprovider",
        )

        authorities.forEach { authority ->
            Robolectric.setupContentProvider(FailOnTypeResolutionContentProvider::class.java, authority)
            val result = createDefaultShareIntentHandler().handleIncomingShareIntent(
                aSendIntent(type = MimeTypes.Any, uri = "content://$authority/private/file.txt".toUri())
            )
            assertThat(result).isNull()
        }
    }

    private fun aSendIntent(type: String, uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        putExtra(Intent.EXTRA_STREAM, uri)
    }

    @Test
    fun `an incoming file is refused when allow_file_send is off`() {
        val handler = createDefaultShareIntentHandler(
            mdmService = FakeMdmService(MdmConfig.default.copy(allowFileSend = false)),
        )
        val result = handler.handleIncomingShareIntent(
            aSendIntent(type = "image/jpeg", uri = "content://sender/image.jpg".toUri())
        )
        assertThat(result).isNull()
    }

    @Test
    fun `plain text can still be shared when allow_file_send is off`() {
        // Sharing text is a message the user could have typed, so the file restriction leaves it alone.
        val handler = createDefaultShareIntentHandler(
            mdmService = FakeMdmService(MdmConfig.default.copy(allowFileSend = false)),
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "a text")
        }
        assertThat(handler.handleIncomingShareIntent(intent)).isEqualTo(ShareIntentData.PlainText("a text"))
    }

    @Test
    fun `a plain text share containing a stream is refused when allow_file_send is off`() {
        val handler = createDefaultShareIntentHandler(
            mdmService = FakeMdmService(MdmConfig.default.copy(allowFileSend = false)),
        )
        val intent = aSendIntent(type = MimeTypes.PlainText, uri = "content://sender/private.txt".toUri()).apply {
            putExtra(Intent.EXTRA_TEXT, "a text")
        }
        assertThat(handler.handleIncomingShareIntent(intent)).isNull()
    }

    @Test
    fun `more than twenty uris are refused`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = MimeTypes.Jpeg
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList((1..21).map { "content://sender/$it.jpg".toUri() }),
            )
        }
        assertThat(createDefaultShareIntentHandler().handleIncomingShareIntent(intent)).isNull()
    }

    private fun createDefaultShareIntentHandler(
        mdmService: MdmService = FakeMdmService(),
    ) = DefaultShareIntentHandler(
        context = RuntimeEnvironment.getApplication(),
        mdmService = mdmService,
    )

    private class ZipContentProvider : ContentProvider() {
        override fun onCreate() = true

        override fun getType(uri: Uri) = "application/zip"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private class FailOnTypeResolutionContentProvider : ContentProvider() {
        override fun onCreate() = true

        override fun getType(uri: Uri): String {
            error("SecureChat-owned provider must not be resolved for an incoming share")
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
