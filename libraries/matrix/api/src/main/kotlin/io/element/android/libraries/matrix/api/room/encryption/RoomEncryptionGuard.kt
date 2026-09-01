/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.room.encryption

import io.element.android.libraries.matrix.api.core.RoomId

/**
 * Refuses to let SecureChat stay in a room that is not end-to-end encrypted.
 *
 * Creating a room already forces encryption on, but that only covers rooms this app makes. Matrix is
 * an open protocol: a compromised homeserver, or simply another client on the same account, can make
 * a plaintext room and invite a SecureChat user into it. Nothing in the join or send paths used to
 * check, so the app would have joined it and sent messages the server could read - which is the one
 * thing the whole deployment is built to prevent.
 *
 * Enforced at the join, because that is the single place every room enters the app. A room that is
 * never joined can never be sent to, so guarding here covers all twelve send methods on the timeline
 * without having to remember each of them - and without a new upstream method silently escaping.
 */
interface RoomEncryptionGuard {
    /**
     * Verifies that [roomId] is encrypted, leaving it if it is not.
     *
     * Fails closed. `RoomInfo.isEncrypted` is nullable because the state may not have synced yet;
     * "not yet known" is treated as "not proven", so the room is left rather than trusted. A brief
     * wait is allowed first, since the value normally arrives moments after the join.
     */
    suspend fun requireEncrypted(roomId: RoomId): Result<Unit>

    sealed class Failure(message: String) : Exception(message) {
        /** The room exists and is definitively not encrypted. */
        class NotEncrypted(roomId: RoomId) : Failure("Room $roomId is not encrypted")

        /** Encryption state never arrived, so it cannot be trusted. */
        class Undetermined(roomId: RoomId) : Failure("Encryption state for $roomId never resolved")
    }
}
