/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.permalink

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class DefaultMatrixToConverterTest : RobolectricTest() {
    @Test
    fun `a valid matrix-to url is accepted`() {
        val url = Uri.parse("https://matrix.to/#/#securechat:chat.securechat.com.au")
        assertThat(DefaultMatrixToConverter().convert(url)).isEqualTo(url)
    }

    @Test
    fun `an unrelated web origin is rejected even when its fragment looks like a room link`() {
        val url = Uri.parse("https://example.org/#/room/#secure:chat.securechat.com.au")
        assertThat(DefaultMatrixToConverter().convert(url)).isNull()
    }

    @Test
    fun `a lookalike matrix-to host is rejected`() {
        val url = Uri.parse("https://matrix.to.example.org/#/@alice:chat.securechat.com.au")
        assertThat(DefaultMatrixToConverter().convert(url)).isNull()
    }

    @Test
    fun `an encoded matrix-to authority is rejected`() {
        val url = Uri.parse("https://matrix%2eto/#/@alice:chat.securechat.com.au")
        assertThat(DefaultMatrixToConverter().convert(url)).isNull()
    }

    @Test
    fun `an insecure matrix-to url is rejected`() {
        val url = Uri.parse("http://matrix.to/#/@alice:chat.securechat.com.au")
        assertThat(DefaultMatrixToConverter().convert(url)).isNull()
    }

    @Test
    fun `a matrix-to url with user info is rejected`() {
        val url = Uri.parse("https://attacker@matrix.to/#/@alice:chat.securechat.com.au")
        assertThat(DefaultMatrixToConverter().convert(url)).isNull()
    }

    @Test
    fun `a matrix-to url on a non-default port is rejected`() {
        val url = Uri.parse("https://matrix.to:8448/#/@alice:chat.securechat.com.au")
        assertThat(DefaultMatrixToConverter().convert(url)).isNull()
    }

    @Test
    fun `a legacy client scheme is rejected`() {
        val url = Uri.parse("legacy-client://user/@alice:chat.securechat.com.au")
        assertThat(DefaultMatrixToConverter().convert(url)).isNull()
    }
}
