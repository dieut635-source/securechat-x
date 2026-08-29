/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.api

import java.net.URI

/**
 * Returns a stable HTTPS homeserver identity for managed-policy comparisons.
 *
 * Host and scheme are case-insensitive, the default HTTPS port and trailing slashes are ignored,
 * and path case is preserved. Invalid or potentially ambiguous URLs are rejected.
 */
fun String.canonicalHomeserverUrl(): String? {
    val value = trim().let { if ("://" in it) it else "https://$it" }
    val uri = runCatching { URI(value).parseServerAuthority() }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque) return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val host = uri.host?.lowercase() ?: return null
    val renderedHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
    val port = uri.port.takeUnless { it == -1 || it == 443 }?.let { ":$it" }.orEmpty()
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return "https://$renderedHost$port$path"
}

/** Invalid homeserver URLs never compare as equivalent, including two equally invalid values. */
fun areHomeserverUrlsEquivalent(first: String, second: String): Boolean {
    val firstCanonical = first.canonicalHomeserverUrl() ?: return false
    val secondCanonical = second.canonicalHomeserverUrl() ?: return false
    return firstCanonical == secondCanonical
}
