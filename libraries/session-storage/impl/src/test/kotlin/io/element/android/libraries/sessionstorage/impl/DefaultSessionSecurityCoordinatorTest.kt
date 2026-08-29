/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.sessionstorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.sessionstorage.api.AuthenticationInvalidatedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSessionSecurityCoordinatorTest {
    @Test
    fun `logout waits for an active commit then invalidates its token`() = runTest {
        val sut = DefaultSessionSecurityCoordinator()
        val token = sut.beginAuthentication()
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()

        val commit = launch {
            sut.commitAuthentication(token) {
                commitStarted.complete(Unit)
                releaseCommit.await()
                operations += "commit"
            }
        }
        commitStarted.await()

        val logout = launch {
            sut.invalidateAuthenticationsAndRun {
                operations += "logout"
            }
        }
        runCurrent()
        assertThat(logout.isCompleted).isFalse()

        releaseCommit.complete(Unit)
        commit.join()
        logout.join()

        assertThat(operations).containsExactly("commit", "logout").inOrder()
        val staleCommit = runCatching {
            sut.commitAuthentication(token) { Unit }
        }
        assertThat(staleCommit.exceptionOrNull()).isInstanceOf(AuthenticationInvalidatedException::class.java)
    }

    @Test
    fun `authentication started during logout cannot commit afterward`() = runTest {
        val sut = DefaultSessionSecurityCoordinator()
        var tokenDuringLogout = -1L

        sut.invalidateAuthenticationsAndRun {
            tokenDuringLogout = sut.beginAuthentication()
        }

        val result = runCatching {
            sut.commitAuthentication(tokenDuringLogout) { Unit }
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationInvalidatedException::class.java)
    }
}
