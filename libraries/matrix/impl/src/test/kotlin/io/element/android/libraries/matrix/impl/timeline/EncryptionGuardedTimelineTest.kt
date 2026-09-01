/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.Timeline
import org.junit.Test

/**
 * Watches the `Timeline` interface for new methods, so nothing gains the ability to publish content
 * without someone deciding whether [EncryptionGuardedTimeline] has to cover it.
 *
 * The obvious version of this test does not work. Comparing the interface against the wrapper's own
 * `declaredMethods` looks right, but `Timeline by delegate` makes Kotlin generate a member for every
 * interface method whether it is overridden or not, so the two sets are always equal. That version
 * was written, it passed, and deleting `forwardEvent` from the wrapper left it passing - it was
 * protecting nothing.
 *
 * Anchoring on a written-down list is cruder and it works: upstream adding a method breaks this
 * test, and the person adding it has to say which side of the guard it belongs on.
 *
 * What this does NOT check is that the seventeen guarded methods really call the guard. That needs a
 * behavioural test driving each one with a guard that refuses.
 */
class EncryptionGuardedTimelineTest {
    /** Methods that publish content into the room. Each one is overridden in the wrapper. */
    private val publishesContent = setOf(
        "sendMessage", "editMessage", "editCaption", "replyMessage",
        "sendImage", "sendVideo", "sendAudio", "sendFile", "sendLocation",
        "sendVoiceMessage", "sendGallery",
        "createPoll", "editPoll", "sendPollResponse", "endPoll",
        "toggleReaction", "forwardEvent",
    )

    /**
     * Methods that do not publish content, with the reason they are safe to let through.
     *
     * Read receipts, `markAsRead` and pagination carry no content. Redaction, pinning, unpinning and
     * cancelling a send remove or annotate what is already in the room - blocking those would leave
     * a user unable to clean up in a room the app is already sitting in, which protects nobody.
     * `loadReplyDetails`, `isEventLoaded` and `getLatestEventId` only read.
     */
    private val doesNotPublish = setOf(
        "sendReadReceipt", "markAsRead", "paginate", "redactEvent", "cancelSend",
        "loadReplyDetails", "isEventLoaded", "pinEvent", "unpinEvent", "getLatestEventId",
        "close",
    )

    /**
     * Property getters and compiler-generated members. Listed rather than filtered by a `get` prefix,
     * because `getLatestEventId` is a real method and a prefix rule would wave it through.
     */
    private val notMethods = setOf(
        "getMode", "getTimelineItems", "getMembershipChangeEventReceived",
        "getOnSyncedEventReceived", "getBackwardPaginationStatus", "getForwardPaginationStatus",
        "access",
    )

    /**
     * Kotlin does not give the JVM its names unchanged: a value-class parameter appends a stability
     * hash (`sendMessage-hUnOzRk`), default arguments add a `$default` bridge, and a suspend function
     * with an interface body adds `$suspendImpl`.
     */
    private fun String.declaredName(): String = substringBefore('-').substringBefore('$')

    @Test
    fun `the timeline interface has not gained a method without a decision about the guard`() {
        val classified = publishesContent + doesNotPublish + notMethods
        val actual = Timeline::class.java.methods
            .map { it.name.declaredName() }
            .filterNot { it in setOf("equals", "hashCode", "toString") }
            .toSet()

        assertThat(actual - classified).isEmpty()
        assertThat(classified - actual).isEmpty()
    }
}
