/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.securebackup.impl.reset.root

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.features.securebackup.impl.R
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.ensureCalledOnce
import io.element.android.tests.testutils.pressBack
import io.element.android.tests.testutils.pressBackKey
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.annotation.Config

class ResetIdentityRootViewTest : RobolectricTest() {
    /**
     * Màn hình này KHÔNG được tự giới thiệu là một lỗi.
     *
     * Bản kế thừa dùng BigIcon.Style.AlertSolid, mà BigIcon gán nhãn trợ năng cho kiểu đó là
     * CommonStrings.common_error — nên trình đọc màn hình đọc "Error" ngay đầu màn hình thiết
     * lập máy mới. Không nhìn thấy bằng mắt, chỉ lộ ra khi đọc cây trợ năng; đó là lý do nó
     * sống sót qua Q10 và chỉ bị bắt khi rà lại bằng uiautomator trên máy thật.
     *
     * Test kiểm đúng cái nhãn đó, không kiểm tên kiểu icon: đổi sang một Style khác cũng mang
     * nhãn "Error" thì vẫn phải đỏ.
     */
    @Test
    fun `the screen does not announce itself as an error`() = runAndroidComposeUiTest {
        setResetRootView(
            ResetIdentityRootState(displayConfirmationDialog = false, eventSink = {}),
        )

        onNodeWithContentDescription(activity!!.getString(CommonStrings.common_error))
            .assertDoesNotExist()
    }

    @Test
    fun `pressing the back HW button invokes the expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce {
            setResetRootView(
                ResetIdentityRootState(displayConfirmationDialog = false, eventSink = {}),
                onBack = it,
            )
            pressBackKey()
        }
    }

    @Test
    fun `clicking on the back navigation button invokes the expected callback`() = runAndroidComposeUiTest {
        ensureCalledOnce {
            setResetRootView(
                ResetIdentityRootState(displayConfirmationDialog = false, eventSink = {}),
                onBack = it,
            )
            pressBack()
        }
    }

    @Test
    @Config(qualifiers = "h720dp")
    fun `clicking Continue displays the confirmation dialog`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<ResetIdentityRootEvent>()
        setResetRootView(
            ResetIdentityRootState(displayConfirmationDialog = false, eventSink = eventsRecorder),
        )

        clickOn(R.string.securechat_identity_setup_details_action)

        eventsRecorder.assertSingle(ResetIdentityRootEvent.Continue)
    }

    @Test
    fun `clicking the confirm button confirms the setup`() = runAndroidComposeUiTest {
        ensureCalledOnce {
            setResetRootView(
                ResetIdentityRootState(displayConfirmationDialog = true, eventSink = {}),
                onContinue = it,
            )
            clickOn(R.string.securechat_identity_setup_confirm_action)
        }
    }

    @Test
    fun `clicking Cancel dismisses the dialog`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<ResetIdentityRootEvent>()
        setResetRootView(
            ResetIdentityRootState(displayConfirmationDialog = true, eventSink = eventsRecorder),
        )

        clickOn(CommonStrings.action_cancel)
        eventsRecorder.assertSingle(ResetIdentityRootEvent.DismissDialog)
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setResetRootView(
    state: ResetIdentityRootState,
    onBack: () -> Unit = EnsureNeverCalled(),
    onContinue: () -> Unit = EnsureNeverCalled(),
) {
    setContent {
        ResetIdentityRootView(state = state, onContinue = onContinue, onBack = onBack)
    }
}
