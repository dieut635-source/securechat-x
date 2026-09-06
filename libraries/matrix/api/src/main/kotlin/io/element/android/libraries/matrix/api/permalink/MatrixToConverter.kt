/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.permalink

import android.net.Uri

/**
 * Validates or converts an input URI to a matrix.to-compatible URI for local parsing.
 */
interface MatrixToConverter {
    /**
     * @param uri the uri to convert.
     * @return a validated matrix.to URI, or `null` when the input is not recognised.
     */
    fun convert(uri: Uri): Uri?
}
