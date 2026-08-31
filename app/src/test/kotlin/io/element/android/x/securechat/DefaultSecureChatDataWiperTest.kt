/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Checks that a wipe actually erases. WHO gets wiped is [SecureChatRemoteWipeTest].
 */
@Config(application = Application::class)
class DefaultSecureChatDataWiperTest : RobolectricTest() {
    private class OnDisk(val data: SessionData, val sessionDir: File, val cacheDir: File) {
        fun stillThere() = sessionDir.exists() || cacheDir.exists()
    }

    private fun onDisk(userId: String): OnDisk {
        val root = File.createTempFile("wipe", "")
        root.delete()
        root.mkdirs()
        val sessionDir = File(root, "session")
        sessionDir.mkdirs()
        File(sessionDir, "messages.db").writeText("secret")
        val cacheDir = File(root, "cache")
        cacheDir.mkdirs()
        File(cacheDir, "photo.jpg").writeText("secret")
        return OnDisk(
            data = aSessionData(
                sessionPath = sessionDir.absolutePath,
                cachePath = cacheDir.absolutePath,
            ).copy(userId = userId),
            sessionDir = sessionDir,
            cacheDir = cacheDir,
        )
    }

    private fun TestScope.createSut(store: InMemorySessionStore) = DefaultSecureChatDataWiper(
        context = RuntimeEnvironment.getApplication(),
        sessionStore = store,
        dispatchers = testCoroutineDispatchers(),
    )

    @Test
    fun `wipeSession erases that account's files and its row`() = runTest {
        val target = onDisk("@target:securechat.com.au")
        val store = InMemorySessionStore()
        store.addSession(target.data)

        createSut(store).wipeSession("@target:securechat.com.au", reason = "test")

        assertThat(target.stillThere()).isFalse()
        // The row holds the tokens and the database passphrase, so it must go too.
        assertThat(store.getAllSessions()).isEmpty()
    }

    @Test
    fun `wipeSession leaves the other accounts untouched`() = runTest {
        val target = onDisk("@target:securechat.com.au")
        val other = onDisk("@other:securechat.com.au")
        val store = InMemorySessionStore()
        store.addSession(target.data)
        store.addSession(other.data)

        createSut(store).wipeSession("@target:securechat.com.au", reason = "test")

        assertThat(target.stillThere()).isFalse()
        assertThat(other.sessionDir.exists()).isTrue()
        assertThat(other.cacheDir.exists()).isTrue()
        assertThat(store.getAllSessions().map { it.userId }).containsExactly("@other:securechat.com.au")
    }

    /**
     * Duress: leaving one account behind would defeat the point.
     */
    @Test
    fun `wipeEverything erases every account`() = runTest {
        val first = onDisk("@first:securechat.com.au")
        val second = onDisk("@second:securechat.com.au")
        val store = InMemorySessionStore()
        store.addSession(first.data)
        store.addSession(second.data)

        createSut(store).wipeEverything(reason = "test")

        assertThat(first.stillThere()).isFalse()
        assertThat(second.stillThere()).isFalse()
        assertThat(store.getAllSessions()).isEmpty()
    }

    /**
     * A wipe interrupted by the process being killed is resumed on the next start, so running it
     * again must not throw.
     */
    @Test
    fun `wiping an account that is already gone is harmless`() = runTest {
        val store = InMemorySessionStore()
        createSut(store).wipeSession("@never-existed:securechat.com.au", reason = "test")
        assertThat(store.getAllSessions()).isEmpty()
    }
}
