/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.viewfolder.impl.file

import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class DefaultFileShareTest : RobolectricTest() {
    @Before
    fun clearOutgoingShares() {
        val context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, DefaultFileShare.OUTGOING_DIRECTORY).deleteRecursively()
    }

    @Test
    fun `staging a log creates an isolated byte-identical outgoing copy`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "logs/${UUID.randomUUID()}/securechat.log").apply {
            parentFile?.mkdirs()
            writeText("private diagnostic data")
        }
        val sut = createSut(context)

        val staged = sut.stageForSharing(source)

        val outgoingRoot = File(context.cacheDir, "temp/outgoing").canonicalFile
        assertThat(staged.parentFile?.parentFile).isEqualTo(outgoingRoot)
        assertThat(staged.name).isEqualTo(source.name)
        assertThat(staged.readBytes()).isEqualTo(source.readBytes())
        assertThat(source.exists()).isTrue()
    }

    @Test
    fun `staging refuses a directory`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "logs/${UUID.randomUUID()}").apply { mkdirs() }
        val sut = createSut(context)

        val failure = runCatchingExceptions {
            sut.stageForSharing(directory)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `staging refuses a file larger than the bounded diagnostic share limit`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "logs/${UUID.randomUUID()}/oversized.log").apply {
            parentFile?.mkdirs()
        }
        RandomAccessFile(source, "rw").use { file ->
            file.setLength(DefaultFileShare.MAX_STAGED_FILE_BYTES + 1)
        }
        val sut = createSut(context)

        val failure = runCatchingExceptions {
            sut.stageForSharing(source)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(File(context.cacheDir, DefaultFileShare.OUTGOING_DIRECTORY).listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `staging deletes expired shares despite revoke failures before enforcing the active share bound`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val outgoingRoot = File(context.cacheDir, DefaultFileShare.OUTGOING_DIRECTORY).apply {
            deleteRecursively()
            mkdirs()
        }
        repeat(DefaultFileShare.MAX_ACTIVE_SHARES) { index ->
            File(outgoingRoot, "expired-$index").apply {
                mkdirs()
                File(this, "old.log").writeText("expired")
                setLastModified(1L)
            }
        }
        val source = File(context.cacheDir, "logs/${UUID.randomUUID()}/securechat.log").apply {
            parentFile?.mkdirs()
            writeText("current")
        }
        val operations = FakeFileShareOperations().apply {
            revokeFailure = IllegalStateException("revocation unavailable after restart")
        }
        val sut = createSut(context, operations)

        val staged = sut.stageForSharing(source)

        assertThat(outgoingRoot.listFiles().orEmpty().map(File::getCanonicalFile))
            .containsExactly(staged.parentFile?.canonicalFile)
        assertThat(operations.revokedUris).hasSize(DefaultFileShare.MAX_ACTIVE_SHARES)
    }

    @Test
    fun `staging fails closed when the maximum number of unexpired shares is active`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val outgoingRoot = File(context.cacheDir, DefaultFileShare.OUTGOING_DIRECTORY).apply {
            deleteRecursively()
            mkdirs()
        }
        repeat(DefaultFileShare.MAX_ACTIVE_SHARES) { index ->
            File(outgoingRoot, "active-$index").apply {
                mkdirs()
                setLastModified(System.currentTimeMillis())
            }
        }
        val source = File(context.cacheDir, "logs/${UUID.randomUUID()}/securechat.log").apply {
            parentFile?.mkdirs()
            writeText("current")
        }
        val sut = createSut(context)

        val failure = runCatchingExceptions {
            sut.stageForSharing(source)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(outgoingRoot.listFiles().orEmpty().asList()).hasSize(DefaultFileShare.MAX_ACTIVE_SHARES)
    }

    @Test
    fun `concurrent staging never exceeds the active share bound`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val source = createSource(context, "concurrent")
        val sut = createSut(context)

        val results = List(DefaultFileShare.MAX_ACTIVE_SHARES + 3) {
            async(Dispatchers.Default) {
                runCatchingExceptions { sut.stageForSharing(source) }
            }
        }.awaitAll()

        assertThat(results.count { it.isSuccess }).isEqualTo(DefaultFileShare.MAX_ACTIVE_SHARES)
        assertThat(outgoingDirectories(context)).hasSize(DefaultFileShare.MAX_ACTIVE_SHARES)
    }

    @Test
    fun `successful chooser launch transfers cleanup ownership until expiry`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val source = createSource(context, "successful share")
        val operations = FakeFileShareOperations()
        val sut = createSut(context, operations)

        sut.share(source.path)

        assertThat(outgoingDirectories(context)).hasSize(1)
        assertThat(operations.launchedUris).hasSize(1)

        advanceTimeBy(DefaultFileShare.STAGED_SHARE_TTL_MILLIS - 1)
        runCurrent()
        assertThat(outgoingDirectories(context)).hasSize(1)

        advanceTimeBy(1)
        runCurrent()
        assertThat(outgoingDirectories(context)).isEmpty()
        assertThat(operations.revokedUris).containsExactlyElementsIn(operations.launchedUris)
    }

    @Test
    fun `cancellation during chooser launch deletes caller-owned staging`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val source = createSource(context, "cancelled share")
        val operations = FakeFileShareOperations()
        val sut = createSut(context, operations)
        lateinit var shareJob: Job
        operations.onLaunch = { shareJob.cancel() }

        shareJob = launch(start = CoroutineStart.LAZY) {
            sut.share(source.path)
        }
        shareJob.start()
        shareJob.join()

        assertThat(shareJob.isCancelled).isTrue()
        assertThat(outgoingDirectories(context)).isEmpty()
        assertThat(operations.revokedUris).containsExactlyElementsIn(operations.launchedUris)
    }

    @Test
    fun `cancellation before chooser launch deletes caller-owned staging`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val source = createSource(context, "cancelled before chooser")
        val operations = FakeFileShareOperations()
        val sut = createSut(context, operations)
        lateinit var shareJob: Job
        operations.onGetUri = { shareJob.cancel() }

        shareJob = launch(start = CoroutineStart.LAZY) {
            sut.share(source.path)
        }
        shareJob.start()
        shareJob.join()

        assertThat(shareJob.isCancelled).isTrue()
        assertThat(operations.launchedUris).isEmpty()
        assertThat(outgoingDirectories(context)).isEmpty()
        assertThat(operations.revokedUris).hasSize(1)
    }

    @Test
    fun `chooser launch failure revokes and deletes caller-owned staging`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val source = createSource(context, "failed share")
        val operations = FakeFileShareOperations().apply {
            launchFailure = IllegalStateException("No chooser available")
        }
        val sut = createSut(context, operations)

        sut.share(source.path)

        assertThat(outgoingDirectories(context)).isEmpty()
        assertThat(operations.revokedUris).containsExactlyElementsIn(operations.launchedUris)
    }

    @Test
    fun `revoke failure does not prevent scheduled staging deletion`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val source = createSource(context, "revoke failure")
        val operations = FakeFileShareOperations().apply {
            revokeFailure = IllegalStateException("Package manager rejected revocation")
        }
        val sut = createSut(context, operations)

        sut.share(source.path)
        advanceTimeBy(DefaultFileShare.STAGED_SHARE_TTL_MILLIS)
        runCurrent()

        assertThat(operations.revokedUris).containsExactlyElementsIn(operations.launchedUris)
        assertThat(outgoingDirectories(context)).isEmpty()
    }

    private fun TestScope.createSut(
        context: Context,
        fileShareOperations: FileShareOperations = FakeFileShareOperations(),
    ): DefaultFileShare {
        return DefaultFileShare(
            context = context,
            coroutineScope = backgroundScope,
            dispatchers = testCoroutineDispatchers(),
            buildMeta = aBuildMeta(applicationId = context.packageName),
        ).also {
            it.operations = fileShareOperations
        }
    }

    private fun createSource(context: Context, content: String): File {
        return File(context.cacheDir, "logs/${UUID.randomUUID()}/securechat.log").apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    private fun outgoingDirectories(context: Context): List<File> {
        return File(context.cacheDir, DefaultFileShare.OUTGOING_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter(File::isDirectory)
    }

    private class FakeFileShareOperations : FileShareOperations {
        val launchedUris = mutableListOf<Uri>()
        val revokedUris = mutableListOf<Uri>()
        var launchFailure: Exception? = null
        var revokeFailure: Exception? = null
        var onGetUri: () -> Unit = {}
        var onLaunch: () -> Unit = {}

        override fun getShareableUri(file: File): Uri {
            onGetUri()
            return Uri.Builder()
                .scheme("content")
                .authority("com.securechat.app.fileprovider")
                .appendPath(requireNotNull(file.parentFile).name)
                .appendPath(file.name)
                .build()
        }

        override fun launchChooser(shareableUri: Uri) {
            launchedUris += shareableUri
            onLaunch()
            launchFailure?.let { throw it }
        }

        override fun revokeReadPermission(shareableUri: Uri) {
            revokedUris += shareableUri
            revokeFailure?.let { throw it }
        }
    }
}
