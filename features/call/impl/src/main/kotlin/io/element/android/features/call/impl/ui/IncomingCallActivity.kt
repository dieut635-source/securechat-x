/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.Inject
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.features.call.api.CallData
import io.element.android.features.call.api.ElementCallEntryPoint
import io.element.android.features.call.impl.di.CallBindings
import io.element.android.features.call.impl.notifications.CallNotificationData
import io.element.android.features.call.impl.utils.ActiveCallManager
import io.element.android.features.call.impl.utils.CallState
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.lockscreen.api.LockScreenEntryPoint
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.features.lockscreen.api.handleSecureFlag
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.services.appnavstate.api.AppForegroundStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Activity that's displayed as a full screen intent when an incoming call is received.
 */
class IncomingCallActivity : AppCompatActivity() {
    companion object {
        /**
         * Extra key for the notification data.
         */
        const val EXTRA_NOTIFICATION_DATA = "EXTRA_NOTIFICATION_DATA"

        /** True when the activity was opened from the notification's answer action. */
        const val EXTRA_ANSWER_REQUESTED = "EXTRA_ANSWER_REQUESTED"
    }

    @Inject
    lateinit var elementCallEntryPoint: ElementCallEntryPoint

    @Inject
    lateinit var activeCallManager: ActiveCallManager

    @Inject
    lateinit var lockScreenService: LockScreenService

    @Inject
    lateinit var lockScreenEntryPoint: LockScreenEntryPoint

    @Inject
    lateinit var appForegroundStateService: AppForegroundStateService

    @Inject
    lateinit var appPreferencesStore: AppPreferencesStore

    @Inject
    lateinit var featureFlagService: FeatureFlagService

    @Inject
    lateinit var enterpriseService: EnterpriseService

    @Inject
    lateinit var buildMeta: BuildMeta

    @AppCoroutineScope
    @Inject lateinit var appCoroutineScope: CoroutineScope

    private val callerDetailsAllowed = MutableStateFlow(false)
    private lateinit var securityInitialization: Deferred<Boolean>
    private var answerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bindings<CallBindings>().inject(this)
        lockScreenService.handleSecureFlag(this)

        // Capture this before this activity can make the process foreground. An external
        // full-screen/notification launch must not inherit the service's optimistic initial
        // Unlocked value during a cold start.
        val wasTrustedForegroundEntry = appForegroundStateService.isInForeground.value &&
            lockScreenService.lockState.value == LockScreenLockState.Unlocked
        securityInitialization = lifecycleScope.async(start = CoroutineStart.UNDISPATCHED) {
            val isPinSetup = lockScreenService.isPinSetup().first()
            if (!isPinSetup) return@async false

            val isStillTrusted = wasTrustedForegroundEntry &&
                appForegroundStateService.isInForeground.value &&
                lockScreenService.lockState.value == LockScreenLockState.Unlocked
            if (isStillTrusted) {
                callerDetailsAllowed.value = true
            } else {
                // Incoming-call activities can become foreground before the ordinary lifecycle
                // lock observes the background transition, so force the lock synchronously.
                lockScreenService.lockIfPinSetup()
            }
            true
        }

        // Keep the screen awake once the user has unlocked the device, but never place caller UI
        // above Android's keyguard or turn on a locked screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val notificationData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_NOTIFICATION_DATA, CallNotificationData::class.java) }
        if (notificationData != null) {
            setContent {
                val lockState by lockScreenService.lockState.collectAsState()
                val detailsAllowed by callerDetailsAllowed.collectAsState()
                val colors by remember {
                    enterpriseService.semanticColorsFlow(sessionId = notificationData.sessionId)
                }.collectAsState(SemanticColorsLightDark.default)
                ElementThemeApp(
                    appPreferencesStore = appPreferencesStore,
                    featureFlagService = featureFlagService,
                    compoundLight = colors.light,
                    compoundDark = colors.dark,
                    buildMeta = buildMeta,
                ) {
                    IncomingCallScreen(
                        notificationData = notificationData,
                        revealCallerDetails = detailsAllowed && lockState == LockScreenLockState.Unlocked,
                        onAnswer = ::onAnswer,
                        onCancel = ::onCancel,
                    )
                }
            }
        } else {
            // No data, finish the activity
            finish()
            return
        }

        activeCallManager.activeCall
            .filter { it?.callState !is CallState.Ringing }
            .onEach { finish() }
            .launchIn(lifecycleScope)

        if (intent.getBooleanExtra(EXTRA_ANSWER_REQUESTED, false)) {
            onAnswer(notificationData)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.getBooleanExtra(EXTRA_ANSWER_REQUESTED, false)) return
        val notificationData = IntentCompat.getParcelableExtra(intent, EXTRA_NOTIFICATION_DATA, CallNotificationData::class.java)
            ?: return
        onAnswer(notificationData)
    }

    private fun onAnswer(notificationData: CallNotificationData) {
        if (answerJob?.isActive == true) return
        answerJob = lifecycleScope.launch {
            // No configured PIN is a fail-closed state for this externally reachable flow.
            if (!securityInitialization.await()) return@launch

            if (!callerDetailsAllowed.value || lockScreenService.lockState.value != LockScreenLockState.Unlocked) {
                if (!lockScreenService.lockIfPinSetup()) return@launch
                startActivity(lockScreenEntryPoint.pinUnlockIntent(this@IncomingCallActivity))
                lockScreenService.lockState.first { it == LockScreenLockState.Unlocked }
                callerDetailsAllowed.value = true
            }

            val callData = CallData(
                sessionId = notificationData.sessionId,
                roomId = notificationData.roomId,
                isAudioCall = notificationData.audioOnly,
            )
            val activeCall = activeCallManager.activeCall.value ?: return@launch
            val ringingState = activeCall.callState as? CallState.Ringing ?: return@launch
            if (activeCall.callData != callData || ringingState.notificationData.eventId != notificationData.eventId) {
                return@launch
            }

            // Re-check at the final boundary in case the app was locked while PIN UI was open.
            if (lockScreenService.lockState.value != LockScreenLockState.Unlocked) return@launch
            elementCallEntryPoint.startCall(callData)
        }
    }

    private fun onCancel() {
        val activeCall = activeCallManager.activeCall.value ?: return
        appCoroutineScope.launch {
            activeCallManager.hangUpCall(callData = activeCall.callData)
        }
    }
}
