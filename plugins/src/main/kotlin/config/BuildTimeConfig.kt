/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package config

object BuildTimeConfig {
    const val APPLICATION_ID = "com.securechat.app"
    const val APPLICATION_NAME = "SecureChat"
    val METADATA_HOST_REVERSED: String? = "com.securechat"
    val OAUTH_CLIENT_URL_PATH: String? = null
    val URL_WEBSITE: String? = "https://chat.securechat.com.au"
    val URL_LOGO: String? = "https://chat.securechat.com.au/securechat/favicon.svg"
    val URL_COPYRIGHT: String? = "https://chat.securechat.com.au"
    val URL_ACCEPTABLE_USE: String? = "https://chat.securechat.com.au"
    val URL_PRIVACY: String? = "https://chat.securechat.com.au"
    val URL_POLICY: String? = "https://chat.securechat.com.au"
    val SERVICES_MAPTILER_BASE_URL: String? = null
    val SERVICES_MAPTILER_APIKEY: String? = null
    val SERVICES_MAPTILER_LIGHT_MAPID: String? = null
    val SERVICES_MAPTILER_DARK_MAPID: String? = null
    val SERVICES_POSTHOG_HOST: String? = null
    val SERVICES_POSTHOG_APIKEY: String? = null
    val SERVICES_SENTRY_DSN: String? = null
    val SERVICES_SENTRY_DSN_RUST: String? = null
    val BUG_REPORT_URL: String? = null
    val BUG_REPORT_APP_NAME: String? = null
    // Do not compile the inherited Firebase project or push gateway into SecureChat. Enable this
    // only after adding a SecureChat-owned Firebase project and push gateway configuration.
    const val PUSH_CONFIG_INCLUDE_FIREBASE: Boolean = false
    const val PUSH_CONFIG_INCLUDE_UNIFIED_PUSH: Boolean = true
    val PUSHER_APP_ID_RELEASE: String? = "com.securechat.app.android"
    val PUSHER_APP_ID_DEBUG: String? = "com.securechat.app.android.debug"
    val PUSHER_APP_ID_NIGHTLY: String? = "com.securechat.app.android.nightly"
}
