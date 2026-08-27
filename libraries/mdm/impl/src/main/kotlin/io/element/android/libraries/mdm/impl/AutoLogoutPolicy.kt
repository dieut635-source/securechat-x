/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.impl

/**
 * Decides whether the `auto_logout_minutes` policy has been exceeded.
 *
 * Kept separate from the Android plumbing so the rule itself can be tested, because getting it wrong
 * is expensive in both directions: too eager and users are signed out mid-conversation, too lax and
 * a lost phone stays signed in.
 */
object AutoLogoutPolicy {
    fun isExpired(
        backgroundedAtMillis: Long?,
        nowMillis: Long,
        autoLogoutMinutes: Int,
    ): Boolean {
        if (autoLogoutMinutes <= 0) return false
        // The app has not been backgrounded since it was last signed in to.
        if (backgroundedAtMillis == null) return false
        val elapsed = nowMillis - backgroundedAtMillis
        // Time appears to have run backwards: either the clock was corrected, or someone moved it to
        // dodge the timeout. This is a security control, so it fails closed and signs the user out.
        if (elapsed < 0) return true
        return elapsed >= autoLogoutMinutes * 60_000L
    }
}
