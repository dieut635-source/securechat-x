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
    // Do not publish placeholder legal links. They stay hidden until dedicated SecureChat-owned
    // pages have been reviewed and deployed; pointing them at the homeserver UI is misleading.
    val URL_COPYRIGHT: String? = null
    val URL_ACCEPTABLE_USE: String? = null
    val URL_PRIVACY: String? = null
    val URL_POLICY: String? = null
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
    // Closed-distribution policy: the production APK must not contain Google/Firebase, and must
    // never fall back to a public UnifiedPush gateway or distributor directory.
    //
    // Firebase stays out: it would route notification metadata through Google.
    //
    // UnifiedPush is now IN, but pointed exclusively at the SecureChat-owned ntfy service at
    // push.securechat.com.au. The fallback gateway in UnifiedPushConfig remains a non-routable
    // .invalid host on purpose: if that service ever stops advertising a Matrix gateway, push
    // must fail closed rather than silently reach a third party.
    const val PUSH_CONFIG_INCLUDE_FIREBASE: Boolean = false
    const val PUSH_CONFIG_INCLUDE_UNIFIED_PUSH: Boolean = true
    val PUSHER_APP_ID_RELEASE: String? = "com.securechat.app.android"
    val PUSHER_APP_ID_DEBUG: String? = "com.securechat.app.android.debug"
    val PUSHER_APP_ID_NIGHTLY: String? = "com.securechat.app.android.nightly"
}
