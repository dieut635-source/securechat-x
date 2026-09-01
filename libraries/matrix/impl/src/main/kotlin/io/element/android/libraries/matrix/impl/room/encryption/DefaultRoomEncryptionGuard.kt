/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.encryption

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.encryption.RoomEncryptionGuard
import io.element.android.libraries.di.SessionScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * How long to wait for the encryption flag to arrive after a join before giving up on it.
 *
 * The value normally lands with the first sync that follows the join. Waiting is what keeps this
 * from throwing users out of perfectly good rooms on a slow connection; the timeout is what keeps a
 * homeserver from parking the app in an unverified room forever by simply never sending the state.
 */
private const val ENCRYPTION_STATE_TIMEOUT_MS = 10_000L

@ContributesBinding(SessionScope::class)
@Inject
class DefaultRoomEncryptionGuard(
    private val client: MatrixClient,
) : RoomEncryptionGuard {
    override suspend fun requireEncrypted(roomId: RoomId): Result<Unit> {
        val room = client.getRoom(roomId)
            ?: return Result.failure(RoomEncryptionGuard.Failure.Undetermined(roomId))

        return room.use {
            val isEncrypted = withTimeoutOrNull(ENCRYPTION_STATE_TIMEOUT_MS) {
                room.roomInfoFlow.map { it.isEncrypted }.filterNotNull().first()
            }
            when (isEncrypted) {
                true -> Result.success(Unit)
                false -> {
                    Timber.w("Leaving $roomId: it is not encrypted")
                    leaveQuietly(roomId)
                    Result.failure(RoomEncryptionGuard.Failure.NotEncrypted(roomId))
                }
                null -> {
                    // Never resolved: refuse the room, but do NOT leave it.
                    //
                    // Leaving here looks tempting - an unverifiable room is not one this app should
                    // sit in - and an earlier version of this guard did exactly that. It is the more
                    // dangerous choice. During the first sync after a login every room reports a
                    // null encryption flag for a while, so a guard that leaves on "unresolved" can
                    // walk an account out of every room it has, and nothing brings those back.
                    //
                    // Refusing without leaving is recoverable: the caller reports the join as failed
                    // and the room is not opened, while a later sync can still resolve the state.
                    // The asymmetry decides it - a false "not encrypted" that only blocks is an
                    // annoyance, a false one that leaves is unrecoverable data loss.
                    Timber.w("Refusing $roomId: encryption state did not resolve")
                    Result.failure(RoomEncryptionGuard.Failure.Undetermined(roomId))
                }
            }
        }
    }

    /**
     * Leaving is best effort on purpose. If it fails the join still has to be reported as refused:
     * staying in the room is bad, but telling the caller the room is safe would be worse.
     */
    private suspend fun leaveQuietly(roomId: RoomId) {
        client.getRoom(roomId)?.use { room ->
            room.leave().onFailure { Timber.e(it, "Could not leave unencrypted room $roomId") }
        }
    }
}
