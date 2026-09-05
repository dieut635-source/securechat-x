/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import config.AnalyticsConfig
import config.BuildTimeConfig
import config.PushProvidersConfig

object ModulesConfig {
    val pushProvidersConfig = PushProvidersConfig(
        includeFirebase = BuildTimeConfig.PUSH_CONFIG_INCLUDE_FIREBASE,
        includeUnifiedPush = BuildTimeConfig.PUSH_CONFIG_INCLUDE_UNIFIED_PUSH,
    )

    val analyticsConfig: AnalyticsConfig
    init {
        val withPosthog = BuildTimeConfig.SERVICES_POSTHOG_APIKEY.isNullOrEmpty().not() &&
            BuildTimeConfig.SERVICES_POSTHOG_HOST.isNullOrEmpty().not()
        val withSentry = BuildTimeConfig.SERVICES_SENTRY_DSN.isNullOrEmpty().not()
        analyticsConfig = if (withPosthog || withSentry) {
            println("Analytics enabled with Posthog: $withPosthog, Sentry: $withSentry")
            AnalyticsConfig.Enabled(withPosthog = withPosthog, withSentry = withSentry)
        } else {
            println("Analytics disabled: no SecureChat-owned endpoints are configured")
            AnalyticsConfig.Disabled
        }
    }
}
