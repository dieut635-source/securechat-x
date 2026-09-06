/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.intent

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.androidutils.system.isAllowedExternalUrl
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.annotation.Config

@Config(application = Application::class)
class SafeUriHandlerTest : RobolectricTest() {
    @Test
    fun `regular HTTPS links are allowed`() {
        assertThat(isAllowedExternalUrl("https://example.org/path?q=value#fragment")).isTrue()
        assertThat(isAllowedExternalUrl("HTTPS://example.org:443/path")).isTrue()
    }

    @Test
    fun `unsafe and ambiguous links are rejected`() {
        listOf(
            "http://example.org",
            "https://user:password@example.org",
            "https://example.org:8448/path",
            "intent://example.org/#Intent;scheme=https;end",
            "file:///data/local/tmp/file",
            "content://com.example.provider/item",
            "javascript:alert(1)",
            "matrix:u/alice:chat.securechat.com.au",
            "not a URI",
        ).forEach { uri ->
            assertThat(isAllowedExternalUrl(uri)).isFalse()
        }
    }
}
