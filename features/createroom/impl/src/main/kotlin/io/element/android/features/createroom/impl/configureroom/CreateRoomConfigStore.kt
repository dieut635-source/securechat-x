/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.createroom.impl.configureroom

import android.net.Uri
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.matrix.api.room.alias.RoomAliasHelper
import io.element.android.libraries.matrix.api.spaces.SpaceRoom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.io.File

@Inject
class CreateRoomConfigStore(
    private val roomAliasHelper: RoomAliasHelper,
) {
    private val createRoomConfigFlow: MutableStateFlow<CreateRoomConfig> = MutableStateFlow(CreateRoomConfig())

    private var cachedAvatarUri: Uri? = null
        set(value) {
            field?.path?.let { File(it) }?.safeDelete()
            field = value
        }

    fun getCreateRoomConfigFlow(): StateFlow<CreateRoomConfig> = createRoomConfigFlow

    fun setRoomName(roomName: String) {
        createRoomConfigFlow.getAndUpdate { config ->
            val roomAccessWithNewAddress = if (config.visibilityState is RoomVisibilityState.Public) {
                val roomAddress = config.visibilityState.roomAddress
                if (roomAddress is RoomAddress.AutoFilled || roomName.isEmpty()) {
                    val roomAliasName = roomAliasHelper.roomAliasNameFromRoomDisplayName(roomName)
                    config.visibilityState.copy(roomAddress = RoomAddress.AutoFilled(roomAliasName))
                } else {
                    config.visibilityState
                }
            } else {
                config.visibilityState
            }
            config.copy(
                roomName = roomName.takeIf { it.isNotEmpty() },
                visibilityState = roomAccessWithNewAddress,
            )
        }
    }

    fun setTopic(topic: String) {
        createRoomConfigFlow.getAndUpdate { config ->
            config.copy(topic = topic.takeIf { it.isNotEmpty() })
        }
    }

    fun setAvatarUri(uri: Uri?, cached: Boolean = false) {
        cachedAvatarUri = uri.takeIf { cached }
        createRoomConfigFlow.getAndUpdate { config ->
            config.copy(avatarUri = uri?.toString())
        }
    }

    /**
     * Sets both the room visibility and its access based on the provided join rule.
     *
     * A public join rule is downgraded to private. SecureChat creates public rooms without
     * encryption, which would leave message bodies readable on the server and inside its backups,
     * so the choice is refused at the data layer rather than only hidden in the UI: the presenter
     * no longer offers it, but an event carrying a public rule must not be able to produce a
     * public room in the window before the presenter's fallback runs.
     */
    fun setJoinRule(joinRule: JoinRuleItem) {
        val safeJoinRule = when (joinRule) {
            is JoinRuleItem.PrivateVisibility -> joinRule
            is JoinRuleItem.PublicVisibility -> JoinRuleItem.PrivateVisibility.Private
        }
        createRoomConfigFlow.getAndUpdate { config ->
            config.copy(
                visibilityState = RoomVisibilityState.Private(joinRuleItem = safeJoinRule)
            )
        }
    }

    fun setRoomAddress(address: String) {
        createRoomConfigFlow.getAndUpdate { config ->
            config.copy(
                visibilityState = when (config.visibilityState) {
                    is RoomVisibilityState.Public -> {
                        val sanitizedAddress = address.lowercase()
                        config.visibilityState.copy(roomAddress = RoomAddress.Edited(sanitizedAddress))
                    }
                    else -> config.visibilityState
                }
            )
        }
    }

    fun setParentSpace(parentSpace: SpaceRoom?, updateVisibility: Boolean) {
        createRoomConfigFlow.getAndUpdate { config ->
            val visibilityState = if (parentSpace != null && updateVisibility) {
                RoomVisibilityState.Private(JoinRuleItem.PrivateVisibility.Restricted(parentSpace.roomId))
            } else {
                config.visibilityState
            }
            config.copy(
                parentSpace = parentSpace,
                visibilityState = visibilityState
            )
        }
    }

    fun clearCachedData() {
        cachedAvatarUri = null
    }
}
