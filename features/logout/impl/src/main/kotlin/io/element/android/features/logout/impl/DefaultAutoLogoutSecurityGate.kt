/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.logout.api.AutoLogoutSecurityGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultAutoLogoutSecurityGate : AutoLogoutSecurityGate {
    private val mutableIsLocked = MutableStateFlow(false)

    override val isLocked: StateFlow<Boolean> = mutableIsLocked.asStateFlow()

    override fun lock() {
        mutableIsLocked.value = true
    }

    override fun unlockAfterCleanup() {
        mutableIsLocked.value = false
    }
}
