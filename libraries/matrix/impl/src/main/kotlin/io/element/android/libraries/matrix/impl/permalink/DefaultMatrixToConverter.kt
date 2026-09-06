/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.permalink

import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.matrix.api.permalink.MatrixToConverter

/**
 * Accepts a well-formed HTTPS matrix.to URI for local parsing.
 *
 * SecureChat does not rewrite arbitrary or legacy client URLs into Matrix permalinks. An unrelated
 * web origin must not become an in-app navigation request merely because its fragment contains a
 * familiar path.
 */
@ContributesBinding(AppScope::class)
class DefaultMatrixToConverter : MatrixToConverter {
    override fun convert(uri: Uri): Uri? {
        val isTrustedMatrixToOrigin = uri.scheme.equals("https", ignoreCase = true) &&
            uri.encodedAuthority.equals("matrix.to", ignoreCase = true) &&
            uri.host.equals("matrix.to", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port == -1 &&
            uri.path == "/" &&
            uri.query == null &&
            uri.fragment?.startsWith("/") == true
        return uri.takeIf { isTrustedMatrixToOrigin }
    }
}
