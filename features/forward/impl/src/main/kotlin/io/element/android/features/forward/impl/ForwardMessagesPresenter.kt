/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.forward.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.TimelineProvider
import io.element.android.libraries.matrix.api.timeline.getActiveTimeline
import io.element.android.libraries.matrix.api.timeline.item.event.EmoteMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EventContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.NoticeMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.mdm.api.MdmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

@AssistedInject
class ForwardMessagesPresenter(
    @Assisted eventId: String,
    @Assisted private val timelineProvider: TimelineProvider,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
    private val mdmService: MdmService,
) : Presenter<ForwardMessagesState> {
    private val eventId: EventId = EventId(eventId)

    @AssistedFactory
    fun interface Factory {
        fun create(eventId: String, timelineProvider: TimelineProvider): ForwardMessagesPresenter
    }

    private val forwardingActionState: MutableState<AsyncAction<List<RoomId>>> = mutableStateOf(AsyncAction.Uninitialized)

    fun onRoomSelected(roomIds: List<RoomId>) {
        sessionCoroutineScope.forwardEvent(eventId, roomIds)
    }

    @Composable
    override fun present(): ForwardMessagesState {
        fun handleEvent(event: ForwardMessagesEvent) {
            when (event) {
                ForwardMessagesEvent.ClearError -> forwardingActionState.value = AsyncAction.Uninitialized
            }
        }

        return ForwardMessagesState(
            forwardAction = forwardingActionState.value,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.forwardEvent(
        eventId: EventId,
        roomIds: List<RoomId>,
    ) = launch {
        suspend {
            val timeline = timelineProvider.getActiveTimeline()
            timeline.assertForwardingAllowed(eventId)
            timeline.forwardEvent(eventId, roomIds)
                .onFailure {
                    Timber.e(it, "Error while forwarding event")
                }
                .getOrThrow()
            roomIds
        }.runCatchingUpdatingState(forwardingActionState)
    }

    private suspend fun Timeline.assertForwardingAllowed(eventId: EventId) {
        // The SDK forwards the complete source event, including any media and caption. Resolve the
        // source before reading the latest policy instead of trusting the screen that opened this flow.
        // Missing, encrypted, malformed, location, or otherwise unknown content is denied rather
        // than risking forwarding an attachment whose type could not be established.
        val content = timelineItems.first()
            .filterIsInstance<MatrixTimelineItem.Event>()
            .firstOrNull { it.eventId == eventId }
            ?.event
            ?.content
        check(mdmService.config.value.allowFileSend || content.isTextOnlyMessage()) {
            "Forwarding files is disabled by the managed configuration"
        }
    }

    private fun EventContent?.isTextOnlyMessage(): Boolean {
        val messageType = (this as? MessageContent)?.type ?: return false
        return messageType is TextMessageType ||
            messageType is NoticeMessageType ||
            messageType is EmoteMessageType
    }
}
