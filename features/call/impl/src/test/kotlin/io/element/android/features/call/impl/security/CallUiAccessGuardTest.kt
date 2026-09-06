/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.security

import com.google.common.truth.Truth.assertThat
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.test.FakeLockScreenService
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CallUiAccessGuardTest {
    @Test
    fun `effective access becomes false immediately when the app lock changes`() {
        assertThat(isCallUiAccessEffective(locallyGranted = true, lockState = LockScreenLockState.Unlocked)).isTrue()
        assertThat(isCallUiAccessEffective(locallyGranted = true, lockState = LockScreenLockState.Locked)).isFalse()
        assertThat(isCallUiAccessEffective(locallyGranted = false, lockState = LockScreenLockState.Unlocked)).isFalse()
    }

    @Test
    fun `a background transition invalidates an otherwise trusted navigation`() {
        assertThat(
            isTrustedCallUiEntryStillValid(
                trustedForegroundEntry = true,
                requestBackgroundEpoch = 7,
                currentBackgroundEpoch = 8,
                appIsForeground = true,
                lockState = LockScreenLockState.Unlocked,
                isFinishing = false,
            )
        ).isFalse()
        assertThat(
            isTrustedCallUiEntryStillValid(
                trustedForegroundEntry = true,
                requestBackgroundEpoch = 7,
                currentBackgroundEpoch = 7,
                appIsForeground = true,
                lockState = LockScreenLockState.Unlocked,
                isFinishing = false,
            )
        ).isTrue()
    }

    @Test
    fun `an app without its mandatory PIN fails closed`() = runTest {
        val lockScreenService = FakeLockScreenService().apply {
            setIsPinSetup(false)
            setLockState(LockScreenLockState.Unlocked)
        }

        val result = CallUiAccessGuard(lockScreenService).prepareAccess(trustedForegroundEntry = true)

        assertThat(result).isEqualTo(CallUiAccessResult.Denied)
    }

    @Test
    fun `an ordinary in-app call can reuse the unlocked foreground session`() = runTest {
        val lockScreenService = unlockedPinService()

        val result = CallUiAccessGuard(lockScreenService).prepareAccess(trustedForegroundEntry = true)

        assertThat(result).isEqualTo(CallUiAccessResult.Granted)
        assertThat(lockScreenService.lockState.value).isEqualTo(LockScreenLockState.Unlocked)
    }

    @Test
    fun `a notification entry forces a fresh PIN even when the old state was unlocked`() = runTest {
        val lockScreenService = unlockedPinService()

        val result = CallUiAccessGuard(lockScreenService).prepareAccess(trustedForegroundEntry = false)

        assertThat(result).isEqualTo(CallUiAccessResult.UnlockRequired)
        assertThat(lockScreenService.lockState.value).isEqualTo(LockScreenLockState.Locked)
    }

    @Test
    fun `a locked in-app entry cannot bypass the PIN`() = runTest {
        val lockScreenService = unlockedPinService().apply {
            setLockState(LockScreenLockState.Locked)
        }

        val result = CallUiAccessGuard(lockScreenService).prepareAccess(trustedForegroundEntry = true)

        assertThat(result).isEqualTo(CallUiAccessResult.UnlockRequired)
        assertThat(lockScreenService.lockState.value).isEqualTo(LockScreenLockState.Locked)
    }

    @Test
    fun `final access check requires both configured PIN and unlocked state`() = runTest {
        val lockScreenService = unlockedPinService()
        val guard = CallUiAccessGuard(lockScreenService)

        assertThat(guard.isUnlockedWithPin()).isTrue()
        lockScreenService.setLockState(LockScreenLockState.Locked)
        assertThat(guard.isUnlockedWithPin()).isFalse()
        lockScreenService.setLockState(LockScreenLockState.Unlocked)
        lockScreenService.setIsPinSetup(false)
        assertThat(guard.isUnlockedWithPin()).isFalse()
    }

    private fun unlockedPinService() = FakeLockScreenService().apply {
        setIsPinSetup(true)
        setLockState(LockScreenLockState.Unlocked)
    }
}
