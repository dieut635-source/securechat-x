/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

object LearnMoreConfig {
    /**
     * Every "Learn more" link in the app points at the SecureChat site.
     *
     * Replace these placeholders with dedicated help pages when the closed deployment publishes them.
     */
    private const val HELP_URL: String = "https://chat.securechat.com.au"

    const val ENCRYPTION_URL: String = HELP_URL
    const val DEVICE_VERIFICATION_URL: String = HELP_URL
    const val SECURE_BACKUP_URL: String = HELP_URL
    const val IDENTITY_CHANGE_URL: String = HELP_URL
    const val HISTORY_VISIBLE_URL: String = HELP_URL
}
