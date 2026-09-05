/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.IntentCompat
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.Inject
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.theme.ForcedDarkElementTheme
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.di.CallBindings
import io.element.android.features.call.impl.pip.PictureInPictureEvent
import io.element.android.features.call.impl.pip.PictureInPicturePresenter
import io.element.android.features.call.impl.pip.PictureInPictureState
import io.element.android.features.call.impl.pip.PipView
import io.element.android.features.call.impl.security.CallUiAccessGuard
import io.element.android.features.call.impl.security.CallUiAccessResult
import io.element.android.features.call.impl.security.CallUiAccessTokenStore
import io.element.android.features.call.impl.security.isCallUiAccessEffective
import io.element.android.features.call.impl.security.isTrustedCallUiEntryStillValid
import io.element.android.features.call.impl.services.CallForegroundService
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.lockscreen.api.LockScreenEntryPoint
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.features.lockscreen.api.handleSecureFlag
import io.element.android.libraries.androidutils.browser.ConsoleMessageLogger
import io.element.android.libraries.androidutils.media.setAspectRatioFromOrientation
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioFocusRequester
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.designsystem.utils.hasCompactHeightWindowSize
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.services.appnavstate.api.AppForegroundStateService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import timber.log.Timber

private val loggerTag = LoggerTag("SecureChatCall")

class ElementCallActivity :
    AppCompatActivity(),
    CallScreenNavigator,
    PipView {
    companion object {
        internal const val ACTION_START_IN_APP_CALL = "io.element.android.features.call.START_IN_APP_CALL"
        internal const val ACTION_RESUME_CALL_FROM_NOTIFICATION = "io.element.android.features.call.RESUME_CALL_FROM_NOTIFICATION"
        internal const val EXTRA_CALL_DATA = "EXTRA_CALL_DATA"
        internal const val EXTRA_FOREGROUND_ACCESS_TOKEN = "EXTRA_FOREGROUND_ACCESS_TOKEN"

        internal fun startCallIntent(context: Context, callData: CallData, foregroundAccessToken: String?): Intent {
            return Intent(context, ElementCallActivity::class.java).apply {
                action = ACTION_START_IN_APP_CALL
                putExtra(EXTRA_CALL_DATA, callData)
                foregroundAccessToken?.let { putExtra(EXTRA_FOREGROUND_ACCESS_TOKEN, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }
        }

        internal fun resumeCallFromNotificationIntent(context: Context): Intent {
            return Intent(context, ElementCallActivity::class.java).apply {
                action = ACTION_RESUME_CALL_FROM_NOTIFICATION
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }
        }
    }

    @Inject lateinit var presenterFactory: CallScreenPresenter.Factory
    @Inject lateinit var appPreferencesStore: AppPreferencesStore
    @Inject lateinit var featureFlagService: FeatureFlagService
    @Inject lateinit var enterpriseService: EnterpriseService
    @Inject lateinit var pictureInPicturePresenter: PictureInPicturePresenter
    @Inject lateinit var buildMeta: BuildMeta
    @Inject lateinit var audioFocus: AudioFocus
    @Inject lateinit var consoleMessageLogger: ConsoleMessageLogger
    @Inject lateinit var lockScreenService: LockScreenService
    @Inject lateinit var lockScreenEntryPoint: LockScreenEntryPoint
    @Inject lateinit var appForegroundStateService: AppForegroundStateService
    @Inject lateinit var callUiAccessTokenStore: CallUiAccessTokenStore

    private lateinit var presenter: Presenter<CallScreenState>

    private var requestPermissionCallback: RequestPermissionCallback? = null

    private val requestPermissionsLauncher = registerPermissionResultLauncher()

    private val webViewTarget = mutableStateOf<CallData?>(null)

    private var eventSink: ((CallScreenEvent) -> Unit)? = null

    private var currentPipOrientation: Int? = null

    private val callUiAccessGranted = MutableStateFlow(false)
    private lateinit var callUiAccessGuard: CallUiAccessGuard
    private var accessJob: Job? = null
    private var unlockJob: Job? = null
    private var pendingCallIntent: Intent? = null
    private var callContentCreated = false
    private var requireFreshUnlockOnStart = false
    private var backgroundEpoch = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bindings<CallBindings>().inject(this)

        lockScreenService.handleSecureFlag(this)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyEffectiveCallUiAccess(false)
        // Participant and call content must stay behind Android's keyguard. Audio continues via
        // the foreground service and the call UI becomes available again after device unlock.

        callUiAccessGuard = CallUiAccessGuard(lockScreenService)
        pendingCallIntent = intent
        observeEffectiveCallUiAccess()

        // A start-call intent may use the already-unlocked foreground app session. Notification,
        // recents and cold-start entries never receive that trust and must force a fresh unlock.
        val trustedForegroundEntry = consumeForegroundAccessToken(intent) &&
            appForegroundStateService.isInForeground.value &&
            lockScreenService.lockState.value == LockScreenLockState.Unlocked
        requestCallUiAccess(trustedForegroundEntry)
    }

    override fun onStart() {
        super.onStart()
        if (requireFreshUnlockOnStart && unlockJob?.isActive != true) {
            accessJob?.cancel()
            if (requestCallUiAccess(trustedForegroundEntry = false)) {
                requireFreshUnlockOnStart = false
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            backgroundEpoch++
            revokeCallUiAccess()
            if (unlockJob?.isActive != true) {
                accessJob?.cancel()
                requireFreshUnlockOnStart = true
                lifecycleScope.launch {
                    lockScreenService.lockIfPinSetup()
                }
            }
        }
        super.onStop()
    }

    private fun observeEffectiveCallUiAccess() {
        combine(callUiAccessGranted, lockScreenService.lockState) { locallyGranted, lockState ->
            isCallUiAccessEffective(locallyGranted, lockState)
        }
            .distinctUntilChanged()
            .onEach(::applyEffectiveCallUiAccess)
            .launchIn(lifecycleScope)
    }

    private fun requestCallUiAccess(trustedForegroundEntry: Boolean): Boolean {
        if (accessJob?.isActive == true) return false
        val requestBackgroundEpoch = backgroundEpoch
        accessJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var result = callUiAccessGuard.prepareAccess(trustedForegroundEntry)
            if (
                result == CallUiAccessResult.Granted &&
                !isTrustedCallUiEntryStillValid(
                    trustedForegroundEntry = trustedForegroundEntry,
                    requestBackgroundEpoch = requestBackgroundEpoch,
                    currentBackgroundEpoch = backgroundEpoch,
                    appIsForeground = appForegroundStateService.isInForeground.value,
                    lockState = lockScreenService.lockState.value,
                    isFinishing = isFinishing,
                )
            ) {
                result = callUiAccessGuard.prepareAccess(trustedForegroundEntry = false)
            }
            when (result) {
                CallUiAccessResult.Denied -> finish()
                CallUiAccessResult.Granted -> grantCallUiAccess()
                CallUiAccessResult.UnlockRequired -> {
                    val currentJob = coroutineContext.job
                    unlockJob = currentJob
                    try {
                        while (true) {
                            if (!lockScreenService.lockIfPinSetup()) {
                                finish()
                                return@launch
                            }
                            startActivity(lockScreenEntryPoint.pinUnlockIntent(this@ElementCallActivity))
                            lockScreenService.lockState.first { it == LockScreenLockState.Unlocked }
                            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
                            if (
                                appForegroundStateService.isInForeground.value &&
                                callUiAccessGuard.isUnlockedWithPin()
                            ) {
                                grantCallUiAccess()
                                return@launch
                            }
                        }
                    } finally {
                        if (unlockJob === currentJob) {
                            unlockJob = null
                        }
                    }
                }
            }
        }
        return true
    }

    private fun grantCallUiAccess() {
        callUiAccessGranted.value = true
        val callIntent = pendingCallIntent.also { pendingCallIntent = null }
        setCallData(callIntent)
        // A notification can only resume an existing activity. If the process/call task no
        // longer exists, setCallData fails closed and finishes without creating call media.
        if (!::presenter.isInitialized || callContentCreated) return
        callContentCreated = true

        pictureInPicturePresenter.setPipView(this)

        Timber.d("Created SecureChat call activity with call type: ${webViewTarget.value}")

        setContent {
            val locallyGranted by callUiAccessGranted.collectAsState()
            val lockState by lockScreenService.lockState.collectAsState()
            val effectiveAccess = isCallUiAccessEffective(locallyGranted, lockState)
            val pipState = pictureInPicturePresenter.present()
            ListenToAndroidEvents(pipState)
            val colors by remember(webViewTarget.value?.sessionId) {
                enterpriseService.semanticColorsFlow(sessionId = webViewTarget.value?.sessionId)
            }.collectAsState(SemanticColorsLightDark.default)

            // When the height is compact, hide the system bars by default to maximize the space for the call, using immersive mode
            val hasCompactHeight = hasCompactHeightWindowSize()
            DisposableEffect(hasCompactHeight, pipState.isInPictureInPicture) {
                if (hasCompactHeight && !pipState.isInPictureInPicture) {
                    val window = this@ElementCallActivity.window ?: return@DisposableEffect onDispose {}
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    val systemBarInsets = WindowInsetsCompat.Type.systemBars()
                    insetsController.hide(systemBarInsets)

                    insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

                    onDispose {
                        insetsController.show(systemBarInsets)
                        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    }
                } else {
                    onDispose {}
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                ElementThemeApp(
                    appPreferencesStore = appPreferencesStore,
                    featureFlagService = featureFlagService,
                    compoundLight = colors.light,
                    compoundDark = colors.dark,
                    buildMeta = buildMeta,
                ) {
                    ForcedDarkElementTheme(
                        colors = colors,
                    ) {
                        val state = presenter.present()
                        eventSink = state.eventSink
                        LaunchedEffect(state.isCallActive) {
                            if (state.isCallActive) {
                                setCallIsActive()
                            }
                        }
                        CallScreenView(
                            state = state,
                            pipState = pipState,
                            isCallUiVisible = effectiveAccess,
                            onConsoleMessage = {
                                consoleMessageLogger.log("SecureChatCall", it)
                            },
                            requestPermissions = { permissions, callback ->
                                if (hasEffectiveCallUiAccess()) {
                                    requestPermissionCallback = callback
                                    requestPermissionsLauncher.launch(permissions)
                                } else {
                                    callback(emptyArray())
                                }
                            }
                        )
                    }
                }
                if (!effectiveAccess) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clearAndSetSemantics { },
                    )
                }
            }
        }
    }

    private fun revokeCallUiAccess() {
        callUiAccessGranted.value = false
        applyEffectiveCallUiAccess(false)
        requestPermissionCallback?.invoke(emptyArray())
        requestPermissionCallback = null
    }

    private fun applyEffectiveCallUiAccess(isGranted: Boolean) {
        findViewById<View>(android.R.id.content)?.visibility = if (isGranted) View.VISIBLE else View.INVISIBLE
        if (isGranted) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            window.decorView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            window.decorView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun hasEffectiveCallUiAccess(): Boolean {
        return isCallUiAccessEffective(callUiAccessGranted.value, lockScreenService.lockState.value)
    }

    private fun setCallIsActive() {
        audioFocus.requestAudioFocus(
            requester = AudioFocusRequester.ElementCall,
            onFocusLost = {
                // If the audio focus is lost, we do not stop the call.
                Timber.tag(loggerTag.value).w("Audio focus lost")
            }
        )
        CallForegroundService.start(this)
    }

    @Composable
    private fun ListenToAndroidEvents(pipState: PictureInPictureState) {
        val pipEventSink by rememberUpdatedState(pipState.eventSink)
        DisposableEffect(Unit) {
            val listener = Runnable {
                if (requestPermissionCallback != null) {
                    Timber.tag(loggerTag.value).w("Ignoring onUserLeaveHint event because user is asked to grant permissions")
                } else {
                    pipEventSink(PictureInPictureEvent.EnterPictureInPicture)
                }
            }
            addOnUserLeaveHintListener(listener)
            onDispose {
                removeOnUserLeaveHintListener(listener)
            }
        }
        DisposableEffect(Unit) {
            val onPictureInPictureModeChangedListener = Consumer { _: PictureInPictureModeChangedInfo ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPipParams()
                }
                pipEventSink(PictureInPictureEvent.OnPictureInPictureModeChanged(isInPictureInPictureMode))
                if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    Timber.tag(loggerTag.value).d("Exiting PiP mode: Hangup the call")
                    eventSink?.invoke(CallScreenEvent.Hangup)
                }
            }
            addOnPictureInPictureModeChangedListener(onPictureInPictureModeChangedListener)
            onDispose {
                removeOnPictureInPictureModeChangedListener(onPictureInPictureModeChangedListener)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCallIntent = intent

        // onNewIntent is also the entry used by the ongoing-call PendingIntent. Hide the
        // existing WebView synchronously, before querying any asynchronous PIN state.
        val trustedForegroundEntry = consumeForegroundAccessToken(intent) &&
            appForegroundStateService.isInForeground.value &&
            lockScreenService.lockState.value == LockScreenLockState.Unlocked
        revokeCallUiAccess()
        accessJob?.cancel()
        requestCallUiAccess(trustedForegroundEntry)
    }

    private fun consumeForegroundAccessToken(intent: Intent): Boolean {
        if (intent.action != ACTION_START_IN_APP_CALL) return false
        val token = intent.getStringExtra(EXTRA_FOREGROUND_ACCESS_TOKEN)
        intent.removeExtra(EXTRA_FOREGROUND_ACCESS_TOKEN)
        return callUiAccessTokenStore.consume(token)
    }

    override fun onDestroy() {
        super.onDestroy()
        audioFocus.releaseAudioFocus()
        CallForegroundService.stop(this)
        pictureInPicturePresenter.setPipView(null)
    }

    override fun finish() {
        // Also remove the task from recents
        finishAndRemoveTask()
    }

    override fun close() {
        finish()
    }

    private fun setCallData(intent: Intent?) {
        val callData = intent?.let {
            IntentCompat.getParcelableExtra(intent, EXTRA_CALL_DATA, CallData::class.java)
        }
        val currentCallData = webViewTarget.value
        if (currentCallData == null) {
            if (callData == null) {
                Timber.tag(loggerTag.value).d("Re-opened the activity but we have no url to load or a cached one, finish the activity")
                finish()
            } else {
                Timber.tag(loggerTag.value).d("Set the call type and create the presenter")
                webViewTarget.value = callData
                presenter = presenterFactory.create(callData, this)
            }
        } else {
            if (callData == null) {
                Timber.tag(loggerTag.value).d("Coming back from notification, do nothing")
            } else if (callData != currentCallData) {
                Timber.tag(loggerTag.value).d("User starts another call, restart the Activity")
                setIntent(startCallIntent(this, callData, callUiAccessTokenStore.issue()))
                recreate()
            } else {
                // Starting the same call again, should not happen, the UI is preventing this. But maybe when using external links.
                Timber.tag(loggerTag.value).d("Starting the same call again, do nothing")
            }
        }
    }

    private fun registerPermissionResultLauncher(): ActivityResultLauncher<Array<String>> {
        return registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val callback = requestPermissionCallback ?: return@registerForActivityResult
            requestPermissionCallback = null
            if (!hasEffectiveCallUiAccess()) {
                callback(emptyArray())
                return@registerForActivityResult
            }
            val permissionsToGrant = mutableListOf<String>()
            permissions.forEach { (permission, granted) ->
                if (granted) {
                    val webKitPermission = when (permission) {
                        Manifest.permission.CAMERA -> PermissionRequest.RESOURCE_VIDEO_CAPTURE
                        Manifest.permission.RECORD_AUDIO -> PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        else -> return@forEach
                    }
                    permissionsToGrant.add(webKitPermission)
                }
            }
            callback(permissionsToGrant.toTypedArray())
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun setPipParams() {
        setPictureInPictureParams(getPictureInPictureParams())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun enterPipMode(): Boolean {
        return if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            enterPictureInPictureMode(getPictureInPictureParams())
        } else {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun setPipOrientation(orientation: Int?) {
        currentPipOrientation = orientation
        setPictureInPictureParams(getPictureInPictureParams())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPictureInPictureParams(): PictureInPictureParams {
        // Portrait for calls seems more appropriate as a fallback value
        val orientation = currentPipOrientation ?: Configuration.ORIENTATION_PORTRAIT
        return PictureInPictureParams.Builder()
            .setAspectRatioFromOrientation(orientation)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(true)
                }
            }
            .build()
    }

    override fun hangUp() {
        eventSink?.invoke(CallScreenEvent.Hangup)
    }
}

internal fun mapWebkitPermissions(permissions: Array<String>): List<String> {
    return permissions.mapNotNull { permission ->
        when (permission) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            else -> null
        }
    }
}
