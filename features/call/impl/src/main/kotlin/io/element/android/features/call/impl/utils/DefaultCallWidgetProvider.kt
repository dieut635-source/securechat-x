/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.widget.CallWidgetSettingsProvider
import io.element.android.services.appnavstate.api.ActiveRoomsHolder

private const val EMBEDDED_CALL_WIDGET_BASE_URL = "https://appassets.androidplatform.net/securechat-call/index.html"

@ContributesBinding(AppScope::class)
class DefaultCallWidgetProvider(
    private val matrixClientsProvider: MatrixClientProvider,
    private val callWidgetSettingsProvider: CallWidgetSettingsProvider,
    private val activeRoomsHolder: ActiveRoomsHolder,
) : CallWidgetProvider {
    override suspend fun getWidget(
        sessionId: SessionId,
        roomId: RoomId,
        isAudioCall: Boolean,
        clientId: String,
        languageTag: String?,
        theme: String?,
    ): Result<CallWidgetProvider.GetWidgetResult> = runCatchingExceptions {
        val matrixClient = matrixClientsProvider.getOrRestore(sessionId).getOrThrow()
        val room = activeRoomsHolder.getActiveRoomMatching(sessionId, roomId)
            ?: matrixClient.getJoinedRoom(roomId)
            ?: error("Room not found")

        val roomInfo = room.info()
        val isEncrypted = roomInfo.isEncrypted ?: room.getUpdatedIsEncrypted().getOrThrow()
        val widgetSettings = callWidgetSettingsProvider.provide(
            // SecureChat calls are bundled with the APK. Never load a user-configurable
            // remote call application inside the privileged call WebView.
            baseUrl = EMBEDDED_CALL_WIDGET_BASE_URL,
            encrypted = isEncrypted,
            direct = room.isDm(),
            isAudioCall = isAudioCall,
            hasActiveCall = roomInfo.hasRoomCall,
        )
        val callUrl = room.generateWidgetWebViewUrl(
            widgetSettings = widgetSettings,
            clientId = clientId,
            languageTag = languageTag,
            theme = theme,
        ).getOrThrow()
        check(isSecureChatCallDocumentUri(callUrl)) {
            "The generated call URL escaped the bundled SecureChat call document"
        }

        val driver = room.getWidgetDriver(widgetSettings).getOrThrow()

        CallWidgetProvider.GetWidgetResult(
            driver = driver,
            url = callUrl,
        )
    }
}
