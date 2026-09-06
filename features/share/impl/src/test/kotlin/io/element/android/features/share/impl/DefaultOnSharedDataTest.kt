/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.share.impl

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.share.api.UriToShare
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment

class DefaultOnSharedDataTest : RobolectricTest() {
    @Test
    fun `post-processing never deletes a caller-provided SecureChat uri`() {
        val context = RuntimeEnvironment.getApplication()
        val authority = "${context.packageName}.fileprovider"
        val provider = Robolectric.setupContentProvider(DeleteRecordingContentProvider::class.java, authority)
        val uri = "content://$authority/camera_capture/forged.jpg".toUri()

        DefaultOnSharedData(context).invoke(
            ShareIntentData.Uris(
                text = null,
                uris = listOf(UriToShare(uri, "image/jpeg")),
            )
        )

        assertThat(provider.deletedUris).isEmpty()
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
