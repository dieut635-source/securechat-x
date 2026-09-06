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
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultEnterpriseServiceTest {
    @Test
    fun `homeserverAllowList contains only the locked SecureChat homeserver`() {
        val defaultEnterpriseService = DefaultEnterpriseService()
        assertThat(defaultEnterpriseService.homeserverAllowList())
            .containsExactly(MdmConfig.DEFAULT_HOMESERVER_URL)
    }

    @Test
    fun `the allow list never exposes a wildcard or alternate homeserver`() {
        val service = DefaultEnterpriseService()
        assertThat(service.homeserverAllowList()).containsExactly(MdmConfig.DEFAULT_HOMESERVER_URL)
    }

    @Test
    fun `isAllowedToConnectToHomeserver only accepts the configured homeserver`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        assertThat(defaultEnterpriseService.isAllowedToConnectToHomeserver(A_HOMESERVER_URL)).isFalse()
        assertThat(defaultEnterpriseService.isAllowedToConnectToHomeserver(MdmConfig.DEFAULT_HOMESERVER_URL)).isTrue()
    }

    @Test
    fun `comparing homeservers ignores case and a trailing slash`() = runTest {
        val service = DefaultEnterpriseService()
        assertThat(service.isAllowedToConnectToHomeserver("https://chat.securechat.com.au/")).isTrue()
        assertThat(service.isAllowedToConnectToHomeserver("HTTPS://CHAT.SECURECHAT.COM.AU")).isTrue()
    }

    @Test
    fun `comparing homeservers accepts a bare host and the default HTTPS port`() = runTest {
        val service = DefaultEnterpriseService()
        assertThat(service.isAllowedToConnectToHomeserver("chat.securechat.com.au")).isTrue()
        assertThat(service.isAllowedToConnectToHomeserver("https://chat.securechat.com.au:443")).isTrue()
    }

    @Test
    fun `a path cannot broaden the locked homeserver identity`() = runTest {
        val service = DefaultEnterpriseService()
        assertThat(service.isAllowedToConnectToHomeserver("https://CHAT.SECURECHAT.COM.AU/Matrix/")).isFalse()
        assertThat(service.isAllowedToConnectToHomeserver("https://chat.securechat.com.au/matrix")).isFalse()
    }

    @Test
    fun `comparing homeservers rejects credentials query fragments and non-HTTPS URLs`() = runTest {
        val service = DefaultEnterpriseService()
        assertThat(service.isAllowedToConnectToHomeserver("http://chat.securechat.com.au")).isFalse()
        assertThat(service.isAllowedToConnectToHomeserver("https://user@chat.securechat.com.au")).isFalse()
        assertThat(service.isAllowedToConnectToHomeserver("https://chat.securechat.com.au?next=elsewhere")).isFalse()
        assertThat(service.isAllowedToConnectToHomeserver("https://chat.securechat.com.au#elsewhere")).isFalse()
    }

    @Test
    fun `two malformed homeservers are never treated as equivalent`() = runTest {
        val service = DefaultEnterpriseService()

        assertThat(service.isAllowedToConnectToHomeserver("not a valid host")).isFalse()
    }

    @Test
    fun `isEnterpriseUser always return false`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        assertThat(defaultEnterpriseService.isEnterpriseUser(A_SESSION_ID)).isFalse()
    }

    @Test
    fun `semanticColorsFlow always emits the SecureChat palette`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        defaultEnterpriseService.semanticColorsFlow(null).test {
            val initialState = awaitItem()
            assertThat(initialState).isEqualTo(SecureChatColors.semanticColors)
            awaitComplete()
        }
    }

    @Test
    fun `brandColorsFlow always emits the SecureChat brand colour`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        defaultEnterpriseService.brandColorsFlow(null).test {
            val initialState = awaitItem()
            assertThat(initialState).isEqualTo(SecureChatColors.brand)
            awaitComplete()
        }
    }

    @Test
    fun `semanticColorsFlow always emits the SecureChat palette for a session`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        defaultEnterpriseService.semanticColorsFlow(A_SESSION_ID).test {
            val initialState = awaitItem()
            assertThat(initialState).isEqualTo(SecureChatColors.semanticColors)
            awaitComplete()
        }
    }

    @Test
    fun `overrideBrandColor has no effect`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        defaultEnterpriseService.overrideBrandColor(A_SESSION_ID, "aColor")
    }

    @Test
    fun `firebasePushGateway returns null`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        assertThat(defaultEnterpriseService.firebasePushGateway()).isNull()
    }

    @Test
    fun `unifiedPushDefaultPushGateway returns null`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        assertThat(defaultEnterpriseService.unifiedPushDefaultPushGateway()).isNull()
    }

    @Test
    fun `bugReportUrlFlow only emits Disabled`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        defaultEnterpriseService.bugReportUrlFlow(A_SESSION_ID).test {
            assertThat(awaitItem()).isEqualTo(BugReportUrl.Disabled)
            awaitComplete()
        }
    }

    @Test
    fun `getNoisyNotificationChannelId returns null`() = runTest {
        val defaultEnterpriseService = DefaultEnterpriseService()
        assertThat(defaultEnterpriseService.getNoisyNotificationChannelId(A_SESSION_ID)).isNull()
    }
}
