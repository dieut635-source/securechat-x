/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.sessionstorage.api

/**
 * Serializes security-sensitive session commits with sign-out.
 *
 * An authentication attempt captures [beginAuthentication] before doing network work. Its final
 * persistence step must run through [commitAuthentication]. A logout invalidates every older token,
 * so an OAuth callback or password request cannot recreate a session just after logout completed.
 */
interface SessionSecurityCoordinator {
    fun beginAuthentication(): Long

    suspend fun <T> commitAuthentication(token: Long, block: suspend () -> T): T

    suspend fun <T> invalidateAuthenticationsAndRun(block: suspend () -> T): T
}

class AuthenticationInvalidatedException : IllegalStateException(
    "The authentication attempt was invalidated by a newer security operation"
)
