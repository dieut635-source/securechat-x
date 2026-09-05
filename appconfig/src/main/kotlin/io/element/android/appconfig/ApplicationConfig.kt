/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

object ApplicationConfig {
    /**
     * Application name used in UI strings. When empty, the variant-specific `R.string.app_name`
     * value is used, preserving the "dbg" and "nightly" suffixes for non-release builds.
     */
    const val APPLICATION_NAME: String = ""

    /**
     * Used in strings that refer to the production mobile client.
     */
    const val PRODUCTION_APPLICATION_NAME: String = "SecureChat"

    /**
     * Used in strings that refer to the web/desktop client.
     */
    const val DESKTOP_APPLICATION_NAME: String = "SecureChat"
}
