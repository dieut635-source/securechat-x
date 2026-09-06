/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.viewfolder.impl.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID

interface FileShare {
    suspend fun share(
        path: String
    )
}

internal interface FileShareOperations {
    fun getShareableUri(file: File): Uri
    fun launchChooser(shareableUri: Uri)
    fun revokeReadPermission(shareableUri: Uri)
}

private class AndroidFileShareOperations(
    private val context: Context,
    private val buildMeta: BuildMeta,
) : FileShareOperations {
    override fun getShareableUri(file: File): Uri {
        val authority = "${buildMeta.applicationId}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file).normalizeScheme()
    }

    override fun launchChooser(shareableUri: Uri) {
        val shareMediaIntent = Intent(Intent.ACTION_SEND)
            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_STREAM, shareableUri)
            .setTypeAndNormalize(MimeTypes.OctetStream)
        val intent = Intent.createChooser(shareMediaIntent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun revokeReadPermission(shareableUri: Uri) {
        context.revokeUriPermission(shareableUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultFileShare(
    @ApplicationContext private val context: Context,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val buildMeta: BuildMeta,
) : FileShare {
    private val stagingMutex = Mutex()

    internal var operations: FileShareOperations = AndroidFileShareOperations(context, buildMeta)

    override suspend fun share(
        path: String,
    ) {
        var stagedFile: File? = null
        var shareableUri: Uri? = null
        var cleanupOwnershipTransferred = false
        try {
            runCatchingExceptions {
                val preparedFile = withContext(dispatchers.io) {
                    // Assign before leaving the IO context. If cancellation arrives while the
                    // blocking copy is completing, the caller's finally block still owns it.
                    stageForSharing(File(path)).also { stagedFile = it }
                }
                val preparedUri = operations.getShareableUri(preparedFile)
                shareableUri = preparedUri
                withContext(dispatchers.main) {
                    operations.launchChooser(preparedUri)
                }
                markShareActive(preparedFile)
                scheduleCleanup(preparedFile, preparedUri)
                // scheduleCleanup starts undispatched and reaches its guarded delay before
                // returning. There is no suspension between this handoff and the flag update.
                cleanupOwnershipTransferred = true
            }.onSuccess {
                Timber.v("Share file succeed")
            }.onFailure {
                Timber.e(it, "Share file failed")
            }
        } finally {
            if (!cleanupOwnershipTransferred) {
                withContext(NonCancellable + dispatchers.io) {
                    stagedFile?.let { file ->
                        runCatchingExceptions { cleanupStagedShare(file, shareableUri) }
                            .onFailure { Timber.w(it, "Unable to clean up a failed outgoing share") }
                    }
                }
            }
        }
    }

    internal suspend fun stageForSharing(source: File): File = stagingMutex.withLock {
        val canonicalSource = source.canonicalFile
        require(canonicalSource.isFile) { "Only regular files can be shared" }

        val outgoingRoot = getOrCreateOutgoingRoot()
        cleanupExpiredSharesLocked(outgoingRoot, System.currentTimeMillis())
        check(outgoingRoot.listFiles().orEmpty().count { it.isDirectory } < MAX_ACTIVE_SHARES) {
            "Too many outgoing shares are still active"
        }
        require(canonicalSource.length() <= MAX_STAGED_FILE_BYTES) {
            "File exceeds the SecureChat diagnostic-sharing size limit"
        }
        val stagingDirectory = File(outgoingRoot, UUID.randomUUID().toString()).canonicalFile
        check(stagingDirectory.parentFile == outgoingRoot) { "Outgoing share directory escaped its private root" }
        if (!stagingDirectory.mkdir()) {
            error("Unable to create an isolated outgoing share directory")
        }

        var copyCompleted = false
        try {
            File(stagingDirectory, canonicalSource.name).canonicalFile.also { destination ->
                check(destination.parentFile == stagingDirectory) { "Outgoing share file escaped its isolated directory" }
                copyWithLimit(canonicalSource, destination)
                copyCompleted = true
            }
        } finally {
            if (!copyCompleted) {
                deleteDirectoryBestEffort(stagingDirectory, "incomplete outgoing share directory")
            }
        }
    }

    private suspend fun copyWithLimit(source: File, destination: File) {
        var copiedBytes = 0L
        source.inputStream().buffered().use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    copiedBytes += read
                    check(copiedBytes <= MAX_STAGED_FILE_BYTES) {
                        "File grew beyond the SecureChat diagnostic-sharing size limit"
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    internal suspend fun cleanupExpiredShares(outgoingRoot: File, nowMillis: Long) = stagingMutex.withLock {
        cleanupExpiredSharesLocked(outgoingRoot.canonicalFile, nowMillis)
    }

    private fun cleanupExpiredSharesLocked(outgoingRoot: File, nowMillis: Long) {
        outgoingRoot.listFiles().orEmpty()
            .filter { it.isDirectory && nowMillis - it.lastModified() >= STAGED_SHARE_TTL_MILLIS }
            .forEach { directory ->
                directory.listFiles().orEmpty()
                    .filter(File::isFile)
                    .forEach { stagedFile ->
                        val shareableUri = try {
                            operations.getShareableUri(stagedFile)
                        } catch (failure: Exception) {
                            Timber.w(failure, "Unable to resolve an expired outgoing share URI")
                            null
                        }
                        shareableUri?.let(::revokeReadPermissionBestEffort)
                    }
                deleteDirectoryBestEffort(directory, "expired outgoing share directory")
            }
    }

    private suspend fun markShareActive(stagedFile: File) = withContext(dispatchers.io) {
        stagingMutex.withLock {
            val stagingDirectory = requireStagingDirectory(stagedFile)
            // Persist the handoff time so a later process instance can enforce the same TTL during
            // its next sweep, after this process's in-memory cleanup job no longer exists.
            check(stagingDirectory.setLastModified(System.currentTimeMillis())) {
                "Unable to record outgoing share expiry time"
            }
        }
    }

    private fun scheduleCleanup(stagedFile: File, shareableUri: Uri) {
        coroutineScope.launch(dispatchers.io, start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(STAGED_SHARE_TTL_MILLIS)
            } finally {
                withContext(NonCancellable + dispatchers.io) {
                    runCatchingExceptions { cleanupStagedShare(stagedFile, shareableUri) }
                        .onFailure { Timber.w(it, "Unable to clean up an outgoing share") }
                }
            }
        }
    }

    internal suspend fun cleanupStagedShare(stagedFile: File, shareableUri: Uri?) = stagingMutex.withLock {
        shareableUri?.let(::revokeReadPermissionBestEffort)
        val stagingDirectory = requireStagingDirectory(stagedFile)
        deleteDirectoryBestEffort(stagingDirectory, "outgoing share directory")
    }

    private fun revokeReadPermissionBestEffort(shareableUri: Uri) {
        try {
            operations.revokeReadPermission(shareableUri)
        } catch (failure: Exception) {
            // Permission revocation is best effort; it must never skip deletion of the staged file.
            Timber.w(failure, "Unable to revoke an outgoing share URI permission")
        }
    }

    private fun deleteDirectoryBestEffort(directory: File, description: String) {
        try {
            if (!directory.deleteRecursively()) {
                Timber.w("Unable to delete $description")
            }
        } catch (failure: Exception) {
            Timber.w(failure, "Unable to delete $description")
        }
    }

    private fun getOrCreateOutgoingRoot(): File {
        return File(context.cacheDir, OUTGOING_DIRECTORY).canonicalFile.apply {
            if (!exists() && !mkdirs()) {
                error("Unable to create the private outgoing share directory")
            }
            check(isDirectory) { "Outgoing share root is not a directory" }
        }
    }

    private fun requireStagingDirectory(stagedFile: File): File {
        val outgoingRoot = File(context.cacheDir, OUTGOING_DIRECTORY).canonicalFile
        val stagingDirectory = requireNotNull(stagedFile.canonicalFile.parentFile) {
            "Outgoing share has no staging directory"
        }
        check(stagingDirectory.parentFile == outgoingRoot) {
            "Outgoing share directory escaped its private root"
        }
        return stagingDirectory
    }

    internal companion object {
        const val OUTGOING_DIRECTORY = "temp/outgoing"
        const val MAX_STAGED_FILE_BYTES = 25L * 1024L * 1024L
        const val MAX_ACTIVE_SHARES = 4
        const val STAGED_SHARE_TTL_MILLIS = 60L * 60L * 1000L
    }
}
