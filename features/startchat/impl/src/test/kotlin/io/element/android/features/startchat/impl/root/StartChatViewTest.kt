/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.startchat.impl.root

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.features.startchat.impl.R
import io.element.android.features.startchat.impl.userlist.aRecentDirectRoomList
import io.element.android.features.startchat.impl.userlist.aUserListState
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.ui.model.getBestName
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.EnsureNeverCalledWithParam
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.ensureCalledOnce
import io.element.android.tests.testutils.ensureCalledOnceWithParam
import io.element.android.tests.testutils.pressBack
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.annotation.Config

class StartChatViewTest : RobolectricTest() {
    @Test
    fun `clicking on back invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<StartChatEvent>(expectEvents = false)
        ensureCalledOnce {
            setStartChatView(
                aCreateRoomRootState(
                    eventSink = eventsRecorder,
                ),
                onCloseClick = it
            )
            pressBack()
        }
    }

    @Test
    fun `clicking on New room invokes the expected callback`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<StartChatEvent>(expectEvents = false)
        ensureCalledOnce {
            setStartChatView(
                aCreateRoomRootState(
                    eventSink = eventsRecorder,
                ),
                onNewRoomClick = it
            )
            clickOn(R.string.screen_create_room_action_create_room)
        }
    }

    @Config(qualifiers = "h1024dp")
    @Test
    fun `clicking on a user suggestion invokes the expected callback`() = runAndroidComposeUiTest {
        val recentDirectRoomList = aRecentDirectRoomList()
        val firstRoom = recentDirectRoomList[0]
        val eventsRecorder = EventsRecorder<StartChatEvent>(expectEvents = false)
        ensureCalledOnceWithParam(firstRoom.roomId) {
            setStartChatView(
                aCreateRoomRootState(
                    userListState = aUserListState(
                        recentDirectRooms = recentDirectRoomList
                    ),
                    eventSink = eventsRecorder,
                ),
                onOpenDM = it
            )
            onNodeWithText(firstRoom.matrixUser.getBestName()).performClick()
        }
    }

    /**
     * Ba lối vào phòng không đi qua lời mời đều KHÔNG được có mặt.
     *
     * Quy tắc do chủ sản phẩm đặt: chỉ quản trị viên tạo phòng và gửi lời mời, có lời mời thì
     * mới vào được. "Gõ địa chỉ phòng" và "danh bạ phòng công khai" đi vòng qua quy tắc đó;
     * "Invite people to SecureChat" thì gửi permalink chứa `@ten:chat.securechat.com.au` sang
     * SMS hay WhatsApp — ra hẳn ngoài app.
     *
     * "Invite people to SecureChat" không có mặt trong test này vì nó đã bị XOÁ hẳn khỏi mã
     * nguồn — không còn tham số, không còn use case, không còn chuỗi để mà tìm. Hai cái còn
     * lại vẫn là cờ, nên vẫn phải kiểm.
     *
     * Hai callback tương ứng dùng [EnsureNeverCalled] ở [setStartChatView], nên test này bắt
     * cả trường hợp nút vẫn còn mà chỉ bị làm cho vô hình.
     *
     * ⚠️ Test này chứng minh giao diện KHÔNG mời gọi ba việc đó. Nó KHÔNG chứng minh máy chủ
     * từ chối chúng — client khác vẫn gọi được API. Chặn thật nằm ở máy chủ.
     */
    @Config(qualifiers = "h1024dp")
    @Test
    fun `the room entry points that bypass an invitation are absent`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<StartChatEvent>(expectEvents = false)
        setStartChatView(
            aCreateRoomRootState(
                applicationName = "test",
                eventSink = eventsRecorder,
            ),
        )

        val context = activity!!
        onNodeWithText(context.getString(R.string.screen_start_chat_join_room_by_address_action))
            .assertDoesNotExist()
        onNodeWithText(context.getString(R.string.screen_room_directory_search_title))
            .assertDoesNotExist()
    }

}

private fun AndroidComposeUiTest<ComponentActivity>.setStartChatView(
    state: StartChatState,
    onCloseClick: () -> Unit = EnsureNeverCalled(),
    onNewRoomClick: () -> Unit = EnsureNeverCalled(),
    onOpenDM: (RoomId) -> Unit = EnsureNeverCalledWithParam(),
    onJoinRoomByAddressClick: () -> Unit = EnsureNeverCalled(),
    onRoomDirectorySearchClick: () -> Unit = EnsureNeverCalled(),
) {
    setContent {
        StartChatView(
            state = state,
            onCloseClick = onCloseClick,
            onNewRoomClick = onNewRoomClick,
            onOpenDM = onOpenDM,
            onJoinByAddressClick = onJoinRoomByAddressClick,
            onRoomDirectorySearchClick = onRoomDirectorySearchClick,
        )
    }
}
