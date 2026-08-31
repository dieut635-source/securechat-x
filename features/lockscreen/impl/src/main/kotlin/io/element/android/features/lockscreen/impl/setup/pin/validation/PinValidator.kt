/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.impl.setup.pin.validation

import dev.zacsweers.metro.Inject
import io.element.android.features.lockscreen.impl.LockScreenConfig
import io.element.android.features.lockscreen.impl.pin.model.PinEntry

@Inject
class PinValidator(private val lockScreenConfig: LockScreenConfig) {
    sealed interface Result {
        data object Valid : Result
        data class Invalid(val failure: SetupPinFailure) : Result
    }

    /**
     * Checks a duress code against the real one.
     *
     * Requiring more than one differing digit is not fussiness. With a four-digit code, a single
     * mistyped digit has 36 neighbours; if the duress code is one of them, one slip erases
     * everything the user has, with no confirmation and no way back. Two digits apart makes that
     * essentially impossible while staying easy to remember.
     */
    fun isDuressPinValid(duressPin: PinEntry, differingDigitsFromMainPin: Int): Result {
        val basic = isPinValid(duressPin)
        if (basic is Result.Invalid) return basic
        return if (differingDigitsFromMainPin < MIN_DURESS_PIN_DIFFERENCE) {
            Result.Invalid(SetupPinFailure.DuressPinTooSimilar)
        } else {
            Result.Valid
        }
    }

    fun isPinValid(pinEntry: PinEntry): Result {
        val pinAsText = pinEntry.toText()
        val isForbidden = lockScreenConfig.forbiddenPinCodes.any { it == pinAsText }
        return if (isForbidden) {
            Result.Invalid(SetupPinFailure.ForbiddenPin)
        } else {
            Result.Valid
        }
    }

    companion object {
        const val MIN_DURESS_PIN_DIFFERENCE = 2
    }
}
