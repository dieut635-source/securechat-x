/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.fixtures.fakes

import uniffi.matrix_sdk.NoHandle
import uniffi.matrix_sdk.OAuthAuthorizationData

class FakeOAuthAuthorizationData(
    private val url: String = "https://chat.securechat.com.au/oauth",
) : OAuthAuthorizationData(NoHandle) {
    override fun loginUrl(): String = url
}
