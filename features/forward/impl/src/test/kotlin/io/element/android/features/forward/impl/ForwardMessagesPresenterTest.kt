/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.forward.impl

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.GalleryMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_UNIQUE_ID
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.LiveTimelineProvider
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.mdm.test.FakeMdmService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ForwardMessagesPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createForwardMessagesPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.forwardAction.isUninitialized()).isTrue()
        }
    }

    @Test
    fun `present - forward successful`() = runTest {
        val forwardEventLambda = lambdaRecorder { _: EventId, _: List<RoomId> ->
            Result.success(Unit)
        }
        val timeline = FakeTimeline().apply {
            this.forwardEventLambda = forwardEventLambda
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val presenter = createForwardMessagesPresenter(fakeRoom = room)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val summary = aRoomSummary()
            presenter.onRoomSelected(listOf(summary.roomId))
            val forwardingState = awaitItem()
            assertThat(forwardingState.forwardAction.isLoading()).isTrue()
            val successfulForwardState = awaitItem()
            assertThat(successfulForwardState.forwardAction).isEqualTo(AsyncAction.Success(listOf(summary.roomId)))
            forwardEventLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - select a room and forward failed, then clear`() = runTest {
        val forwardEventLambda = lambdaRecorder { _: EventId, _: List<RoomId> ->
            Result.failure<Unit>(IllegalStateException("error"))
        }
        val timeline = FakeTimeline().apply {
            this.forwardEventLambda = forwardEventLambda
        }
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val presenter = createForwardMessagesPresenter(fakeRoom = room)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val summary = aRoomSummary()
            presenter.onRoomSelected(listOf(summary.roomId))
            skipItems(1)
            val failedForwardState = awaitItem()
            assertThat(failedForwardState.forwardAction.isFailure()).isTrue()
            // Then clear error
            failedForwardState.eventSink(ForwardMessagesEvent.ClearError)
            assertThat(awaitItem().forwardAction.isUninitialized()).isTrue()
            forwardEventLambda.assertions().isCalledOnce()
        }
    }

    @Test
    fun `allow_file_send off preserves text-only forwarding`() = runTest {
        val forwardEventLambda = lambdaRecorder { _: EventId, _: List<RoomId> -> Result.success(Unit) }
        val timeline = timelineWithMessage().apply {
            this.forwardEventLambda = forwardEventLambda
        }
        val presenter = createForwardMessagesPresenter(
            fakeRoom = FakeJoinedRoom(liveTimeline = timeline),
            mdmService = FakeMdmService(MdmConfig.default.copy(allowFileSend = false)),
        )
        val roomId = aRoomSummary().roomId

        presenter.onRoomSelected(listOf(roomId))
        advanceUntilIdle()

        forwardEventLambda.assertions().isCalledOnce().with(value(AN_EVENT_ID), value(listOf(roomId)))
    }

    @Test
    fun `allow_file_send off blocks media file voice and gallery forwarding`() = runTest {
        attachmentMessageTypes().forEach { messageType ->
            val forwardEventLambda = lambdaRecorder { _: EventId, _: List<RoomId> -> Result.success(Unit) }
            val timeline = timelineWithMessage(messageType).apply {
                this.forwardEventLambda = forwardEventLambda
            }
            val presenter = createForwardMessagesPresenter(
                fakeRoom = FakeJoinedRoom(liveTimeline = timeline),
                mdmService = FakeMdmService(MdmConfig.default.copy(allowFileSend = false)),
            )

            presenter.onRoomSelected(listOf(aRoomSummary().roomId))
            advanceUntilIdle()

            forwardEventLambda.assertions().isNeverCalled()
        }
    }

    @Test
    fun `allow_file_send off conservatively blocks an event missing from the timeline`() = runTest {
        val forwardEventLambda = lambdaRecorder { _: EventId, _: List<RoomId> -> Result.success(Unit) }
        val timeline = FakeTimeline().apply {
            this.forwardEventLambda = forwardEventLambda
        }
        val presenter = createForwardMessagesPresenter(
            fakeRoom = FakeJoinedRoom(liveTimeline = timeline),
            mdmService = FakeMdmService(MdmConfig.default.copy(allowFileSend = false)),
        )

        presenter.onRoomSelected(listOf(aRoomSummary().roomId))
        advanceUntilIdle()

        forwardEventLambda.assertions().isNeverCalled()
    }

    @Test
    fun `policy change while resolving an event blocks media at the forwarding boundary`() = runTest {
        val policy = FakeMdmService(MdmConfig.default.copy(allowFileSend = true))
        val timelineItems = MutableSharedFlow<List<MatrixTimelineItem>>()
        val forwardEventLambda = lambdaRecorder { _: EventId, _: List<RoomId> -> Result.success(Unit) }
        val timeline = FakeTimeline(timelineItems = timelineItems).apply {
            this.forwardEventLambda = forwardEventLambda
        }
        val presenter = createForwardMessagesPresenter(
            fakeRoom = FakeJoinedRoom(liveTimeline = timeline),
            mdmService = policy,
        )

        presenter.onRoomSelected(listOf(aRoomSummary().roomId))
        advanceUntilIdle()
        policy.emit(MdmConfig.default.copy(allowFileSend = false))
        timelineItems.emit(
            listOf(
                MatrixTimelineItem.Event(
                    A_UNIQUE_ID,
                    anEventTimelineItem(content = aMessageContent(messageType = attachmentMessageTypes().first())),
                )
            )
        )
        advanceUntilIdle()

        forwardEventLambda.assertions().isNeverCalled()
    }
}

fun TestScope.createForwardMessagesPresenter(
    eventId: EventId = AN_EVENT_ID,
    fakeRoom: FakeJoinedRoom = FakeJoinedRoom(),
    mdmService: MdmService = FakeMdmService(),
) = ForwardMessagesPresenter(
    eventId = eventId.value,
    timelineProvider = LiveTimelineProvider(fakeRoom),
    sessionCoroutineScope = this,
    mdmService = mdmService,
)

private fun timelineWithMessage(messageType: MessageType? = null): FakeTimeline {
    val content = if (messageType == null) aMessageContent() else aMessageContent(messageType = messageType)
    return FakeTimeline(
        timelineItems = MutableStateFlow(
            listOf(MatrixTimelineItem.Event(A_UNIQUE_ID, anEventTimelineItem(content = content)))
        )
    )
}

private fun attachmentMessageTypes(): List<MessageType> {
    val source = MediaSource(url = "mxc://example.org/media", json = null)
    return listOf(
        ImageMessageType(
            filename = "image.jpg",
            caption = null,
            formattedCaption = null,
            source = source,
            info = null,
        ),
        FileMessageType(
            filename = "file.pdf",
            caption = null,
            formattedCaption = null,
            source = source,
            info = null,
        ),
        VoiceMessageType(
            filename = "voice.ogg",
            caption = null,
            formattedCaption = null,
            source = source,
            info = null,
            details = null,
        ),
        GalleryMessageType(body = "mixed gallery", formatted = null, items = emptyList()),
    )
}
