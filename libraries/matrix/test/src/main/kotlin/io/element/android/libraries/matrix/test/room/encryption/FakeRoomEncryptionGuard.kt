/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.test.room.encryption

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.encryption.RoomEncryptionGuard

/**
 * Defaults to allowing the room through, so existing tests keep describing what they were written to
 * describe. A test that cares about the guard passes [result] explicitly.
 */
class FakeRoomEncryptionGuard(
    private val result: (RoomId) -> Result<Unit> = { Result.success(Unit) },
) : RoomEncryptionGuard {
    val checkedRooms = mutableListOf<RoomId>()

    override suspend fun requireEncrypted(roomId: RoomId): Result<Unit> {
        checkedRooms += roomId
        return result(roomId)
    }
}
