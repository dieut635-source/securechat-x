/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.permalink

import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.matrix.api.core.MatrixPatterns
import io.element.android.libraries.matrix.api.core.RoomAlias
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilderError

@ContributesBinding(AppScope::class)
class DefaultPermalinkBuilder : PermalinkBuilder {
    override fun permalinkForUser(userId: UserId): Result<String> {
        if (!MatrixPatterns.isUserId(userId.value)) {
            return Result.failure(PermalinkBuilderError.InvalidData)
        }
        return Result.success(buildMatrixUri(USER_PATH, userId.value))
    }

    override fun permalinkForRoomAlias(roomAlias: RoomAlias): Result<String> {
        if (!MatrixPatterns.isRoomAlias(roomAlias.value)) {
            return Result.failure(PermalinkBuilderError.InvalidData)
        }
        return Result.success(buildMatrixUri(ROOM_PATH, roomAlias.value))
    }

    private fun buildMatrixUri(entityPath: String, identifier: String): String {
        val identifierWithoutSigil = identifier.drop(1)
        return "matrix:$entityPath/${Uri.encode(identifierWithoutSigil, ":")}"
    }

    private companion object {
        const val USER_PATH = "u"
        const val ROOM_PATH = "r"
    }
}
