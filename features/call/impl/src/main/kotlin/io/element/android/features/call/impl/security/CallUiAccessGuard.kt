/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.security

import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import kotlinx.coroutines.flow.first

internal enum class CallUiAccessResult {
    Granted,
    UnlockRequired,
    Denied,
}

internal fun isCallUiAccessEffective(locallyGranted: Boolean, lockState: LockScreenLockState): Boolean {
    return locallyGranted && lockState == LockScreenLockState.Unlocked
}

internal fun isTrustedCallUiEntryStillValid(
    trustedForegroundEntry: Boolean,
    requestBackgroundEpoch: Long,
    currentBackgroundEpoch: Long,
    appIsForeground: Boolean,
    lockState: LockScreenLockState,
    isFinishing: Boolean,
): Boolean {
    return trustedForegroundEntry &&
        requestBackgroundEpoch == currentBackgroundEpoch &&
        appIsForeground &&
        lockState == LockScreenLockState.Unlocked &&
        !isFinishing
}

/**
 * Fail-closed boundary for activities which host call media.
 *
 * A currently unlocked state is trusted only when the caller captured that the app was already
 * foreground before crossing the activity boundary. This prevents the lock service's optimistic
 * cold-start state, or an activity brought forward by a PendingIntent, from granting access.
 */
internal class CallUiAccessGuard(
    private val lockScreenService: LockScreenService,
) {
    suspend fun prepareAccess(trustedForegroundEntry: Boolean): CallUiAccessResult {
        if (!lockScreenService.isPinSetup().first()) return CallUiAccessResult.Denied

        if (trustedForegroundEntry && lockScreenService.lockState.value == LockScreenLockState.Unlocked) {
            return CallUiAccessResult.Granted
        }

        return if (lockScreenService.lockIfPinSetup()) {
            CallUiAccessResult.UnlockRequired
        } else {
            CallUiAccessResult.Denied
        }
    }

    suspend fun isUnlockedWithPin(): Boolean {
        return lockScreenService.isPinSetup().first() &&
            lockScreenService.lockState.value == LockScreenLockState.Unlocked
    }
}
