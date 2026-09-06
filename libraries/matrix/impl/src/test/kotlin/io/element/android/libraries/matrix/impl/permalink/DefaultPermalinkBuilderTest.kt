/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.permalink

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomAlias
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class DefaultPermalinkBuilderTest : RobolectricTest() {
    private val sut = DefaultPermalinkBuilder()

    @Test
    fun `user links use the matrix scheme`() {
        assertThat(sut.permalinkForUser(UserId("@alice:chat.securechat.com.au")).getOrThrow())
            .isEqualTo("matrix:u/alice:chat.securechat.com.au")
    }

    @Test
    fun `reserved characters in user identifiers are encoded`() {
        val userId = UserId("@it/sme:chat.securechat.com.au")
        val link = sut.permalinkForUser(userId).getOrThrow()

        assertThat(link)
            .isEqualTo("matrix:u/it%2Fsme:chat.securechat.com.au")
    }

    @Test
    fun `room alias links use the matrix scheme`() {
        val roomAlias = RoomAlias("#secure:chat.securechat.com.au")
        val link = sut.permalinkForRoomAlias(roomAlias).getOrThrow()

        assertThat(link)
            .isEqualTo("matrix:r/secure:chat.securechat.com.au")
    }
}
