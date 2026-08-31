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

    /**
     * The only host allowed to act as SecureChat's push gateway.
     *
     * Without this, a device whose ntfy app was never pointed at SecureChat's server keeps ntfy's
     * default, https://ntfy.sh. That public server DOES advertise a Matrix gateway
     * (verified: GET https://ntfy.sh/_matrix/push/v1/notify returns
     * {"unifiedpush":{"gateway":"matrix"}}), so gateway resolution would succeed and every push
     * would be routed through a third party — leaking who receives a message, in which room, and
     * when. The .invalid fallback below does not catch this: it only fires when NO gateway is
     * found, not when the wrong one is.
     *
     * ntfy has no managed-configuration support (no app_restrictions.xml, no RestrictionsManager),
     * so no MDM can prevent that misconfiguration on the device side. It has to be caught here.
     */
    const val ALLOWED_GATEWAY_HOST = "push.securechat.com.au"

    const val INDEX = 1
    const val NAME = "UnifiedPush"
}
