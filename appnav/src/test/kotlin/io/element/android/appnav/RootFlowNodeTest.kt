/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.appnav

import android.content.Intent
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.bumble.appyx.core.modality.AncestryInfo
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.node
import com.bumble.appyx.core.state.SavedStateMap
import com.bumble.appyx.navmodel.backstack.activeElement
import com.bumble.appyx.testing.junit4.util.MainDispatcherRule
import com.bumble.appyx.testing.unit.common.helper.parentNodeTestHelper
import com.bumble.appyx.utils.customisations.NodeCustomisationDirectoryImpl
import com.google.common.truth.Truth.assertThat
import io.element.android.appnav.intent.IntentResolver
import io.element.android.appnav.root.RootNavStateFlowFactory
import io.element.android.appnav.root.RootPresenter
import io.element.android.appnav.session.MatrixSessionCache
import io.element.android.appnav.session.SyncOrchestrator
import io.element.android.features.login.api.LoginEntryPoint
import io.element.android.features.login.api.LoginParams
import io.element.android.features.login.api.accesscontrol.AccountProviderAccessControl
import io.element.android.features.login.test.FakeLoginEntryPoint
import io.element.android.features.login.test.FakeLoginIntentResolver
import io.element.android.features.login.test.accesscontrol.FakeAccountProviderAccessControl
import io.element.android.features.logout.api.AutoLogoutSecurityGate
import io.element.android.features.networkmonitor.test.FakeNetworkMonitor
import io.element.android.features.preferences.test.FakeCacheService
import io.element.android.features.rageshake.test.FakeBugReportEntryPoint
import io.element.android.features.rageshake.test.logs.FakeAnnouncementService
import io.element.android.features.share.test.FakeShareIntentHandler
import io.element.android.features.signedout.test.FakeSignedOutEntryPoint
import io.element.android.libraries.architecture.AssistedNodeFactory
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.auth.AuthenticationException
import io.element.android.libraries.matrix.api.auth.MatrixAuthenticationService
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeSdkMetadata
import io.element.android.libraries.matrix.test.auth.FakeMatrixAuthenticationService
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.ui.media.test.FakeImageLoaderHolder
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.test.FakeMdmService
import io.element.android.libraries.oauth.test.FakeOAuthIntentResolver
import io.element.android.libraries.preferences.test.FakeSessionPreferencesStoreFactory
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.FakeSessionSecurityCoordinator
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.services.analytics.test.watchers.FakeAnalyticsColdStartWatcher
import io.element.android.services.apperror.test.FakeAppErrorStateService
import io.element.android.services.appnavstate.test.FakeAppForegroundStateService
import io.element.android.tests.testutils.node.FakeNodeFactoriesBindings
import io.element.android.tests.testutils.node.FakeParentNode
import io.element.android.tests.testutils.presenter.NotUsedPresenter
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private const val A_LOGIN_LINK = "https://chat.securechat.com.au/login/?account_provider=example.com&login_hint=mxid:@alice:example.com"

private val A_LOGIN_PARAMS = LoginParams(
    accountProvider = "example.com",
    loginHint = "mxid:@alice:example.com",
)

private val A_LOGIN_ENTRY_POINT_PARAMS = LoginEntryPoint.Params(
    accountProvider = A_LOGIN_PARAMS.accountProvider,
    loginHint = A_LOGIN_PARAMS.loginHint,
)

class RootFlowNodeTest : RobolectricTest() {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `given no session, when a login link is handled before the nav state is observed, then the login params are kept`() = runTest {
        var loginEntryPointParams: LoginEntryPoint.Params? = null
        val rootFlowNode = createRootFlowNode(
            loginEntryPoint = FakeLoginEntryPoint { buildContext, params ->
                loginEntryPointParams = params
                node(buildContext) {}
            },
        )
        rootFlowNode.parentNodeTestHelper()
        // The intent is handled first, this is what happens when the deep link cold starts the app.
        rootFlowNode.handleIntent(aLoginIntent())
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.NotLoggedInFlow(A_LOGIN_PARAMS))
        // Then the first nav state emission lands, it must not override the login params.
        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.NotLoggedInFlow(A_LOGIN_PARAMS))
        assertThat(loginEntryPointParams).isEqualTo(A_LOGIN_ENTRY_POINT_PARAMS)
    }

    @Test
    fun `given no session, when a login link is handled after the nav state is observed, then the login params are applied`() = runTest {
        var loginEntryPointParams: LoginEntryPoint.Params? = null
        val rootFlowNode = createRootFlowNode(
            loginEntryPoint = FakeLoginEntryPoint { buildContext, params ->
                loginEntryPointParams = params
                node(buildContext) {}
            },
        )
        rootFlowNode.parentNodeTestHelper()
        // The nav state is observed first, this is what happens when the app is already running.
        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.NotLoggedInFlow(null))
        rootFlowNode.handleIntent(aLoginIntent())
        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.NotLoggedInFlow(A_LOGIN_PARAMS))
        assertThat(loginEntryPointParams).isEqualTo(A_LOGIN_ENTRY_POINT_PARAMS)
    }

    @Test
    fun `given automatic logout cleanup is locked at startup, authenticated state and intents stay behind splash`() = runTest {
        val securityGate = TestAutoLogoutSecurityGate(initiallyLocked = true)
        val session = aSessionData()
        val sessionStore = InMemorySessionStore(initialList = listOf(session))
        val rootFlowNode = createRootFlowNode(
            loginEntryPoint = FakeLoginEntryPoint { buildContext, _ -> node(buildContext) {} },
            sessionStore = sessionStore,
            autoLogoutSecurityGate = securityGate,
        )
        rootFlowNode.parentNodeTestHelper()

        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.SplashScreen)

        rootFlowNode.handleIntent(aLoginIntent())
        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.SplashScreen)

        securityGate.unlockAfterCleanup()
        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.SplashScreen)

        sessionStore.removeSession(session.userId)
        runCurrent()

        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.NotLoggedInFlow(null))
    }

    @Test
    fun `given DPC restrictions are pending, navigation and intents stay behind splash until policy arrives`() = runTest {
        val mdmService = FakeMdmService(MdmConfig.restrictionsPending)
        val rootFlowNode = createRootFlowNode(
            loginEntryPoint = FakeLoginEntryPoint { buildContext, _ -> node(buildContext) {} },
            mdmService = mdmService,
        )
        rootFlowNode.parentNodeTestHelper()

        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.SplashScreen)

        rootFlowNode.handleIntent(aLoginIntent())
        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.SplashScreen)

        mdmService.emit(MdmConfig.default)
        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.NotLoggedInFlow(null))
    }

    @Test
    fun `given security locks and unlocks while login policy check is suspended, in-flight navigation is not replayed`() = runTest {
        val policyCheckStarted = CompletableDeferred<Unit>()
        val resumePolicyCheck = CompletableDeferred<Unit>()
        val securityGate = TestAutoLogoutSecurityGate(initiallyLocked = false)
        val accountProviderAccessControl = object : AccountProviderAccessControl {
            override suspend fun isAllowedToConnectToAccountProvider(accountProviderUrl: String): Boolean {
                policyCheckStarted.complete(Unit)
                resumePolicyCheck.await()
                return true
            }
        }
        val rootFlowNode = createRootFlowNode(
            loginEntryPoint = FakeLoginEntryPoint { buildContext, _ -> node(buildContext) {} },
            autoLogoutSecurityGate = securityGate,
            accountProviderAccessControl = accountProviderAccessControl,
        )
        rootFlowNode.parentNodeTestHelper()
        runCurrent()

        val intentJob = launch { rootFlowNode.handleIntent(aLoginIntent()) }
        policyCheckStarted.await()
        securityGate.lock()
        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.SplashScreen)

        securityGate.unlockAfterCleanup()
        runCurrent()
        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.NotLoggedInFlow(null))

        resumePolicyCheck.complete(Unit)
        intentJob.join()
        runCurrent()

        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.NotLoggedInFlow(null))
    }

    @Test
    fun `given legacy multiple local sessions startup and intents fail closed without deleting data`() = runTest {
        val originalSession = aSessionData(sessionId = A_SESSION_ID.value, isTokenValid = true)
        val requestedSession = aSessionData(
            sessionId = A_SESSION_ID_2.value,
            isTokenValid = true,
            lastUsageIndex = 1,
        )
        val sessionStore = InMemorySessionStore(initialList = listOf(originalSession, requestedSession))
        val rootFlowNode = createRootFlowNode(
            loginEntryPoint = FakeLoginEntryPoint { buildContext, _ -> node(buildContext) {} },
            sessionStore = sessionStore,
            featureFlagService = FakeFeatureFlagService(
                initialState = mapOf(FeatureFlags.MultiAccount.key to true),
            ),
        )
        rootFlowNode.parentNodeTestHelper()
        runCurrent()

        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.SplashScreen)
        assertThat(sessionStore.getAllSessions()).containsExactly(originalSession, requestedSession)

        rootFlowNode.handleIntent(aLoginIntent())
        runCurrent()

        assertThat(rootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.SplashScreen)
        assertThat(sessionStore.getAllSessions()).containsExactly(originalSession, requestedSession)
    }

    @Test
    fun `given saved logged in navigation when managed policy rejects session restore then navigate to login`() = runTest {
        val session = aSessionData(isTokenValid = true)
        val sourceAuthenticationService = FakeMatrixAuthenticationService()
        val sourceMatrixSessionCache = createMatrixSessionCache(sourceAuthenticationService)
        sourceAuthenticationService.givenMatrixClient(
            FakeMatrixClient(sessionCoroutineScope = backgroundScope, userIdServerNameLambda = { A_SESSION_ID.value })
        )
        assertThat(sourceMatrixSessionCache.getOrRestore(A_SESSION_ID).isSuccess).isTrue()
        val sourceRootFlowNode = createRootFlowNode(
            loginEntryPoint = FakeLoginEntryPoint { buildContext, _ -> node(buildContext) {} },
            sessionStore = InMemorySessionStore(initialList = listOf(session)),
            matrixSessionCache = sourceMatrixSessionCache,
        )
        sourceRootFlowNode.backstack.safeRoot(RootFlowNode.NavTarget.LoggedInFlow(A_SESSION_ID, navId = 0))
        val savedStateMap = sourceRootFlowNode.saveInstanceState { true }

        val restoredSessionStore = InMemorySessionStore(initialList = listOf(session))
        val rejectingMatrixSessionCache = createMatrixSessionCache(
            PolicyRejectingAuthenticationService(restoredSessionStore)
        )
        val restoredRootFlowNode = createRootFlowNode(
            loginEntryPoint = FakeLoginEntryPoint { buildContext, _ -> node(buildContext) {} },
            sessionStore = restoredSessionStore,
            matrixSessionCache = rejectingMatrixSessionCache,
            savedStateMap = savedStateMap,
        )
        restoredRootFlowNode.parentNodeTestHelper()

        runCurrent()

        assertThat(restoredSessionStore.getLatestSession()).isNull()
        assertThat(rejectingMatrixSessionCache.getOrNull(A_SESSION_ID)).isNull()
        assertThat(restoredRootFlowNode.backstack.activeElement).isEqualTo(RootFlowNode.NavTarget.NotLoggedInFlow(null))
    }

    private fun aLoginIntent() = Intent(Intent.ACTION_VIEW, Uri.parse(A_LOGIN_LINK))

    private fun TestScope.createRootFlowNode(
        loginEntryPoint: LoginEntryPoint,
        sessionStore: SessionStore = InMemorySessionStore(),
        autoLogoutSecurityGate: AutoLogoutSecurityGate = TestAutoLogoutSecurityGate(initiallyLocked = false),
        mdmService: FakeMdmService = FakeMdmService(),
        accountProviderAccessControl: AccountProviderAccessControl = FakeAccountProviderAccessControl { true },
        matrixSessionCache: MatrixSessionCache = createMatrixSessionCache(FakeMatrixAuthenticationService()),
        featureFlagService: FeatureFlagService = FakeFeatureFlagService(),
        savedStateMap: SavedStateMap? = null,
    ): RootFlowNode {
        val parentNode = FakeParentNode(
            graph = FakeNodeFactoriesBindings(
                mapOf(
                    NotLoggedInFlowNode::class to AssistedNodeFactory { buildContext, plugins ->
                        NotLoggedInFlowNode(
                            buildContext = buildContext,
                            plugins = plugins,
                            loginEntryPoint = loginEntryPoint,
                            imageLoaderHolder = FakeImageLoaderHolder(),
                            analyticsColdStartWatcher = FakeAnalyticsColdStartWatcher(),
                        )
                    }
                )
            )
        )
        return RootFlowNode(
            buildContext = BuildContext(
                ancestryInfo = AncestryInfo.Child(anchor = parentNode),
                savedStateMap = savedStateMap,
                customisations = NodeCustomisationDirectoryImpl(),
            ),
            plugins = emptyList(),
            sessionStore = sessionStore,
            autoLogoutSecurityGate = autoLogoutSecurityGate,
            mdmService = mdmService,
            accountProviderAccessControl = accountProviderAccessControl,
            navStateFlowFactory = RootNavStateFlowFactory(
                sessionStore = sessionStore,
                cacheService = FakeCacheService(),
                matrixSessionCache = matrixSessionCache,
                imageLoaderHolder = FakeImageLoaderHolder(),
                sessionPreferencesStoreFactory = FakeSessionPreferencesStoreFactory(),
            ),
            matrixSessionCache = matrixSessionCache,
            presenter = RootPresenter(
                crashDetectionPresenter = NotUsedPresenter(),
                rageshakeDetectionPresenter = NotUsedPresenter(),
                appErrorStateService = FakeAppErrorStateService(),
                analyticsService = FakeAnalyticsService(),
                sdkMetadata = FakeSdkMetadata("sha"),
            ),
            bugReportEntryPoint = FakeBugReportEntryPoint(),
            signedOutEntryPoint = FakeSignedOutEntryPoint(),
            intentResolver = IntentResolver(
                deeplinkParser = { null },
                loginIntentResolver = FakeLoginIntentResolver { A_LOGIN_PARAMS },
                oAuthIntentResolver = FakeOAuthIntentResolver { null },
                permalinkParser = FakePermalinkParser(),
                shareIntentHandler = FakeShareIntentHandler(),
            ),
            featureFlagService = featureFlagService,
            announcementService = FakeAnnouncementService(),
            analyticsService = FakeAnalyticsService(),
            analyticsColdStartWatcher = FakeAnalyticsColdStartWatcher(),
            appCoroutineScope = backgroundScope,
        )
    }

    private fun TestScope.createMatrixSessionCache(authenticationService: MatrixAuthenticationService): MatrixSessionCache {
        return MatrixSessionCache(
            authenticationService = authenticationService,
            syncOrchestratorFactory = createSyncOrchestratorFactory(),
            analyticsService = FakeAnalyticsService(),
            sessionSecurityCoordinator = FakeSessionSecurityCoordinator(),
        )
    }

    private fun TestScope.createSyncOrchestratorFactory(): SyncOrchestrator.Factory {
        val dispatchers = testCoroutineDispatchers()
        return object : SyncOrchestrator.Factory {
            override fun create(matrixClient: MatrixClient, sessionCoroutineScope: CoroutineScope): SyncOrchestrator {
                return SyncOrchestrator(
                    matrixClient = matrixClient,
                    sessionCoroutineScope = sessionCoroutineScope,
                    appForegroundStateService = FakeAppForegroundStateService(),
                    networkMonitor = FakeNetworkMonitor(),
                    dispatchers = dispatchers,
                    analyticsService = FakeAnalyticsService(),
                )
            }
        }
    }

    private class PolicyRejectingAuthenticationService(
        private val sessionStore: SessionStore,
    ) : MatrixAuthenticationService by FakeMatrixAuthenticationService() {
        override suspend fun restoreSession(sessionId: SessionId): Result<MatrixClient> {
            sessionStore.removeSession(sessionId.value)
            return Result.failure(AuthenticationException.InvalidServerName("Session rejected by managed policy"))
        }
    }

    private class TestAutoLogoutSecurityGate(initiallyLocked: Boolean) : AutoLogoutSecurityGate {
        private val mutableIsLocked = MutableStateFlow(initiallyLocked)
        override val isLocked: StateFlow<Boolean> = mutableIsLocked

        override fun lock() {
            mutableIsLocked.value = true
        }

        override fun unlockAfterCleanup() {
            mutableIsLocked.value = false
        }
    }
}
