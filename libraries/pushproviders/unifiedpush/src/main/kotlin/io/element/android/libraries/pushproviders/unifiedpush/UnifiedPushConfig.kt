/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush

object UnifiedPushConfig {
    /**
     * Fail-closed fallbacks. Kept non-routable on purpose.
     *
     * The normal path never uses these: [UnifiedPushGatewayResolver] derives the gateway from the
     * endpoint the distributor hands out, and SecureChat's ntfy service at push.securechat.com.au
     * advertises a Matrix gateway, so resolution succeeds against SecureChat's own host.
     *
     * These constants are only reached when that advertisement is missing or the endpoint is
     * unparseable. In upstream Element that falls back to a public gateway; here it must not.
     * A .invalid host makes push break loudly instead of quietly routing notification metadata
     * through a third party.
     */
    const val DEFAULT_PUSH_GATEWAY_HTTP_URL: String = "https://push-disabled.securechat.invalid/_matrix/push/v1/notify"

    const val UNIFIED_PUSH_DISTRIBUTORS_URL = "https://push-disabled.securechat.invalid/distributors"

    const val INDEX = 1
    const val NAME = "UnifiedPush"
}
