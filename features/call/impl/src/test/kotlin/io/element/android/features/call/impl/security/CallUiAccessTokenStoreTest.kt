/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallUiAccessTokenStoreTest {
    @Test
    fun `token can be consumed exactly once`() {
        val store = CallUiAccessTokenStore()
        val token = store.issue()

        assertThat(store.consume(token)).isTrue()
        assertThat(store.consume(token)).isFalse()
    }

    @Test
    fun `issuing a new token invalidates the previous navigation`() {
        val store = CallUiAccessTokenStore()
        val staleToken = store.issue()
        val currentToken = store.issue()

        assertThat(store.consume(staleToken)).isFalse()
        assertThat(store.consume(currentToken)).isTrue()
    }

    @Test
    fun `missing and arbitrary tokens fail closed`() {
        val store = CallUiAccessTokenStore()
        store.issue()

        assertThat(store.consume(null)).isFalse()
        assertThat(store.consume("not-issued")).isFalse()
    }
}
