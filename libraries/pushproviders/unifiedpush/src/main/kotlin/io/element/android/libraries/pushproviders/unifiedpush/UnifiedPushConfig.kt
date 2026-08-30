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
     * Fail-closed placeholders. This module is excluded from SecureChat production builds, and
     * these non-routable values prevent an accidental future inclusion from contacting a public
     * gateway or distributor directory.
     */
    const val DEFAULT_PUSH_GATEWAY_HTTP_URL: String = "https://push-disabled.securechat.invalid/_matrix/push/v1/notify"

    const val UNIFIED_PUSH_DISTRIBUTORS_URL = "https://push-disabled.securechat.invalid/distributors"

    const val INDEX = 1
    const val NAME = "UnifiedPush"
}
