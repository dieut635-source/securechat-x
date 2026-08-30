/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.permalink

/**
 * This class turns a uri to a [PermalinkData].
 * Supported inputs are validated matrix.to links, configured client permalinks, and `matrix:` URIs.
 * Unrecognised HTTPS origins remain fallback links and are never treated as trusted app links.
 */
interface PermalinkParser {
    /**
     * Turns a uri string to a [PermalinkData].
     * A uri that is not a Matrix permalink is reported as a fallback link rather than as an error, so this never throws.
     * https://github.com/matrix-org/matrix-doc/blob/master/proposals/1704-matrix.to-permalinks.md
     *
     * @param uriString the uri to parse.
     */
    fun parse(uriString: String): PermalinkData
}
