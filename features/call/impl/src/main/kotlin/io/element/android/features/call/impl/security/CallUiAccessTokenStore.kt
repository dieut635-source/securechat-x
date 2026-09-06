/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.security

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.UUID

/**
 * Issues a single-use, process-local capability for an in-app call navigation.
 *
 * The token is intentionally not persisted. Task restoration, process death, or replaying an old
 * start intent therefore falls back to the PIN gate instead of inheriting an old unlocked state.
 */
@SingleIn(AppScope::class)
@Inject
class CallUiAccessTokenStore {
    private val lock = Any()
    private var currentToken: String? = null

    internal fun issue(): String = synchronized(lock) {
        UUID.randomUUID().toString().also { currentToken = it }
    }

    internal fun consume(token: String?): Boolean = synchronized(lock) {
        if (token == null || token != currentToken) {
            false
        } else {
            currentToken = null
            true
        }
    }
}
