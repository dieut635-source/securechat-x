/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.sessionstorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.sessionstorage.api.AuthenticationInvalidatedException
import io.element.android.libraries.sessionstorage.api.SessionSecurityCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSessionSecurityCoordinator : SessionSecurityCoordinator {
    private val mutex = Mutex()
    private val epoch = AtomicLong(0L)

    override fun beginAuthentication(): Long = epoch.get()

    override suspend fun <T> commitAuthentication(token: Long, block: suspend () -> T): T = mutex.withLock {
        if (token != epoch.get() || token % 2L != 0L) {
            throw AuthenticationInvalidatedException()
        }
        block()
    }

    override suspend fun <T> invalidateAuthenticationsAndRun(block: suspend () -> T): T = mutex.withLock {
        // Odd epochs mean logout is in progress. Incrementing again in finally also rejects an
        // authentication operation that happened to begin while local session cleanup was running.
        epoch.incrementAndGet()
        try {
            block()
        } finally {
            epoch.incrementAndGet()
        }
    }
}
