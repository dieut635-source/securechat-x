/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.security

import android.app.Application
import androidx.core.content.FileProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@Config(application = Application::class)
class FileProviderScopeTest : RobolectricTest() {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val mainAuthority = "${context.packageName}.fileprovider"
    private val notificationsAuthority = "${context.packageName}.notifications.fileprovider"

    @Test
    fun `providers expose only their explicit outbound directories`() {
        val allowedFiles = mapOf(
            fileInCache("temp/camera/${uniqueName()}.jpg") to "camera_capture",
            fileInCache("temp/media/${uniqueName()}/image.jpg") to "media",
            fileInCache("temp/outgoing/${uniqueName()}/log.txt") to "outgoing",
            fileInFiles("notification_sounds/message.ogg") to "notification_sounds",
        )

        allowedFiles.forEach { (file, expectedRoot) ->
            val uri = FileProvider.getUriForFile(context, mainAuthority, file)
            assertThat(uri.pathSegments.first()).isEqualTo(expectedRoot)
        }

        // Keep allowed and rejected checks in one Robolectric test. AndroidX FileProvider caches
        // canonical roots by authority, while Robolectric gives each test a distinct dataDir.
        val rejectedFiles = listOf(
            fileInCache("logs/logcat.log"),
            fileInCache("${uniqueName()}/matrix-sdk-event-cache/cache.db"),
            fileInCache("uploads/upload.bin"),
            fileInCache("edited-media/image.jpg"),
            fileInCache("temp/fileviewer/debug.json"),
            fileInCache("temp/notif/image.jpg"),
            fileInCache("temp/voice/message.ogg"),
            fileInFiles("sessions/${uniqueName()}/matrix-sdk-state/state.db"),
        )

        rejectedFiles.forEach { file ->
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, mainAuthority, file)
            }
        }

        val notificationFile = fileInCache("temp/notif/${uniqueName()}/image.jpg")
        val uri = FileProvider.getUriForFile(context, notificationsAuthority, notificationFile)
        assertThat(uri.pathSegments.first()).isEqualTo("downloads")

        listOf(
            fileInCache("temp/media/image.jpg"),
            fileInCache("logs/logcat.log"),
            fileInFiles("notification_sounds/message.ogg"),
        ).forEach { file ->
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, notificationsAuthority, file)
            }
        }
    }

    private fun fileInCache(relativePath: String): File = createFile(context.cacheDir, relativePath)

    private fun fileInFiles(relativePath: String): File = createFile(context.filesDir, relativePath)

    private fun createFile(root: File, relativePath: String): File {
        return File(root, relativePath).apply {
            parentFile?.mkdirs()
            writeText("test")
        }
    }

    private fun uniqueName(): String = UUID.randomUUID().toString()
}
