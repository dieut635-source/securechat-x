/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.oauth.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.auth.FAKE_REDIRECT_URL
import io.element.android.libraries.matrix.test.auth.FakeOAuthRedirectUrlProvider
import io.element.android.libraries.oauth.api.OAuthAction
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class DefaultOAuthUrlParserTest : RobolectricTest() {
    @Test
    fun `test empty url`() {
        val sut = createDefaultOAuthUrlParser()
        assertThat(sut.parse("")).isNull()
    }

    @Test
    fun `test regular url`() {
        val sut = createDefaultOAuthUrlParser()
        assertThat(sut.parse("https://chat.securechat.com.au")).isNull()
    }

    @Test
    fun `test cancel url`() {
        val sut = createDefaultOAuthUrlParser()
        val aCancelUrl = "$FAKE_REDIRECT_URL?error=access_denied&state=IFF1UETGye2ZA8pO"
        assertThat(sut.parse(aCancelUrl)).isEqualTo(OAuthAction.GoBack())
    }

    @Test
    fun `test success url`() {
        val sut = createDefaultOAuthUrlParser()
        val aSuccessUrl = "$FAKE_REDIRECT_URL?state=IFF1UETGye2ZA8pO&code=y6X1GZeqA3xxOWcTeShgv8nkgFJXyzWB"
        assertThat(sut.parse(aSuccessUrl)).isEqualTo(OAuthAction.Success(aSuccessUrl))
    }

    @Test
    fun `test unknown url`() {
        val sut = createDefaultOAuthUrlParser()
        val anUnknownUrl = "$FAKE_REDIRECT_URL?state=IFF1UETGye2ZA8pO&goat=y6X1GZeqA3xxOWcTeShgv8nkgFJXyzWB"
        assertThat(sut.parse(anUnknownUrl)).isNull()
    }

    @Test
    fun `callback path prefix is rejected`() {
        val sut = createDefaultOAuthUrlParser()
        val url = "${FAKE_REDIRECT_URL}evil?state=state&code=code"

        assertThat(sut.parse(url)).isNull()
    }

    @Test
    fun `callback with a fragment is rejected`() {
        val sut = createDefaultOAuthUrlParser()
        val url = "$FAKE_REDIRECT_URL?state=state&code=code#fragment"

        assertThat(sut.parse(url)).isNull()
    }

    @Test
    fun `callback without state is rejected`() {
        val sut = createDefaultOAuthUrlParser()
        val url = "$FAKE_REDIRECT_URL?code=code"

        assertThat(sut.parse(url)).isNull()
    }

    @Test
    fun `callback with duplicate state is rejected`() {
        val sut = createDefaultOAuthUrlParser()
        val url = "$FAKE_REDIRECT_URL?state=first&state=second&code=code"

        assertThat(sut.parse(url)).isNull()
    }

    @Test
    fun `callback mixing success and error parameters is rejected`() {
        val sut = createDefaultOAuthUrlParser()
        val url = "$FAKE_REDIRECT_URL?state=state&code=code&error=access_denied"

        assertThat(sut.parse(url)).isNull()
    }

    private fun createDefaultOAuthUrlParser(): DefaultOAuthUrlParser {
        return DefaultOAuthUrlParser(
            oAuthRedirectUrlProvider = FakeOAuthRedirectUrlProvider(),
        )
    }
}
