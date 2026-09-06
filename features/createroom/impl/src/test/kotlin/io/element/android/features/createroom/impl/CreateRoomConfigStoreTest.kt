/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.createroom.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.createroom.impl.configureroom.CreateRoomConfigStore
import io.element.android.features.createroom.impl.configureroom.JoinRuleItem
import io.element.android.features.createroom.impl.configureroom.RoomVisibilityState
import io.element.android.libraries.matrix.test.room.alias.FakeRoomAliasHelper
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * SecureChat creates public rooms without encryption, which would leave message bodies readable on
 * the server and inside its backups. The presenter no longer offers a public join rule, but hiding
 * an option in the UI is not a security control: these tests pin the refusal at the data layer,
 * where it holds regardless of which screen or event reaches the store.
 */
class CreateRoomConfigStoreTest {
    @Test
    fun `setJoinRule downgrades a public join rule to private`() = runTest {
        val store = CreateRoomConfigStore(FakeRoomAliasHelper())

        store.setJoinRule(JoinRuleItem.PublicVisibility.Public)

        assertThat(store.getCreateRoomConfigFlow().value.visibilityState)
            .isEqualTo(RoomVisibilityState.Private(JoinRuleItem.PrivateVisibility.Private))
    }

    @Test
    fun `setJoinRule downgrades an ask-to-join join rule to private`() = runTest {
        val store = CreateRoomConfigStore(FakeRoomAliasHelper())

        store.setJoinRule(JoinRuleItem.PublicVisibility.AskToJoin)

        assertThat(store.getCreateRoomConfigFlow().value.visibilityState)
            .isEqualTo(RoomVisibilityState.Private(JoinRuleItem.PrivateVisibility.Private))
    }

    @Test
    fun `setJoinRule keeps a private join rule untouched`() = runTest {
        val store = CreateRoomConfigStore(FakeRoomAliasHelper())

        store.setJoinRule(JoinRuleItem.PrivateVisibility.Private)

        assertThat(store.getCreateRoomConfigFlow().value.visibilityState)
            .isEqualTo(RoomVisibilityState.Private(JoinRuleItem.PrivateVisibility.Private))
    }

    @Test
    fun `a public join rule never produces a public visibility state`() = runTest {
        val store = CreateRoomConfigStore(FakeRoomAliasHelper())

        store.setRoomName("A room")
        store.setJoinRule(JoinRuleItem.PublicVisibility.Public)
        // A room address only exists for public rooms, so this must not resurrect one.
        store.setRoomAddress("a-room")

        assertThat(store.getCreateRoomConfigFlow().value.visibilityState)
            .isNotInstanceOf(RoomVisibilityState.Public::class.java)
    }
}
