/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class DefaultLoginIntentResolverTest : RobolectricTest() {
    @Test
    fun `public login configuration links are disabled`() {
        val sut = DefaultLoginIntentResolver()

        listOf(
            "https://chat.securechat.com.au/securechat/?account_provider=chat.securechat.com.au",
            "https://chat.securechat.com.au/securechat/?account_provider=example.org&login_hint=mxid:@alice:example.org",
            "http://chat.securechat.com.au/securechat/?account_provider=example.org",
        ).forEach { uri ->
            assertThat(sut.parse(uri)).isNull()
        }
    }
}
