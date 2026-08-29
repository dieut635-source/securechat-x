/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.logout.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.sessionstorage.test.FakeSessionSecurityCoordinator
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultLogoutUseCaseTest {
    @Test
    fun `test logout from one session`() = runTest {
        val logoutLambda1 = lambdaRecorder<Boolean, Boolean, Unit> { _, _ -> }
        val client1 = FakeMatrixClient(A_USER_ID).apply {
            logoutLambda = logoutLambda1
        }
        val sut = DefaultLogoutUseCase(
            sessionStore = InMemorySessionStore(
                initialList = listOf(
                    aSessionData(sessionId = A_USER_ID.value),
                )
            ),
            matrixClientProvider = FakeMatrixClientProvider(
                getClient = { sessionId ->
                    when (sessionId) {
                        A_USER_ID -> Result.success(client1)
                        else -> error("Unexpected sessionId")
                    }
                }
            ),
            sessionSecurityCoordinator = FakeSessionSecurityCoordinator(),
        )
        sut.logoutAll(ignoreSdkError = true)
        logoutLambda1.assertions().isCalledOnce().with(value(true), value(true))
    }

    @Test
    fun `test logout from several sessions`() = runTest {
        val logoutLambda1 = lambdaRecorder<Boolean, Boolean, Unit> { _, _ -> }
        val logoutLambda2 = lambdaRecorder<Boolean, Boolean, Unit> { _, _ -> }
        val client1 = FakeMatrixClient(A_USER_ID).apply {
            logoutLambda = logoutLambda1
        }
        val client2 = FakeMatrixClient(A_USER_ID_2).apply {
            logoutLambda = logoutLambda2
        }
        val sut = DefaultLogoutUseCase(
            sessionStore = InMemorySessionStore(
                initialList = listOf(
                    aSessionData(sessionId = A_USER_ID.value),
                    aSessionData(sessionId = A_USER_ID_2.value),
                )
            ),
            sessionSecurityCoordinator = FakeSessionSecurityCoordinator(),
            matrixClientProvider = FakeMatrixClientProvider(
                getClient = { sessionId ->
                    when (sessionId) {
                        A_USER_ID -> Result.success(client1)
                        A_USER_ID_2 -> Result.success(client2)
                        else -> error("Unexpected sessionId")
                    }
                }
            ),
        )
        sut.logoutAll(ignoreSdkError = true)
        logoutLambda1.assertions().isCalledOnce().with(value(true), value(true))
        logoutLambda2.assertions().isCalledOnce().with(value(true), value(true))
    }

    @Test
    fun `logout with ignored SDK errors removes a session whose client cannot be restored`() = runTest {
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(sessionId = A_USER_ID.value),
            )
        )
        val sut = DefaultLogoutUseCase(
            sessionStore = sessionStore,
            matrixClientProvider = FakeMatrixClientProvider(
                getClient = { sessionId ->
                    when (sessionId) {
                        A_USER_ID -> Result.failure(Exception("Session not found"))
                        else -> error("Unexpected sessionId")
                    }
                }
            ),
            sessionSecurityCoordinator = FakeSessionSecurityCoordinator(),
        )
        sut.logoutAll(ignoreSdkError = true)
        assertThat(sessionStore.getAllSessions()).isEmpty()
    }

    @Test
    fun `logout with ignored SDK errors removes a throwing session and continues with later sessions`() = runTest {
        val logoutLambda1 = lambdaRecorder<Boolean, Boolean, Unit> { _, _ ->
            throw IllegalStateException("Local SDK cleanup failed")
        }
        val logoutLambda2 = lambdaRecorder<Boolean, Boolean, Unit> { _, _ -> }
        val client1 = FakeMatrixClient(A_USER_ID).apply { logoutLambda = logoutLambda1 }
        val client2 = FakeMatrixClient(A_USER_ID_2).apply { logoutLambda = logoutLambda2 }
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(sessionId = A_USER_ID.value),
                aSessionData(sessionId = A_USER_ID_2.value),
            )
        )
        val sut = DefaultLogoutUseCase(
            sessionStore = sessionStore,
            matrixClientProvider = FakeMatrixClientProvider(
                getClient = { sessionId ->
                    when (sessionId) {
                        A_USER_ID -> Result.success(client1)
                        A_USER_ID_2 -> Result.success(client2)
                        else -> error("Unexpected sessionId")
                    }
                }
            ),
            sessionSecurityCoordinator = FakeSessionSecurityCoordinator(),
        )

        sut.logoutAll(ignoreSdkError = true)

        logoutLambda1.assertions().isCalledOnce().with(value(true), value(true))
        logoutLambda2.assertions().isCalledOnce().with(value(true), value(true))
        assertThat(sessionStore.getSession(A_USER_ID.value)).isNull()
    }

    @Test
    fun `test logout no sessions`() = runTest {
        val sut = DefaultLogoutUseCase(
            sessionStore = InMemorySessionStore(
                initialList = emptyList()
            ),
            sessionSecurityCoordinator = FakeSessionSecurityCoordinator(),
            matrixClientProvider = FakeMatrixClientProvider(
                getClient = { sessionId ->
                    when (sessionId) {
                        else -> error("Unexpected sessionId")
                    }
                }
            ),
        )
        sut.logoutAll(ignoreSdkError = true)
        // No error
    }
}
