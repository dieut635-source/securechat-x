/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.sessionstorage.test

import io.element.android.libraries.sessionstorage.api.AuthenticationInvalidatedException
import io.element.android.libraries.sessionstorage.api.SessionSecurityCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class FakeSessionSecurityCoordinator : SessionSecurityCoordinator {
    private val mutex = Mutex()
    private val publicationMutex = Mutex()
    private val epoch = AtomicLong(0L)

    override fun beginAuthentication(): Long = epoch.get()

    override suspend fun <T> commitAuthentication(token: Long, block: suspend () -> T): T = mutex.withLock {
        if (token != epoch.get() || token % 2L != 0L) {
            throw AuthenticationInvalidatedException()
        }
        block()
    }

    override suspend fun <T> invalidateAuthenticationsAndRun(block: suspend () -> T): T = mutex.withLock {
        epoch.incrementAndGet()
        try {
            block()
        } finally {
            epoch.incrementAndGet()
        }
    }

    override suspend fun <T> serializeSessionPublication(block: suspend () -> T): T =
        publicationMutex.withLock { block() }
}
