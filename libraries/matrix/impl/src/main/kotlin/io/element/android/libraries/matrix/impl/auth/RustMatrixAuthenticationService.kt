/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.enterprise.api.ClientEnterpriseHook
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.enterprise.api.canConnectToAnyHomeserver
import io.element.android.libraries.androidutils.crypto.ClientSecret
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.mapFailure
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.auth.AuthenticationException
import io.element.android.libraries.matrix.api.auth.ElementClassicSession
import io.element.android.libraries.matrix.api.auth.MatrixAuthenticationService
import io.element.android.libraries.matrix.api.auth.MatrixHomeServerDetails
import io.element.android.libraries.matrix.api.auth.OAuthDetails
import io.element.android.libraries.matrix.api.auth.OAuthPrompt
import io.element.android.libraries.matrix.api.auth.SessionRestorationException
import io.element.android.libraries.matrix.api.auth.external.ExternalSession
import io.element.android.libraries.matrix.api.auth.qrlogin.MatrixQrCodeLoginData
import io.element.android.libraries.matrix.api.auth.qrlogin.QrCodeLoginStep
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.exception.ClientException
import io.element.android.libraries.matrix.api.paths.SessionPaths
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.matrix.impl.ClientBuilderSlidingSync
import io.element.android.libraries.matrix.impl.RustMatrixClient
import io.element.android.libraries.matrix.impl.RustMatrixClientFactory
import io.element.android.libraries.matrix.impl.RustTemporaryMatrixClient
import io.element.android.libraries.matrix.impl.auth.qrlogin.QrErrorMapper
import io.element.android.libraries.matrix.impl.auth.qrlogin.SdkQrCodeLoginData
import io.element.android.libraries.matrix.impl.auth.qrlogin.toStep
import io.element.android.libraries.matrix.impl.exception.mapClientException
import io.element.android.libraries.matrix.impl.keys.SecretGenerator
import io.element.android.libraries.matrix.impl.mapper.toSessionData
import io.element.android.libraries.matrix.impl.paths.SessionPathsFactory
import io.element.android.libraries.matrix.impl.toSession
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.sessionstorage.api.LoginType
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionSecurityCoordinator
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.HumanQrLoginException
import org.matrix.rustcomponents.sdk.QrCodeData
import org.matrix.rustcomponents.sdk.QrCodeDecodeException
import org.matrix.rustcomponents.sdk.QrLoginProgress
import org.matrix.rustcomponents.sdk.QrLoginProgressListener
import org.matrix.rustcomponents.sdk.SecretsBundleWithUserId
import timber.log.Timber
import uniffi.matrix_sdk.OAuthAuthorizationData
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
// NỢ KỸ THUẬT, không phải đã sửa. Lớp này vượt ngưỡng LargeClass của detekt sau khi
// thêm phần khoá thiết bị và cứng hoá đăng nhập. Tách nhỏ là việc thật, nhưng đây là mã
// xác thực nên phải làm riêng, có test đi kèm, không gộp vào một đợt audit. Tạm chặn cảnh
// báo để cổng lint còn bắt được lỗi MỚI, chứ không phải để coi như đã xong.
@Suppress("LargeClass")
class RustMatrixAuthenticationService(
    private val sessionPathsFactory: SessionPathsFactory,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val sessionStore: SessionStore,
    private val rustMatrixClientFactory: RustMatrixClientFactory,
    private val secretGenerator: SecretGenerator,
    private val oAuthConfigurationProvider: OAuthConfigurationProvider,
    private val enterpriseService: EnterpriseService,
    private val featureFlagService: FeatureFlagService,
    private val clientEnterpriseHook: ClientEnterpriseHook,
    private val mdmService: MdmService,
    private val sessionSecurityCoordinator: SessionSecurityCoordinator,
    private val secureChatDeviceIdProvider: SecureChatDeviceIdProvider,
) : MatrixAuthenticationService {
    // Passphrase which will be used for new sessions. Existing sessions will use the passphrase
    // stored in the SessionData.
    private val pendingKey by lazy { getDatabaseKey() }

    @Volatile
    private var currentAttempt: AuthenticationAttempt? = null

    // Serializes the complete password-authentication lifecycle with homeserver replacement. This
    // mutex is deliberately held across network login and publication: allowing replacement or a
    // second login to touch the same SDK client in that window can orphan a bearer token or destroy
    // a session that has just been published.
    private val authenticationLifecycleMutex = Mutex()
    private val replaceAttemptMutex = Mutex()

    // Keep the legacy interface surface for now, but fail closed at every non-password API boundary.
    private val passwordOnlyAuthenticationEnabled: Boolean
        get() = true

    @Volatile
    private var pendingOAuth: PendingOAuth? = null

    private val newMatrixClientObservers = mutableListOf<(MatrixClient) -> Unit>()
    override fun listenToNewMatrixClients(lambda: (MatrixClient) -> Unit) {
        newMatrixClientObservers.add(lambda)
    }

    private data class AuthenticationAttempt(
        val client: Client,
        val sessionPaths: SessionPaths,
        val accountProvider: String?,
        val connectionHomeserver: String?,
        val securityToken: Long,
        val authenticatedCleanupStarted: AtomicBoolean = AtomicBoolean(false),
    )

    private data class PendingOAuth(
        val authorizationData: OAuthAuthorizationData,
        val attempt: AuthenticationAttempt,
        val prompt: OAuthPrompt,
    )

    override suspend fun restoreSession(sessionId: SessionId): Result<MatrixClient> = withContext(coroutineDispatchers.io) {
        runCatchingExceptions {
            val sessionData = requireOnlyStoredSession(sessionId)
            if (!sessionData.isTokenValid) {
                throw SessionRestorationException.InvalidToken()
            }
            if (!isStoredSessionEndpointAllowed(sessionData)) {
                // Preserve the only local copy of the token so an administrator can revoke it.
                // Contacting a now-disallowed endpoint or deleting the evidence would both violate
                // the SecureChat recovery policy.
                throw AuthenticationException.InvalidServerName(
                    "The stored session is quarantined by managed configuration"
                )
            }
            val expectedDeviceId = resolveExpectedDeviceIdForRestore(sessionData)
            assertPasswordSessionBelongsToThisInstallation(sessionData, expectedDeviceId)
            // Use the sessionData.passphrase, which can be null for a previously created session
            if (sessionData.passphrase == null) {
                Timber.w("Restoring a session without a passphrase")
            } else {
                Timber.w("Restoring a session with a passphrase")
            }
            val matrixClient = rustMatrixClientFactory.create(sessionData)
            try {
                val currentSessionData = requireOnlyStoredSession(sessionId)
                if (!isStoredSessionEndpointAllowed(currentSessionData)) {
                    throw AuthenticationException.InvalidServerName(
                        "The stored session is quarantined by managed configuration"
                    )
                }
                assertPasswordSessionBelongsToThisInstallation(currentSessionData, expectedDeviceId)
            } catch (failure: Exception) {
                closeQuarantinedRestoredClient(matrixClient, failure)
                throw failure
            }
            if (sessionData.loginType != LoginType.PASSWORD) {
                revokeLegacyStoredSession(matrixClient)
                throw AuthenticationException.InvalidServerName(
                    "The legacy session was revoked; administrator enrollment is required"
                )
            }
            matrixClient
        }.mapFailure { failure ->
            failure.mapClientException()
        }
    }

    private fun getDatabaseKey(): ClientSecret {
        Timber.d("New sessions will be encrypted with a raw key")
        return secretGenerator.generateKey()
    }

    override suspend fun setHomeserver(
        homeserver: String,
        accountProvider: String,
    ): Result<MatrixHomeServerDetails> =
        withContext(coroutineDispatchers.io) {
            authenticationLifecycleMutex.withLock {
                val securityToken = sessionSecurityCoordinator.beginAuthentication()
                replaceAttemptMutex.withLock {
                    discardCurrentAttempt()
                    val emptySessionPath = sessionPathsFactory.create()
                    var attempt: AuthenticationAttempt? = null
                    try {
                        runCatchingExceptions {
                            assertNoExistingLocalSession()
                            assertAccountProviderAllowed(accountProvider)
                            // This is the endpoint that receives the password. Checking only the
                            // user-facing account provider would allow a delegated or caller-supplied URL
                            // outside policy to capture credentials before restoration rejects it.
                            assertAccountProviderAllowed(homeserver)
                            val client = makeClient(sessionPaths = emptySessionPath) {
                                serverNameOrHomeserverUrl(homeserver)
                            }
                            attempt = AuthenticationAttempt(
                                client = client,
                                sessionPaths = emptySessionPath,
                                accountProvider = accountProvider,
                                connectionHomeserver = homeserver,
                                securityToken = securityToken,
                            ).also { currentAttempt = it }

                            client.homeserverLoginDetails().map().copy(supportsOAuthLogin = false).also {
                                // Re-check after discovery: policy or the active attempt may have changed
                                // during the network request.
                                assertAuthenticationAttemptAllowed(attempt)
                            }
                        }.onFailure {
                            attempt?.let { clearAttempt(it, destroyClient = true, deleteSessionPaths = true) }
                                ?: emptySessionPath.deleteRecursively()
                        }.mapFailure { failure ->
                            Timber.e(failure, "Failed to set homeserver to $homeserver")
                            failure.mapAuthenticationException()
                        }
                    } catch (failure: CancellationException) {
                        // runCatchingExceptions deliberately rethrows cancellation. Do not leave
                        // the unpublished client/currentAttempt/session directory behind.
                        attempt?.let { clearAttempt(it, destroyClient = true, deleteSessionPaths = true) }
                            ?: emptySessionPath.deleteRecursively()
                        throw failure
                    }
                }
            }
        }

    override suspend fun login(username: String, password: String): Result<SessionId> =
        withContext(coroutineDispatchers.io) {
            authenticationLifecycleMutex.withLock {
                runCatchingExceptions {
                    val attempt = requireCurrentAttempt()
                    assertAuthenticationAttemptAllowed(attempt)
                    val client = attempt.client
                    val secureChatDeviceId = secureChatDeviceIdProvider.getOrCreate()
                    try {
                        client.login(
                            username = username,
                            password = password,
                            initialDeviceName = "SecureChat Android",
                            deviceId = secureChatDeviceId,
                        )
                    } catch (failure: Exception) {
                        // A structured Matrix error is a completed rejection and cannot contain a
                        // successful login token, so keep this controlled attempt alive to correct a
                        // password. Cancellation, response loss and local failures are ambiguous and
                        // must invalidate any possibly committed token before a retry can begin.
                        if (!failure.isDefinitiveMatrixLoginRejection()) {
                            cleanupAuthenticatedAttempt(attempt, failure)
                        }
                        throw failure
                    }
                    finishAuthenticatedAttempt(attempt) {
                        assertAuthenticationAttemptAllowedAfterAuthentication(attempt)
                        // Ensure that the user is not already logged in with the same account.
                        ensureNotAlreadyLoggedIn(client)
                        val sessionData = client.session()
                            .toSessionData(
                                isTokenValid = true,
                                loginType = LoginType.PASSWORD,
                                passphrase = pendingKey.formattedAsString(),
                                sessionPaths = attempt.sessionPaths,
                                accountProvider = attempt.accountProvider,
                            )
                        assertStoredSessionBelongsToThisInstallation(sessionData, secureChatDeviceId)
                        assertAuthenticatedSessionAllowed(attempt, sessionData.homeserverUrl)
                        val matrixClient = rustMatrixClientFactory.create(client, sessionData, isMessageSearchAvailable())

                        // Apply enterprise hooks to the newly created client as soon as possible.
                        clientEnterpriseHook(matrixClient)

                        assertAuthenticatedSessionAllowed(attempt, sessionData.homeserverUrl)
                        commitAuthenticatedSession(
                            attempt = attempt,
                            matrixClient = matrixClient,
                            sessionData = sessionData,
                            policyCheck = {
                                assertAuthenticationAttemptAllowed(attempt)
                                assertAccountProviderAllowed(sessionData.homeserverUrl)
                            },
                        )

                        SessionId(sessionData.userId)
                    }
                }.mapFailure { failure ->
                    Timber.e(failure, "Failed to login")
                    failure.mapAuthenticationException()
                }
            }
        }

    override fun doSecretsContainBackupKey(
        userId: UserId,
        secrets: String,
        backupInfo: String,
    ): Boolean {
        return try {
            SecretsBundleWithUserId.fromStr(
                userId = userId.value,
                bundle = secrets,
                backupInfo = backupInfo,
            ).use { secretsBundle ->
                secretsBundle.containsBackupKey()
            }
        } catch (failure: Exception) {
            Timber.e(failure, "Failed to parse secrets for previous Matrix app session $userId")
            false
        }
    }

    override suspend fun importCreatedSession(externalSession: ExternalSession): Result<SessionId> =
        withContext(coroutineDispatchers.io) {
            if (passwordOnlyAuthenticationEnabled) {
                return@withContext disabledAuthenticationMethod()
            }
            runCatchingExceptions {
                val attempt = requireCurrentAttempt()
                assertAuthenticationAttemptAllowed(attempt)
                val client = attempt.client
                val sessionData = externalSession.toSessionData(
                    isTokenValid = true,
                    loginType = LoginType.PASSWORD,
                    passphrase = pendingKey.formattedAsString(),
                    sessionPaths = attempt.sessionPaths,
                    accountProvider = attempt.accountProvider,
                )

                // We restore the client using the just retrieved session data
                client.restoreSession(sessionData.toSession())
                assertAuthenticationAttemptAllowedAfterAuthentication(attempt)
                val matrixClient = rustMatrixClientFactory.create(client, sessionData, isMessageSearchAvailable())

                // Apply enterprise hooks to the newly created client as soon as possible
                clientEnterpriseHook(matrixClient)

                // We wait for the verification state to be known
                matrixClient.waitForKnownVerificationState()

                // And once it's ready we share it and save the actual session data
                assertAuthenticationAttemptAllowedAfterAuthentication(attempt)
                commitAuthenticatedSession(attempt, matrixClient, sessionData)

                SessionId(sessionData.userId)
            }
        }

    override suspend fun getOAuthUrl(
        prompt: OAuthPrompt,
        loginHint: String?,
    ): Result<OAuthDetails> {
        return withContext(coroutineDispatchers.io) {
            if (passwordOnlyAuthenticationEnabled) {
                return@withContext disabledAuthenticationMethod()
            }
            runCatchingExceptions {
                val attempt = requireCurrentAttempt()
                assertAuthenticationAttemptAllowed(attempt)
                val client = attempt.client
                val oAuthAuthorizationData = client.urlForOauth(
                    oauthConfiguration = oAuthConfigurationProvider.get(),
                    prompt = prompt.toRustPrompt(),
                    loginHint = loginHint,
                    // If we want to restore a previous session for which we have encryption keys, we can pass the deviceId here. At the moment, we don't
                    deviceId = null,
                    additionalScopes = emptyList(),
                )
                // Policy may change while MAS metadata is being resolved.
                assertAuthenticationAttemptAllowed(attempt)
                val getUrlResolver = RustTemporaryMatrixClient(client, attempt.sessionPaths)
                val url = oAuthAuthorizationData.loginUrl()
                    .let {
                        enterpriseService.tweakMasUrl(
                            url = it,
                            urlContentFetcher = getUrlResolver,
                        )
                    }
                assertAuthenticationAttemptAllowed(attempt)
                pendingOAuth?.authorizationData?.close()
                pendingOAuth = PendingOAuth(
                    authorizationData = oAuthAuthorizationData,
                    attempt = attempt,
                    prompt = prompt,
                )
                OAuthDetails(url)
            }.mapFailure { failure ->
                Timber.e(failure, "Failed to get OAuth URL")
                failure.mapAuthenticationException()
            }
        }
    }

    override suspend fun cancelOAuthLogin(): Result<Unit> {
        return withContext(coroutineDispatchers.io) {
            runCatchingExceptions {
                pendingOAuth?.let { pending ->
                    pending.attempt.client.abortOauthAuth(pending.authorizationData)
                    pending.authorizationData.close()
                }
                pendingOAuth = null
            }.mapFailure { failure ->
                Timber.e(failure, "Failed to cancel OAuth login")
                failure.mapAuthenticationException()
            }
        }
    }

    // Kept for source/API compatibility with the upstream login feature. SecureChat deliberately
    // does not retain the supplied session or import its secrets into a password-authenticated client.
    override fun setElementClassicSession(session: ElementClassicSession?) = Unit

    /**
     * callbackUrl should be the `url` from `OAuthAction` (with all the parameters).
     */
    override suspend fun loginWithOAuth(callbackUrl: String): Result<SessionId> {
        return withContext(coroutineDispatchers.io) {
            if (passwordOnlyAuthenticationEnabled) {
                return@withContext disabledAuthenticationMethod()
            }
            runCatchingExceptions {
                val pending = pendingOAuth ?: error("You need to call `getOAuthUrl()` first")
                val attempt = pending.attempt
                assertAuthenticationAttemptAllowed(attempt)
                val client = attempt.client
                client.loginWithOauthCallback(
                    callbackUrl = callbackUrl,
                )
                assertAuthenticationAttemptAllowedAfterAuthentication(attempt)
                // Free the pending data since we won't use it to abort the flow anymore
                pending.authorizationData.close()
                if (pendingOAuth === pending) pendingOAuth = null
                // Ensure that the user is not already logged in with the same account
                ensureNotAlreadyLoggedIn(client)
                val sessionData = client.session().toSessionData(
                    isTokenValid = true,
                    loginType = LoginType.OIDC,
                    passphrase = pendingKey.formattedAsString(),
                    sessionPaths = attempt.sessionPaths,
                    accountProvider = attempt.accountProvider,
                )
                val matrixClient = rustMatrixClientFactory.create(client, sessionData, isMessageSearchAvailable())

                // Apply enterprise hooks to the newly created client as soon as possible
                clientEnterpriseHook(matrixClient)

                matrixClient.waitForKnownVerificationState()

                assertAuthenticationAttemptAllowedAfterAuthentication(attempt)
                commitAuthenticatedSession(attempt, matrixClient, sessionData) {
                    assertAuthenticationAttemptAllowed(attempt)
                    if (pending.prompt == OAuthPrompt.Create && !mdmService.config.value.allowRegistration) {
                        throw AuthenticationException.InvalidServerName(
                            "Account registration is blocked by managed configuration"
                        )
                    }
                }

                SessionId(sessionData.userId)
            }.mapFailure { failure ->
                Timber.e(failure, "Failed to login with OAuth")
                failure.mapAuthenticationException()
            }
        }
    }

    @Throws(AuthenticationException.AccountAlreadyLoggedIn::class)
    private suspend fun ensureNotAlreadyLoggedIn(client: Client) {
        val newUserId = client.userId()
        if (sessionStore.numberOfSessions() > 0) {
            // The enclosing authenticated-attempt boundary owns remote logout, client close and
            // temporary-path deletion. Keeping cleanup in one place prevents partial/double cleanup.
            throw AuthenticationException.AccountAlreadyLoggedIn(newUserId)
        }
    }

    override suspend fun loginWithQrCode(qrCodeData: MatrixQrCodeLoginData, progress: (QrCodeLoginStep) -> Unit) =
        withContext(coroutineDispatchers.io) {
            if (passwordOnlyAuthenticationEnabled) {
                return@withContext disabledAuthenticationMethod()
            }
            val sdkQrCodeLoginData = (qrCodeData as SdkQrCodeLoginData).rustQrCodeData
            val securityToken = sessionSecurityCoordinator.beginAuthentication()
            val oAuthConfiguration = oAuthConfigurationProvider.get()
            val progressListener = object : QrLoginProgressListener {
                override fun onUpdate(state: QrLoginProgress) {
                    Timber.d("QR Code login progress: $state")
                    progress(state.toStep())
                }
            }
            runCatchingExceptions {
                assertQrCodeAccountProviderAllowed(qrCodeData)
                val attempt = replaceAttemptMutex.withLock {
                    discardCurrentAttempt()
                    val emptySessionPaths = sessionPathsFactory.create()
                    val client = try {
                        makeQrCodeLoginClient(
                            sessionPaths = emptySessionPaths,
                            qrCodeData = sdkQrCodeLoginData,
                        )
                    } catch (failure: Exception) {
                        emptySessionPaths.deleteRecursively()
                        throw failure
                    }
                    AuthenticationAttempt(
                        client = client,
                        sessionPaths = emptySessionPaths,
                        accountProvider = qrCodeData.serverName(),
                        connectionHomeserver = qrCodeData.serverName(),
                        securityToken = securityToken,
                    ).also { currentAttempt = it }
                }
                val client = attempt.client
                client.newLoginWithQrCodeHandler(
                    oauthConfiguration = oAuthConfiguration,
                ).use {
                    it.scan(
                        qrCodeData = qrCodeData.rustQrCodeData,
                        progressListener = progressListener,
                    )
                }
                assertQrCodeAccountProviderAllowedAfterAuthentication(qrCodeData, attempt)
                // Ensure that the user is not already logged in with the same account
                ensureNotAlreadyLoggedIn(client)
                val sessionData = client.session()
                    .toSessionData(
                        isTokenValid = true,
                        loginType = LoginType.QR,
                        passphrase = pendingKey.formattedAsString(),
                        sessionPaths = attempt.sessionPaths,
                        accountProvider = attempt.accountProvider,
                    )
                val matrixClient = rustMatrixClientFactory.create(client, sessionData, isMessageSearchAvailable())

                // Apply enterprise hooks to the newly created client as soon as possible
                clientEnterpriseHook(matrixClient)

                assertQrCodeAccountProviderAllowedAfterAuthentication(qrCodeData, attempt)
                commitAuthenticatedSession(attempt, matrixClient, sessionData) {
                    assertQrCodeAccountProviderAllowed(qrCodeData)
                }

                SessionId(sessionData.userId)
            }.mapFailure {
                when (it) {
                    is QrCodeDecodeException -> QrErrorMapper.map(it)
                    is HumanQrLoginException -> QrErrorMapper.map(it)
                    else -> it
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                Timber.e(throwable, "Failed to login with QR code")
            }
        }

    private suspend fun makeClient(
        sessionPaths: SessionPaths,
        config: suspend ClientBuilder.() -> ClientBuilder,
    ): Client {
        Timber.d("Creating client with simplified sliding sync")
        return rustMatrixClientFactory
            .getBaseClientBuilder(
                sessionPaths = sessionPaths,
                clientSecret = pendingKey,
                slidingSyncType = ClientBuilderSlidingSync.Discovered,
                isMessageSearchAvailable = isMessageSearchAvailable(),
            )
            .config()
            .build()
    }

    private suspend fun makeQrCodeLoginClient(
        sessionPaths: SessionPaths,
        qrCodeData: QrCodeData,
    ): Client {
        Timber.d("Creating client for QR Code login with simplified sliding sync")
        // The 2025 version of MSC4108 provides baseUrl; the 2024 version has null baseUrl and uses
        // serverName instead, which can be null or malformed. We only enforce presence/non-blankness
        // here and rely on serverNameOrHomeserverUrl()/the Rust builder layer to validate structure.
        val baseUrlOrServerName = qrCodeData.baseUrl() ?: qrCodeData.serverName()

        if (baseUrlOrServerName == null) {
            // With the 2024 version of MSC4108 we treat the absence of serverName as meaning that
            // the other device is not signed in.
            Timber.e("The QR code is from a device that is not yet signed in")
            throw HumanQrLoginException.OtherDeviceNotSignedIn()
        }

        if (baseUrlOrServerName.isBlank()) {
            Timber.e("The QR code contains an empty base URL or server name, which is invalid")
            throw HumanQrLoginException.Unknown()
        }

        return rustMatrixClientFactory
            .getBaseClientBuilder(
                sessionPaths = sessionPaths,
                clientSecret = pendingKey,
                slidingSyncType = ClientBuilderSlidingSync.Discovered,
                isMessageSearchAvailable = isMessageSearchAvailable(),
            )
            .serverNameOrHomeserverUrl(baseUrlOrServerName)
            .build()
    }

    private fun requireCurrentAttempt(): AuthenticationAttempt = currentAttempt
        ?: throw AuthenticationException.InvalidServerName("No account provider has been configured")

    private suspend fun discardCurrentAttempt() {
        val attempt = currentAttempt ?: return
        pendingOAuth?.takeIf { it.attempt === attempt }?.let {
            runCatchingExceptions { it.authorizationData.close() }
            pendingOAuth = null
        }
        // Replacement can race with the instant at which the remote server creates a token. Always
        // attempt remote invalidation before closing/deleting the temporary client; closing first
        // could leave an untracked bearer token alive when the login response arrives concurrently.
        cleanupAuthenticatedAttempt(
            attempt,
            AuthenticationException.InvalidServerName("The authentication attempt was superseded"),
        )
    }

    private fun clearAttempt(
        attempt: AuthenticationAttempt,
        destroyClient: Boolean,
        deleteSessionPaths: Boolean,
    ) {
        if (currentAttempt === attempt) currentAttempt = null
        if (destroyClient) {
            try {
                attempt.client.close()
            } catch (failure: Exception) {
                Timber.e(failure, "Failed to close an authentication client")
            }
        }
        if (deleteSessionPaths) attempt.sessionPaths.deleteRecursively()
    }

    private suspend fun assertAuthenticationAttemptAllowed(attempt: AuthenticationAttempt) {
        if (currentAttempt !== attempt) {
            throw AuthenticationException.InvalidServerName("The authentication attempt was superseded")
        }
        val accountProvider = attempt.accountProvider
            ?: throw AuthenticationException.InvalidServerName("No account provider has been configured")
        assertAccountProviderAllowed(accountProvider)
        val connectionHomeserver = attempt.connectionHomeserver
            ?: throw AuthenticationException.InvalidServerName("No homeserver connection has been configured")
        assertAccountProviderAllowed(connectionHomeserver)
    }

    private suspend fun assertAuthenticationAttemptAllowedAfterAuthentication(attempt: AuthenticationAttempt) {
        abortAuthenticatedClientIfBlocked(attempt) {
            assertAuthenticationAttemptAllowed(attempt)
        }
    }

    private suspend fun assertAuthenticatedSessionAllowed(
        attempt: AuthenticationAttempt,
        homeserverUrl: String,
    ) {
        abortAuthenticatedClientIfBlocked(attempt) {
            assertAuthenticationAttemptAllowed(attempt)
            assertAccountProviderAllowed(homeserverUrl)
        }
    }

    private suspend fun assertAccountProviderAllowed(accountProvider: String) {
        if (!enterpriseService.isAllowedToConnectToHomeserver(accountProvider)) {
            throw AuthenticationException.InvalidServerName("The account provider is blocked by managed configuration")
        }
    }

    private suspend fun assertQrCodeAccountProviderAllowed(qrCodeData: MatrixQrCodeLoginData) {
        // QR login data can carry a server name and a separate connection base URL. A finite
        // homeserver allowlist cannot safely prove that both values resolve to the same controlled
        // deployment before the SDK starts the transfer, so keep the API boundary aligned with the
        // onboarding UI and disable QR login for every locked-down build.
        if (!enterpriseService.canConnectToAnyHomeserver()) {
            throw AuthenticationException.InvalidServerName(
                "QR login is disabled while the homeserver is restricted by policy"
            )
        }
        val accountProvider = qrCodeData.serverName()
        if (accountProvider != null) {
            assertAccountProviderAllowed(accountProvider)
        }
    }

    private suspend fun assertQrCodeAccountProviderAllowedAfterAuthentication(
        qrCodeData: MatrixQrCodeLoginData,
        attempt: AuthenticationAttempt,
    ) {
        abortAuthenticatedClientIfBlocked(attempt) {
            if (currentAttempt !== attempt) {
                throw AuthenticationException.InvalidServerName("The QR authentication attempt was superseded")
            }
            assertQrCodeAccountProviderAllowed(qrCodeData)
        }
    }

    private suspend fun commitAuthenticatedSession(
        attempt: AuthenticationAttempt,
        matrixClient: MatrixClient,
        sessionData: SessionData,
        policyCheck: suspend () -> Unit = { assertAuthenticationAttemptAllowed(attempt) },
    ) {
        // A replacement and a publication transfer ownership of the same temporary client. Keep
        // both operations under one mutex so replacement cannot destroy a client after it has been
        // persisted but before the successful attempt releases its strong reference.
        replaceAttemptMutex.withLock {
            var sessionWasAdded = false
            try {
                sessionSecurityCoordinator.commitAuthentication(attempt.securityToken) {
                    sessionSecurityCoordinator.serializeSessionPublication {
                        if (currentAttempt !== attempt) {
                            throw AuthenticationException.InvalidServerName("The authentication attempt was superseded")
                        }
                        policyCheck()
                        if (sessionStore.numberOfSessions() > 0) {
                            throw AuthenticationException.AccountAlreadyLoggedIn(sessionData.userId)
                        }
                        sessionStore.addSession(sessionData)
                        sessionWasAdded = true
                        newMatrixClientObservers.forEach { it.invoke(matrixClient) }
                        clearAttempt(attempt, destroyClient = false, deleteSessionPaths = false)
                    }
                }
            } catch (failure: Exception) {
                if (sessionWasAdded) {
                    withContext(NonCancellable) {
                        try {
                            sessionStore.removeSession(sessionData.userId)
                        } catch (rollbackFailure: Exception) {
                            failure.addSuppressed(rollbackFailure)
                            Timber.e(rollbackFailure, "Failed to roll back a session after publication failed")
                        }
                    }
                }
                abortAuthenticatedClientIfBlocked(attempt) { throw failure }
            }
        }
    }

    private suspend fun assertNoExistingLocalSession() {
        sessionStore.getAllSessions().firstOrNull()?.let { existingSession ->
            throw AuthenticationException.AccountAlreadyLoggedIn(existingSession.userId)
        }
    }

    private fun <T> disabledAuthenticationMethod(): Result<T> {
        return Result.failure(
            AuthenticationException.Generic("SecureChat supports password authentication only")
        )
    }

    private fun Exception.isDefinitiveMatrixLoginRejection(): Boolean {
        return this is ClientException.MatrixApi || mapClientException() is ClientException.MatrixApi
    }

    private suspend fun isStoredSessionEndpointAllowed(sessionData: SessionData): Boolean {
        val accountProvider = sessionData.accountProvider ?: sessionData.homeserverUrl
        return enterpriseService.isAllowedToConnectToHomeserver(accountProvider) &&
            enterpriseService.isAllowedToConnectToHomeserver(sessionData.homeserverUrl)
    }

    private fun assertStoredSessionBelongsToThisInstallation(
        sessionData: SessionData,
        expectedDeviceId: String,
    ) {
        if (sessionData.loginType != LoginType.PASSWORD) return
        if (sessionData.deviceId != expectedDeviceId) {
            throw AuthenticationException.InvalidServerName(
                "The stored session belongs to a different SecureChat installation and is quarantined"
            )
        }
    }

    private fun assertPasswordSessionBelongsToThisInstallation(
        sessionData: SessionData,
        expectedDeviceId: String,
    ) = assertStoredSessionBelongsToThisInstallation(sessionData, expectedDeviceId)

    private suspend fun resolveExpectedDeviceIdForRestore(sessionData: SessionData): String {
        if (sessionData.loginType == LoginType.PASSWORD) {
            secureChatDeviceIdProvider.seedDeviceIdFromLegacySessionIfNeeded(sessionData.deviceId)
        }
        return secureChatDeviceIdProvider.getOrCreate()
    }

    private suspend fun requireOnlyStoredSession(sessionId: SessionId): SessionData {
        val sessions = sessionStore.getAllSessions()
        if (sessions.size > 1) {
            throw AuthenticationException.AccountAlreadyLoggedIn(sessionId.value)
        }
        return sessions.singleOrNull()
            ?.takeIf { it.userId == sessionId.value }
            ?: throw SessionRestorationException.MissingSession(sessionId)
    }

    private suspend fun <T> finishAuthenticatedAttempt(
        attempt: AuthenticationAttempt,
        block: suspend () -> T,
    ): T {
        return try {
            block()
        } catch (failure: Exception) {
            cleanupAuthenticatedAttempt(attempt, failure)
            throw failure
        }
    }

    private suspend fun cleanupAuthenticatedAttempt(
        attempt: AuthenticationAttempt,
        primaryFailure: Exception,
    ) = withContext(NonCancellable) {
        if (!attempt.authenticatedCleanupStarted.compareAndSet(false, true)) return@withContext
        try {
            attempt.client.logout()
        } catch (logoutFailure: Exception) {
            primaryFailure.addSuppressed(logoutFailure)
            Timber.e(logoutFailure, "Failed to invalidate an uncommitted SecureChat login")
        }
        try {
            attempt.client.close()
        } catch (closeFailure: Exception) {
            primaryFailure.addSuppressed(closeFailure)
            Timber.e(closeFailure, "Failed to close an uncommitted SecureChat login client")
        } finally {
            clearAttempt(attempt, destroyClient = false, deleteSessionPaths = true)
        }
    }

    private suspend fun closeQuarantinedRestoredClient(
        matrixClient: RustMatrixClient,
        primaryFailure: Exception,
    ) = withContext(NonCancellable) {
        try {
            // Close the in-memory SDK client without contacting the blocked endpoint, deleting its
            // encrypted local files, or discarding the token required for administrator revocation.
            matrixClient.destroy()
        } catch (closeFailure: Exception) {
            primaryFailure.addSuppressed(closeFailure)
            Timber.e(closeFailure, "Failed to close a quarantined restored client")
        }
    }

    private suspend fun revokeLegacyStoredSession(matrixClient: RustMatrixClient) {
        try {
            // This path is reached only after both the account provider and the actual endpoint
            // pass the allowlist. Local state is removed only when remote token revocation succeeds.
            matrixClient.logout(userInitiated = true, ignoreSdkError = false)
        } catch (failure: Exception) {
            closeQuarantinedRestoredClient(matrixClient, failure)
            throw failure
        }
    }

    /**
     * Authentication can complete while device policy changes in another process callback. Fail
     * closed before observers see the client or the session is persisted, and discard the
     * authenticated temporary client so it cannot be retried under the new policy.
     */
    private suspend fun abortAuthenticatedClientIfBlocked(
        attempt: AuthenticationAttempt,
        policyCheck: suspend () -> Unit,
    ) {
        try {
            policyCheck()
        } catch (failure: Exception) {
            // Cancellation must not interrupt token invalidation/local cleanup at this security
            // boundary. Always preserve and rethrow the original policy/epoch failure.
            cleanupAuthenticatedAttempt(attempt, failure)
            throw failure
        }
    }

    private suspend fun isMessageSearchAvailable(): Boolean =
        featureFlagService.isFeatureEnabled(FeatureFlags.MessageSearch)

    private suspend fun MatrixClient.waitForKnownVerificationState() {
        withTimeoutOrNull(10.seconds) {
            Timber.d("Waiting for a known verification status...")
            val status = sessionVerificationService.sessionVerifiedStatus.first { it != SessionVerifiedStatus.Unknown }
            Timber.d("Finished waiting for a known verification status: $status")
        } ?: Timber.w("Timed out waiting for a known verification status")
    }
}
