/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.enterprise.impl

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.enterprise.api.BugReportUrl
import io.element.android.libraries.matrix.test.A_HOMESERVER_URL
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.test.FakeMdmService
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultEnterpriseServiceTest {
    @Test
    fun `homeserverAllowList is the homeserver from the managed configuration`() {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        assertThat(defaultEnterpriseService.homeserverAllowList())
            .containsExactly(MdmConfig.DEFAULT_HOMESERVER_URL)
    }

    @Test
    fun `an administrator can point the app at another homeserver`() {
        val service = DefaultEnterpriseService(FakeMdmService(MdmConfig.default.copy(homeserverUrl = "https://matrix.example.com")))
        assertThat(service.homeserverAllowList()).containsExactly("https://matrix.example.com")
    }

    @Test
    fun `isAllowedToConnectToHomeserver only accepts the configured homeserver`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        assertThat(defaultEnterpriseService.isAllowedToConnectToHomeserver(A_HOMESERVER_URL)).isFalse()
        assertThat(defaultEnterpriseService.isAllowedToConnectToHomeserver(MdmConfig.DEFAULT_HOMESERVER_URL)).isTrue()
    }

    @Test
    fun `comparing homeservers ignores case and a trailing slash`() = runTest {
        val service = DefaultEnterpriseService(FakeMdmService())
        assertThat(service.isAllowedToConnectToHomeserver("https://chat.securechat.com.au/")).isTrue()
        assertThat(service.isAllowedToConnectToHomeserver("HTTPS://CHAT.SECURECHAT.COM.AU")).isTrue()
    }

    @Test
    fun `isEnterpriseUser always return false`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        assertThat(defaultEnterpriseService.isEnterpriseUser(A_SESSION_ID)).isFalse()
    }

    @Test
    fun `semanticColorsFlow always emits the SecureChat palette`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        defaultEnterpriseService.semanticColorsFlow(null).test {
            val initialState = awaitItem()
            assertThat(initialState).isEqualTo(SecureChatColors.semanticColors)
            awaitComplete()
        }
    }

    @Test
    fun `brandColorsFlow always emits the SecureChat brand colour`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        defaultEnterpriseService.brandColorsFlow(null).test {
            val initialState = awaitItem()
            assertThat(initialState).isEqualTo(SecureChatColors.brand)
            awaitComplete()
        }
    }

    @Test
    fun `semanticColorsFlow always emits the SecureChat palette for a session`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        defaultEnterpriseService.semanticColorsFlow(A_SESSION_ID).test {
            val initialState = awaitItem()
            assertThat(initialState).isEqualTo(SecureChatColors.semanticColors)
            awaitComplete()
        }
    }

    @Test
    fun `overrideBrandColor has no effect`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        defaultEnterpriseService.overrideBrandColor(A_SESSION_ID, "aColor")
    }

    @Test
    fun `firebasePushGateway returns null`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        assertThat(defaultEnterpriseService.firebasePushGateway()).isNull()
    }

    @Test
    fun `unifiedPushDefaultPushGateway returns null`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        assertThat(defaultEnterpriseService.unifiedPushDefaultPushGateway()).isNull()
    }

    @Test
    fun `bugReportUrlFlow only emits Disabled`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        defaultEnterpriseService.bugReportUrlFlow(A_SESSION_ID).test {
            assertThat(awaitItem()).isEqualTo(BugReportUrl.Disabled)
            awaitComplete()
        }
    }

    @Test
    fun `getNoisyNotificationChannelId returns null`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService(FakeMdmService())
        assertThat(defaultEnterpriseService.getNoisyNotificationChannelId(A_SESSION_ID)).isNull()
    }
}
