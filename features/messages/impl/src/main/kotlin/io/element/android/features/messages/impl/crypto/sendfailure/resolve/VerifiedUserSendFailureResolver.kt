/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.crypto.sendfailure.resolve

import androidx.compose.runtime.mutableStateOf
import io.element.android.libraries.matrix.api.core.SendHandle
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import timber.log.Timber

/**
 * This class is responsible for resolving and resending a failed message sent to a verified user.
 * It also allow to resend the message without resolving the failure, for example if the user has in the meantime verified their device again.
 * It's using the [VerifiedUserSendFailureIterator] to iterate over the different failures (ie. the different users concerned by the failure).
 * This way, the user can resolve and resend the message for each user concerned, one by one.
 */
class VerifiedUserSendFailureResolver(
    private val transactionId: TransactionId,
    private val sendHandle: SendHandle,
    private val iterator: VerifiedUserSendFailureIterator,
) {
    val currentSendFailure = mutableStateOf<LocalEventSendState.Failed.VerifiedUser?>(null)

    init {
        if (iterator.hasNext()) {
            currentSendFailure.value = iterator.next()
        }
    }

    suspend fun resend(): Result<Unit> {
        return sendHandle.retry()
            .onSuccess {
                Timber.d("Succeed to resend message with transactionId: $transactionId")
                currentSendFailure.value = null
            }
            .onFailure {
                Timber.e(it, "Failed to resend message with transactionId: $transactionId")
            }
    }
}
