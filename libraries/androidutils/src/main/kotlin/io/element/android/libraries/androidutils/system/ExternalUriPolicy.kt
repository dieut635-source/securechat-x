/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.system

import io.element.android.libraries.core.extensions.runCatchingExceptions
import java.net.URI

/**
 * Returns whether [url] is safe to hand to a browser or another application.
 *
 * External content is untrusted. Only strict HTTPS URLs without embedded credentials or a
 * non-standard port are allowed; all custom and local-content schemes fail closed.
 */
fun isAllowedExternalUrl(url: String): Boolean {
    val uri = runCatchingExceptions { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals(HTTPS_SCHEME, ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == HTTPS_PORT)
}

private const val HTTPS_SCHEME = "https"
private const val HTTPS_PORT = 443
