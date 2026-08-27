/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoLogoutPolicyTest {
    private val minute = 60_000L

    @Test
    fun `zero minutes disables the timeout entirely`() {
        assertThat(
            AutoLogoutPolicy.isExpired(backgroundedAtMillis = 0L, nowMillis = 100 * minute, autoLogoutMinutes = 0)
        ).isFalse()
    }

    @Test
    fun `an app that has never been backgrounded is never expired`() {
        assertThat(
            AutoLogoutPolicy.isExpired(backgroundedAtMillis = null, nowMillis = 100 * minute, autoLogoutMinutes = 5)
        ).isFalse()
    }

    @Test
    fun `the user stays signed in until the timeout is reached`() {
        assertThat(
            AutoLogoutPolicy.isExpired(backgroundedAtMillis = 0L, nowMillis = 4 * minute, autoLogoutMinutes = 5)
        ).isFalse()
    }

    @Test
    fun `the user is signed out once the timeout is reached exactly`() {
        assertThat(
            AutoLogoutPolicy.isExpired(backgroundedAtMillis = 0L, nowMillis = 5 * minute, autoLogoutMinutes = 5)
        ).isTrue()
    }

    @Test
    fun `the user is signed out after the timeout has passed`() {
        assertThat(
            AutoLogoutPolicy.isExpired(backgroundedAtMillis = 0L, nowMillis = 90 * minute, autoLogoutMinutes = 5)
        ).isTrue()
    }

    @Test
    fun `a clock moved backwards signs the user out rather than granting extra time`() {
        // Winding the device clock back would otherwise postpone the timeout indefinitely.
        assertThat(
            AutoLogoutPolicy.isExpired(backgroundedAtMillis = 100 * minute, nowMillis = 1 * minute, autoLogoutMinutes = 5)
        ).isTrue()
    }
}
