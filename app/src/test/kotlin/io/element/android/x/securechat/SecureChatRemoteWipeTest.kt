/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@Config(application = Application::class)
class SecureChatRemoteWipeTest : RobolectricTest() {
    private fun aSessionOnDisk(userId: String, revoked: Boolean): Triple<io.element.android.libraries.sessionstorage.api.SessionData, File, File> {
        val root = File.createTempFile("wipe", "").let { it.delete(); it.mkdirs(); it }
        val sessionDir = File(root, "$userId-session").apply { mkdirs(); File(this, "messages.db").writeText("secret") }
        val cacheDir = File(root, "$userId-cache").apply { mkdirs(); File(this, "photo.jpg").writeText("secret") }
        val data = aSessionData(
            isTokenValid = !revoked,
            sessionPath = sessionDir.absolutePath,
            cachePath = cacheDir.absolutePath,
        ).copy(userId = userId)
        return Triple(data, sessionDir, cacheDir)
    }

    // Scope con tự huỷ, KHÔNG dùng chính TestScope và cũng không dùng backgroundScope.
    // Luồng quan sát phiên không bao giờ kết thúc: chạy trong TestScope thì runTest chờ mãi và
    // test treo; chạy trong backgroundScope thì nó không được thu thập trước advanceUntilIdle,
    // và test đỏ vì tưởng wipe không chạy. Đo bằng println mới ra: start() được gọi nhưng
    // không có emission nào.
    private fun TestScope.createSut(
        sessionStore: SessionStore,
        scope: CoroutineScope,
    ) = SecureChatRemoteWipe(
        context = RuntimeEnvironment.getApplication(),
        coroutineScope = scope,
        sessionStore = sessionStore,
        dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
    )

    private suspend fun TestScope.runObserver(sessionStore: SessionStore, assertions: suspend () -> Unit) {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            createSut(sessionStore, scope).start()
            advanceUntilIdle()
            assertions()
        } finally {
            scope.cancel()
        }
    }

    /**
     * The dangerous direction: a healthy session must never be touched. Getting this wrong destroys
     * the data of every working device.
     */
    @Test
    fun `a session the homeserver still accepts is left completely alone`() = runTest {
        val (data, sessionDir, cacheDir) = aSessionOnDisk("@ok:securechat.com.au", revoked = false)
        val store = InMemorySessionStore()
        store.addSession(data)

        runObserver(store) {
            assertThat(sessionDir.exists()).isTrue()
            assertThat(cacheDir.exists()).isTrue()
            assertThat(store.getAllSessions()).hasSize(1)
        }
    }

    @Test
    fun `a session the homeserver revoked is erased and its row removed`() = runTest {
        val (data, sessionDir, cacheDir) = aSessionOnDisk("@revoked:securechat.com.au", revoked = true)
        val store = InMemorySessionStore()
        store.addSession(data)

        runObserver(store) {
            assertThat(sessionDir.exists()).isFalse()
            assertThat(cacheDir.exists()).isFalse()
            // The row carries the user id, device id, tokens and the database passphrase; it must go too.
            assertThat(store.getAllSessions()).isEmpty()
        }
    }

    /**
     * A device may hold more than one account. Revoking one must not erase the others.
     */
    @Test
    fun `only the revoked session is erased when another account is healthy`() = runTest {
        val (revoked, revokedDir, revokedCache) = aSessionOnDisk("@revoked:securechat.com.au", revoked = true)
        val (healthy, healthyDir, healthyCache) = aSessionOnDisk("@healthy:securechat.com.au", revoked = false)
        val store = InMemorySessionStore()
        store.addSession(revoked)
        store.addSession(healthy)

        runObserver(store) {
            assertThat(revokedDir.exists()).isFalse()
            assertThat(revokedCache.exists()).isFalse()
            assertThat(healthyDir.exists()).isTrue()
            assertThat(healthyCache.exists()).isTrue()
            assertThat(store.getAllSessions().map { it.userId }).containsExactly("@healthy:securechat.com.au")
        }
    }
}
