/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.test

import io.element.android.features.logout.api.SecureChatDataWiper

class FakeSecureChatDataWiper : SecureChatDataWiper {
    val wipedSessions = mutableListOf<String>()
    var wipeEverythingCount = 0
        private set

    override suspend fun wipeSession(userId: String, reason: String) {
        wipedSessions += userId
    }

    override suspend fun wipeEverything(reason: String) {
        wipeEverythingCount++
    }

    /** Counted separately so a test can tell the deferred duress path from the blocking one. */
    var beginWipeEverythingCount = 0
        private set

    override suspend fun beginWipeEverything(reason: String) {
        beginWipeEverythingCount++
    }
}
