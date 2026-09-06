/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.zacsweers.metro.Inject
import io.element.android.features.login.impl.accesscontrol.DefaultAccountProviderAccessControl
import io.element.android.features.login.impl.accountprovider.AccountProviderDataSource
import io.element.android.features.login.impl.accountprovider.SaveAccountProviderToHistory
import io.element.android.features.login.impl.error.ChangeServerError
import io.element.android.features.login.impl.localnetwork.LocalNetworkPermissionGate
import io.element.android.features.login.impl.screens.chooseaccountprovider.ChooseAccountProviderPresenter
import io.element.android.features.login.impl.screens.classic.loginwithclassic.LoginWithClassicPresenter
import io.element.android.features.login.impl.screens.confirmaccountprovider.ConfirmAccountProviderPresenter
import io.element.android.features.login.impl.screens.createaccount.AccountCreationNotSupported
import io.element.android.features.login.impl.screens.onboarding.OnBoardingPresenter
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.auth.MatrixAuthenticationService
import io.element.android.libraries.matrix.api.auth.OAuthPrompt
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.oauth.api.OAuthAction
import io.element.android.libraries.oauth.api.OAuthActionFlow

/**
 * Presenter responsible for managing the login flow, including handling OAuth actions and
 * submitting login requests.
 * It's a helper to avoid code duplication. It is used by [OnBoardingPresenter], [ConfirmAccountProviderPresenter],
 * [ChooseAccountProviderPresenter] and [LoginWithClassicPresenter].
 */
@Inject
class LoginModePresenter(
    private val oAuthActionFlow: OAuthActionFlow,
    private val authenticationService: MatrixAuthenticationService,
    private val localNetworkPermissionGate: LocalNetworkPermissionGate,
    private val saveAccountProviderToHistory: SaveAccountProviderToHistory,
    private val mdmService: MdmService,
    private val accountProviderDataSource: AccountProviderDataSource,
    private val accountProviderAccessControl: DefaultAccountProviderAccessControl,
) : Presenter<LoginModeState> {
    private var pendingOAuthRequest: LoginModeEvent.Submit? = null

    @Composable
    override fun present(): LoginModeState {
        val loginMode: MutableState<AsyncData<LoginMode>> = remember { mutableStateOf(AsyncData.Uninitialized) }

        val gateState = localNetworkPermissionGate.present<LoginModeEvent.Submit>(
            urlOf = { it.homeserverUrl },
            onProceed = { request -> performSubmit(request, loginMode) },
        )

        // Forward OAuth navigation events to the presenter's own loginMode state.
        LaunchedEffect(Unit) {
            oAuthActionFlow.collect { action ->
                if (action != null) handleOAuthAction(action, loginMode)
            }
        }

        fun handleEvent(event: LoginModeEvent) {
            when (event) {
                LoginModeEvent.ClearError -> loginMode.value = AsyncData.Uninitialized
                is LoginModeEvent.Submit -> gateState.submit(event)
                LoginModeEvent.DismissLocalNetworkPermission -> gateState.abort()
                LoginModeEvent.RequestLocalNetworkPermission -> gateState.requestPermission()
            }
        }
        return LoginModeState(
            loginMode = loginMode.value,
            localNetworkPermissionDialog = gateState.dialog,
            eventSink = ::handleEvent,
        )
    }

    private suspend fun performSubmit(request: LoginModeEvent.Submit, loginMode: MutableState<AsyncData<LoginMode>>) {
        suspend {
            assertRequestAllowed(request)
            // This is the stable policy identity for the configured authentication client. The
            // visible provider flow can move to a newly-pushed MDM value while this attempt is open.
            accountProviderDataSource.setUrl(request.homeserverUrl)
            // Reuse the details the caller already resolved when it configured the homeserver, so we don't
            // run setHomeserver (a network round-trip) again. Otherwise configure the homeserver ourselves.
            val matrixHomeServerDetails = request.preConfiguredDetails
                ?: authenticationService.setHomeserver(request.homeserverUrl).recoverCatching {
                    // Fallback to the well-known-resolved URL if the primary URL failed and the caller supplied one.
                    if (request.resolvedHomeserverUrl != null && request.resolvedHomeserverUrl != request.homeserverUrl) {
                        authenticationService.setHomeserver(
                            homeserver = request.resolvedHomeserverUrl,
                            accountProvider = request.homeserverUrl,
                        ).getOrThrow()
                    } else {
                        throw it
                    }
                }.getOrThrow()
            // Network discovery and preconfigured details can both outlive a managed-policy update.
            assertRequestAllowed(request)
            when {
                matrixHomeServerDetails.supportsOAuthLogin -> {
                    val oAuthPrompt = if (request.isAccountCreation) OAuthPrompt.Create else OAuthPrompt.Login
                    val oAuthDetails = authenticationService.getOAuthUrl(prompt = oAuthPrompt, loginHint = request.loginHint).getOrThrow()
                    try {
                        // OAuth metadata lookup can outlive a registration or homeserver policy
                        // update. Do not open a browser for a flow that has just been disabled.
                        assertRequestAllowed(request)
                    } catch (failure: Exception) {
                        authenticationService.cancelOAuthLogin()
                        throw failure
                    }
                    pendingOAuthRequest = request
                    LoginMode.OAuth(oAuthDetails)
                }
                request.isAccountCreation -> throw AccountCreationNotSupported()
                matrixHomeServerDetails.supportsPasswordLogin -> {
                    pendingOAuthRequest = null
                    LoginMode.PasswordLogin
                }
                else -> error("Unsupported authentication flow")
            }
        }.runCatchingUpdatingState(
            state = loginMode,
            errorTransform = {
                when (it) {
                    is AccountCreationNotSupported -> it
                    else -> ChangeServerError.from(it)
                }
            }
        )
    }

    private suspend fun handleOAuthAction(action: OAuthAction, loginMode: MutableState<AsyncData<LoginMode>>) {
        if (action is OAuthAction.GoBack && action.toUnblock && loginMode.value !is AsyncData.Loading) {
            // Ignore GoBack that isn't tied to an in-flight login — it comes from LoginFlowNode
            // when the user backs out after a completed login attempt.
            return
        }
        loginMode.value = AsyncData.Loading()
        when (action) {
            is OAuthAction.GoBack -> authenticationService.cancelOAuthLogin()
                .onSuccess {
                    pendingOAuthRequest = null
                    loginMode.value = AsyncData.Uninitialized
                }
                .onFailure { loginMode.value = AsyncData.Failure(it) }
            is OAuthAction.Success -> {
                val request = pendingOAuthRequest
                val policyFailure = runCatchingExceptions {
                    checkNotNull(request) { "No OAuth authentication attempt is pending" }
                    assertRequestAllowed(request)
                }.exceptionOrNull()
                if (policyFailure != null) {
                    authenticationService.cancelOAuthLogin()
                    loginMode.value = AsyncData.Failure(ChangeServerError.from(policyFailure))
                } else {
                    authenticationService.loginWithOAuth(action.url)
                        .onSuccess { saveAccountProviderToHistory() }
                        .onFailure { loginMode.value = AsyncData.Failure(it) }
                }
                pendingOAuthRequest = null
            }
        }
        oAuthActionFlow.reset()
    }

    private suspend fun assertRequestAllowed(request: LoginModeEvent.Submit) {
        accountProviderAccessControl.assertIsAllowedToConnectToAccountProvider(
            title = request.homeserverUrl,
            accountProviderUrl = request.homeserverUrl,
        )
        if (request.isAccountCreation && !mdmService.config.value.allowRegistration) {
            throw AccountCreationNotSupported()
        }
    }
}
