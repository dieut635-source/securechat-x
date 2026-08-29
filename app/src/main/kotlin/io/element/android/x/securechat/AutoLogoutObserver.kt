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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.element.android.features.logout.api.LogoutUseCase
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.mdm.api.areHomeserverUrlsEquivalent
import io.element.android.libraries.mdm.impl.AutoLogoutPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DefaultLifecycleObserver {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val preferencesLock = Any()
    private val schedulingLock = Any()
    private val logoutInProgress = AtomicBoolean(false)
    private var backgroundExpiryJob: Job? = null
    private var logoutRetryJob: Job? = null
    private var retryAttempt = 0
    @Volatile
    private var isInBackground = false

    init {
        // Subscribe undispatched so a policy update cannot slip between construction and collector
        // startup. Only timeout changes matter here; the other managed keys have their own owners.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mdmService.config
                .map { it.autoLogoutMinutes }
                .distinctUntilChanged()
                .drop(1)
                .collect { handleTimeoutPolicyChanged() }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var enforcementJob: Job? = null
            mdmService.config
                .map { it.homeserverUrl }
                .distinctUntilChanged(::areHomeserverUrlsEquivalent)
                .drop(1)
                .collect {
                    enforcementJob?.cancelAndJoin()
                    enforcementJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        enforceManagedHomeserverChange()
                    }
                }
        }
    }

    private suspend fun enforceManagedHomeserverChange() {
        var attempt = 0
        while (true) {
            try {
                // A managed server replacement applies to current sessions as well as the login
                // screen. This also invalidates any authentication already in flight.
                logoutUseCase.logoutAll(ignoreSdkError = true)
                return
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val retryDelayMillis = retryDelayMillis(attempt++)
                Timber.e(failure, "Failed to enforce a managed homeserver change; retrying")
                delay(retryDelayMillis)
            }
        }
    }

    fun start() {
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        processLifecycle.addObserver(this)
        initializeFromPersistedState(
            isInBackground = !processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
    }

    /** Reconciles a deadline restored after Android recreates the process. */
    internal fun initializeFromPersistedState(isInBackground: Boolean) {
        this.isInBackground = isInBackground
        handleTimeoutPolicyChanged()
    }

    override fun onStop(owner: LifecycleOwner) {
        isInBackground = true
        // Preserve an earlier, unhandled background period when a previous logout attempt failed.
        val backgroundedAt = synchronized(preferencesLock) {
            preferences.getLong(KEY_BACKGROUNDED_AT, NOT_SET).takeIf { it != NOT_SET }
                ?: now().also { timestamp ->
                    preferences.edit { putLong(KEY_BACKGROUNDED_AT, timestamp) }
                }
        }
        scheduleBackgroundExpiry(backgroundedAt)
    }

    private fun scheduleBackgroundExpiry(backgroundedAt: Long) {
        val minutes = mdmService.config.value.autoLogoutMinutes
        if (!isInBackground || minutes <= 0) {
            cancelBackgroundExpiry()
            return
        }
        val elapsed = now() - backgroundedAt
        val remainingMillis = if (elapsed < 0) {
            0L
        } else {
            (minutes * MILLIS_PER_MINUTE - elapsed).coerceAtLeast(0L)
        }
        synchronized(schedulingLock) {
            backgroundExpiryJob?.cancel()
            logoutRetryJob?.cancel()
            logoutRetryJob = null
            backgroundExpiryJob = scope.launch {
                delay(remainingMillis)
                requestLogout(backgroundedAt)
            }
        }
    }

    private fun handleTimeoutPolicyChanged() {
        val backgroundedAt = synchronized(preferencesLock) {
            preferences.getLong(KEY_BACKGROUNDED_AT, NOT_SET).takeIf { it != NOT_SET }
        } ?: return
        val minutes = mdmService.config.value.autoLogoutMinutes

        if (minutes <= 0) {
            cancelScheduledJobs(resetRetryAttempt = true)
            if (!isInBackground) clearBackgroundedAt(backgroundedAt)
        } else if (isInBackground) {
            // Recompute from the original persisted start. Shorter policies therefore expire at
            // once when overdue; longer policies move the timer to their later deadline.
            scheduleBackgroundExpiry(backgroundedAt)
        } else if (AutoLogoutPolicy.isExpired(backgroundedAt, now(), minutes)) {
            cancelScheduledJobs(resetRetryAttempt = false)
            requestLogout(backgroundedAt)
        } else {
            cancelScheduledJobs(resetRetryAttempt = true)
            clearBackgroundedAt(backgroundedAt)
        }
    }

    private fun requestLogout(backgroundedAt: Long) {
        val isStillCurrent = synchronized(preferencesLock) {
            preferences.getLong(KEY_BACKGROUNDED_AT, NOT_SET) == backgroundedAt
        }
        if (!isStillCurrent) return

        val minutes = mdmService.config.value.autoLogoutMinutes
        if (!AutoLogoutPolicy.isExpired(backgroundedAt, now(), minutes)) {
            // The administrator may have lengthened the timeout while this timer was waiting.
            if (isInBackground) {
                scheduleBackgroundExpiry(backgroundedAt)
            } else {
                cancelScheduledJobs(resetRetryAttempt = true)
                clearBackgroundedAt(backgroundedAt)
            }
            return
        }
        if (!logoutInProgress.compareAndSet(false, true)) return

        Timber.i("Auto-logout: backgrounded for longer than $minutes minutes, signing out")
        scope.launch {
            var failure: Exception? = null
            try {
                logoutUseCase.logoutAll(ignoreSdkError = true)
                clearBackgroundedAt(backgroundedAt)
                cancelScheduledJobs(resetRetryAttempt = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failure = exception
                Timber.e(exception, "Auto-logout failed; scheduling a retry")
            } finally {
                logoutInProgress.set(false)
                if (failure != null) scheduleLogoutRetry(backgroundedAt)
            }
        }
    }

    private fun scheduleLogoutRetry(backgroundedAt: Long) {
        val isStillCurrent = synchronized(preferencesLock) {
            preferences.getLong(KEY_BACKGROUNDED_AT, NOT_SET) == backgroundedAt
        }
        if (!isStillCurrent) return

        val minutes = mdmService.config.value.autoLogoutMinutes
        if (!AutoLogoutPolicy.isExpired(backgroundedAt, now(), minutes)) {
            if (isInBackground) {
                scheduleBackgroundExpiry(backgroundedAt)
            } else {
                cancelScheduledJobs(resetRetryAttempt = true)
                clearBackgroundedAt(backgroundedAt)
            }
            return
        }

        synchronized(schedulingLock) {
            logoutRetryJob?.cancel()
            val retryDelayMillis = retryDelayMillis(retryAttempt)
            retryAttempt = (retryAttempt + 1).coerceAtMost(MAX_RETRY_EXPONENT)
            logoutRetryJob = scope.launch {
                delay(retryDelayMillis)
                requestLogout(backgroundedAt)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        isInBackground = false
        cancelBackgroundExpiry()
        val minutes = mdmService.config.value.autoLogoutMinutes
        val backgroundedAt = synchronized(preferencesLock) {
            preferences.getLong(KEY_BACKGROUNDED_AT, NOT_SET).takeIf { it != NOT_SET }
        }

        if (!AutoLogoutPolicy.isExpired(backgroundedAt, now(), minutes)) {
            cancelScheduledJobs(resetRetryAttempt = true)
            clearBackgroundedAt(backgroundedAt)
            return
        }
        synchronized(schedulingLock) {
            logoutRetryJob?.cancel()
            logoutRetryJob = null
        }
        requestLogout(backgroundedAt ?: return)
    }

    private fun cancelBackgroundExpiry() {
        synchronized(schedulingLock) {
            backgroundExpiryJob?.cancel()
            backgroundExpiryJob = null
        }
    }

    private fun cancelScheduledJobs(resetRetryAttempt: Boolean) {
        synchronized(schedulingLock) {
            backgroundExpiryJob?.cancel()
            backgroundExpiryJob = null
            logoutRetryJob?.cancel()
            logoutRetryJob = null
            if (resetRetryAttempt) retryAttempt = 0
        }
    }

    private fun clearBackgroundedAt(expected: Long?) {
        if (expected == null) return
        synchronized(preferencesLock) {
            if (preferences.getLong(KEY_BACKGROUNDED_AT, NOT_SET) == expected) {
                preferences.edit { remove(KEY_BACKGROUNDED_AT) }
            }
        }
    }

    private fun retryDelayMillis(attempt: Int): Long =
        (INITIAL_RETRY_DELAY_MILLIS shl attempt.coerceAtMost(MAX_RETRY_EXPONENT))
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)

    private companion object {
        const val PREFERENCES_NAME = "securechat_auto_logout"
        const val KEY_BACKGROUNDED_AT = "backgrounded_at"
        const val NOT_SET = -1L
        const val MILLIS_PER_MINUTE = 60_000L
        const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 60_000L
        const val MAX_RETRY_EXPONENT = 6
    }
}
