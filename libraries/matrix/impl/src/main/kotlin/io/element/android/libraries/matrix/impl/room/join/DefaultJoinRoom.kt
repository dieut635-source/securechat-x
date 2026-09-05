/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room.join

import dev.zacsweers.metro.ContributesBinding
import im.vector.app.features.analytics.plan.JoinedRoom
import io.element.android.libraries.core.extensions.mapFailure
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import io.element.android.libraries.matrix.api.exception.ClientException
import io.element.android.libraries.matrix.api.exception.ErrorKind
import io.element.android.libraries.matrix.api.room.encryption.RoomEncryptionGuard
import io.element.android.libraries.matrix.api.room.join.JoinRoom
import io.element.android.libraries.matrix.impl.analytics.toAnalyticsJoinedRoom
import io.element.android.services.analytics.api.AnalyticsService

@ContributesBinding(SessionScope::class)
class DefaultJoinRoom(
    private val client: MatrixClient,
    private val analyticsService: AnalyticsService,
    private val encryptionGuard: RoomEncryptionGuard,
) : JoinRoom {
    override suspend fun invoke(
        roomIdOrAlias: RoomIdOrAlias,
        serverNames: List<String>,
        trigger: JoinedRoom.Trigger
    ): Result<Unit> {
        return when (roomIdOrAlias) {
            is RoomIdOrAlias.Id -> {
                if (serverNames.isEmpty()) {
                    client.joinRoom(roomIdOrAlias.roomId)
                } else {
                    client.joinRoomByIdOrAlias(roomIdOrAlias, serverNames)
                }
            }
            is RoomIdOrAlias.Alias -> {
                client.joinRoomByIdOrAlias(roomIdOrAlias, serverNames = emptyList())
            }
        }.onSuccess { roomInfo ->
            if (roomInfo != null) {
                analyticsService.capture(roomInfo.toAnalyticsJoinedRoom(trigger))
            }
        }.mapCatching { roomInfo ->
            // Every room enters SecureChat through here, so this is where "encrypted or not at all"
            // is enforced. A plaintext room created by a compromised server or by another client on
            // the same account would otherwise be joinable, and everything sent in it readable by
            // the server. The guard leaves the room and turns the join into a failure.
            val joinedRoomId = roomInfo?.id ?: (roomIdOrAlias as? RoomIdOrAlias.Id)?.roomId
            if (joinedRoomId == null) {
                // Joining by alias returns a null RoomInfo when the room does not reach the room
                // list within the SDK's timeout - but the join itself has already happened. So this
                // is not "nothing occurred": it is "we are in a room whose encryption we cannot
                // check", which is exactly the case the guard exists for.
                //
                // An earlier version let this through, on the grounds that there was no room id to
                // check. That made the branch fail open, and inconsistently so: the guard itself
                // treats an unresolved encryption state as unsafe, while the caller treated an
                // unresolved room id as safe.
                //
                // Reported as a failure and not left, for the same reason the guard does not leave
                // on an unresolved state: without a room id there is nothing to leave, and a failed
                // join keeps the room closed to the user. The send-time guard covers what remains.
                throw RoomEncryptionGuard.Failure.Undetermined(RoomId("!unknown"))
            }
            encryptionGuard.requireEncrypted(joinedRoomId).getOrThrow()
            roomInfo
        }.mapFailure {
            if (it is ClientException.MatrixApi) {
                when (it.kind) {
                    ErrorKind.Forbidden -> JoinRoom.Failures.UnauthorizedJoin
                    else -> it
                }
            } else {
                it
            }
        }.map { }
    }
}
