/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.features.logout.api.LogoutUseCase
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class)
class AutoLogoutObserverTest : RobolectricTest() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val owner = TestLifecycleOwner()

    @Before
    fun clearPreferences() {
        preferences().edit().clear().commit()
    }

    @Test
    fun `zero minutes clears a stored background period without logging out`() = runTest {
        var now = 0L
        var logoutCalls = 0
        val observer = observer(
            now = { now },
            autoLogoutMinutes = 0,
            logout = { logoutCalls++ },
        )

        observer.onStop(owner)
        now = 60 * MINUTE
        observer.onStart(owner)
        runCurrent()

        assertThat(logoutCalls).isEqualTo(0)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `returning at the timeout logs out and clears the handled period`() = runTest {
        var now = 10 * MINUTE
        val ignoreSdkErrorValues = mutableListOf<Boolean>()
        val observer = observer(
            now = { now },
            autoLogoutMinutes = 5,
            logout = { ignoreSdkErrorValues.add(it) },
        )

        observer.onStop(owner)
        now += 5 * MINUTE
        observer.onStart(owner)
        runCurrent()

        assertThat(ignoreSdkErrorValues).containsExactly(true)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `the timeout logs out while the app remains in the background`() = runTest {
        var logoutCalls = 0
        val observer = observer(
            now = { testScheduler.currentTime },
            autoLogoutMinutes = 5,
            logout = { logoutCalls++ },
        )

        observer.onStop(owner)
        advanceTimeBy(5 * MINUTE - 1)
        runCurrent()
        assertThat(logoutCalls).isEqualTo(0)

        advanceTimeBy(1)
        runCurrent()
        assertThat(logoutCalls).isEqualTo(1)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `returning before the timeout cancels the background expiry`() = runTest {
        var logoutCalls = 0
        val observer = observer(
            now = { testScheduler.currentTime },
            autoLogoutMinutes = 5,
            logout = { logoutCalls++ },
        )

        observer.onStop(owner)
        advanceTimeBy(4 * MINUTE)
        observer.onStart(owner)
        runCurrent()

        assertThat(logoutCalls).isEqualTo(0)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `background process recreation enforces an expired persisted deadline without foregrounding`() = runTest {
        var now = 0L
        observer(now = { now }, autoLogoutMinutes = 5).onStop(owner)

        var logoutCalls = 0
        now = 6 * MINUTE
        observer(
            now = { now },
            autoLogoutMinutes = 5,
            logout = { logoutCalls++ },
        ).initializeFromPersistedState(isInBackground = true)
        runCurrent()

        assertThat(logoutCalls).isEqualTo(1)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `a failed logout preserves the original deadline and is retried`() = runTest {
        var now = 0L
        var attempts = 0
        val observer = observer(
            now = { now },
            autoLogoutMinutes = 1,
            logout = {
                attempts++
                if (attempts == 1) error("first attempt failed")
            },
        )

        observer.onStop(owner)
        now = 2 * MINUTE
        observer.onStart(owner)
        runCurrent()
        assertThat(attempts).isEqualTo(1)
        assertThat(preferences().getLong(KEY_BACKGROUNDED_AT, -1)).isEqualTo(0L)

        // A later stop must not replace the already-expired deadline with a newer one.
        now = 3 * MINUTE
        observer.onStop(owner)
        observer.onStart(owner)
        runCurrent()

        assertThat(attempts).isEqualTo(2)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `shortening the policy while backgrounded logs out immediately when the original period is overdue`() = runTest {
        var logoutCalls = 0
        val mdmService = TestMdmService(MdmConfig.default.copy(autoLogoutMinutes = 5))
        val observer = observer(
            now = { testScheduler.currentTime },
            mdmService = mdmService,
            logout = { logoutCalls++ },
        )

        observer.onStop(owner)
        advanceTimeBy(2 * MINUTE)
        mdmService.emit(MdmConfig.default.copy(autoLogoutMinutes = 1))
        runCurrent()

        assertThat(logoutCalls).isEqualTo(1)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `lengthening the policy while backgrounded moves the deadline from the original start`() = runTest {
        var logoutCalls = 0
        val mdmService = TestMdmService(MdmConfig.default.copy(autoLogoutMinutes = 1))
        val observer = observer(
            now = { testScheduler.currentTime },
            mdmService = mdmService,
            logout = { logoutCalls++ },
        )

        observer.onStop(owner)
        advanceTimeBy(30_000)
        mdmService.emit(MdmConfig.default.copy(autoLogoutMinutes = 5))
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()
        assertThat(logoutCalls).isEqualTo(0)

        advanceTimeBy(4 * MINUTE)
        runCurrent()
        assertThat(logoutCalls).isEqualTo(1)
    }

    @Test
    fun `disabling the policy while backgrounded cancels expiry and foreground clears the period`() = runTest {
        var logoutCalls = 0
        val mdmService = TestMdmService(MdmConfig.default.copy(autoLogoutMinutes = 1))
        val observer = observer(
            now = { testScheduler.currentTime },
            mdmService = mdmService,
            logout = { logoutCalls++ },
        )

        observer.onStop(owner)
        advanceTimeBy(30_000)
        mdmService.emit(MdmConfig.default.copy(autoLogoutMinutes = 0))
        runCurrent()
        advanceTimeBy(5 * MINUTE)
        runCurrent()

        assertThat(logoutCalls).isEqualTo(0)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isTrue()

        observer.onStart(owner)
        runCurrent()
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `a failed background logout retries automatically after a bounded delay`() = runTest {
        var attempts = 0
        val observer = observer(
            now = { testScheduler.currentTime },
            autoLogoutMinutes = 1,
            logout = {
                attempts++
                if (attempts == 1) error("first attempt failed")
            },
        )

        observer.onStop(owner)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertThat(attempts).isEqualTo(1)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isTrue()

        advanceTimeBy(999)
        runCurrent()
        assertThat(attempts).isEqualTo(1)

        advanceTimeBy(1)
        runCurrent()
        assertThat(attempts).isEqualTo(2)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `disabling the policy in foreground clears a failed logout deadline and cancels retry`() = runTest {
        var attempts = 0
        val mdmService = TestMdmService(MdmConfig.default.copy(autoLogoutMinutes = 1))
        val observer = observer(
            now = { testScheduler.currentTime },
            mdmService = mdmService,
            logout = {
                attempts++
                error("logout failed")
            },
        )

        observer.onStop(owner)
        advanceTimeBy(MINUTE)
        observer.onStart(owner)
        runCurrent()
        assertThat(attempts).isEqualTo(1)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isTrue()

        mdmService.emit(MdmConfig.default.copy(autoLogoutMinutes = 0))
        runCurrent()
        advanceTimeBy(MINUTE)
        runCurrent()

        assertThat(attempts).isEqualTo(1)
        assertThat(preferences().contains(KEY_BACKGROUNDED_AT)).isFalse()
    }

    @Test
    fun `changing the managed homeserver logs out current sessions immediately`() = runTest {
        var logoutCalls = 0
        val mdmService = TestMdmService(MdmConfig.default)
        observer(
            now = { 0L },
            mdmService = mdmService,
            logout = { logoutCalls++ },
        )

        mdmService.emit(MdmConfig.default.copy(homeserverUrl = "https://replacement.example.com"))
        runCurrent()

        assertThat(logoutCalls).isEqualTo(1)
    }

    @Test
    fun `a failed managed homeserver logout retries automatically after a bounded delay`() = runTest {
        var attempts = 0
        val mdmService = TestMdmService(MdmConfig.default)
        observer(
            now = { testScheduler.currentTime },
            mdmService = mdmService,
            logout = {
                attempts++
                if (attempts == 1) error("first attempt failed")
            },
        )

        mdmService.emit(MdmConfig.default.copy(homeserverUrl = "https://replacement.example.com"))
        runCurrent()
        assertThat(attempts).isEqualTo(1)

        advanceTimeBy(999)
        runCurrent()
        assertThat(attempts).isEqualTo(1)

        advanceTimeBy(1)
        runCurrent()
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `canonical-equivalent managed homeserver updates do not log out`() = runTest {
        var logoutCalls = 0
        val mdmService = TestMdmService(MdmConfig.default)
        observer(
            now = { testScheduler.currentTime },
            mdmService = mdmService,
            logout = { logoutCalls++ },
        )

        mdmService.emit(
            MdmConfig.default.copy(homeserverUrl = "HTTPS://CHAT.SECURECHAT.COM.AU:443/")
        )
        runCurrent()
        assertThat(logoutCalls).isEqualTo(0)

        mdmService.emit(MdmConfig.default.copy(homeserverUrl = "https://chat.securechat.com.au/other"))
        runCurrent()
        assertThat(logoutCalls).isEqualTo(1)
    }

    private fun kotlinx.coroutines.test.TestScope.observer(
        now: () -> Long,
        autoLogoutMinutes: Int,
        logout: (Boolean) -> Unit = {},
    ): AutoLogoutObserver = observer(
        now = now,
        mdmService = object : MdmService {
            override val config: StateFlow<MdmConfig> = MutableStateFlow(
                MdmConfig.default.copy(autoLogoutMinutes = autoLogoutMinutes)
            )
        },
        logout = logout,
    )

    private fun kotlinx.coroutines.test.TestScope.observer(
        now: () -> Long,
        mdmService: MdmService,
        logout: (Boolean) -> Unit = {},
    ): AutoLogoutObserver = AutoLogoutObserver(
        context = context,
        mdmService = mdmService,
        logoutUseCase = object : LogoutUseCase {
            override suspend fun logoutAll(ignoreSdkError: Boolean) {
                logout(ignoreSdkError)
            }
        },
        now = now,
        scope = backgroundScope,
    )

    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private class TestMdmService(initialConfig: MdmConfig) : MdmService {
        private val mutableConfig = MutableStateFlow(initialConfig)
        override val config: StateFlow<MdmConfig> = mutableConfig

        fun emit(config: MdmConfig) {
            mutableConfig.value = config
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry(this)
    }

    private companion object {
        const val PREFERENCES_NAME = "securechat_auto_logout"
        const val KEY_BACKGROUNDED_AT = "backgrounded_at"
        const val MINUTE = 60_000L
    }
}
