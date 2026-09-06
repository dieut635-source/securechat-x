/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.login

import com.google.common.truth.Truth.assertThat
import io.element.android.features.enterprise.test.FakeEnterpriseService
import io.element.android.features.login.impl.accesscontrol.DefaultAccountProviderAccessControl
import io.element.android.features.login.impl.accountprovider.AccountProviderDataSource
import io.element.android.features.login.impl.accountprovider.anAccountProviderDataSource
import io.element.android.features.login.impl.error.ChangeServerError
import io.element.android.features.login.impl.screens.createaccount.AccountCreationNotSupported
import io.element.android.features.login.impl.screens.onboarding.createLoginModePresenter
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.test.auth.FakeMatrixAuthenticationService
import io.element.android.libraries.matrix.test.auth.aMatrixHomeServerDetails
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.test.FakeMdmService
import io.element.android.libraries.oauth.api.OAuthAction
import io.element.android.libraries.oauth.test.FakeOAuthActionFlow
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LoginModePresenterManagedPolicyTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `submit rejects preconfigured details after the managed homeserver changes`() = runTest {
        val policy = policyFixture()
        val presenter = createLoginModePresenter(
            accountProviderDataSource = policy.accountProviderDataSource,
            mdmService = policy.mdmService,
            accountProviderAccessControl = policy.accessControl,
        )

        presenter.test {
            val initialState = awaitItem()
            policy.replaceHomeserver()
            initialState.eventSink(
                LoginModeEvent.Submit(
                    isAccountCreation = false,
                    homeserverUrl = ORIGINAL_HOMESERVER,
                    resolvedHomeserverUrl = null,
                    loginHint = null,
                    preConfiguredDetails = aMatrixHomeServerDetails(supportsPasswordLogin = true),
                )
            )

            assertThat(awaitItem().loginMode).isInstanceOf(AsyncData.Loading::class.java)
            val failure = awaitItem().loginMode as AsyncData.Failure<*>
            assertThat(failure.error).isInstanceOf(ChangeServerError.UnauthorizedAccountProvider::class.java)
        }
    }

    @Test
    fun `oauth callback rejects an attempt whose managed homeserver changed while the browser was open`() = runTest {
        val policy = policyFixture()
        val oAuthActionFlow = FakeOAuthActionFlow()
        val presenter = createLoginModePresenter(
            oAuthActionFlow = oAuthActionFlow,
            authenticationService = FakeMatrixAuthenticationService(
                setHomeserverResult = {
                    Result.success(aMatrixHomeServerDetails(supportsOAuthLogin = true))
                },
            ),
            accountProviderDataSource = policy.accountProviderDataSource,
            mdmService = policy.mdmService,
            accountProviderAccessControl = policy.accessControl,
        )

        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(
                LoginModeEvent.Submit(
                    isAccountCreation = false,
                    homeserverUrl = ORIGINAL_HOMESERVER,
                    resolvedHomeserverUrl = null,
                    loginHint = null,
                )
            )
            assertThat(awaitItem().loginMode).isInstanceOf(AsyncData.Loading::class.java)
            assertThat(awaitItem().loginMode.dataOrNull()).isInstanceOf(LoginMode.OAuth::class.java)

            policy.replaceHomeserver()
            oAuthActionFlow.post(OAuthAction.Success("securechat://oauth/callback"))

            assertThat(awaitItem().loginMode).isInstanceOf(AsyncData.Loading::class.java)
            val failure = awaitItem().loginMode as AsyncData.Failure<*>
            assertThat(failure.error).isInstanceOf(ChangeServerError.UnauthorizedAccountProvider::class.java)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `account creation rejects a registration policy change during OAuth setup`() = runTest {
        val policy = policyFixture(allowRegistration = true)
        val presenter = createLoginModePresenter(
            authenticationService = FakeMatrixAuthenticationService(
                setHomeserverResult = {
                    Result.success(aMatrixHomeServerDetails(supportsOAuthLogin = true))
                },
            ),
            accountProviderDataSource = policy.accountProviderDataSource,
            mdmService = policy.mdmService,
            accountProviderAccessControl = policy.accessControl,
        )

        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(
                LoginModeEvent.Submit(
                    isAccountCreation = true,
                    homeserverUrl = ORIGINAL_HOMESERVER,
                    resolvedHomeserverUrl = null,
                    loginHint = null,
                )
            )
            assertThat(awaitItem().loginMode).isInstanceOf(AsyncData.Loading::class.java)
            // setHomeserver() has completed and getOAuthUrl() is now suspended in its simulated
            // metadata lookup, so this exercises the post-OAuth registration-policy check.
            advanceTimeBy(1)
            runCurrent()
            policy.disableRegistration()

            val failure = awaitItem().loginMode as AsyncData.Failure<*>
            assertThat(failure.error).isInstanceOf(AccountCreationNotSupported::class.java)
        }
    }

    private fun policyFixture(allowRegistration: Boolean = false): PolicyFixture {
        val mdmService = FakeMdmService(
            MdmConfig.default.copy(
                homeserverUrl = ORIGINAL_HOMESERVER,
                allowRegistration = allowRegistration,
            )
        )
        val enterpriseService = FakeEnterpriseService(
            defaultHomeserverListResult = { listOf(mdmService.config.value.homeserverUrl) },
            isAllowedToConnectToHomeserverResult = { it == mdmService.config.value.homeserverUrl },
            isElementProEnforcedResult = { false },
        )
        return PolicyFixture(
            mdmService = mdmService,
            accountProviderDataSource = anAccountProviderDataSource(
                enterpriseService = enterpriseService,
                mdmService = mdmService,
            ),
            accessControl = DefaultAccountProviderAccessControl(
                isEnterpriseBuild = { false },
                enterpriseService = enterpriseService,
            ),
        )
    }

    private data class PolicyFixture(
        val mdmService: FakeMdmService,
        val accountProviderDataSource: AccountProviderDataSource,
        val accessControl: DefaultAccountProviderAccessControl,
    ) {
        fun replaceHomeserver() {
            mdmService.emit(MdmConfig.default.copy(homeserverUrl = REPLACEMENT_HOMESERVER))
        }

        fun disableRegistration() {
            mdmService.emit(mdmService.config.value.copy(allowRegistration = false))
        }
    }

    private companion object {
        const val ORIGINAL_HOMESERVER = "https://one.example.com"
        const val REPLACEMENT_HOMESERVER = "https://two.example.com"
    }
}
