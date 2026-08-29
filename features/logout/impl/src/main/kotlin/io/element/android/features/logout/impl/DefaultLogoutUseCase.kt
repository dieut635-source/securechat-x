/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.logout.api.LogoutUseCase
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.SessionSecurityCoordinator
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@ContributesBinding(AppScope::class)
class DefaultLogoutUseCase(
    private val sessionStore: SessionStore,
    private val matrixClientProvider: MatrixClientProvider,
    private val sessionSecurityCoordinator: SessionSecurityCoordinator,
) : LogoutUseCase {
    override suspend fun logoutAll(ignoreSdkError: Boolean) {
        sessionSecurityCoordinator.invalidateAuthenticationsAndRun {
            var firstFailure: Throwable? = null
            sessionStore.getAllSessions()
                .map { sessionData ->
                    SessionId(sessionData.userId)
                }
                .forEach { sessionId ->
                    Timber.d("Logging out sessionId: $sessionId")
                    try {
                        matrixClientProvider.getOrRestore(sessionId).getOrThrow()
                            .logout(userInitiated = true, ignoreSdkError = ignoreSdkError)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        Timber.e(error, "Failed to log out MatrixClient for sessionId: $sessionId")
                        if (ignoreSdkError) {
                            // Auto-logout and PIN-forgotten flows are security boundaries. Even when
                            // restoration or the SDK's local cleanup fails, remove the persisted session
                            // and continue attempting every other account.
                            try {
                                sessionStore.removeSession(sessionId.value)
                            } catch (removalError: Throwable) {
                                if (removalError is CancellationException) throw removalError
                                Timber.e(removalError, "Failed to remove local sessionId: $sessionId")
                                if (firstFailure == null) firstFailure = removalError
                            }
                        } else if (firstFailure == null) {
                            firstFailure = error
                        }
                    }
                }
            firstFailure?.let { throw it }
        }
    }
}
