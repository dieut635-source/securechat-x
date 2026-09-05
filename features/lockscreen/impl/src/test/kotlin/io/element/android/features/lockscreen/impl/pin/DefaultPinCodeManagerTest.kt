/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.impl.pin

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.lockscreen.impl.pin.storage.InMemoryLockScreenStore
import io.element.android.features.lockscreen.impl.storage.LockScreenStore
import io.element.android.features.logout.api.SecureChatDataWiper
import io.element.android.features.logout.test.FakeSecureChatDataWiper
import io.element.android.libraries.cryptography.api.EncryptionDecryptionService
import io.element.android.libraries.cryptography.api.SecretKeyRepository
import io.element.android.libraries.cryptography.impl.AESEncryptionDecryptionService
import io.element.android.libraries.cryptography.test.SimpleSecretKeyRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultPinCodeManagerTest {
    @Test
    fun `given a pin code when create and delete assert no pin code left`() = runTest {
        val pinCodeManager = createDefaultPinCodeManager()
        pinCodeManager.hasPinCode().test {
            assertThat(awaitItem()).isFalse()
            pinCodeManager.createPinCode("1234")
            assertThat(awaitItem()).isTrue()
            pinCodeManager.deletePinCode()
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `given a pin code when create and verify with the same pin succeed`() = runTest {
        val pinCodeManager = createDefaultPinCodeManager()
        val pinCode = "1234"
        pinCodeManager.createPinCode(pinCode)
        assertThat(pinCodeManager.verifyPinCode(pinCode)).isTrue()
    }

    @Test
    fun `given a pin code when create and verify with a different pin fails`() = runTest {
        val pinCodeManager = createDefaultPinCodeManager()
        pinCodeManager.createPinCode("1234")
        assertThat(pinCodeManager.verifyPinCode("1235")).isFalse()
    }

    /**
     * The whole point of a duress code: from the outside it must be indistinguishable from the real
     * one. No error, no warning, no different screen. Anything that betrayed the difference would
     * tell whoever is standing over the phone to keep pressing.
     */
    @Test
    fun `the duress code unlocks exactly like the real one`() = runTest {
        val wiper = FakeSecureChatDataWiper()
        val pinCodeManager = createDefaultPinCodeManager(dataWiper = wiper)
        pinCodeManager.createPinCode("1234")
        pinCodeManager.createDuressPinCode("9876")

        assertThat(pinCodeManager.verifyPinCode("9876")).isTrue()
    }

    @Test
    fun `the duress code erases every account`() = runTest {
        val wiper = FakeSecureChatDataWiper()
        val pinCodeManager = createDefaultPinCodeManager(dataWiper = wiper)
        pinCodeManager.createPinCode("1234")
        pinCodeManager.createDuressPinCode("9876")

        pinCodeManager.verifyPinCode("9876")

        // beginWipeEverything, not wipeEverything: the duress unlock must return as soon as the keys
        // are destroyed. Waiting for the files to go froze the screen for seconds and gave away
        // which code had been typed - see SecureChatDataWiper.beginWipeEverything.
        assertThat(wiper.beginWipeEverythingCount).isEqualTo(1)
        assertThat(wiper.wipeEverythingCount).isEqualTo(0)
    }

    /**
     * The dangerous direction. A normal unlock must never destroy anything.
     */
    @Test
    fun `the real code erases nothing`() = runTest {
        val wiper = FakeSecureChatDataWiper()
        val pinCodeManager = createDefaultPinCodeManager(dataWiper = wiper)
        pinCodeManager.createPinCode("1234")
        pinCodeManager.createDuressPinCode("9876")

        assertThat(pinCodeManager.verifyPinCode("1234")).isTrue()
        assertThat(wiper.wipeEverythingCount).isEqualTo(0)
        assertThat(wiper.beginWipeEverythingCount).isEqualTo(0)
    }

    @Test
    fun `a code that is neither is refused and erases nothing`() = runTest {
        val wiper = FakeSecureChatDataWiper()
        val pinCodeManager = createDefaultPinCodeManager(dataWiper = wiper)
        pinCodeManager.createPinCode("1234")
        pinCodeManager.createDuressPinCode("9876")

        assertThat(pinCodeManager.verifyPinCode("5555")).isFalse()
        assertThat(wiper.wipeEverythingCount).isEqualTo(0)
    }

    /**
     * Belt and braces. The setup screen refuses a duress code too close to the real one, but if one
     * ever became identical through some other path, the real code must still win: unlocking must
     * never be the thing that destroys the data.
     */
    @Test
    fun `if both codes were somehow identical the real one wins and nothing is erased`() = runTest {
        val wiper = FakeSecureChatDataWiper()
        val pinCodeManager = createDefaultPinCodeManager(dataWiper = wiper)
        pinCodeManager.createPinCode("1234")
        pinCodeManager.createDuressPinCode("1234")

        assertThat(pinCodeManager.verifyPinCode("1234")).isTrue()
        assertThat(wiper.wipeEverythingCount).isEqualTo(0)
    }

    @Test
    fun `deleting the pin code removes the duress code with it`() = runTest {
        val pinCodeManager = createDefaultPinCodeManager()
        pinCodeManager.createPinCode("1234")
        pinCodeManager.createDuressPinCode("9876")
        assertThat(pinCodeManager.hasDuressPinCode()).isTrue()

        pinCodeManager.deletePinCode()

        assertThat(pinCodeManager.hasDuressPinCode()).isFalse()
    }
}

fun createDefaultPinCodeManager(
    lockScreenStore: LockScreenStore = InMemoryLockScreenStore(),
    secretKeyRepository: SecretKeyRepository = SimpleSecretKeyRepository(),
    encryptionDecryptionService: EncryptionDecryptionService = AESEncryptionDecryptionService(),
    dataWiper: SecureChatDataWiper = FakeSecureChatDataWiper(),
) = DefaultPinCodeManager(
    lockScreenStore = lockScreenStore,
    secretKeyRepository = secretKeyRepository,
    encryptionDecryptionService = encryptionDecryptionService,
    dataWiper = dataWiper,
)
