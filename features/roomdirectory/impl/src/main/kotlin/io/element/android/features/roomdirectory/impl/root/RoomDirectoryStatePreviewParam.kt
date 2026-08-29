/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdirectory.impl.root

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.roomdirectory.api.RoomDescription
import io.element.android.libraries.matrix.api.core.RoomAlias
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

open class RoomDirectoryStatePreviewParam : PreviewParameterProvider<RoomDirectoryState> {
    override val values: Sequence<RoomDirectoryState>
        get() = sequenceOf(
            aRoomDirectoryState(),
            aRoomDirectoryState(
                query = "SecureChat",
                roomDescriptions = aRoomDescriptionList(),
            ),
            aRoomDirectoryState(
                query = "SecureChat",
                roomDescriptions = aRoomDescriptionList(),
                displayLoadMoreIndicator = true,
            ),
        )
}

fun aRoomDirectoryState(
    query: String = "",
    displayLoadMoreIndicator: Boolean = false,
    roomDescriptions: ImmutableList<RoomDescription> = persistentListOf(),
    eventSink: (RoomDirectoryEvent) -> Unit = {},
) = RoomDirectoryState(
    query = query,
    roomDescriptions = roomDescriptions,
    displayLoadMoreIndicator = displayLoadMoreIndicator,
    eventSink = eventSink,
)

fun aRoomDescriptionList(): ImmutableList<RoomDescription> {
    return persistentListOf(
        RoomDescription(
            roomId = RoomId("!exa:chat.securechat.com.au"),
            name = "SecureChat Android",
            topic = "SecureChat is a secure, private and decentralized messenger.",
            alias = RoomAlias("#securechat-android:chat.securechat.com.au"),
            avatarUrl = null,
            joinRule = RoomDescription.JoinRule.PUBLIC,
            numberOfMembers = 2765,
        ),
        RoomDescription(
            roomId = RoomId("!exi:chat.securechat.com.au"),
            name = "SecureChat Community",
            topic = "SecureChat is a secure, private and decentralized messenger.",
            alias = RoomAlias("#securechat-community:chat.securechat.com.au"),
            avatarUrl = null,
            joinRule = RoomDescription.JoinRule.UNKNOWN,
            numberOfMembers = 356,
        )
    )
}
