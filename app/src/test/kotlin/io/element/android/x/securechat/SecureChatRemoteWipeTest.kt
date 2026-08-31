/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import com.google.common.truth.Truth.assertThat
import io.element.android.features.logout.api.SecureChatDataWiper
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Decides WHO gets wiped. What a wipe actually erases is [DefaultSecureChatDataWiperTest].
 */
class SecureChatRemoteWipeTest {
    private class RecordingWiper : SecureChatDataWiper {
        val wipedSessions = mutableListOf<String>()
        var wipedEverything = false
        override suspend fun wipeSession(userId: String, reason: String) { wipedSessions += userId }
        override suspend fun wipeEverything(reason: String) { wipedEverything = true }
    }

    // A self-cancelling child scope: the session flow never completes, so running it in the
    // TestScope hangs runTest, and running it in backgroundScope leaves it uncollected.
    private suspend fun TestScope.observing(
        sessionStore: SessionStore,
        wiper: SecureChatDataWiper,
        assertions: suspend () -> Unit,
    ) {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            SecureChatRemoteWipe(scope, sessionStore, wiper).start()
            advanceUntilIdle()
            assertions()
        } finally {
            scope.cancel()
        }
    }

    /**
     * The dangerous direction. Wiping a session the homeserver still accepts destroys the data of
     * every working device, so this is the assertion that matters most.
     */
    @Test
    fun `a session the homeserver still accepts is never wiped`() = runTest {
        val store = InMemorySessionStore()
        store.addSession(aSessionData(isTokenValid = true).copy(userId = "@ok:securechat.com.au"))
        val wiper = RecordingWiper()

        observing(store, wiper) {
            assertThat(wiper.wipedSessions).isEmpty()
            assertThat(wiper.wipedEverything).isFalse()
        }
    }

    @Test
    fun `a session the homeserver revoked is wiped`() = runTest {
        val store = InMemorySessionStore()
        store.addSession(aSessionData(isTokenValid = false).copy(userId = "@revoked:securechat.com.au"))
        val wiper = RecordingWiper()

        observing(store, wiper) {
            assertThat(wiper.wipedSessions).containsExactly("@revoked:securechat.com.au")
        }
    }

    /**
     * A revocation says nothing about the other accounts on the phone, so it must not touch them.
     * Erasing everything is reserved for duress.
     */
    @Test
    fun `only the revoked account is wiped when another one is healthy`() = runTest {
        val store = InMemorySessionStore()
        store.addSession(aSessionData(isTokenValid = false).copy(userId = "@revoked:securechat.com.au"))
        store.addSession(aSessionData(isTokenValid = true).copy(userId = "@healthy:securechat.com.au"))
        val wiper = RecordingWiper()

        observing(store, wiper) {
            assertThat(wiper.wipedSessions).containsExactly("@revoked:securechat.com.au")
            assertThat(wiper.wipedEverything).isFalse()
        }
    }
}
