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
import io.element.android.libraries.matrix.api.auth.ElementClassicSession
import io.element.android.libraries.matrix.api.auth.OAuthPrompt
import io.element.android.libraries.matrix.api.auth.external.ExternalSession
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.exception.ClientException
import io.element.android.libraries.matrix.api.exception.ErrorKind
import io.element.android.libraries.matrix.impl.ClientBuilderProvider
import io.element.android.libraries.matrix.impl.FakeClientBuilderProvider
import io.element.android.libraries.matrix.impl.auth.qrlogin.SdkQrCodeLoginData
import io.element.android.libraries.matrix.impl.createRustMatrixClientFactory
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustSession
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClient
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClientBuilder
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiHomeserverLoginDetails
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiOAuthAuthorizationData
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiQrCodeData
import io.element.android.libraries.matrix.impl.paths.SessionPathsFactory
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.auth.FakeOAuthRedirectUrlProvider
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.test.FakeMdmService
import io.element.android.libraries.sessionstorage.api.LoginType
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

private const val TEST_SECURECHAT_DEVICE_ID = "SC-0123456789abcdefghijKL"

@OptIn(ExperimentalCoroutinesApi::class)
// NỢ KỸ THUẬT, không phải đã sửa. Lớp này vượt ngưỡng LargeClass của detekt sau khi
// thêm phần khoá thiết bị và cứng hoá đăng nhập. Tách nhỏ là việc thật, nhưng đây là mã
// xác thực nên phải làm riêng, có test đi kèm, không gộp vào một đợt audit. Tạm chặn cảnh
// báo để cổng lint còn bắt được lỗi MỚI, chứ không phải để coi như đã xong.
@Suppress("LargeClass")
class RustMatrixAuthenticationServiceTest {
    @Test
    fun `QR login is rejected at the authentication boundary`() = runTest {
        val homeserver = "https://chat.securechat.com.au"
        val sut = createRustMatrixAuthenticationService(
            enterpriseService = FakeEnterpriseService(
                defaultHomeserverListResult = { listOf(homeserver) },
                isAllowedToConnectToHomeserverResult = { it == homeserver },
            ),
        )
        val qrCodeData = SdkQrCodeLoginData(
            FakeFfiQrCodeData(
                serverNameResult = { homeserver },
            )
        )

        val result = sut.loginWithQrCode(qrCodeData) { }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.Generic::class.java)
    }

    @Test
    fun `setHomeserver is successful`() = runTest {
        val sut = createRustMatrixAuthenticationService(
            clientBuilderProvider = FakeClientBuilderProvider(
                provideResult = {
                    FakeFfiClientBuilder(
                        buildResult = {
                            FakeFfiClient(
                                homeserverLoginDetailsResult = {
                                    FakeFfiHomeserverLoginDetails(
                                        supportsPasswordLogin = true,
                                        supportsOAuthLogin = true,
                                    )
                                }
                            )
                        }
                    )
                }
            ),
        )
        val result = sut.setHomeserver("https://chat.securechat.com.au").getOrThrow()

        assertThat(result.supportsPasswordLogin).isTrue()
        assertThat(result.supportsOAuthLogin).isFalse()
    }

    @Test
    fun `password login sends the persisted SecureChat device id`() = runTest {
        val expectedDeviceId = TEST_SECURECHAT_DEVICE_ID
        var providerWasCalled = false
        var loginDeviceId: String? = null
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(supportsPasswordLogin = true) },
            session = aRustSession(deviceId = expectedDeviceId),
            loginResult = { _, _, _, deviceId ->
                assertThat(providerWasCalled).isTrue()
                loginDeviceId = deviceId
            },
            withUtdHook = {},
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> }),
            clientBuilderProvider = successfulClientBuilderProvider(client),
            secureChatDeviceIdProvider = SecureChatDeviceIdProvider {
                providerWasCalled = true
                expectedDeviceId
            },
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()

        assertThat(sut.login("alice", "password").isSuccess).isTrue()
        assertThat(loginDeviceId).isEqualTo(expectedDeviceId)
    }

    @Test
    fun `ambiguous password login failure cleans a possibly committed remote session`() = runTest {
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(supportsPasswordLogin = true) },
            loginResult = { _, _, _, _ -> error("Response was lost after server-side login") },
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()

        val result = sut.login("alice", "password")

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
        assertThat(sut.login("alice", "password").exceptionOrNull())
            .isInstanceOf(AuthenticationException.InvalidServerName::class.java)
    }

    @Test
    fun `structured password rejection keeps the controlled attempt available for correction`() = runTest {
        var loginCount = 0
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(supportsPasswordLogin = true) },
            loginResult = { _, _, _, _ ->
                if (loginCount++ == 0) {
                    throw ClientException.MatrixApi(
                        kind = ErrorKind.Forbidden,
                        code = "M_FORBIDDEN",
                        message = "Invalid password",
                        details = null,
                    )
                }
            },
            withUtdHook = {},
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()

        assertThat(sut.login("alice", "wrong-password").isFailure).isTrue()
        assertThat(sut.login("alice", "correct-password").isSuccess).isTrue()

        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(logoutCount).isEqualTo(0)
        assertThat(closeCount).isEqualTo(0)
    }

    @Test
    fun `legacy session secrets are ignored before password login`() = runTest {
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(supportsPasswordLogin = true) },
            withUtdHook = {},
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> }),
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )

        sut.setElementClassicSession(
            ElementClassicSession(
                userId = A_USER_ID,
                homeserverUrl = "https://chat.securechat.com.au",
                secrets = "legacy-secrets-must-not-be-imported",
                roomKeysVersion = "legacy-backup-must-not-be-imported",
                doesContainBackupKey = true,
            )
        )
        assertThat(sut.javaClass.declaredFields.map { it.name }).doesNotContain("elementClassicSession")
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()
        assertThat(sut.login("alice", "password").isSuccess).isTrue()
    }

    @Test
    fun `setHomeserver rejects creating a second local session before network access`() = runTest {
        var clientBuilderWasRequested = false
        val sessionStore = InMemorySessionStore(initialList = listOf(aSessionData()))
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider {
                clientBuilderWasRequested = true
                FakeFfiClientBuilder()
            },
        )

        val result = sut.setHomeserver("https://chat.securechat.com.au")

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.AccountAlreadyLoggedIn::class.java)
        assertThat(clientBuilderWasRequested).isFalse()
    }

    @Test
    fun `setHomeserver rejects stored sessions even when the latest-session pointer is corrupt`() = runTest {
        var clientBuilderWasRequested = false
        val backingStore = InMemorySessionStore(initialList = listOf(aSessionData()))
        val sessionStore = object : SessionStore by backingStore {
            override suspend fun getLatestSession(): SessionData? = null
        }
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider {
                clientBuilderWasRequested = true
                FakeFfiClientBuilder()
            },
        )

        val result = sut.setHomeserver("https://chat.securechat.com.au")

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.AccountAlreadyLoggedIn::class.java)
        assertThat(clientBuilderWasRequested).isFalse()
        assertThat(backingStore.getAllSessions()).hasSize(1)
    }

    @Test
    fun `cancelling homeserver discovery closes and clears the unpublished attempt`() = runTest {
        val discoveryStarted = CompletableDeferred<Unit>()
        val keepDiscoverySuspended = CompletableDeferred<Unit>()
        var closeCount = 0
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = {
                discoveryStarted.complete(Unit)
                keepDiscoverySuspended.await()
                FakeFfiHomeserverLoginDetails()
            },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )

        val discovery = async { sut.setHomeserver("https://chat.securechat.com.au") }
        discoveryStarted.await()
        discovery.cancel()
        discovery.join()

        assertThat(closeCount).isEqualTo(1)
        assertThat(sut.login("alice", "password").exceptionOrNull())
            .isInstanceOf(AuthenticationException.InvalidServerName::class.java)
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
    fun `setHomeserver rejects a delegated connection url outside policy`() = runTest {
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

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.InvalidServerName::class.java)
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
    fun `password login cleans remote and local state when matrix client creation fails`() = runTest {
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = { error("Failed to finish Matrix client creation") },
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()

        val result = sut.login("alice", "password")

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `password login cleans remote and local state when enterprise hook fails`() = runTest {
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
            clientEnterpriseHook = ClientEnterpriseHook { error("Enterprise hook failed") },
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()

        val result = sut.login("alice", "password")

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `cancelling after password authentication cannot leave an uncommitted remote session`() = runTest {
        val hookStarted = CompletableDeferred<Unit>()
        val keepHookSuspended = CompletableDeferred<Unit>()
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
            clientEnterpriseHook = ClientEnterpriseHook {
                hookStarted.complete(Unit)
                keepHookSuspended.await()
            },
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()
        val login = async { sut.login("alice", "password") }
        hookStarted.await()

        login.cancel()
        login.join()
        runCurrent()

        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `session import is rejected before restoring SDK state`() = runTest {
        var restoreWasCalled = false
        val client = FakeFfiClient(restoreSessionResult = { restoreWasCalled = true })
        val sut = createRustMatrixAuthenticationService(clientBuilderProvider = successfulClientBuilderProvider(client))
        val result = sut.importCreatedSession(
            ExternalSession(
                userId = "@alice:account.example.com",
                deviceId = "DEVICE",
                accessToken = "token",
                refreshToken = null,
                homeserverUrl = "https://account.example.com",
            )
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.Generic::class.java)
        assertThat(restoreWasCalled).isFalse()
    }

    @Test
    fun `OAuth URL and callback are rejected at the authentication boundary`() = runTest {
        var getOAuthUrlWasCalled = false
        var oauthCallbackWasCalled = false
        val client = FakeFfiClient(
            urlForOauthResult = {
                getOAuthUrlWasCalled = true
                FakeFfiOAuthAuthorizationData()
            },
            loginWithOauthCallbackResult = { oauthCallbackWasCalled = true },
        )
        val sut = createRustMatrixAuthenticationService(clientBuilderProvider = successfulClientBuilderProvider(client))

        val urlResult = sut.getOAuthUrl(OAuthPrompt.Login, null)
        val callbackResult = sut.loginWithOAuth("com.securechat:/?code=code")

        assertThat(urlResult.exceptionOrNull()).isInstanceOf(AuthenticationException.Generic::class.java)
        assertThat(callbackResult.exceptionOrNull()).isInstanceOf(AuthenticationException.Generic::class.java)
        assertThat(getOAuthUrlWasCalled).isFalse()
        assertThat(oauthCallbackWasCalled).isFalse()
    }

    @Test
    fun `homeserver replacement waits for an in-flight password login and cannot destroy the published client`() = runTest {
        val originalProvider = "https://one.example.com"
        val replacementProvider = "https://two.example.com"
        val loginStarted = CompletableDeferred<Unit>()
        val finishLogin = CompletableDeferred<Unit>()
        var loginCount = 0
        var logoutCount = 0
        var closeCount = 0
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            session = aRustSession(homeserverUrl = originalProvider),
            withUtdHook = {},
            loginResult = { _, _, _, _ ->
                loginCount++
                loginStarted.complete(Unit)
                finishLogin.await()
            },
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )
        assertThat(sut.setHomeserver(originalProvider).isSuccess).isTrue()

        val login = async { sut.login("alice", "password") }
        loginStarted.await()
        val replacement = async { sut.setHomeserver(replacementProvider) }
        runCurrent()

        assertThat(replacement.isCompleted).isFalse()
        assertThat(logoutCount).isEqualTo(0)
        assertThat(closeCount).isEqualTo(0)
        finishLogin.complete(Unit)

        assertThat(login.await().isSuccess).isTrue()
        assertThat(replacement.await().exceptionOrNull())
            .isInstanceOf(AuthenticationException.AccountAlreadyLoggedIn::class.java)
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(loginCount).isEqualTo(1)
        assertThat(logoutCount).isEqualTo(0)
        assertThat(closeCount).isEqualTo(0)
    }

    @Test
    fun `concurrent password logins serialize and only one can publish`() = runTest {
        val loginStarted = CompletableDeferred<Unit>()
        val finishLogin = CompletableDeferred<Unit>()
        var loginCount = 0
        var logoutCount = 0
        var closeCount = 0
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            withUtdHook = {},
            loginResult = { _, _, _, _ ->
                loginCount++
                loginStarted.complete(Unit)
                finishLogin.await()
            },
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()

        val firstLogin = async { sut.login("alice", "password") }
        loginStarted.await()
        val secondLogin = async { sut.login("alice", "password") }
        runCurrent()

        assertThat(secondLogin.isCompleted).isFalse()
        assertThat(loginCount).isEqualTo(1)
        finishLogin.complete(Unit)

        assertThat(firstLogin.await().isSuccess).isTrue()
        assertThat(secondLogin.await().exceptionOrNull())
            .isInstanceOf(AuthenticationException.InvalidServerName::class.java)
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(loginCount).isEqualTo(1)
        assertThat(logoutCount).isEqualTo(0)
        assertThat(closeCount).isEqualTo(0)
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
        coordinator.invalidateAuthenticationsAndRun {}

        val result = sut.login("alice", "password")

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
    }

    @Test
    fun `OAuth account creation is rejected even when registration is enabled`() = runTest {
        val sut = createRustMatrixAuthenticationService(
            mdmService = FakeMdmService(MdmConfig.default.copy(allowRegistration = true)),
        )

        val result = sut.getOAuthUrl(OAuthPrompt.Create, null)

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.Generic::class.java)
    }

    @Test
    fun `restore quarantines a session blocked by the current managed homeserver without network use`() = runTest {
        var clientBuilderWasRequested = false
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = "@alice:blocked.example.com",
                    isTokenValid = true,
                    homeserverUrl = "https://blocked.example.com",
                    loginType = LoginType.PASSWORD,
                )
            )
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider {
                clientBuilderWasRequested = true
                FakeFfiClientBuilder()
            },
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { it == "https://chat.securechat.com.au" },
            ),
        )

        val result = sut.restoreSession(SessionId("@alice:blocked.example.com"))

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(clientBuilderWasRequested).isFalse()
    }

    @Test
    fun `restore quarantines a legacy password session from a non SecureChat device before network use`() = runTest {
        var clientBuilderWasRequested = false
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = A_USER_ID.value,
                    deviceId = "DEVICE",
                    isTokenValid = true,
                    homeserverUrl = "https://chat.securechat.com.au",
                    loginType = LoginType.PASSWORD,
                )
            )
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider {
                clientBuilderWasRequested = true
                FakeFfiClientBuilder()
            },
        )

        val result = sut.restoreSession(SessionId(A_USER_ID.value))

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(clientBuilderWasRequested).isFalse()
    }

    @Test
    fun `restore quarantines a SecureChat session owned by another installation before network use`() = runTest {
        var clientBuilderWasRequested = false
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = A_USER_ID.value,
                    deviceId = "SC-0123456789abcdefghijKM",
                    isTokenValid = true,
                    homeserverUrl = "https://chat.securechat.com.au",
                    loginType = LoginType.PASSWORD,
                )
            )
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider {
                clientBuilderWasRequested = true
                FakeFfiClientBuilder()
            },
        )

        val result = sut.restoreSession(SessionId(A_USER_ID.value))

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(clientBuilderWasRequested).isFalse()
    }

    @Test
    fun `restore accepts the password session owned by this SecureChat installation`() = runTest {
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = A_USER_ID.value,
                    deviceId = TEST_SECURECHAT_DEVICE_ID,
                    isTokenValid = true,
                    homeserverUrl = "https://chat.securechat.com.au",
                    loginType = LoginType.PASSWORD,
                )
            ),
            updateUserProfileResult = { _, _, _ -> },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(
                FakeFfiClient(withUtdHook = {}),
            ),
            secureChatDeviceIdProvider = SecureChatDeviceIdProvider { TEST_SECURECHAT_DEVICE_ID },
        )

        val result = sut.restoreSession(SessionId(A_USER_ID.value))

        assertThat(result.isSuccess).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
    }

    @Test
    fun `password login rejects a delegated homeserver outside the managed allowlist before network use`() = runTest {
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

        val result = sut.setHomeserver(delegatedHomeserver, accountProvider)

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.InvalidServerName::class.java)
        assertThat(sessionStore.getAllSessions()).isEmpty()
    }

    @Test
    fun `password login cleans up when SDK session resolves to a homeserver outside policy`() = runTest {
        val allowedHomeserver = "https://chat.securechat.com.au"
        val delegatedHomeserver = "https://matrix-backend.example.net"
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails() },
            session = aRustSession(homeserverUrl = delegatedHomeserver),
            withUtdHook = {},
            logoutResult = { logoutCount++ },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { it == allowedHomeserver },
            ),
        )
        assertThat(sut.setHomeserver(allowedHomeserver, allowedHomeserver).isSuccess).isTrue()

        val result = sut.login("alice", "password")

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.InvalidServerName::class.java)
        assertThat(sessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `restore rejects a delegated homeserver outside the managed allowlist`() = runTest {
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
                    loginType = LoginType.PASSWORD,
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

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(policyChecks).containsExactly(accountProvider, delegatedHomeserver).inOrder()
    }

    @Test
    fun `restore remotely revokes legacy non-password sessions before removing local state`() = runTest {
        for (loginType in listOf(LoginType.OIDC, LoginType.SSO, LoginType.DIRECT, LoginType.QR, LoginType.UNKNOWN)) {
            var logoutCount = 0
            var closeCount = 0
            val sessionStore = InMemorySessionStore(
                initialList = listOf(
                    aSessionData(
                        sessionId = A_USER_ID.value,
                        deviceId = TEST_SECURECHAT_DEVICE_ID,
                        isTokenValid = true,
                        homeserverUrl = "https://chat.securechat.com.au",
                        accountProvider = "https://chat.securechat.com.au",
                        loginType = loginType,
                    )
                ),
                updateUserProfileResult = { _, _, _ -> },
            )
            val client = FakeFfiClient(
                withUtdHook = {},
                logoutResult = { logoutCount++ },
                closeResult = { closeCount++ },
            )
            val sut = createRustMatrixAuthenticationService(
                sessionStore = sessionStore,
                clientBuilderProvider = successfulClientBuilderProvider(client),
            )

            val result = sut.restoreSession(SessionId(A_USER_ID.value))

            assertThat(result.isFailure).isTrue()
            assertThat(sessionStore.getAllSessions()).isEmpty()
            assertThat(logoutCount).isEqualTo(1)
            assertThat(closeCount).isEqualTo(1)
        }
    }

    @Test
    fun `restore preserves a legacy session when remote revocation fails`() = runTest {
        var logoutCount = 0
        var closeCount = 0
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = A_USER_ID.value,
                    deviceId = TEST_SECURECHAT_DEVICE_ID,
                    isTokenValid = true,
                    homeserverUrl = "https://chat.securechat.com.au",
                    accountProvider = "https://chat.securechat.com.au",
                    loginType = LoginType.OIDC,
                )
            ),
            updateUserProfileResult = { _, _, _ -> },
        )
        val client = FakeFfiClient(
            withUtdHook = {},
            logoutResult = {
                logoutCount++
                error("Remote revocation failed")
            },
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )

        val result = sut.restoreSession(SessionId(A_USER_ID.value))

        assertThat(result.isFailure).isTrue()
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(logoutCount).isEqualTo(1)
        assertThat(closeCount).isEqualTo(1)
    }

    @Test
    fun `restore quarantines a client when policy changes during client creation`() = runTest {
        val accountProvider = "https://chat.securechat.com.au"
        var allowed = true
        var closeCount = 0
        var logoutCount = 0
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = A_USER_ID.value,
                    deviceId = TEST_SECURECHAT_DEVICE_ID,
                    isTokenValid = true,
                    homeserverUrl = accountProvider,
                    accountProvider = accountProvider,
                    loginType = LoginType.PASSWORD,
                )
            ),
            updateUserProfileResult = { _, _, _ -> },
        )
        val client = FakeFfiClient(
            restoreSessionResult = { allowed = false },
            withUtdHook = {},
            closeResult = { closeCount++ },
            logoutResult = { logoutCount++ },
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
        assertThat(sessionStore.getAllSessions()).hasSize(1)
        assertThat(closeCount).isEqualTo(1)
        assertThat(logoutCount).isEqualTo(0)
    }

    @Test
    fun `restore rejects multiple stored sessions before creating a client`() = runTest {
        var clientBuilderWasRequested = false
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(sessionId = A_USER_ID.value, isTokenValid = true),
                aSessionData(sessionId = "@bob:chat.securechat.com.au", isTokenValid = true),
            )
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = FakeClientBuilderProvider {
                clientBuilderWasRequested = true
                FakeFfiClientBuilder()
            },
        )

        val result = sut.restoreSession(SessionId(A_USER_ID.value))

        assertThat(result.exceptionOrNull()?.cause)
            .isInstanceOf(AuthenticationException.AccountAlreadyLoggedIn::class.java)
        assertThat(sessionStore.getAllSessions()).hasSize(2)
        assertThat(clientBuilderWasRequested).isFalse()
    }

    @Test
    fun `restore closes without publishing when a second session appears during client creation`() = runTest {
        var closeCount = 0
        val sessionStore = InMemorySessionStore(
            initialList = listOf(
                aSessionData(
                    sessionId = A_USER_ID.value,
                    deviceId = TEST_SECURECHAT_DEVICE_ID,
                    isTokenValid = true,
                )
            ),
            updateUserProfileResult = { _, _, _ -> },
        )
        val client = FakeFfiClient(
            restoreSessionResult = {
                sessionStore.addSession(
                    aSessionData(sessionId = "@bob:chat.securechat.com.au", isTokenValid = true)
                )
            },
            withUtdHook = {},
            closeResult = { closeCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )

        val result = sut.restoreSession(SessionId(A_USER_ID.value))

        assertThat(result.exceptionOrNull()?.cause)
            .isInstanceOf(AuthenticationException.AccountAlreadyLoggedIn::class.java)
        assertThat(sessionStore.getAllSessions()).hasSize(2)
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
    fun `publication rejects a local session that appears after password authentication`() = runTest {
        val backingSessionStore = InMemorySessionStore(updateUserProfileResult = { _, _, _ -> })
        var sessionCountChecks = 0
        var addSessionWasCalled = false
        val sessionStore = object : SessionStore by backingSessionStore {
            override suspend fun numberOfSessions(): Int = if (sessionCountChecks++ == 0) 0 else 1

            override suspend fun addSession(sessionData: SessionData) {
                addSessionWasCalled = true
                backingSessionStore.addSession(sessionData)
            }
        }
        var logoutCount = 0
        val client = FakeFfiClient(
            homeserverLoginDetailsResult = { FakeFfiHomeserverLoginDetails(supportsPasswordLogin = true) },
            withUtdHook = {},
            logoutResult = { logoutCount++ },
        )
        val sut = createRustMatrixAuthenticationService(
            sessionStore = sessionStore,
            clientBuilderProvider = successfulClientBuilderProvider(client),
        )
        assertThat(sut.setHomeserver("https://chat.securechat.com.au").isSuccess).isTrue()

        val result = sut.login("alice", "password")

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthenticationException.AccountAlreadyLoggedIn::class.java)
        assertThat(addSessionWasCalled).isFalse()
        assertThat(backingSessionStore.getAllSessions()).isEmpty()
        assertThat(logoutCount).isEqualTo(1)
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
        assertThat(replacement.await().exceptionOrNull())
            .isInstanceOf(AuthenticationException.AccountAlreadyLoggedIn::class.java)
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
        secureChatDeviceIdProvider: SecureChatDeviceIdProvider = SecureChatDeviceIdProvider {
            A_DEVICE_ID.value
        },
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
            secureChatDeviceIdProvider = secureChatDeviceIdProvider,
        )
    }
}
