/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.permalink

import android.net.Uri
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.MatrixPatterns
import io.element.android.libraries.matrix.api.core.RoomAlias
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.permalink.MatrixToConverter
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import kotlinx.collections.immutable.toImmutableList
import org.matrix.rustcomponents.sdk.MatrixId
import org.matrix.rustcomponents.sdk.parseMatrixEntityFrom

/**
 * This class turns a `matrix:` protocol link or a validated matrix.to permalink into
 * [PermalinkData]. Unrecognised web origins remain ordinary fallback links.
 */
@ContributesBinding(AppScope::class)
class DefaultPermalinkParser(
    private val matrixToConverter: MatrixToConverter
) : PermalinkParser {
    /**
     * Turns a uri string to a [PermalinkData].
     * https://github.com/matrix-org/matrix-doc/blob/master/proposals/1704-matrix.to-permalinks.md
     */
    override fun parse(uriString: String): PermalinkData {
        val uri = uriString.toUri()
        val matrixToUri = if (uri.scheme == "matrix") {
            // take matrix: URI as is to [parseMatrixEntityFrom]
            uri
        } else {
            // Only a converter-approved web permalink is passed to the SDK parser.
            matrixToConverter.convert(uri) ?: return PermalinkData.FallbackLink(uri)
        }

        matrixToUri.userIdWithSlash()?.let { userId ->
            return PermalinkData.UserLink(userId = userId)
        }

        val result = runCatchingExceptions {
            parseMatrixEntityFrom(matrixToUri.toString())
        }.getOrNull()
        return if (result == null) {
            PermalinkData.FallbackLink(uri)
        } else {
            val viaParameters = result.via.toImmutableList()
            when (val id = result.id) {
                is MatrixId.User -> PermalinkData.UserLink(
                    userId = UserId(id.id),
                )
                is MatrixId.Room -> PermalinkData.RoomLink(
                    roomIdOrAlias = RoomId(id.id).toRoomIdOrAlias(),
                    viaParameters = viaParameters,
                )
                is MatrixId.RoomAlias -> PermalinkData.RoomLink(
                    roomIdOrAlias = RoomAlias(id.alias).toRoomIdOrAlias(),
                    viaParameters = viaParameters,
                )
                is MatrixId.EventOnRoomId -> PermalinkData.RoomLink(
                    roomIdOrAlias = RoomId(id.roomId).toRoomIdOrAlias(),
                    eventId = EventId(id.eventId),
                    viaParameters = viaParameters,
                )
                is MatrixId.EventOnRoomAlias -> PermalinkData.RoomLink(
                    roomIdOrAlias = RoomAlias(id.alias).toRoomIdOrAlias(),
                    eventId = EventId(id.eventId),
                    viaParameters = viaParameters,
                )
            }
        }
    }
}

/**
 * A slash is allowed in the local part of a user id, but it also separates an identifier from an
 * event id in a permalink, so [parseMatrixEntityFrom] cannot tell the two apart. Only a user id can
 * start with an `@`, so recover it here rather than letting the ambiguity reach the SDK.
 */
private fun Uri.userIdWithSlash(): UserId? {
    val identifier = fragment
        ?.removePrefix("/")
        ?.substringBefore('?')
        ?: return null
    if (!identifier.startsWith("@") || !identifier.contains('/')) return null
    return identifier.takeIf { MatrixPatterns.isUserId(it) }?.let(::UserId)
}
