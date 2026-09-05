/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.GalleryItemInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.room.IntentionalMention
import io.element.android.libraries.matrix.api.room.location.AssetType
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.InReplyTo
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import io.element.android.libraries.matrix.api.room.encryption.RoomEncryptionGuard
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.api.timeline.Timeline

/**
 * Wraps a timeline so nothing can be published into a room that is not end-to-end encrypted.
 *
 * SecureChat forces encryption on every room it creates, and refuses to open one it joined that
 * turns out to be plaintext. Neither covers a room that arrives some other way - through a sync from
 * another client on the account, or a join whose state never resolved - so this is the last line:
 * the point where content actually leaves the device.
 *
 * Every method that puts content on the server is overridden; everything else passes straight
 * through by delegation. That list is not a matter of taste, and it is not derived from the method
 * name: `forwardEvent` and `createPoll` publish content while sounding like neither a send nor an
 * edit, and both would have been missed by a prefix rule. `EncryptionGuardedTimelineTest` walks the
 * `Timeline` interface with reflection and fails when an unguarded method appears, so a new upstream
 * method cannot quietly slip past.
 *
 * Deliberately not guarded: read receipts, pagination, redaction, pinning and cancelling a send.
 * None of them publish message content, and blocking them would break the ability to read and to
 * clean up in a room the app is already in.
 */
internal class EncryptionGuardedTimeline(
    private val delegate: Timeline,
    private val roomId: RoomId,
    private val isEncrypted: () -> Boolean?,
) : Timeline by delegate {
    /**
     * Fails closed. `isEncrypted` is nullable because the state may not have synced yet, and "not
     * yet known" is not "safe": a homeserver that simply never sends the encryption state would
     * otherwise buy itself an unguarded window.
     */
    private suspend fun <T> guarded(block: suspend () -> Result<T>): Result<T> =
        if (isEncrypted() == true) {
            block()
        } else {
            Result.failure(RoomEncryptionGuard.Failure.NotEncrypted(roomId))
        }

    override suspend fun createPoll(
        question: String,
        answers: List<String>,
        maxSelections: Int,
        pollKind: PollKind,
    ): Result<Unit> = guarded { delegate.createPoll(question = question, answers = answers, maxSelections = maxSelections, pollKind = pollKind) }

    override suspend fun editCaption(
        eventOrTransactionId: EventOrTransactionId,
        caption: String?,
        formattedCaption: String?,
    ): Result<Unit> = guarded { delegate.editCaption(eventOrTransactionId = eventOrTransactionId, caption = caption, formattedCaption = formattedCaption) }

    override suspend fun editMessage(
        eventOrTransactionId: EventOrTransactionId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
    ): Result<Unit> = guarded { delegate.editMessage(eventOrTransactionId = eventOrTransactionId, body = body, htmlBody = htmlBody, intentionalMentions = intentionalMentions) }

    override suspend fun editPoll(
        pollStartId: EventId,
        question: String,
        answers: List<String>,
        maxSelections: Int,
        pollKind: PollKind,
    ): Result<Unit> = guarded { delegate.editPoll(pollStartId = pollStartId, question = question, answers = answers, maxSelections = maxSelections, pollKind = pollKind) }

    override suspend fun endPoll(
        pollStartId: EventId,
        text: String,
    ): Result<Unit> = guarded { delegate.endPoll(pollStartId = pollStartId, text = text) }

    override suspend fun forwardEvent(
        eventId: EventId,
        roomIds: List<RoomId>,
    ): Result<Unit> = guarded { delegate.forwardEvent(eventId = eventId, roomIds = roomIds) }

    override suspend fun replyMessage(
        repliedToEventId: EventId,
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        fromNotification: Boolean,
        msgType: MsgType,
    ): Result<Unit> = guarded { delegate.replyMessage(repliedToEventId = repliedToEventId, body = body, htmlBody = htmlBody, intentionalMentions = intentionalMentions, fromNotification = fromNotification, msgType = msgType) }

    override suspend fun sendAudio(
        file: File,
        audioInfo: AudioInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
    ): Result<MediaUploadHandler> = guarded { delegate.sendAudio(file = file, audioInfo = audioInfo, caption = caption, formattedCaption = formattedCaption, inReplyToEventId = inReplyToEventId) }

    override suspend fun sendFile(
        file: File,
        fileInfo: FileInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
    ): Result<MediaUploadHandler> = guarded { delegate.sendFile(file = file, fileInfo = fileInfo, caption = caption, formattedCaption = formattedCaption, inReplyToEventId = inReplyToEventId) }

    override suspend fun sendGallery(
        items: List<GalleryItemInfo>,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
    ): Result<MediaUploadHandler> = guarded { delegate.sendGallery(items = items, caption = caption, formattedCaption = formattedCaption, inReplyToEventId = inReplyToEventId) }

    override suspend fun sendImage(
        file: File,
        thumbnailFile: File?,
        imageInfo: ImageInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
    ): Result<MediaUploadHandler> = guarded { delegate.sendImage(file = file, thumbnailFile = thumbnailFile, imageInfo = imageInfo, caption = caption, formattedCaption = formattedCaption, inReplyToEventId = inReplyToEventId) }

    override suspend fun sendLocation(
        body: String,
        geoUri: String,
        description: String?,
        zoomLevel: Int?,
        assetType: AssetType?,
        inReplyToEventId: EventId?,
    ): Result<Unit> = guarded { delegate.sendLocation(body = body, geoUri = geoUri, description = description, zoomLevel = zoomLevel, assetType = assetType, inReplyToEventId = inReplyToEventId) }

    override suspend fun sendMessage(
        body: String,
        htmlBody: String?,
        intentionalMentions: List<IntentionalMention>,
        msgType: MsgType,
        asPlainText: Boolean,
    ): Result<Unit> = guarded { delegate.sendMessage(body = body, htmlBody = htmlBody, intentionalMentions = intentionalMentions, msgType = msgType, asPlainText = asPlainText) }

    override suspend fun sendPollResponse(
        pollStartId: EventId,
        answers: List<String>,
    ): Result<Unit> = guarded { delegate.sendPollResponse(pollStartId = pollStartId, answers = answers) }

    override suspend fun sendVideo(
        file: File,
        thumbnailFile: File?,
        videoInfo: VideoInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
    ): Result<MediaUploadHandler> = guarded { delegate.sendVideo(file = file, thumbnailFile = thumbnailFile, videoInfo = videoInfo, caption = caption, formattedCaption = formattedCaption, inReplyToEventId = inReplyToEventId) }

    override suspend fun sendVoiceMessage(
        file: File,
        audioInfo: AudioInfo,
        waveform: List<Float>,
        inReplyToEventId: EventId?,
    ): Result<MediaUploadHandler> = guarded { delegate.sendVoiceMessage(file = file, audioInfo = audioInfo, waveform = waveform, inReplyToEventId = inReplyToEventId) }

    override suspend fun toggleReaction(
        emoji: String,
        eventOrTransactionId: EventOrTransactionId,
    ): Result<Boolean> = guarded { delegate.toggleReaction(emoji = emoji, eventOrTransactionId = eventOrTransactionId) }
}
