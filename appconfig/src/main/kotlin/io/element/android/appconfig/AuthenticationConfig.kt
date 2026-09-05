/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

object AuthenticationConfig {
    /**
     * The homeserver SecureChat signs in to by default, and the one offered for autocomplete.
     */
    const val DEFAULT_HOMESERVER_URL = "https://chat.securechat.com.au"

    /**
     * Optional self-hosted documentation for unsupported-server errors.
     *
     * Keep disabled until an audited SecureChat-owned route exists. The authentication flow must
     * not send users to an upstream public site or guess a path on the production homeserver.
     */
    val SLIDING_SYNC_READ_MORE_URL: String? = null

    /**
     * Force a sliding sync proxy url, if not null, the proxy url in the .well-known file will be ignored.
     */
    val SLIDING_SYNC_PROXY_URL: String? = null
}
