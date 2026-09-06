/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.call.api.CallData
import io.element.android.features.call.api.ElementCallEntryPoint
import io.element.android.features.call.impl.notifications.CallNotificationData
import io.element.android.features.call.impl.security.CallUiAccessTokenStore
import io.element.android.features.call.impl.ui.ElementCallActivity
import io.element.android.features.call.impl.utils.ActiveCallManager
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.services.appnavstate.api.AppForegroundStateService

@ContributesBinding(AppScope::class)
class DefaultElementCallEntryPoint(
    @ApplicationContext private val context: Context,
    private val activeCallManager: ActiveCallManager,
    private val callUiAccessTokenStore: CallUiAccessTokenStore,
    private val lockScreenService: LockScreenService,
    private val appForegroundStateService: AppForegroundStateService,
) : ElementCallEntryPoint {
    override fun startCall(callData: CallData) {
        val foregroundAccessToken = if (
            appForegroundStateService.isInForeground.value &&
            lockScreenService.lockState.value == LockScreenLockState.Unlocked
        ) {
            callUiAccessTokenStore.issue()
        } else {
            null
        }
        context.startActivity(ElementCallActivity.startCallIntent(context, callData, foregroundAccessToken))
    }

    override suspend fun handleIncomingCall(
        callData: CallData,
        eventId: EventId,
        senderId: UserId,
        roomName: String?,
        senderName: String?,
        avatarUrl: String?,
        timestamp: Long,
        expirationTimestamp: Long,
        notificationChannelId: String,
        textContent: String?,
    ) {
        val incomingCallNotificationData = CallNotificationData(
            sessionId = callData.sessionId,
            roomId = callData.roomId,
            eventId = eventId,
            senderId = senderId,
            roomName = roomName,
            senderName = senderName,
            avatarUrl = avatarUrl,
            timestamp = timestamp,
            expirationTimestamp = expirationTimestamp,
            notificationChannelId = notificationChannelId,
            textContent = textContent,
            audioOnly = callData.isAudioCall,
        )
        activeCallManager.registerIncomingCall(notificationData = incomingCallNotificationData)
    }
}
