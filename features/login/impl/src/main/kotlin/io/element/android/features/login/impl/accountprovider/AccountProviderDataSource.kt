/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.accountprovider

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.appconfig.AuthenticationConfig
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.enterprise.api.canConnectToAnyHomeserver
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@SingleIn(AppScope::class)
@Inject
class AccountProviderDataSource(
    private val enterpriseService: EnterpriseService,
    private val mdmService: MdmService,
    private val appPreferencesStore: AppPreferencesStore,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
) {
    // The provider used when the user has not selected one: an enterprise/MDM-configured provider,
    // else the SecureChat homeserver. The most recently used provider (from history) can override it, see init.
    private val accountProvider: MutableStateFlow<AccountProvider> = MutableStateFlow(configuredAccountProvider())

    val flow: StateFlow<AccountProvider> = accountProvider.asStateFlow()

    // The account provider the user last explicitly selected (via [setAccountProvider] / [setUrl]).
    // Unlike [flow], this is not recomputed by [reset], so it survives to be persisted to history on a
    // successful sign-in even when the login flow is torn down in between (e.g. across an OAuth round-trip).
    var lastSelectedAccountProviderUrl: String? = null
        private set

    init {
        // Seed the default from the last used provider, unless the user has already selected one.
        coroutineScope.launch {
            val configuredAccountProvider = configuredAccountProvider()
            val default = defaultAccountProvider()
            if (accountProvider.value == configuredAccountProvider) {
                accountProvider.value = default
            }
        }
        // Restrictions can be updated while the login screen is open. Re-read the enterprise service
        // after every MDM update instead of retaining the homeserver captured in this singleton's constructor.
        coroutineScope.launch {
            mdmService.config.collectLatest {
                if (!enterpriseService.canConnectToAnyHomeserver()) {
                    accountProvider.emit(configuredAccountProvider())
                }
            }
        }
    }

    suspend fun reset() {
        accountProvider.emit(defaultAccountProvider())
    }

    /**
     * The provider to default to: the most recently used one from history when the user is free to
     * connect to any provider, otherwise the enterprise/MDM-configured provider.
     */
    private suspend fun defaultAccountProvider(): AccountProvider {
        if (!enterpriseService.canConnectToAnyHomeserver()) {
            return configuredAccountProvider()
        }
        val lastUsedProvider = appPreferencesStore.getHomeserverHistoryFlow().first().firstOrNull()
        return lastUsedProvider?.let { createAccountProvider(it) } ?: configuredAccountProvider()
    }

    private fun configuredAccountProvider(): AccountProvider = createAccountProvider(
        url = enterpriseService.homeserverAllowList()
            .firstOrNull { it != EnterpriseService.ANY_ACCOUNT_PROVIDER }
            ?: AuthenticationConfig.DEFAULT_HOMESERVER_URL
    )

    suspend fun setUrl(url: String) {
        setAccountProvider(createAccountProvider(url))
    }

    suspend fun setAccountProvider(data: AccountProvider) {
        lastSelectedAccountProviderUrl = data.url
        accountProvider.emit(data)
    }

    private fun createAccountProvider(url: String): AccountProvider {
        return AccountProvider(
            url = url,
            subtitle = null,
            isPublic = false,
            isMatrixOrg = false,
        )
    }
}
