/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/**
 * Proves the guard actually runs, rather than that it exists.
 *
 * [EncryptionGuardedTimelineTest] watches the interface for new methods; it cannot tell whether an
 * overridden method really consults the guard. That gap matters: every override is one line, and a
 * one-line mistake - calling the delegate directly - produces code that compiles, passes the drift
 * test, and silently publishes into a plaintext room.
 *
 * The delegate here throws on every call. So a method that reaches the delegate fails loudly instead
 * of quietly returning something plausible: any test below that does not see a clean guard failure
 * has found a hole.
 */
class EncryptionGuardedTimelineBehaviourTest {
    private class ExplodingTimeline : Timeline by FakeTimeline() {
        override suspend fun sendMessage(
            body: String,
            htmlBody: String?,
            intentionalMentions: List<io.element.android.libraries.matrix.api.room.IntentionalMention>,
            msgType: io.element.android.libraries.matrix.api.timeline.MsgType,
            asPlainText: Boolean,
        ): Result<Unit> = error("delegate reached: the guard did not run")
    }

    private fun guarded(encrypted: Boolean?) = EncryptionGuardedTimeline(
        delegate = ExplodingTimeline(),
        roomId = A_ROOM_ID,
        isEncrypted = { encrypted },
    )

    @Test
    fun `sending is refused when the room is not encrypted`() = runTest {
        val result = guarded(encrypted = false).sendMessage(
            body = "hello",
            htmlBody = null,
            intentionalMentions = emptyList(),
        )

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `sending is refused when the encryption state is unknown`() = runTest {
        // The dangerous direction. "Not yet known" must not be read as "fine": a homeserver that
        // never sends the encryption state would otherwise buy itself an unguarded window.
        val result = guarded(encrypted = null).sendMessage(
            body = "hello",
            htmlBody = null,
            intentionalMentions = emptyList(),
        )

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `forwarding is refused when the room is not encrypted`() = runTest {
        // forwardEvent moves a message from one room into this one. A prefix rule over method names
        // missed it while writing the guard, which is why it gets its own test.
        val result = guarded(encrypted = false)
            .forwardEvent(EventId("\$anEvent"), listOf(A_ROOM_ID))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `editing is refused when the room is not encrypted`() = runTest {
        val result = guarded(encrypted = false).editMessage(
            eventOrTransactionId = EventOrTransactionId.Event(EventId("\$anEvent")),
            body = "edited",
            htmlBody = null,
            intentionalMentions = emptyList(),
        )

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `sending a file is refused when the room is not encrypted`() = runTest {
        val result = guarded(encrypted = false).sendFile(
            file = File("/dev/null"),
            fileInfo = FileInfo(mimetype = null, size = null, thumbnailInfo = null, thumbnailSource = null),
            caption = null,
            formattedCaption = null,
            inReplyToEventId = null,
        )

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `creating a poll is refused when the room is not encrypted`() = runTest {
        // Another one a name-based rule would have let through.
        val result = guarded(encrypted = false).createPoll(
            question = "?",
            answers = listOf("a", "b"),
            maxSelections = 1,
            pollKind = PollKind.Disclosed,
        )

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `reacting is refused when the room is not encrypted`() = runTest {
        val result = guarded(encrypted = false)
            .toggleReaction(emoji = "x", eventOrTransactionId = EventOrTransactionId.Event(EventId("\$anEvent")))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `an encrypted room lets the call through to the delegate`() = runTest {
        // The control. Without it every test above would also pass if the guard refused everything
        // unconditionally, which would be a different bug wearing the same green tick.
        val thrown = runCatching {
            guarded(encrypted = true).sendMessage(
                body = "hello",
                htmlBody = null,
                intentionalMentions = emptyList(),
            )
        }

        assertThat(thrown.exceptionOrNull()?.message).contains("delegate reached")
    }
}
