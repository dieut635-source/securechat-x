/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.api

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes the app lock state: whether a PIN or biometric lock is set up, whether it is mandatory, and whether the app is locked right now.
 */
interface LockScreenService {
    /**
     * The current lock state of the app.
     */
    val lockState: StateFlow<LockScreenLockState>

    /**
     * Check if setting up the lock screen is required.
     * @return true if the lock screen is mandatory and not setup yet, false otherwise.
     */
    fun isSetupRequired(): Flow<Boolean>

    /**
     * Check if pin is setup.
     * @return true if the pin is setup, false otherwise.
     */
    fun isPinSetup(): Flow<Boolean>

    /**
     * Immediately moves the app to the locked state when a PIN is configured.
     *
     * This is intended for security boundaries which can be entered from outside the normal
     * foreground lifecycle (for example, an incoming-call pending intent). It deliberately
     * ignores the configured background grace period.
     *
     * @return true when a PIN exists and the app was locked, false when no PIN is configured.
     */
    suspend fun lockIfPinSetup(): Boolean
}

/**
 * Prevents app content from appearing in screenshots, recordings, or recents previews.
 * @param activity the activity to set the flag on.
 */
fun LockScreenService.handleSecureFlag(activity: ComponentActivity) {
    // SecureChat is closed-distribution software: capture protection is an application
    // invariant, not an optional side effect of whether the user has completed PIN setup.
    activity.window.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.setRecentsScreenshotEnabled(false)
    }
}
