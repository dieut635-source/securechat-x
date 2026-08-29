/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import com.google.common.truth.Truth.assertThat
import io.element.android.features.enterprise.api.ClientEnterpriseHook
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.enterprise.test.FakeEnterpriseService
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.auth.AuthenticationException
import io.element.android.libraries.matrix.api.auth.OAuthPrompt
import io.element.android.libraries.matrix.api.auth.external.ExternalSession
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.impl.ClientBuilderProvider
import io.element.android.libraries.matrix.impl.FakeClientBuilderProvider
import io.element.android.libraries.matrix.impl.createRustMatrixClientFactory
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustSession
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClient
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClientBuilder
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiHomeserverLoginDetails
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeOAuthAuthorizationData
import io.element.android.libraries.matrix.impl.paths.SessionPathsFactory
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.auth.FakeOAuthRedirectUrlProvider
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.test.FakeMdmService
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.sessionstorage.test.FakeSessionSecurityCoordinator
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import io.element.android.libraries.workmanager.test.FakeWorkManagerScheduler
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RustMatrixAuthenticationServiceTest {
    @Test
    fun `setHomeserver is successful`() = runTest {
        val sut = createRustMatrixAuthenticationService(
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = {
                    FakeFfiClientBuilder(
                        buildResult = {
                            FakeFfiClient(
                                homeserverLoginDetailsResult = {
                                    FakeFfiHomeserverLoginDetails()
                                }
                            )
                        }
                    )
                }
            ),
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()
    }

    @Test
    fun `setHomeserver can fail gracefully and clean up the temporary client`() = runTest {
        val closeResult = lambdaRecorder<Unit> {}
        val sut = createRustMatrixAuthenticationService(
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = {
                    FakeFfiClientBuilder(
                        buildResult = {
                            FakeFfiClient(
                                homeserverLoginDetailsResult = {
                                    throw IllegalStateException("Failed to get homeserver login details")
                                },
                                closeResult = closeResult,
                            )
                        },
                    )
                },
            ),
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isFailure).isTrue()
        closeResult.assertions().isCalledOnce()
    }

    @Test
    fun `setHomeserver validates the account provider while allowing a delegated connection url`() = runTest {
        val allowedAccountProvider = "https://account.example.com"
        val sut = createRustMatrixAuthenticationService(
            clientBuilderProvider = successfulClientBuilderProvider(),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { it == allowedAccountProvider },
            ),
        )

        val result = sut.setHomeserver(
            homeserver = "https://delegated.example.net",
            accountProvider = allowedAccountProvider,
        )

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `password login rejects a provider blocked after homeserver discovery`() = runTest {
        var allowed = true
        val sut = createRustMatrixAuthenticationService(
            clientBuilderProvider = successfulClientBuilderProvider(),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { allowed },
                tweakMasUrlResult = { url, _ -> url },
            ),
        )
        assertThat(sut.setHomeserver("https://account.example.com").isSuccess).isTrue()

        allowed = false
        val result = sut.login("alice", "password")

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.InvalidServerName::class.java)
    }

    @Test
    fun `password login discards an authenticated client when policy changes in flight`() = runTest {
        var allowed = true
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore()
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            loginResult = { _, _, _, _ -> allowed = false },
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { allowed },
            ),
        )
        assertThat(sut.setHomeserver("https://account.example.com").isSuccess).isTrue()

        val result = sut.login("alice", "password")

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.InvalidServerName::class.java)
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `password login rechecks policy after suspending client setup`() = runTest {
        var allowed = true
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = {},
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { allowed },
                tweakMasUrlResult = { url, _ -> url },
            ),
            clientEnterpriseHook = ClientEnterpriseHook { allowed = false },
        )
        assertThat(sut.setHomeserver("https://account.example.com").isSuccess).isTrue()

        val result = sut.login("alice", "password")

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.InvalidServerName::class.java)
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `import discards a restored client when policy changes in flight`() = runTest {
        var allowed = true
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore()
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            restoreSessionResult = { allowed = false },
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { allowed },
            ),
        )
        assertThat(sut.setHomeserver("https://account.example.com").isSuccess).isTrue()

        val result = sut.importCreatedSession(
            ExternalSession(
                userId = "@alice:account.example.com",
                deviceId = "DEVICE",
                accessToken = "token",
                refreshToken = null,
                homeserverUrl = "https://account.example.com",
            )
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.InvalidServerName::class.java)
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `OAuth login discards an authenticated client when policy changes in flight`() = runTest {
        var allowed = true
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore()
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            loginWithOauthCallbackResult = { allowed = false },
            urlForOauthResult = { FakeOAuthAuthorizationData() },
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { allowed },
                tweakMasUrlResult = { url, _ -> url },
            ),
        )
        assertThat(sut.setHomeserver("https://account.example.com").isSuccess).isTrue()
        assertThat(sut.getOAuthUrl(OAuthPrompt.Login, null).isSuccess).isTrue()

        val result = sut.loginWithOAuth("com.securechat:/?code=code")

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.InvalidServerName::class.java)
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `a superseded password attempt cannot publish under the replacement provider`() = runTest {
        val originalProvider = "https://one.example.com"
        val replacementProvider = "https://two.example.com"
        var allowedProvider = originalProvider
        lateinit var sut: RustMatrixAuthenticationService
        val originalClient = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = {},
            loginResult = { _, _, _, _ ->
                allowedProvider = replacementProvider
                assertThat(sut.setHomeserver(replacementProvider).isSuccess).isTrue()
            },
        )
        val replacementClient = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = {},
        )
        var buildCount = 0
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = {
                    FakeFfiClientBuilder(
                        buildResult = { if (buildCount++ == 0) originalClient else replacementClient },
                    )
                }
            ),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { it == allowedProvider },
            ),
        )
        assertThat(sut.setHomeserver(originalProvider).isSuccess).isTrue()

        val originalResult = sut.login("alice", "password")

        assertThat(originalResult.exceptionOrNull()).isInstanceOf(AuthenticationException.InvalidServerName::class.java)
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
    }

    @Test
    fun `logout epoch invalidates an authentication configured before logout`() = runTest {
        var logoutCount = 0
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val coordinator = FakeSessionSecurityCoordinator()
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = {},
            logoutResult = { logoutCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
            sessionSecurityCoordinator = coordinator,
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()
        coordinator.invalidateAuthenticationsAndRun { Unit }

        val result = sut.login("alice", "password")

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
    }

    @Test
    fun `OAuth account creation is discarded when registration is disabled during callback`() = runTest {
        val mdmService = FakeMdmService(MdmConfig.default.copy(allowRegistration = true))
        var logoutCount = 0
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = {},
            urlForOauthResult = { FakeOAuthAuthorizationData() },
            loginWithOauthCallbackResult = {
                mdmService.emit(mdmService.config.value.copy(allowRegistration = false))
            },
            logoutResult = { logoutCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
            mdmService = mdmService,
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()
        assertThat(sut.getOAuthUrl(OAuthPrompt.Create, null).isSuccess).isTrue()

        val result = sut.loginWithOAuth("com.securechat:/?code=code")

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
    }

    @Test
    fun `restore removes a session blocked by the current managed homeserver`() = runTest {
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = "@alice:blocked.example.com",
                    isTokenValid = true,
                    homeserverUrl = "https://blocked.example.com",
                )
            )
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { it == "https://chat.securechat.com.au" },
            ),
        )

        val result = sut.restoreSession(SessionId("@alice:blocked.example.com"))

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).isEmpty()
    }

    @Test
    fun `password login persists the policy account provider separately from a delegated homeserver`() = runTest {
        val accountProvider = "https://chat.securechat.com.au"
        val delegatedHomeserver = "https://matrix-backend.example.net"
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            session = aRustSession(homeserverUrl = delegatedHomeserver),
            withUtdHook = {},
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { it == accountProvider },
            ),
        )

        assertThat(sut.setHomeserver(delegatedHomeserver, accountProvider).isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()

        val storedSession = sessionStore.getLatestSession()
        assertThat(storedSession?.homeserverUrl).isEqualTo(delegatedHomeserver)
        assertThat(storedSession?.accountProvider).isEqualTo(accountProvider)
    }

    @Test
    fun `restore validates a persisted account provider instead of the delegated homeserver`() = runTest {
        val accountProvider = "https://chat.securechat.com.au"
        val delegatedHomeserver = "https://matrix-backend.example.net"
        val policyChecks = mutableListOf<String>()
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = A_USER_ID.value,
                    isTokenValid = true,
                    homeserverUrl = delegatedHomeserver,
                    accountProvider = accountProvider,
                )
            ),
            updateUserProfileResult = { _, _, _ -> },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(
                FakeFfiClient(withUtdHook = {}),
            ),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = {
                    policyChecks += it
                    it == accountProvider
                },
            ),
        )

        val result = sut.restoreSession(SessionId(A_USER_ID.value))

        assertThat(result.isSuccess).isTrue()
        assertThat(policyChecks).containsExactly(accountProvider, accountProvider).inOrder()
    }

    @Test
    fun `restore discards a client when policy changes during client creation`() = runTest {
        val accountProvider = "https://chat.securechat.com.au"
        var allowed = true
        var closeCount = 0
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = A_USER_ID.value,
                    isTokenValid = true,
                    homeserverUrl = "https://matrix-backend.example.net",
                    accountProvider = accountProvider,
                )
            ),
            updateUserProfileResult = { _, _, _ -> },
        )
        val client = FakeFfiClient(
            restoreSessionResult = { allowed = false },
            withUtdHook = {},
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { allowed && it == accountProvider },
            ),
        )

        val result = sut.restoreSession(SessionId(A_USER_ID.value))

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `observer failure rolls back a newly persisted session`() = runTest {
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = {},
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )
        sut.listenToNewMatrixClients { error("Failed to publish the client") }
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()

        val result = sut.login("alice", "password")

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `attempt replacement waits for session publication to transfer client ownership`() = runTest {
        val addSessionStarted = CompletableDeferred<Unit>()
        val releaseAddSession = CompletableDeferred<Unit>()
        val backingSessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val sessionStore = object : SessionStore by backingSessionStore {
            override suspend fun addSession(sessionData: SessionData) {
                addSessionStarted.complete(Unit)
                releaseAddSession.await()
                backingSessionStore.addSession(sessionData)
            }
        }
        var originalClientCloseCount = 0
        val originalClient = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = {},
            closeResult = { originalClientCloseCount++ },
        )
        val replacementClient = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
        )
        var buildCount = 0
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = {
                    FakeFfiClientBuilder(
                        buildResult = { if (buildCount++ == 0) originalClient else replacementClient },
                    )
                },
            ),
        )
        assertThat(sut.setHomeserver("https://one.example.com").isSuccess).isTrue()
        val login = async { sut.login("alice", "password") }
        addSessionStarted.await()

        val replacement = async { sut.setHomeserver("https://two.example.com") }
        runCurrent()

        assertThat(replacement.isCompleted).isFalse()
        releaseAddSession.complete(Unit)
        assertThat(login.await().isSuccess).isTrue()
        assertThat(replacement.await().isSuccess).isTrue()
        assertThat(backingSessionStore.getAllSessions()).hasSize(1)
        assertThat(originalClientCloseCount).isEqualTo(0)
    }

    private fun successfulClientBuilderProvider(
        client: FakeFfiClient = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() }
        ),
    ): ClientBuilderProvider = FakeClientBuilderProvider(
        provideResult = {
            FakeFfiClientBuilder(
                buildResult = { client }
            )
        },
    )

    private fun TestScope.createRustMatrixAuthenticationService(
        sessionStore: SessionStore = InMemorySessionStore(),
        clientBuilderProvider: ClientBuilderProvider = FakeClientBuilderProvider(),
        enterpriseService: EnterpriseService = FakeEnterpriseService(
            isAllowedToConnectToHomeserverResult = { true },
            tweakMasUrlResult = { url, _ -> url },
        ),
        clientEnterpriseHook: ClientEnterpriseHook = ClientEnterpriseHook {},
        mdmService: FakeMdmService = FakeMdmService(MdmConfig.default.copy(allowRegistration = true)),
        sessionSecurityCoordinator: FakeSessionSecurityCoordinator = FakeSessionSecurityCoordinator(),
    ): RustMatrixAuthenticationService {
        val baseDirectory = File("/base")
        val cacheDirectory = File("/cache")
        val rustMatrixClientFactory = createRustMatrixClientFactory(
            cacheDirectory = cacheDirectory,
            sessionStore = sessionStore,
            clientBuilderProvider = clientBuilderProvider,
            workManagerScheduler = FakeWorkManagerScheduler(submitLambda = {}),
        )
        return RustMatrixAuthenticationService(
            sessionPathsFactory = SessionPathsFactory(baseDirectory, cacheDirectory),
            coroutineDispatchers = testCoroutineDispatchers(),
            sessionStore = sessionStore,
            rustMatrixClientFactory = rustMatrixClientFactory,
            secretGenerator = FakeSecretGenerator(),
            oAuthConfigurationProvider = OAuthConfigurationProvider(
                buildMeta = aBuildMeta(),
                oAuthRedirectUrlProvider = FakeOAuthRedirectUrlProvider(),
            ),
            enterpriseService = enterpriseService,
            featureFlagService = FakeFeatureFlagService(),
            clientEnterpriseHook = clientEnterpriseHook,
            mdmService = mdmService,
            sessionSecurityCoordinator = sessionSecurityCoordinator,
        )
    }
}
