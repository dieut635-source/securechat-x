/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.services.analyticsproviders.posthog

import dev.zacsweers.metro.Inject

@Inject
class PosthogEndpointConfigProvider {
    fun provide(): PosthogEndpointConfig? {
        return PosthogEndpointConfig(
            host = BuildConfig.POSTHOG_HOST,
            apiKey = BuildConfig.POSTHOG_APIKEY,
        ).takeIf {
            it.isValid
        }
    }
}
