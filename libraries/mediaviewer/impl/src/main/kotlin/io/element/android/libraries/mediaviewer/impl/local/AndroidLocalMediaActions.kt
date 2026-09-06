/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.androidutils.file.saveWithUniqueFileName
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@ContributesBinding(AppScope::class)
class AndroidLocalMediaActions(
    @ApplicationContext private val context: Context,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val buildMeta: BuildMeta,
) : LocalMediaActions {
    private var activityContext: Context? = null

    @Composable
    override fun Configure() {
        val context = LocalContext.current
        return DisposableEffect(Unit) {
            activityContext = context
            onDispose {
                activityContext = null
            }
        }
    }

    override suspend fun saveOnDisk(localMedia: LocalMedia): Result<Unit> = withContext(coroutineDispatchers.io) {
        require(localMedia.uri.scheme == ContentResolver.SCHEME_FILE)
        runCatchingExceptions {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveOnDiskUsingMediaStore(localMedia)
            } else {
                saveOnDiskUsingExternalStorageApi(localMedia)
            }
        }.onSuccess {
            Timber.v("Save on disk succeed")
        }.onFailure {
            Timber.e(it, "Save on disk failed")
        }
    }

    override suspend fun share(localMedia: LocalMedia): Result<Unit> = withContext(coroutineDispatchers.io) {
        require(localMedia.uri.scheme == ContentResolver.SCHEME_FILE)
        runCatchingExceptions {
            // Make a copy of the shared file in the cache directory, otherwise the original file will be gone once this screen is dismissed
            // and will prevent sharing the media to another room inside the app.
            val sourceFile = localMedia.uri.toFile().canonicalFile
            require(sourceFile.isFile) { "Only regular media files can be shared" }
            val shareDirectory = File(context.cacheDir, "temp/media/${UUID.randomUUID()}").apply {
                if (!mkdirs()) error("Unable to create the private media share directory")
            }
            val copiedFile = sourceFile.copyTo(File(shareDirectory, sourceFile.name), overwrite = false)
            val shareableUri = copiedFile.toShareableUri()
            val shareMediaIntent = Intent(Intent.ACTION_SEND)
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, shareableUri)
                .setTypeAndNormalize(localMedia.info.mimeType)
            withContext(coroutineDispatchers.main) {
                val intent = Intent.createChooser(shareMediaIntent, null)
                activityContext!!.startActivity(intent)
            }
        }.onSuccess {
            Timber.v("Share media succeed")
        }.onFailure {
            Timber.e(it, "Share media failed")
        }
    }

    override suspend fun open(localMedia: LocalMedia): Result<Unit> = withContext(coroutineDispatchers.io) {
        require(localMedia.uri.scheme == ContentResolver.SCHEME_FILE)
        runCatchingExceptions {
            if (localMedia.isAndroidPackageFile()) {
                throw SecurityException("Opening Android package files is disabled by SecureChat policy")
            }
            openFile(localMedia)
        }.onSuccess {
            Timber.v("Open media succeed")
        }.onFailure {
            Timber.e(it, "Open media failed")
        }
    }

    private suspend fun openFile(localMedia: LocalMedia) {
        val openMediaIntent = Intent(Intent.ACTION_VIEW)
            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setDataAndType(localMedia.toShareableUri(), localMedia.info.mimeType)
        withContext(coroutineDispatchers.main) {
            activityContext?.startActivity(openMediaIntent)
        }
    }

    private fun File.toShareableUri(): Uri {
        val authority = "${buildMeta.applicationId}.fileprovider"
        return FileProvider.getUriForFile(context, authority, this).normalizeScheme()
    }

    private fun LocalMedia.toShareableUri(): Uri {
        return this.toFile().toShareableUri()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveOnDiskUsingMediaStore(localMedia: LocalMedia) {
        val resolver = context.contentResolver
        val outputUri = saveWithUniqueFileName(localMedia.info.filename) { displayName ->
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, localMedia.info.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } ?: throw IOException("Unable to create the destination file")
        localMedia.openStream()?.use { input ->
            resolver.openOutputStream(outputUri).use { output ->
                input.copyTo(output!!, DEFAULT_BUFFER_SIZE)
            }
        }
    }

    private fun saveOnDiskUsingExternalStorageApi(localMedia: LocalMedia) {
        val target = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            localMedia.info.filename
        )
        localMedia.openStream()?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun LocalMedia.openStream(): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    /**
     * Tries to extract a file from the uri.
     */
    private fun LocalMedia.toFile(): File {
        return uri.toFile()
    }

    private fun LocalMedia.isAndroidPackageFile(): Boolean {
        return info.mimeType.equals(MimeTypes.Apk, ignoreCase = true) ||
            info.fileExtension.equals("apk", ignoreCase = true) ||
            info.filename.endsWith(".apk", ignoreCase = true)
    }
}
