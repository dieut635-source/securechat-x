/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import android.content.Intent
import android.os.Parcelable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.navigation.NavElements
import com.bumble.appyx.core.navigation.NavKey
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.core.state.MutableSavedStateMap
import com.bumble.appyx.core.state.SavedStateMap
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.newRoot
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.transitionhandler.rememberBackstackFader
import com.bumble.appyx.navmodel.backstack.transitionhandler.rememberBackstackSlider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.JoinedRoom
import io.element.android.annotations.ContributesNode
import io.element.android.appnav.intent.IntentResolver
import io.element.android.appnav.intent.ResolvedIntent
import io.element.android.appnav.room.RoomFlowNode
import io.element.android.appnav.room.RoomNavigationTarget
import io.element.android.appnav.root.RootNavStateFlowFactory
import io.element.android.appnav.root.RootPresenter
import io.element.android.appnav.root.RootView
import io.element.android.appnav.session.MatrixSessionCache
import io.element.android.features.announcement.api.AnnouncementService
import io.element.android.features.login.api.LoginParams
import io.element.android.features.login.api.accesscontrol.AccountProviderAccessControl
import io.element.android.features.logout.api.AutoLogoutSecurityGate
import io.element.android.features.rageshake.api.bugreport.BugReportEntryPoint
import io.element.android.features.share.api.ShareIntentData
import io.element.android.features.signedout.api.SignedOutEntryPoint
import io.element.android.libraries.accountselect.api.AccountSelectEntryPoint
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.appyx.rememberDelegateTransitionHandler
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.architecture.waitForChildAttached
import io.element.android.libraries.core.uri.ensureProtocol
import io.element.android.libraries.deeplink.api.DeeplinkData
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.asEventId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.sessionstorage.api.LoggedInState
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.ui.common.nodes.emptyNode
import io.element.android.services.analytics.api.AnalyticsLongRunningTransaction
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analytics.api.watchers.AnalyticsColdStartWatcher
import io.element.android.services.appnavstate.api.ROOM_OPENED_FROM_NOTIFICATION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.parcelize.Parcelize
import timber.log.Timber

@ContributesNode(AppScope::class)
@AssistedInject
class RootFlowNode(
    @Assisted val buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val sessionStore: SessionStore,
    private val autoLogoutSecurityGate: AutoLogoutSecurityGate,
    private val mdmService: MdmService,
    private val accountProviderAccessControl: AccountProviderAccessControl,
    private val navStateFlowFactory: RootNavStateFlowFactory,
    private val matrixSessionCache: MatrixSessionCache,
    private val presenter: RootPresenter,
    private val bugReportEntryPoint: BugReportEntryPoint,
    private val signedOutEntryPoint: SignedOutEntryPoint,
    private val accountSelectEntryPoint: AccountSelectEntryPoint,
    private val intentResolver: IntentResolver,
    private val featureFlagService: FeatureFlagService,
    private val announcementService: AnnouncementService,
    private val analyticsService: AnalyticsService,
    private val analyticsColdStartWatcher: AnalyticsColdStartWatcher,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : BaseFlowNode<RootFlowNode.NavTarget>(
    backstack = BackStack(
        initialElement = NavTarget.SplashScreen,
        savedStateMap = null,
    ),
    buildContext = buildContext,
    plugins = plugins
) {
    /**
     * Login params coming from a launch or new [Intent], waiting to be consumed by the not logged in flow.
     * Kept here so that the root nav target can be computed from both the logged in state and the pending
     * login params, whatever the order in which the intent and the first nav state emission are processed.
     */
    private var pendingLoginParams: LoginParams? = null
    private var requiresLoggedOutStateAfterSecurityLock = autoLogoutSecurityGate.isLocked.value
    @Volatile
    private var legacyMultiSessionBlocked = false
    private val navigationEpoch = MutableStateFlow(0L)
    private val navigationAttemptMutex = Mutex()

    override fun onBuilt() {
        analyticsColdStartWatcher.start()
        appCoroutineScope.launch {
            if (enforceSingleSessionInvariant()) return@launch
            // Never restore an authenticated saved graph while an automatic logout is pending. Also
            // reject stale logged-in navigation state after cleanup completed before this node was
            // built: the persisted SessionStore is the source of truth for whether restoration is safe.
            val savedNavElements = getSavedNavElements(buildContext.savedStateMap)
            val savedSessionIds = savedNavElements
                ?.mapNotNull { it.key.navTarget.requiredSessionId() }
                .orEmpty()
            val canAttemptSavedStateRestore = savedNavElements != null &&
                savedNavElements.none { it.key.navTarget is NavTarget.AccountSelect } &&
                !autoLogoutSecurityGate.isLocked.value &&
                !mdmService.config.value.restrictionsPending &&
                savedSessionIds.all { sessionStore.getSession(it.value) != null }
            val matrixSessionsRestored = canAttemptSavedStateRestore &&
                matrixSessionCache.restoreWithSavedState(buildContext.savedStateMap)
            val canRestoreSavedState = matrixSessionsRestored &&
                savedSessionIds.all { matrixSessionCache.getOrNull(it) != null }
            if (buildContext.savedStateMap != null && !canRestoreSavedState) {
                Timber.w("Discarding saved navigation state because its sessions could not be restored")
            }
            if (
                canRestoreSavedState &&
                buildContext.savedStateMap != null &&
                restoreSavedState(buildContext.savedStateMap)
            ) {
                observeNavState(skipFirstUnlockedState = true, savedStateMap = buildContext.savedStateMap)
            } else {
                if (isNavigationSecurityBlocked()) enterCurrentSecurityLock()
                observeNavState(skipFirstUnlockedState = false, savedStateMap = null)
            }
        }
        super.onBuilt()
    }

    override fun onSaveInstanceState(state: MutableSavedStateMap) {
        if (isNavigationSecurityBlocked()) {
            // Ensure Appyx can only persist the neutral splash target if the gate changed before its
            // lifecycle collector had a chance to replace an authenticated backstack.
            enterCurrentSecurityLock()
            super.onSaveInstanceState(state)
            return
        }
        super.onSaveInstanceState(state)
        matrixSessionCache.saveIntoSavedState(state)
        navStateFlowFactory.saveIntoSavedState(state)
    }

    private fun observeNavState(
        skipFirstUnlockedState: Boolean,
        savedStateMap: SavedStateMap?,
    ) {
        var shouldSkipFirstUnlockedState = skipFirstUnlockedState
        combine(
            navStateFlowFactory.create(savedStateMap),
            autoLogoutSecurityGate.isLocked,
            mdmService.config.map { it.restrictionsPending }.distinctUntilChanged(),
        ) { navState, isSecurityLocked, restrictionsPending ->
            Triple(navState, isSecurityLocked, restrictionsPending)
        }
            .distinctUntilChanged()
            .onEach { (navState, isSecurityLocked, restrictionsPending) ->
                Timber.v("navState=$navState")
                if (enforceSingleSessionInvariant()) {
                    shouldSkipFirstUnlockedState = false
                    return@onEach
                }
                if (restrictionsPending) {
                    shouldSkipFirstUnlockedState = false
                    enterRestrictionsPendingLock()
                    return@onEach
                }
                if (isSecurityLocked) {
                    shouldSkipFirstUnlockedState = false
                    enterAutoLogoutSecurityLock()
                    return@onEach
                }
                if (requiresLoggedOutStateAfterSecurityLock) {
                    // Cleanup can complete before SessionStore's flow delivers NotLoggedIn. Keep the
                    // splash screen in place rather than briefly resurrecting the cached session.
                    if (navState.loggedInState is LoggedInState.NotLoggedIn) {
                        requiresLoggedOutStateAfterSecurityLock = false
                        switchToNotLoggedInFlow(null)
                    }
                    return@onEach
                }
                if (shouldSkipFirstUnlockedState) {
                    shouldSkipFirstUnlockedState = false
                    return@onEach
                }
                when (navState.loggedInState) {
                    is LoggedInState.LoggedIn -> {
                        if (navState.loggedInState.isTokenValid) {
                            val sessionId = SessionId(navState.loggedInState.sessionId)
                            if (matrixSessionCache.getOrNull(sessionId) != null) {
                                switchToLoggedInFlow(sessionId, navState.cacheIndex)
                            } else {
                                tryToRestoreLatestSession(
                                    onSuccess = { sessionId -> switchToLoggedInFlow(sessionId, navState.cacheIndex) },
                                    onFailure = { switchToNotLoggedInFlow(null) }
                                )
                            }
                        } else {
                            switchToSignedOutFlow(SessionId(navState.loggedInState.sessionId))
                        }
                    }
                    LoggedInState.NotLoggedIn -> {
                        switchToNotLoggedInFlow(pendingLoginParams)
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun enterAutoLogoutSecurityLock() {
        requiresLoggedOutStateAfterSecurityLock = true
        pendingLoginParams = null
        // Do not use safeRoot here. SplashScreen can still exist as an inactive element, in which
        // case safeRoot intentionally becomes a no-op and could leave authenticated UI active.
        backstack.newRoot(NavTarget.SplashScreen)
        invalidateInFlightNavigation()
    }

    private fun enterRestrictionsPendingLock() {
        pendingLoginParams = null
        backstack.newRoot(NavTarget.SplashScreen)
        invalidateInFlightNavigation()
    }

    private fun enterLegacyMultiSessionLock() {
        if (!legacyMultiSessionBlocked) {
            Timber.e("SecureChat blocked a legacy local state containing more than one session")
        }
        legacyMultiSessionBlocked = true
        pendingLoginParams = null
        backstack.newRoot(NavTarget.SplashScreen)
        invalidateInFlightNavigation()
    }

    private suspend fun enforceSingleSessionInvariant(): Boolean {
        if (legacyMultiSessionBlocked) return true
        if (sessionStore.numberOfSessions() <= 1) return false
        enterLegacyMultiSessionLock()
        return true
    }

    private fun invalidateInFlightNavigation() {
        navigationEpoch.update { currentEpoch -> currentEpoch + 1 }
    }

    private fun enterCurrentSecurityLock() {
        if (legacyMultiSessionBlocked) {
            enterLegacyMultiSessionLock()
        } else if (autoLogoutSecurityGate.isLocked.value || requiresLoggedOutStateAfterSecurityLock) {
            enterAutoLogoutSecurityLock()
        } else {
            enterRestrictionsPendingLock()
        }
    }

    private fun isNavigationSecurityBlocked(): Boolean =
        legacyMultiSessionBlocked ||
            autoLogoutSecurityGate.isLocked.value ||
            requiresLoggedOutStateAfterSecurityLock ||
            mdmService.config.value.restrictionsPending

    /**
     * Re-check the security gates immediately before every navigation mutation. Intent handling and
     * session restoration cross suspension points, so a check made at their entry is not sufficient:
     * the DPC or auto-logout observer may have locked navigation while the operation was in flight.
     */
    private fun abortNavigationIfBlocked(navigationAttempt: NavigationAttempt? = null): Boolean {
        if (navigationAttempt != null && navigationAttempt.epoch != navigationEpoch.value) {
            Timber.w("Discarding navigation from an obsolete security epoch")
            return true
        }
        if (!isNavigationSecurityBlocked()) return false
        enterCurrentSecurityLock()
        return true
    }

    private fun pushIfNavigationAllowed(navTarget: NavTarget, navigationAttempt: NavigationAttempt? = null) {
        if (abortNavigationIfBlocked(navigationAttempt)) return
        backstack.push(navTarget)
    }

    private fun popIfNavigationAllowed(navigationAttempt: NavigationAttempt? = null) {
        if (abortNavigationIfBlocked(navigationAttempt)) return
        backstack.pop()
    }

    /**
     * Runs one externally supplied navigation request in an isolated child job. A security lock
     * advances [navigationEpoch], permanently invalidating the request even if the same session is
     * attached again after unlock. Only this child is cancelled; the caller of [handleIntent]
     * completes normally and the Root/lifecycle scopes are left untouched.
     */
    private suspend fun runNavigationAttempt(
        navigationAttempt: NavigationAttempt,
        block: suspend () -> Unit,
    ) {
        if (abortNavigationIfBlocked(navigationAttempt)) return
        supervisorScope {
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                navigationAttemptMutex.withLock {
                    if (abortNavigationIfBlocked(navigationAttempt)) return@withLock
                    block()
                }
            }
            val epochInvalidation = async(start = CoroutineStart.UNDISPATCHED) {
                navigationEpoch.first { epoch -> epoch != navigationAttempt.epoch }
            }
            // These are level-triggered security states, not pulse events: auto logout remains
            // locked through cleanup, and restrictions stay pending until a definitive DPC snapshot.
            // Observing them directly closes the delay before observeNavState() advances the epoch.
            val autoLogoutInvalidation = async(start = CoroutineStart.UNDISPATCHED) {
                autoLogoutSecurityGate.isLocked.first { isLocked -> isLocked }
            }
            val restrictionsInvalidation = async(start = CoroutineStart.UNDISPATCHED) {
                mdmService.config.first { config -> config.restrictionsPending }
            }
            try {
                val operationCompleted = select<Boolean> {
                    operation.onAwait { true }
                    epochInvalidation.onAwait { false }
                    autoLogoutInvalidation.onAwait {
                        // The watcher may resume after cleanup already unlocked the gate. The epoch
                        // still invalidates this request, but a stale callback must not put the
                        // normal login flow back behind the splash screen.
                        if (autoLogoutSecurityGate.isLocked.value) {
                            enterAutoLogoutSecurityLock()
                        }
                        false
                    }
                    restrictionsInvalidation.onAwait {
                        // As above, do not re-enter a restriction lock from an already completed
                        // StateFlow emission after the definitive policy snapshot arrived.
                        if (mdmService.config.value.restrictionsPending) {
                            enterRestrictionsPendingLock()
                        }
                        false
                    }
                }
                val navigationWasInvalidated = !operationCompleted || abortNavigationIfBlocked(navigationAttempt)
                if (navigationWasInvalidated) {
                    Timber.w("Cancelling in-flight navigation invalidated by a security lock")
                    operation.cancelAndJoin()
                    navigationAttemptMutex.withLock {
                        rollbackLatestSessionChange(navigationAttempt)
                    }
                } else {
                    navigationAttempt.clearLatestSessionChange()
                }
            } finally {
                operation.cancel()
                epochInvalidation.cancel()
                autoLogoutInvalidation.cancel()
                restrictionsInvalidation.cancel()
            }
        }
    }

    private suspend fun setLatestSessionForNavigation(
        sessionId: SessionId,
        navigationAttempt: NavigationAttempt,
    ): Boolean {
        if (enforceSingleSessionInvariant()) return false
        if (abortNavigationIfBlocked(navigationAttempt)) return false
        val previousSessionId = sessionStore.getLatestSessionId() ?: run {
            Timber.e("Refusing an external session switch without a rollback target")
            return false
        }
        if (abortNavigationIfBlocked(navigationAttempt)) return false
        if (previousSessionId == sessionId) return true

        navigationAttempt.prepareLatestSessionChange(previousSessionId, sessionId)
        sessionStore.setLatestSession(sessionId.value)
        return !abortNavigationIfBlocked(navigationAttempt)
    }

    private suspend fun rollbackLatestSessionChange(navigationAttempt: NavigationAttempt) {
        val change = navigationAttempt.consumeLatestSessionChange() ?: return
        if (autoLogoutSecurityGate.isLocked.value || requiresLoggedOutStateAfterSecurityLock) {
            // Auto logout owns session cleanup. Never resurrect the session it is deleting.
            return
        }
        if (sessionStore.getLatestSessionId() == change.requestedSessionId) {
            Timber.w("Rolling back latest-session selection from an invalidated external navigation request")
            sessionStore.setLatestSession(change.previousSessionId.value)
        }
    }

    /**
     * Restore the saved state for navigation in the current backstack.
     *
     * **WARNING:** this is an unsafe operation abusing the internals of the Appyx library, but it's the only way allow async state
     * restoration and not having to block the main thread when the app starts.
     *
     * Modify with utmost care and double check any possible Appyx updates that might break this.
     */
    @Suppress("UNCHECKED_CAST")
    private fun restoreSavedState(savedStateMap: SavedStateMap?): Boolean {
        if (abortNavigationIfBlocked()) return false
        val savedElements = getSavedNavElements(savedStateMap)
        if (savedElements != null) {
            backstack.accept(ReplaceAllOperation(savedElements))
            return true
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun getSavedNavElements(savedStateMap: SavedStateMap?): NavElements<NavTarget, BackStack.State>? {
        // 'NavModel' is the key used for storing the nav model state data in the map in Appyx.
        return savedStateMap?.get("NavModel") as? NavElements<NavTarget, BackStack.State>
    }

    private fun NavTarget.requiredSessionId(): SessionId? = when (this) {
        is NavTarget.AccountSelect -> currentSessionId
        is NavTarget.LoggedInFlow -> sessionId
        is NavTarget.SignedOutFlow -> sessionId
        is NavTarget.BugReport,
        is NavTarget.NotLoggedInFlow,
        is NavTarget.SplashScreen -> null
    }

    /**
     * Extract the saved state for navigation in the [navTarget].
     *
     * **WARNING:** this is an unsafe operation abusing the internals of the Appyx library, but it's the only way allow async state
     * restoration and not having to block the main thread when the app starts.
     *
     * Modify with utmost care and double check any possible Appyx updates that might break this.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractSavedStateForNavTarget(navTarget: NavTarget, savedStateMap: SavedStateMap?): SavedStateMap? {
        // 'ChildrenState' is the key used for storing the children state data in the map in Appyx
        val childrenState = savedStateMap?.get("ChildrenState") as? Map<NavKey<NavTarget>, SavedStateMap> ?: return null
        return childrenState.entries.find { (key, _) -> key.navTarget == navTarget }?.value
    }

    private fun switchToLoggedInFlow(sessionId: SessionId, navId: Int) {
        if (isNavigationSecurityBlocked()) {
            enterCurrentSecurityLock()
            return
        }
        pendingLoginParams = null
        backstack.safeRoot(NavTarget.LoggedInFlow(sessionId, navId))
    }

    private fun switchToNotLoggedInFlow(params: LoginParams?, navigationAttempt: NavigationAttempt? = null) {
        if (abortNavigationIfBlocked(navigationAttempt)) return
        Timber.d("switchToNotLoggedInFlow, hasLoginParams=${params != null}")
        matrixSessionCache.removeAll()
        backstack.safeRoot(NavTarget.NotLoggedInFlow(params))
    }

    private fun switchToSignedOutFlow(sessionId: SessionId) {
        if (isNavigationSecurityBlocked()) {
            enterCurrentSecurityLock()
            return
        }
        pendingLoginParams = null
        backstack.safeRoot(NavTarget.SignedOutFlow(sessionId))
    }

    private suspend fun restoreSessionIfNeeded(
        sessionId: SessionId,
        onFailure: () -> Unit,
        onSuccess: (SessionId) -> Unit,
    ) {
        matrixSessionCache.getOrRestore(sessionId).onSuccess {
            Timber.v("Succeed to restore session $sessionId")
            onSuccess(sessionId)
        }.onFailure {
            Timber.e(it, "Failed to restore session $sessionId")
            onFailure()
        }
    }

    private suspend fun tryToRestoreLatestSession(
        onSuccess: (SessionId) -> Unit, onFailure: () -> Unit
    ) {
        if (enforceSingleSessionInvariant()) return
        val latestSessionId = sessionStore.getLatestSessionId()
        if (latestSessionId == null) {
            onFailure()
            return
        }
        restoreSessionIfNeeded(latestSessionId, onFailure, onSuccess)
    }

    private fun onOpenBugReport() {
        pushIfNavigationAllowed(NavTarget.BugReport)
    }

    @Composable
    override fun View(modifier: Modifier) {
        val state = presenter.present()
        RootView(
            state = state,
            modifier = modifier,
            onOpenBugReport = this::onOpenBugReport,
        ) {
            val backstackSlider = rememberBackstackSlider<NavTarget>(
                transitionSpec = { spring(stiffness = Spring.StiffnessMediumLow) },
            )
            val backstackFader = rememberBackstackFader<NavTarget>(
                transitionSpec = { spring(stiffness = Spring.StiffnessMediumLow) },
            )
            val transitionHandler = rememberDelegateTransitionHandler<NavTarget, BackStack.State> { navTarget ->
                when (navTarget) {
                    is NavTarget.SplashScreen,
                    is NavTarget.LoggedInFlow,
                    is NavTarget.NotLoggedInFlow -> backstackFader
                    else -> backstackSlider
                }
            }
            BackstackView(transitionHandler = transitionHandler)
            announcementService.Render(Modifier)
        }
    }

    sealed interface NavTarget : Parcelable {
        @Parcelize data object SplashScreen : NavTarget

        @Parcelize data class AccountSelect(
            val currentSessionId: SessionId,
            val shareIntentData: ShareIntentData?,
            val permalinkData: PermalinkData?,
        ) : NavTarget

        @Parcelize data class NotLoggedInFlow(
            val params: LoginParams?
        ) : NavTarget

        @Parcelize data class LoggedInFlow(
            val sessionId: SessionId, val navId: Int
        ) : NavTarget

        @Parcelize data class SignedOutFlow(
            val sessionId: SessionId
        ) : NavTarget

        @Parcelize data object BugReport : NavTarget
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node {
        return when (navTarget) {
            is NavTarget.LoggedInFlow -> {
                val matrixClient = matrixSessionCache.getOrNull(navTarget.sessionId)
                    ?: return emptyNode(buildContext).also {
                        Timber.w("Couldn't find any session, go through SplashScreen")
                    }
                val inputs = LoggedInAppScopeFlowNode.Inputs(matrixClient)
                val callback = object : LoggedInAppScopeFlowNode.Callback {
                    override fun navigateToBugReport() {
                        pushIfNavigationAllowed(NavTarget.BugReport)
                    }

                    override fun navigateToAddAccount() {
                        Timber.w("Add-account navigation ignored in SecureChat single-device mode")
                    }
                }
                val savedNavState = extractSavedStateForNavTarget(navTarget, this.buildContext.savedStateMap)
                val buildContext = if (savedNavState != null) {
                    Timber.d("Creating a $navTarget with restored saved state")
                    buildContext.copy(savedStateMap = savedNavState)
                } else {
                    buildContext.copy(savedStateMap = savedNavState)
                }
                createNode<LoggedInAppScopeFlowNode>(buildContext, plugins = listOf(inputs, callback))
            }
            is NavTarget.NotLoggedInFlow -> {
                val callback = object : NotLoggedInFlowNode.Callback {
                    override fun navigateToBugReport() {
                        pushIfNavigationAllowed(NavTarget.BugReport)
                    }

                    override fun onDone() {
                        if (abortNavigationIfBlocked()) return
                        pendingLoginParams = null
                        popIfNavigationAllowed()
                    }
                }
                val params = NotLoggedInFlowNode.Params(
                    loginParams = navTarget.params,
                )
                createNode<NotLoggedInFlowNode>(buildContext, plugins = listOf(params, callback))
            }
            is NavTarget.SignedOutFlow -> {
                signedOutEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    params = SignedOutEntryPoint.Params(
                        sessionId = navTarget.sessionId,
                    ),
                )
            }
            NavTarget.SplashScreen -> emptyNode(buildContext)
            NavTarget.BugReport -> {
                val callback = object : BugReportEntryPoint.Callback {
                    override fun onDone() {
                        popIfNavigationAllowed()
                    }
                }
                bugReportEntryPoint.createNode(
                    parentNode = this,
                    buildContext = buildContext,
                    callback = callback,
                )
            }
            is NavTarget.AccountSelect -> {
                // This target can only arrive from stale saved state or synthetic navigation.
                // Never expose a chooser that could revive a second local account.
                enterLegacyMultiSessionLock()
                emptyNode(buildContext)
            }
        }
    }

    suspend fun handleIntent(intent: Intent) {
        val navigationAttempt = NavigationAttempt(navigationEpoch.value)
        if (enforceSingleSessionInvariant()) return
        if (abortNavigationIfBlocked(navigationAttempt)) {
            Timber.w("Intent ignored while a mandatory SecureChat security policy is pending")
            return
        }
        val resolvedIntent = intentResolver.resolve(intent) ?: return
        if (abortNavigationIfBlocked(navigationAttempt)) return
        runNavigationAttempt(navigationAttempt) {
            if (abortNavigationIfBlocked(navigationAttempt)) return@runNavigationAttempt
            when (resolvedIntent) {
                is ResolvedIntent.Navigation -> {
                    val openingRoomFromNotification = intent.getBooleanExtra(ROOM_OPENED_FROM_NOTIFICATION, false)
                    if (openingRoomFromNotification && resolvedIntent.deeplinkData is DeeplinkData.Room) {
                        analyticsService.startLongRunningTransaction(AnalyticsLongRunningTransaction.NotificationToMessage)
                    }
                    navigateTo(resolvedIntent.deeplinkData, navigationAttempt)
                }
                is ResolvedIntent.Login -> onLoginLink(resolvedIntent.params, navigationAttempt)
                is ResolvedIntent.OAuth -> Timber.w("OAuth callback ignored because browser authentication is disabled")
                is ResolvedIntent.Permalink -> navigateTo(resolvedIntent.permalinkData, navigationAttempt)
                is ResolvedIntent.IncomingShare -> onIncomingShare(resolvedIntent.shareIntentData, navigationAttempt)
            }
        }
    }

    private suspend fun onLoginLink(params: LoginParams, navigationAttempt: NavigationAttempt) {
        val isAllowed = accountProviderAccessControl.isAllowedToConnectToAccountProvider(
            params.accountProvider.ensureProtocol()
        )
        if (abortNavigationIfBlocked(navigationAttempt)) return
        if (isAllowed) {
            // Is there a session already?
            val sessions = sessionStore.getAllSessions()
            if (abortNavigationIfBlocked(navigationAttempt)) return
            if (sessions.isNotEmpty()) {
                if (featureFlagService.isFeatureEnabled(FeatureFlags.MultiAccount)) {
                    Timber.w("Multi-account feature flag ignored by SecureChat single-device policy")
                }
                Timber.w("Login link ignored because one local SecureChat session already exists")
            } else {
                if (abortNavigationIfBlocked(navigationAttempt)) return
                pendingLoginParams = params
                switchToNotLoggedInFlow(params, navigationAttempt)
            }
        } else {
            Timber.w("Login link ignored, we are not allowed to connect to the homeserver")
        }
    }

    private suspend fun onIncomingShare(shareIntentData: ShareIntentData, navigationAttempt: NavigationAttempt) {
        if (enforceSingleSessionInvariant()) return
        // Is there a session already?
        val latestSessionId = sessionStore.getLatestSessionId()
        if (abortNavigationIfBlocked(navigationAttempt)) return
        if (latestSessionId == null) {
            // No session, open login
            switchToNotLoggedInFlow(null, navigationAttempt)
        } else {
            // wait for the current session to be restored
            val loggedInFlowNode = attachSession(latestSessionId, navigationAttempt) ?: return
            if (abortNavigationIfBlocked(navigationAttempt)) return
            loggedInFlowNode.attachIncomingShare(shareIntentData)
        }
    }

    private suspend fun navigateTo(permalinkData: PermalinkData, navigationAttempt: NavigationAttempt) {
        Timber.d("Navigating to $permalinkData")
        if (enforceSingleSessionInvariant()) return
        // Is there a session already?
        val latestSessionId = sessionStore.getLatestSessionId()
        if (abortNavigationIfBlocked(navigationAttempt)) return
        if (latestSessionId == null) {
            // No session, open login
            switchToNotLoggedInFlow(null, navigationAttempt)
        } else {
            // wait for the current session to be restored
            val loggedInFlowNode = attachSession(latestSessionId, navigationAttempt) ?: return
            when (permalinkData) {
                is PermalinkData.FallbackLink -> Unit
                is PermalinkData.RoomEmailInviteLink -> Unit
                else -> {
                    if (abortNavigationIfBlocked(navigationAttempt)) return
                    loggedInFlowNode.attachPermalinkData(permalinkData, navigationAttempt)
                }
            }
        }
    }

    private suspend fun LoggedInFlowNode.attachPermalinkData(
        permalinkData: PermalinkData,
        navigationAttempt: NavigationAttempt,
    ) {
        if (abortNavigationIfBlocked(navigationAttempt)) return
        when (permalinkData) {
            is PermalinkData.FallbackLink -> Unit
            is PermalinkData.RoomEmailInviteLink -> Unit
            is PermalinkData.RoomLink -> {
                // If there is a thread id, focus on it in the main timeline
                val focusedEventId = if (permalinkData.threadId != null) {
                    permalinkData.threadId?.asEventId()
                } else {
                    permalinkData.eventId
                }
                val roomFlowNode = attachRoom(
                    roomIdOrAlias = permalinkData.roomIdOrAlias,
                    trigger = JoinedRoom.Trigger.MobilePermalink,
                    serverNames = permalinkData.viaParameters,
                    initialElement = RoomNavigationTarget.Root(eventId = focusedEventId),
                    clearBackstack = true
                )
                if (abortNavigationIfBlocked(navigationAttempt)) return
                roomFlowNode.maybeAttachThread(permalinkData.threadId, permalinkData.eventId, navigationAttempt)
            }
            is PermalinkData.UserLink -> {
                if (abortNavigationIfBlocked(navigationAttempt)) return
                attachUser(permalinkData.userId)
            }
        }
    }

    private suspend fun RoomFlowNode.maybeAttachThread(
        threadId: ThreadId?,
        focusedEventId: EventId?,
        navigationAttempt: NavigationAttempt,
    ) {
        if (threadId != null && !abortNavigationIfBlocked(navigationAttempt)) {
            attachThread(threadId, focusedEventId)
        }
    }

    private suspend fun navigateTo(deeplinkData: DeeplinkData, navigationAttempt: NavigationAttempt) {
        Timber.d("Navigating to $deeplinkData")
        attachSession(deeplinkData.sessionId, navigationAttempt)?.let { loggedInFlowNode ->
            if (abortNavigationIfBlocked(navigationAttempt)) return
            when (deeplinkData) {
                is DeeplinkData.Root -> Unit // The room list will always be shown, observing FtueState
                is DeeplinkData.Room -> {
                    val roomFlowNode = loggedInFlowNode.attachRoom(
                        roomIdOrAlias = deeplinkData.roomId.toRoomIdOrAlias(),
                        initialElement = RoomNavigationTarget.Root(eventId = deeplinkData.threadId?.asEventId() ?: deeplinkData.eventId),
                        clearBackstack = true,
                    )
                    if (abortNavigationIfBlocked(navigationAttempt)) return
                    roomFlowNode.maybeAttachThread(deeplinkData.threadId, deeplinkData.eventId, navigationAttempt)
                }
            }
        }
    }

    private suspend fun attachSession(sessionId: SessionId, navigationAttempt: NavigationAttempt): LoggedInFlowNode? {
        if (enforceSingleSessionInvariant()) return null
        if (abortNavigationIfBlocked(navigationAttempt)) return null
        // Ensure that the session is the latest one
        if (!setLatestSessionForNavigation(sessionId, navigationAttempt)) return null
        val loggedInFlowNode = waitForChildAttached<LoggedInAppScopeFlowNode, NavTarget> { navTarget ->
            navTarget is NavTarget.LoggedInFlow && navTarget.sessionId == sessionId
        }.attachSession()
        if (abortNavigationIfBlocked(navigationAttempt)) return null
        return loggedInFlowNode
    }
}

private class NavigationAttempt(val epoch: Long) {
    @Volatile
    private var latestSessionChange: LatestSessionChange? = null

    fun prepareLatestSessionChange(previousSessionId: SessionId, requestedSessionId: SessionId) {
        latestSessionChange = LatestSessionChange(previousSessionId, requestedSessionId)
    }

    fun clearLatestSessionChange() {
        latestSessionChange = null
    }

    fun consumeLatestSessionChange(): LatestSessionChange? {
        return latestSessionChange.also { latestSessionChange = null }
    }
}

private data class LatestSessionChange(
    val previousSessionId: SessionId,
    val requestedSessionId: SessionId,
)

private suspend fun SessionStore.getLatestSessionId() = getLatestSession()?.userId?.let(::SessionId)
