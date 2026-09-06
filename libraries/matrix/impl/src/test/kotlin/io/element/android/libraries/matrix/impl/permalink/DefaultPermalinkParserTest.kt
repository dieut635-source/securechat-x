/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.permalink

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class DefaultPermalinkParserTest : RobolectricTest() {
    @Test
    fun `a matrix-to link to a user id with a slash is parsed as a user link`() {
        val result = createParser().parse("https://matrix.to/#/@it/sme:chat.securechat.com.au")
        assertThat(result).isEqualTo(PermalinkData.UserLink(UserId("@it/sme:chat.securechat.com.au")))
    }

    @Test
    fun `an escaped slash in a user id is parsed as a user link`() {
        val result = createParser().parse("https://matrix.to/#/@it%2Fsme:chat.securechat.com.au")
        assertThat(result).isEqualTo(PermalinkData.UserLink(UserId("@it/sme:chat.securechat.com.au")))
    }

    @Test
    fun `an arbitrary web link to a user id is not parsed as a user link`() {
        val result = createParser().parse("https://example.org/#/user/@it/sme:chat.securechat.com.au")
        assertThat(result).isInstanceOf(PermalinkData.FallbackLink::class.java)
    }

    @Test
    fun `a user id with a slash that is not a valid user id is not a user link`() {
        val result = createParser().parse("https://example.org/foo/@it/sme")
        assertThat(result).isInstanceOf(PermalinkData.FallbackLink::class.java)
    }

    private fun createParser() = DefaultPermalinkParser(
        matrixToConverter = DefaultMatrixToConverter(),
    )
}
