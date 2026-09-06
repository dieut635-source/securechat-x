/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.enterprise.impl

import androidx.compose.ui.graphics.Color
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.features.enterprise.api.BugReportUrl
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.libraries.matrix.api.ClientUrlContentFetcher
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.api.areHomeserverUrlsEquivalent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@ContributesBinding(AppScope::class)
class DefaultEnterpriseService : EnterpriseService {
    override suspend fun isEnterpriseUser(sessionId: SessionId) = false
    override suspend fun tweakMasUrl(url: String, urlContentFetcher: ClientUrlContentFetcher) = url

    /**
     * SecureChat always pins sign-in to its production homeserver. Managed configuration cannot
     * weaken this rule. Returning a singleton without the "*" wildcard also hides the inherited
     * "change server" affordance.
     */
    override fun homeserverAllowList(): List<String> = listOf(MdmConfig.DEFAULT_HOMESERVER_URL)

    override suspend fun isAllowedToConnectToHomeserver(homeserverUrl: String): Boolean {
        return areHomeserverUrlsEquivalent(homeserverUrl, MdmConfig.DEFAULT_HOMESERVER_URL)
    }

    override suspend fun isElementProEnforced(serverName: String): Boolean = false

    override suspend fun overrideBrandColor(sessionId: SessionId?, brandColor: String?) = Unit

    override fun brandColorsFlow(sessionId: SessionId?): Flow<Color?> {
        return flowOf(SecureChatColors.brand)
    }

    override fun semanticColorsFlow(sessionId: SessionId?): Flow<SemanticColorsLightDark> {
        return flowOf(SecureChatColors.semanticColors)
    }

    override fun firebasePushGateway(): String? = null
    override fun unifiedPushDefaultPushGateway(): String? = null

    override fun bugReportUrlFlow(sessionId: SessionId?): Flow<BugReportUrl> {
        // The inherited default points at an upstream service. SecureChat has no bug-report
        // endpoint of its own yet, so the feature stays off.
        return flowOf(BugReportUrl.Disabled)
    }

    override fun getNoisyNotificationChannelId(sessionId: SessionId): String? = null
}
