/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.impl.setup.pin.validation

import com.google.common.truth.Truth.assertThat
import io.element.android.features.lockscreen.impl.LockScreenConfig
import io.element.android.features.lockscreen.impl.pin.model.PinEntry
import org.junit.Test

/**
 * The emergency code erases everything, with no confirmation and no way back. So the rule that
 * keeps it away from the everyday code is itself a safety mechanism, and is tested as one.
 */
class DuressPinValidationTest {
    private val sut = PinValidator(
        lockScreenConfig = LockScreenConfig(
            isPinMandatory = false,
            forbiddenPinCodes = setOf("1234"),
            pinSize = 4,
            maxPinCodeAttemptsBeforeLogout = 3,
            gracePeriod = kotlin.time.Duration.ZERO,
            isStrongBiometricsEnabled = true,
            isWeakBiometricsEnabled = true,
        )
    )

    private fun pin(text: String) = PinEntry.createEmpty(text.length).fillWith(text)

    @Test
    fun `a code one digit away from the real one is refused`() {
        val result = sut.isDuressPinValid(pin("9875"), mainPin = "9876")
        assertThat(result).isEqualTo(PinValidator.Result.Invalid(SetupPinFailure.DuressPinTooSimilar))
    }

    @Test
    fun `an identical code is refused`() {
        val result = sut.isDuressPinValid(pin("9876"), mainPin = "9876")
        assertThat(result).isEqualTo(PinValidator.Result.Invalid(SetupPinFailure.DuressPinTooSimilar))
    }

    @Test
    fun `two digits apart is accepted`() {
        val result = sut.isDuressPinValid(pin("9855"), mainPin = "9876")
        assertThat(result).isEqualTo(PinValidator.Result.Valid)
    }

    @Test
    fun `completely different is accepted`() {
        val result = sut.isDuressPinValid(pin("1357"), mainPin = "9876")
        assertThat(result).isEqualTo(PinValidator.Result.Valid)
    }

    /**
     * The forbidden-code rule still applies: an emergency code must not be something a stranger
     * would try first, or it fires by accident.
     */
    @Test
    fun `a forbidden code is still refused even if far from the real one`() {
        val result = sut.isDuressPinValid(pin("1234"), mainPin = "9876")
        assertThat(result).isEqualTo(PinValidator.Result.Invalid(SetupPinFailure.ForbiddenPin))
    }
}
