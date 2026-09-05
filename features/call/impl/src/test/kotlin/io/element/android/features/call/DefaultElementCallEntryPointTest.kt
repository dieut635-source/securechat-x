/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call

import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.DefaultElementCallEntryPoint
import io.element.android.features.call.impl.notifications.CallNotificationData
import io.element.android.features.call.impl.security.CallUiAccessTokenStore
import io.element.android.features.call.impl.ui.ElementCallActivity
import io.element.android.features.call.utils.FakeActiveCallManager
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.test.FakeLockScreenService
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.services.appnavstate.test.FakeAppForegroundStateService
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.seconds

class DefaultElementCallEntryPointTest : RobolectricTest() {
    @Test
    fun `startCall - starts ElementCallActivity setup with the needed extras`() = runTest {
        val entryPoint = createEntryPoint()
        val callData = CallData(A_SESSION_ID, A_ROOM_ID, isAudioCall = false)
        entryPoint.startCall(callData)

        val expectedIntent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, ElementCallActivity::class.java)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertThat(intent.component).isEqualTo(expectedIntent.component)
        assertThat(intent.action).isEqualTo(ElementCallActivity.ACTION_START_IN_APP_CALL)
        assertThat(IntentCompat.getParcelableExtra(intent, ElementCallActivity.EXTRA_CALL_DATA, CallData::class.java)).isEqualTo(callData)
        assertThat(intent.getStringExtra(ElementCallActivity.EXTRA_FOREGROUND_ACCESS_TOKEN)).isNotNull()
    }

    @Test
    fun `ongoing call notification intent cannot be mistaken for a trusted in-app start`() {
        val intent = ElementCallActivity.resumeCallFromNotificationIntent(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )

        assertThat(intent.component?.className).isEqualTo(ElementCallActivity::class.java.name)
        assertThat(intent.action).isEqualTo(ElementCallActivity.ACTION_RESUME_CALL_FROM_NOTIFICATION)
        assertThat(intent.hasExtra(ElementCallActivity.EXTRA_CALL_DATA)).isFalse()
        assertThat(intent.hasExtra(ElementCallActivity.EXTRA_FOREGROUND_ACCESS_TOKEN)).isFalse()
    }

    @Test
    fun `a call started while the app is not foreground has no trusted access token`() = runTest {
        val entryPoint = createEntryPoint(
            appForegroundStateService = FakeAppForegroundStateService(initialForegroundValue = false),
        )

        entryPoint.startCall(CallData(A_SESSION_ID, A_ROOM_ID, isAudioCall = false))

        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertThat(intent.hasExtra(ElementCallActivity.EXTRA_FOREGROUND_ACCESS_TOKEN)).isFalse()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleIncomingCall - registers the incoming call using ActiveCallManager`() = runTest {
        val registerIncomingCallLambda = lambdaRecorder<CallNotificationData, Unit> {}
        val activeCallManager = FakeActiveCallManager(registerIncomingCallResult = registerIncomingCallLambda)
        val entryPoint = createEntryPoint(activeCallManager = activeCallManager)

        entryPoint.handleIncomingCall(
            callData = CallData(A_SESSION_ID, A_ROOM_ID, isAudioCall = false),
            eventId = AN_EVENT_ID,
            senderId = A_USER_ID_2,
            roomName = "roomName",
            senderName = "senderName",
            avatarUrl = "avatarUrl",
            timestamp = 0,
            expirationTimestamp = 0,
            notificationChannelId = "notificationChannelId",
            textContent = "textContent",
        )

        advanceTimeBy(1.seconds)

        registerIncomingCallLambda.assertions().isCalledOnce()
    }

    private fun TestScope.createEntryPoint(
        activeCallManager: FakeActiveCallManager = FakeActiveCallManager(),
        appForegroundStateService: FakeAppForegroundStateService = FakeAppForegroundStateService(),
        lockScreenService: FakeLockScreenService = FakeLockScreenService().apply {
            setIsPinSetup(true)
            setLockState(LockScreenLockState.Unlocked)
        },
        callUiAccessTokenStore: CallUiAccessTokenStore = CallUiAccessTokenStore(),
    ) = DefaultElementCallEntryPoint(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        activeCallManager = activeCallManager,
        callUiAccessTokenStore = callUiAccessTokenStore,
        lockScreenService = lockScreenService,
        appForegroundStateService = appForegroundStateService,
    )
}
