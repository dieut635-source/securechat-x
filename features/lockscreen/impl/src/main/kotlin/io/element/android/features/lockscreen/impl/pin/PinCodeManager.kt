/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.impl.pin

import kotlinx.coroutines.flow.Flow

/**
 * This interface is the main interface to manage the pin code.
 * Implementation should take care of encrypting the pin code and storing it.
 */
interface PinCodeManager {
    /**
     * Callbacks for pin code management events.
     */
    interface Callback {
        /**
         * Called when the pin code is verified.
         */
        fun onPinCodeVerified()

        /**
         * Called when the pin code is created.
         */
        fun onPinCodeCreated()

        /**
         * Called when the pin code is removed.
         */
        fun onPinCodeRemoved()
    }

    /**
     * Register a callback to be notified of pin code management events.
     */
    fun addCallback(callback: Callback)

    /**
     * Unregister callback to be notified of pin code management events.
     */
    fun removeCallback(callback: Callback)

    /**
     * @return true if a pin code is available.
     */
    fun hasPinCode(): Flow<Boolean>

    /**
     * @return the size of the saved pin code. Return null if no pin code is saved.
     */
    suspend fun getPinCodeSize(): Int?

    /**
     * Creates a new encrypted pin code.
     * @param pinCode the clear pin code to create
     */
    suspend fun createPinCode(pinCode: String)

    /**
     * @return true if the pin code is correct.
     */
    /**
     * Verifies the code that was entered.
     *
     * Returns true for the main code and, indistinguishably, for the duress code — entering the
     * duress code erases every account first and then reports success. From the outside the two are
     * identical: no error, no warning, no different screen. Anything that betrayed the difference
     * would tell whoever is standing over the phone to keep pressing.
     */
    suspend fun verifyPinCode(pinCode: String): Boolean

    /** True once a duress code has been set. */
    suspend fun hasDuressPinCode(): Boolean

    /**
     * Stores the duress code. The caller is responsible for refusing one that is too close to the
     * main code; see PinValidator.
     */
    suspend fun createDuressPinCode(pinCode: String)

    /**
     * How many digit positions [pinCode] differs from the main code in.
     *
     * The setup screen needs to know whether a proposed duress code is dangerously close to the
     * real one, but it must never hold the real one to find out — a PIN in a Compose state, or
     * worse in a Parcelize'd nav target, is a PIN written to disk. So the comparison happens in
     * here, where the code is already decrypted, and only a number comes out.
     *
     * Returns [Int.MAX_VALUE] when there is no main code to compare against.
     */
    suspend fun countDifferencesFromPinCode(pinCode: String): Int

    /**
     * Deletes the previously created pin code.
     */
    suspend fun deletePinCode()

    /**
     * @return the number of remaining attempts before the pin code is blocked.
     */
    suspend fun getRemainingPinCodeAttemptsNumber(): Int
}
