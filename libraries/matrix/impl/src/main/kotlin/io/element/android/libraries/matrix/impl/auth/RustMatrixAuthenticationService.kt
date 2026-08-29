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
import io.element.android.libraries.matrix.api.paths.SessionPaths
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.libraries.matrix.impl.ClientBuilderSlidingSync
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
import kotlin.time.Duration.Companion.seconds

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
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
) : MatrixAuthenticationService {
    // Any existing Element Classic session that we want to try to import secrets from during login.
    private var elementClassicSession: ElementClassicSession? = null

    // Passphrase which will be used for new sessions. Existing sessions will use the passphrase
    // stored in the SessionData.
    private val pendingKey by lazy { getDatabaseKey() }

    @Volatile
    private var currentAttempt: AuthenticationAttempt? = null
    private val replaceAttemptMutex = Mutex()

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
        val securityToken: Long,
    )

    private data class PendingOAuth(
        val authorizationData: OAuthAuthorizationData,
        val attempt: AuthenticationAttempt,
        val prompt: OAuthPrompt,
    )

    override suspend fun restoreSession(sessionId: SessionId): Result<MatrixClient> = withContext(coroutineDispatchers.io) {
        runCatchingExceptions {
            val sessionData = sessionStore.getSession(sessionId.value)
            if (sessionData != null) {
                if (sessionData.isTokenValid) {
                    if (!isStoredSessionAllowed(sessionData)) {
                        // A managed homeserver change is a security boundary. Forget the token
                        // before returning a failure so startup and background workers cannot keep
                        // retrying a session that is no longer permitted.
                        discardBlockedStoredSession(sessionData)
                        throw AuthenticationException.InvalidServerName(
                            "The stored session is blocked by managed configuration"
                        )
                    }
                    // Use the sessionData.passphrase, which can be null for a previously created session
                    if (sessionData.passphrase == null) {
                        Timber.w("Restoring a session without a passphrase")
                    } else {
                        Timber.w("Restoring a session with a passphrase")
                    }
                    val matrixClient = rustMatrixClientFactory.create(sessionData)
                    if (!isStoredSessionAllowed(sessionData)) {
                        // Client creation restores the SDK session and can suspend on disk and SDK
                        // work. Re-check after it completes so a policy update in that window cannot
                        // publish a client for a provider that is no longer allowed.
                        discardBlockedStoredSession(sessionData, matrixClient)
                        throw AuthenticationException.InvalidServerName(
                            "The stored session is blocked by managed configuration"
                        )
                    }
                    matrixClient
                } else {
                    throw SessionRestorationException.InvalidToken()
                }
            } else {
                throw SessionRestorationException.MissingSession(sessionId)
            }
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
            val securityToken = sessionSecurityCoordinator.beginAuthentication()
            replaceAttemptMutex.withLock {
                discardCurrentAttempt()
                val emptySessionPath = sessionPathsFactory.create()
                var attempt: AuthenticationAttempt? = null
                runCatchingExceptions {
                    assertAccountProviderAllowed(accountProvider)
                    val client = makeClient(sessionPaths = emptySessionPath) {
                        serverNameOrHomeserverUrl(homeserver)
                    }
                    attempt = AuthenticationAttempt(
                        client = client,
                        sessionPaths = emptySessionPath,
                        accountProvider = accountProvider,
                        securityToken = securityToken,
                    ).also { currentAttempt = it }

                    client.homeserverLoginDetails().map().also {
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
            }
        }

    override suspend fun login(username: String, password: String): Result<SessionId> =
        withContext(coroutineDispatchers.io) {
            runCatchingExceptions {
                val attempt = requireCurrentAttempt()
                assertAuthenticationAttemptAllowed(attempt)
                val client = attempt.client
                client.login(
                    username = username,
                    password = password,
                    initialDeviceName = "SecureChat Android",
                    deviceId = null,
                )
                assertAuthenticationAttemptAllowedAfterAuthentication(attempt)
                // Ensure that the user is not already logged in with the same account
                ensureNotAlreadyLoggedIn(client)
                tryToImportSecretForElementClassicSession(client)
                val sessionData = client.session()
                    .toSessionData(
                        isTokenValid = true,
                        loginType = LoginType.PASSWORD,
                        passphrase = pendingKey.formattedAsString(),
                        sessionPaths = attempt.sessionPaths,
                        accountProvider = attempt.accountProvider,
                    )
                val matrixClient = rustMatrixClientFactory.create(client, sessionData, isMessageSearchAvailable())

                // Apply enterprise hooks to the newly created client as soon as possible
                clientEnterpriseHook(matrixClient)

                assertAuthenticationAttemptAllowedAfterAuthentication(attempt)
                commitAuthenticatedSession(attempt, matrixClient, sessionData)

                SessionId(sessionData.userId)
            }.mapFailure { failure ->
                Timber.e(failure, "Failed to login")
                failure.mapAuthenticationException()
            }
        }

    private suspend fun tryToImportSecretForElementClassicSession(client: Client) {
        elementClassicSession
            ?.takeIf {
                // Note: the SDK will also do this check
                it.userId.value == client.userId()
            }
            ?.let {
                val secrets = it.secrets
                val roomKeysVersion = it.roomKeysVersion
                if (secrets == null || roomKeysVersion == null) {
                    Timber.d("No secrets or roomKeysVersion found for previous Matrix app session ${it.userId}; skipping import")
                } else {
                    Timber.d("Trying to import secrets for previous Matrix app session ${it.userId}")
                    runCatchingExceptions {
                        SecretsBundleWithUserId.fromStr(
                            userId = it.userId.value,
                            bundle = secrets,
                            backupInfo = roomKeysVersion,
                        ).use { secretsBundle ->
                            client.encryption().importSecretsBundle(secretsBundle)
                        }
                    }.onFailure { failure ->
                        Timber.e(failure, "Failed to import secrets for previous Matrix app session ${it.userId}")
                    }
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

    override fun setElementClassicSession(session: ElementClassicSession?) {
        elementClassicSession = session
    }

    /**
     * callbackUrl should be the `url` from `OAuthAction` (with all the parameters).
     */
    override suspend fun loginWithOAuth(callbackUrl: String): Result<SessionId> {
        return withContext(coroutineDispatchers.io) {
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
                tryToImportSecretForElementClassicSession(client)
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
        val accountAlreadyLoggedIn = sessionStore.getAllSessions().any {
            it.userId == newUserId
        }
        if (accountAlreadyLoggedIn) {
            // Sign out the client, ignoring any error
            runCatchingExceptions {
                client.logout()
            }
            throw AuthenticationException.AccountAlreadyLoggedIn(newUserId)
        }
    }

    override suspend fun loginWithQrCode(qrCodeData: MatrixQrCodeLoginData, progress: (QrCodeLoginStep) -> Unit) =
        withContext(coroutineDispatchers.io) {
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

    private fun discardCurrentAttempt() {
        val attempt = currentAttempt ?: return
        pendingOAuth?.takeIf { it.attempt === attempt }?.let {
            runCatching { it.authorizationData.close() }
            pendingOAuth = null
        }
        clearAttempt(attempt, destroyClient = true, deleteSessionPaths = true)
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
    }

    private suspend fun assertAuthenticationAttemptAllowedAfterAuthentication(attempt: AuthenticationAttempt) {
        abortAuthenticatedClientIfBlocked(attempt) {
            assertAuthenticationAttemptAllowed(attempt)
        }
    }

    private suspend fun assertAccountProviderAllowed(accountProvider: String) {
        if (!enterpriseService.isAllowedToConnectToHomeserver(accountProvider)) {
            throw AuthenticationException.InvalidServerName("The account provider is blocked by managed configuration")
        }
    }

    private suspend fun assertQrCodeAccountProviderAllowed(qrCodeData: MatrixQrCodeLoginData) {
        val accountProvider = qrCodeData.serverName()
        if (accountProvider == null) {
            if (!enterpriseService.canConnectToAnyHomeserver()) {
                throw AuthenticationException.InvalidServerName(
                    "The QR code does not identify an account provider allowed by managed configuration"
                )
            }
        } else {
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
                    if (currentAttempt !== attempt) {
                        throw AuthenticationException.InvalidServerName("The authentication attempt was superseded")
                    }
                    policyCheck()
                    sessionStore.addSession(sessionData)
                    sessionWasAdded = true
                    newMatrixClientObservers.forEach { it.invoke(matrixClient) }
                    clearAttempt(attempt, destroyClient = false, deleteSessionPaths = false)
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

    private suspend fun isStoredSessionAllowed(sessionData: SessionData): Boolean {
        val accountProvider = sessionData.accountProvider ?: sessionData.homeserverUrl
        return enterpriseService.isAllowedToConnectToHomeserver(accountProvider)
    }

    private suspend fun discardBlockedStoredSession(
        sessionData: SessionData,
        matrixClient: MatrixClient? = null,
    ) = withContext(NonCancellable) {
        if (matrixClient != null) {
            try {
                // Ignore any remote logout error: local client shutdown and session-directory
                // deletion remain mandatory after policy rejects the restored provider.
                matrixClient.logout(userInitiated = false, ignoreSdkError = true)
            } catch (failure: Exception) {
                Timber.e(failure, "Failed to close a restored client rejected by managed policy")
            }
        }
        try {
            sessionStore.removeSession(sessionData.userId)
        } catch (failure: Exception) {
            Timber.e(failure, "Failed to remove a stored session rejected by managed policy")
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
            withContext(NonCancellable) {
                try {
                    attempt.client.logout()
                } catch (logoutFailure: Exception) {
                    Timber.e(logoutFailure, "Failed to log out an authentication client rejected by managed policy")
                }
                try {
                    attempt.client.close()
                } catch (closeFailure: Exception) {
                    Timber.e(closeFailure, "Failed to close an authentication client rejected by managed policy")
                } finally {
                    clearAttempt(attempt, destroyClient = false, deleteSessionPaths = true)
                }
            }
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
