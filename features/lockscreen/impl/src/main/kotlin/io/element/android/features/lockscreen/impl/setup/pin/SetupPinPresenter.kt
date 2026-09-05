/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.impl.setup.pin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.features.lockscreen.impl.LockScreenConfig
import io.element.android.features.lockscreen.impl.pin.PinCodeManager
import io.element.android.features.lockscreen.impl.pin.model.PinEntry
import io.element.android.features.lockscreen.impl.setup.pin.validation.PinValidator
import io.element.android.features.lockscreen.impl.setup.pin.validation.SetupPinFailure
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.meta.BuildMeta
import kotlinx.coroutines.delay

/**
 * Some time for the ui to refresh before showing confirmation step.
 */
private const val DELAY_BEFORE_CONFIRMATION_STEP_IN_MILLIS = 100L

@Inject
class SetupPinPresenter(
    private val lockScreenConfig: LockScreenConfig,
    private val pinValidator: PinValidator,
    private val buildMeta: BuildMeta,
    private val pinCodeManager: PinCodeManager,
) : Presenter<SetupPinState> {
    /**
     * Set by the node for the emergency step. PinCodeManager only reports the everyday code being
     * created, so the emergency step needs its own way to say it is finished.
     */
    var onDuressPinCreated: () -> Unit = {}
    @Composable
    override fun present(): SetupPinState = present(isDuressStep = false)

    /**
     * The emergency code is chosen with the same two-step flow as the everyday one, so this is the
     * same presenter with a different validation rule and a different destination.
     */
    @Composable
    fun present(isDuressStep: Boolean): SetupPinState {
        var choosePinEntry by remember {
            mutableStateOf(PinEntry.createEmpty(lockScreenConfig.pinSize))
        }
        var confirmPinEntry by remember {
            mutableStateOf(PinEntry.createEmpty(lockScreenConfig.pinSize))
        }
        var isConfirmationStep by remember {
            mutableStateOf(false)
        }
        var setupPinFailure by remember {
            mutableStateOf<SetupPinFailure?>(null)
        }
        LaunchedEffect(choosePinEntry) {
            if (choosePinEntry.isComplete()) {
                val validation = if (isDuressStep) {
                    // The real code never leaves PinCodeManager; only the distance comes back.
                    pinValidator.isDuressPinValid(
                        duressPin = choosePinEntry,
                        differingDigitsFromMainPin = pinCodeManager.countDifferencesFromPinCode(choosePinEntry.toText()),
                    )
                } else {
                    pinValidator.isPinValid(choosePinEntry)
                }
                when (val pinValidationResult = validation) {
                    is PinValidator.Result.Invalid -> {
                        setupPinFailure = pinValidationResult.failure
                    }
                    PinValidator.Result.Valid -> {
                        delay(DELAY_BEFORE_CONFIRMATION_STEP_IN_MILLIS)
                        isConfirmationStep = true
                    }
                }
            }
        }

        LaunchedEffect(confirmPinEntry) {
            if (confirmPinEntry.isComplete()) {
                if (confirmPinEntry == choosePinEntry) {
                    if (isDuressStep) {
                        pinCodeManager.createDuressPinCode(confirmPinEntry.toText())
                        onDuressPinCreated()
                    } else {
                        pinCodeManager.createPinCode(confirmPinEntry.toText())
                    }
                } else {
                    setupPinFailure = SetupPinFailure.PinsDoNotMatch
                }
            }
        }

        fun handleEvent(event: SetupPinEvent) {
            when (event) {
                is SetupPinEvent.OnPinEntryChanged -> {
                    // Use the fromConfirmationStep flag from ui to avoid race condition.
                    if (event.fromConfirmationStep) {
                        confirmPinEntry = confirmPinEntry.fillWith(event.entryAsText)
                    } else {
                        choosePinEntry = choosePinEntry.fillWith(event.entryAsText)
                    }
                }
                SetupPinEvent.ClearFailure -> {
                    when (setupPinFailure) {
                        is SetupPinFailure.PinsDoNotMatch -> {
                            choosePinEntry = choosePinEntry.clear()
                            confirmPinEntry = confirmPinEntry.clear()
                        }
                        is SetupPinFailure.ForbiddenPin -> {
                            choosePinEntry = choosePinEntry.clear()
                        }
                        is SetupPinFailure.DuressPinTooSimilar -> {
                            // Clear both: the user has to pick a different emergency code, and
                            // leaving the confirmation half-filled invites another near miss.
                            choosePinEntry = choosePinEntry.clear()
                            confirmPinEntry = confirmPinEntry.clear()
                        }
                        null -> Unit
                    }
                    isConfirmationStep = false
                    setupPinFailure = null
                }
            }
        }

        return SetupPinState(
            choosePinEntry = choosePinEntry,
            confirmPinEntry = confirmPinEntry,
            isConfirmationStep = isConfirmationStep,
            setupPinFailure = setupPinFailure,
            isDuressStep = isDuressStep,
            appName = buildMeta.applicationName,
            eventSink = ::handleEvent,
        )
    }
}
