/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.element.android.features.logout.api.LogoutUseCase
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.mdm.impl.AutoLogoutPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Signs the user out once the app has spent longer than `auto_logout_minutes` in the background.
 *
 * The moment the app went to the background is written to disk rather than kept in memory, so the
 * timeout still applies when Android kills the process while it is backgrounded - which, for a phone
 * left in a drawer, is the normal case rather than the exception.
 */
class AutoLogoutObserver(
    context: Context,
    private val mdmService: MdmService,
    private val logoutUseCase: LogoutUseCase,
    private val now: () -> Long = System::currentTimeMillis,
) : DefaultLifecycleObserver {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // App-lifetime scope: the sign-out must survive the screen that triggered it going away.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        // Backgrounded. Record when, so the timeout survives the process being killed.
        preferences.edit { putLong(KEY_BACKGROUNDED_AT, now()) }
    }

    override fun onStart(owner: LifecycleOwner) {
        val minutes = mdmService.config.value.autoLogoutMinutes
        val backgroundedAt = preferences.getLong(KEY_BACKGROUNDED_AT, NOT_SET).takeIf { it != NOT_SET }
        // Clear first: whatever happens next, this background period has been dealt with.
        preferences.edit { remove(KEY_BACKGROUNDED_AT) }

        if (AutoLogoutPolicy.isExpired(backgroundedAt, now(), minutes)) {
            Timber.i("Auto-logout: backgrounded for longer than $minutes minutes, signing out")
            scope.launch {
                runCatching { logoutUseCase.logoutAll(ignoreSdkError = true) }
                    .onFailure { Timber.e(it, "Auto-logout failed") }
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "securechat_auto_logout"
        const val KEY_BACKGROUNDED_AT = "backgrounded_at"
        const val NOT_SET = -1L
    }
}
