/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.file

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment

class TemporaryUriDeleterTest : RobolectricTest() {
    @Test
    fun `only an exact private camera capture uri can be deleted`() {
        val context = RuntimeEnvironment.getApplication()
        val authority = "${context.packageName}.fileprovider"
        val provider = Robolectric.setupContentProvider(DeleteRecordingContentProvider::class.java, authority)
        val deleter = DefaultTemporaryUriDeleter(context)
        val allowedUri = "content://$authority/camera_capture/capture.jpg".toUri()

        deleter.delete(allowedUri)
        listOf(
            "content://$authority/camera_capture",
            "content://$authority/camera_capture_evil/capture.jpg",
            "content://$authority/cache/photo.jpg",
            "content://$authority/media/image.jpg",
            "content://$authority/outgoing/log.txt",
            "content://$authority/notification_sounds/message.ogg",
            "content://other.app.fileprovider/camera_capture/capture.jpg",
            "https://$authority/camera_capture/capture.jpg",
        ).forEach { deleter.delete(it.toUri()) }

        assertThat(provider.deletedUris).containsExactly(allowedUri)
    }

    private class DeleteRecordingContentProvider : ContentProvider() {
        val deletedUris = mutableListOf<Uri>()

        override fun onCreate() = true

        override fun getType(uri: Uri): String? = null

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletedUris += uri
            return 1
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
