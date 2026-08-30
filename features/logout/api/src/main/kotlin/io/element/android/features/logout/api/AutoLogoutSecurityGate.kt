/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Blocks access to authenticated UI while a mandatory automatic logout is being enforced.
 *
 * A timeout is a security boundary, so a failed or delayed cleanup must keep the application
 * locked. The gate may only be reopened after local session cleanup has completed successfully.
 */
interface AutoLogoutSecurityGate {
    val isLocked: StateFlow<Boolean>

    fun lock()

    fun unlockAfterCleanup()
}
