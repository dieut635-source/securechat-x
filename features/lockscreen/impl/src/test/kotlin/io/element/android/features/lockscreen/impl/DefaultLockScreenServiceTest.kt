/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.impl

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.impl.biometric.BiometricAuthenticatorManager
import io.element.android.features.lockscreen.impl.biometric.FakeBiometricAuthenticatorManager
import io.element.android.features.lockscreen.impl.fixtures.aLockScreenConfig
import io.element.android.features.lockscreen.impl.pin.PinCodeManager
import io.element.android.features.lockscreen.impl.pin.SECRET_KEY_ALIAS
import io.element.android.features.lockscreen.impl.pin.createDefaultPinCodeManager
import io.element.android.features.lockscreen.impl.pin.storage.InMemoryLockScreenStore
import io.element.android.features.lockscreen.impl.storage.LockScreenStore
import io.element.android.libraries.cryptography.api.SecretKeyRepository
import io.element.android.libraries.cryptography.test.SimpleSecretKeyRepository
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import io.element.android.libraries.sessionstorage.test.observer.FakeSessionObserver
import io.element.android.services.appnavstate.api.AppForegroundStateService
import io.element.android.services.appnavstate.test.FakeAppForegroundStateService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultLockScreenServiceTest {
    @Test
    fun `when the pin is not mandatory and no pin is configured isSetupRequired emits false`() = runTest {
        val sut = createDefaultLockScreenService(
            lockScreenConfig = aLockScreenConfig(isPinMandatory = false)
        )
        sut.isSetupRequired().test {
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `when the pin is mandatory, isSetupRequired emits true`() = runTest {
        val lockScreenStore = InMemoryLockScreenStore()
        val secretKeyRepository = SimpleSecretKeyRepository()
        val pinCodeManager = createDefaultPinCodeManager(
            lockScreenStore = lockScreenStore,
            secretKeyRepository = secretKeyRepository,
        )
        val sut = createDefaultLockScreenService(
            lockScreenConfig = aLockScreenConfig(isPinMandatory = true),
            lockScreenStore = lockScreenStore,
            secretKeyRepository = secretKeyRepository,
            pinCodeManager = pinCodeManager,
        )
        sut.isSetupRequired().test {
            assertThat(awaitItem()).isTrue()
            // An everyday code alone does NOT finish setup - the emergency code is still owed.
            secretKeyRepository.getOrCreateKey(SECRET_KEY_ALIAS, true)
            assertThat(awaitItem()).isTrue()
            // Users deletes the pin code. Still true, now for the original reason: no code at all
            // on a handset where one is mandatory. The flow does not dedupe, so it emits again.
            secretKeyRepository.deleteKey("elementx.SECRET_KEY_ALIAS_PIN_CODE")
            assertThat(awaitItem()).isTrue()
        }
    }

    @Test
    fun `a main code without a duress code leaves setup unfinished`() = runTest {
        // The hole this covers, seen on hardware 04/09/2026: the duress step still offered a back
        // button, and leaving it landed the user in the app. isSetupRequired asked only whether an
        // everyday code existed, so it answered "done" and the app never asked again - a handset
        // with an everyday code, no emergency code, and no route back to the step that makes one.
        val lockScreenStore = InMemoryLockScreenStore()
        val secretKeyRepository = SimpleSecretKeyRepository()
        val pinCodeManager = createDefaultPinCodeManager(
            lockScreenStore = lockScreenStore,
            secretKeyRepository = secretKeyRepository,
        )
        val sut = createDefaultLockScreenService(
            lockScreenConfig = aLockScreenConfig(isPinMandatory = true),
            lockScreenStore = lockScreenStore,
            secretKeyRepository = secretKeyRepository,
            pinCodeManager = pinCodeManager,
        )
        pinCodeManager.createPinCode("428913")

        assertThat(sut.isSetupRequired().first()).isTrue()
    }

    @Test
    fun `setup is finished only once both codes exist`() = runTest {
        val lockScreenStore = InMemoryLockScreenStore()
        val secretKeyRepository = SimpleSecretKeyRepository()
        val pinCodeManager = createDefaultPinCodeManager(
            lockScreenStore = lockScreenStore,
            secretKeyRepository = secretKeyRepository,
        )
        val sut = createDefaultLockScreenService(
            lockScreenConfig = aLockScreenConfig(isPinMandatory = true),
            lockScreenStore = lockScreenStore,
            secretKeyRepository = secretKeyRepository,
            pinCodeManager = pinCodeManager,
        )
        pinCodeManager.createPinCode("428913")
        pinCodeManager.createDuressPinCode("706254")

        assertThat(sut.isSetupRequired().first()).isFalse()
    }

    @Test
    fun `an optional pin that was never set does not demand a duress code`() = runTest {
        // Nothing has been chosen, so nothing is owed. Getting this wrong would force a lock screen
        // on every user who never asked for one.
        val sut = createDefaultLockScreenService(
            lockScreenConfig = aLockScreenConfig(isPinMandatory = false),
        )

        assertThat(sut.isSetupRequired().first()).isFalse()
    }

    @Test
    fun `when the last session is deleted, the pin code is removed`() = runTest {
        val sessionObserver = FakeSessionObserver()
        val secretKeyRepository = SimpleSecretKeyRepository()
        val sut = createDefaultLockScreenService(
            lockScreenConfig = aLockScreenConfig(isPinMandatory = true),
            secretKeyRepository = secretKeyRepository,
            sessionObserver = sessionObserver,
        )
        sut.isPinSetup().test {
            assertThat(awaitItem()).isFalse()
            // When the user configure the pin code, the setup is not required anymore
            secretKeyRepository.getOrCreateKey(SECRET_KEY_ALIAS, true)
            assertThat(awaitItem()).isTrue()
            sessionObserver.onSessionDeleted("userId", wasLastSession = false)
            expectNoEvents()
            sessionObserver.onSessionDeleted("userId", wasLastSession = true)
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `lockIfPinSetup locks immediately when a pin exists`() = runTest {
        val lockScreenStore = InMemoryLockScreenStore()
        val secretKeyRepository = SimpleSecretKeyRepository()
        val pinCodeManager = createDefaultPinCodeManager(
            lockScreenStore = lockScreenStore,
            secretKeyRepository = secretKeyRepository,
        )
        val sut = createDefaultLockScreenService(
            lockScreenStore = lockScreenStore,
            pinCodeManager = pinCodeManager,
        )
        pinCodeManager.createPinCode("1234")

        assertThat(sut.lockIfPinSetup()).isTrue()
        assertThat(sut.lockState.value).isEqualTo(LockScreenLockState.Locked)
    }

    @Test
    fun `lockIfPinSetup returns false when no pin exists`() = runTest {
        val sut = createDefaultLockScreenService()

        assertThat(sut.lockIfPinSetup()).isFalse()
    }
}

private fun TestScope.createDefaultLockScreenService(
    lockScreenConfig: LockScreenConfig = aLockScreenConfig(),
    lockScreenStore: LockScreenStore = InMemoryLockScreenStore(),
    secretKeyRepository: SecretKeyRepository = SimpleSecretKeyRepository(),
    pinCodeManager: PinCodeManager = createDefaultPinCodeManager(
        lockScreenStore = lockScreenStore,
        secretKeyRepository = secretKeyRepository,
    ),
    sessionObserver: SessionObserver = FakeSessionObserver(),
    appForegroundStateService: AppForegroundStateService = FakeAppForegroundStateService(),
    biometricAuthenticatorManager: BiometricAuthenticatorManager = FakeBiometricAuthenticatorManager(),
) = DefaultLockScreenService(
    lockScreenConfig = lockScreenConfig,
    lockScreenStore = lockScreenStore,
    pinCodeManager = pinCodeManager,
    coroutineScope = backgroundScope,
    sessionObserver = sessionObserver,
    appForegroundStateService = appForegroundStateService,
    biometricAuthenticatorManager = biometricAuthenticatorManager,
)
