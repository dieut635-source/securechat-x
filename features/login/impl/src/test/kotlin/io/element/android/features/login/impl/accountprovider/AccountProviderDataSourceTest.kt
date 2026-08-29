/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.accountprovider

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.AuthenticationConfig
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.enterprise.test.FakeEnterpriseService
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.test.FakeMdmService
import io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore
import io.element.android.tests.testutils.WarmUpRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AccountProviderDataSourceTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val sut = anAccountProviderDataSource()
        sut.flow.test {
            val initialState = awaitItem()
            assertThat(initialState).isEqualTo(
                AccountProvider(
                    url = AuthenticationConfig.DEFAULT_HOMESERVER_URL,
                    title = "chat.securechat.com.au",
                    subtitle = null,
                    isPublic = false,
                    isMatrixOrg = false,
                )
            )
        }
    }

    @Test
    fun `present - a configured provider has no special public branding`() = runTest {
        val sut = anAccountProviderDataSource(
            enterpriseService = FakeEnterpriseService(
                defaultHomeserverListResult = { listOf("https://example.com") }
            ),
        )
        sut.flow.test {
            val initialState = awaitItem()
            assertThat(initialState).isEqualTo(
                AccountProvider(
                    url = "https://example.com",
                    title = "example.com",
                    subtitle = null,
                    isPublic = false,
                    isMatrixOrg = false,
                )
            )
        }
    }

    @Test
    fun `present - ensure that default homeserver is not star char`() = runTest {
        val sut = anAccountProviderDataSource(
            enterpriseService = FakeEnterpriseService(
                defaultHomeserverListResult = { listOf(EnterpriseService.ANY_ACCOUNT_PROVIDER, AuthenticationConfig.DEFAULT_HOMESERVER_URL) }
            ),
        )
        sut.flow.test {
            val initialState = awaitItem()
            assertThat(initialState).isEqualTo(
                AccountProvider(
                    url = AuthenticationConfig.DEFAULT_HOMESERVER_URL,
                    title = "chat.securechat.com.au",
                    subtitle = null,
                    isPublic = false,
                    isMatrixOrg = false,
                )
            )
        }
    }

    @Test
    fun `present - user change and reset`() = runTest {
        val sut = anAccountProviderDataSource()
        sut.flow.test {
            val initialState = awaitItem()
            assertThat(initialState.url).isEqualTo(AuthenticationConfig.DEFAULT_HOMESERVER_URL)
            sut.setAccountProvider(AccountProvider(url = "https://example.com"))
            val changedState = awaitItem()
            assertThat(changedState).isEqualTo(
                AccountProvider(
                    url = "https://example.com",
                    title = "example.com",
                    subtitle = null,
                    isPublic = false,
                    isMatrixOrg = false,
                )
            )
            sut.reset()
            val resetState = awaitItem()
            assertThat(resetState.url).isEqualTo(AuthenticationConfig.DEFAULT_HOMESERVER_URL)
        }
    }

    @Test
    fun `present - set url and reset`() = runTest {
        val sut = anAccountProviderDataSource()
        sut.flow.test {
            val initialState = awaitItem()
            assertThat(initialState.url).isEqualTo(AuthenticationConfig.DEFAULT_HOMESERVER_URL)
            sut.setUrl(url = "https://example.com")
            val changedState = awaitItem()
            assertThat(changedState).isEqualTo(
                AccountProvider(
                    url = "https://example.com",
                    title = "example.com",
                    subtitle = null,
                    isPublic = false,
                    isMatrixOrg = false,
                )
            )
            sut.reset()
            val resetState = awaitItem()
            assertThat(resetState.url).isEqualTo(AuthenticationConfig.DEFAULT_HOMESERVER_URL)
        }
    }

    @Test
    fun `present - defaults to the most recently used provider from history`() = runTest {
        val sut = anAccountProviderDataSource(
            appPreferencesStore = InMemoryAppPreferencesStore(
                homeserverHistory = listOf("https://example.com", "https://other.example.com"),
            ),
        )
        sut.flow.test {
            assertThat(awaitItem().url).isEqualTo("https://example.com")
        }
    }

    @Test
    fun `present - history is ignored when the account provider is enforced`() = runTest {
        val sut = anAccountProviderDataSource(
            enterpriseService = FakeEnterpriseService(
                defaultHomeserverListResult = { listOf("https://enforced.org") }
            ),
            appPreferencesStore = InMemoryAppPreferencesStore(
                homeserverHistory = listOf("https://example.com"),
            ),
        )
        sut.flow.test {
            assertThat(awaitItem().url).isEqualTo("https://enforced.org")
        }
    }

    @Test
    fun `present - reset returns to the most recently used provider from history`() = runTest {
        val sut = anAccountProviderDataSource(
            appPreferencesStore = InMemoryAppPreferencesStore(
                homeserverHistory = listOf("https://example.com"),
            ),
        )
        sut.flow.test {
            assertThat(awaitItem().url).isEqualTo("https://example.com")
            sut.setUrl("https://other.com")
            assertThat(awaitItem().url).isEqualTo("https://other.com")
            sut.reset()
            assertThat(awaitItem().url).isEqualTo("https://example.com")
        }
    }

    @Test
    fun `managed homeserver updates replace a provider captured at startup`() = runTest {
        var configuredHomeserver = "https://one.example.com"
        val mdmService = FakeMdmService(MdmConfig.default.copy(homeserverUrl = configuredHomeserver))
        val sut = anAccountProviderDataSource(
            enterpriseService = FakeEnterpriseService(
                defaultHomeserverListResult = { listOf(configuredHomeserver) },
            ),
            mdmService = mdmService,
            coroutineScope = backgroundScope,
        )

        sut.flow.test {
            assertThat(awaitItem().url).isEqualTo("https://one.example.com")

            configuredHomeserver = "https://two.example.com"
            mdmService.emit(MdmConfig.default.copy(homeserverUrl = configuredHomeserver))

            assertThat(awaitItem().url).isEqualTo("https://two.example.com")
        }
    }
}
