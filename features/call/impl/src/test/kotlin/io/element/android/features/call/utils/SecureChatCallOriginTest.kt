/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.utils

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.utils.isAllowedSecureChatCallNetworkUri
import io.element.android.features.call.impl.utils.isAllowedSecureChatCallSubresourceUri
import io.element.android.features.call.impl.utils.isSecureChatCallDocumentUri
import io.element.android.features.call.impl.utils.isSecureChatCallOrigin
import io.element.android.features.call.impl.utils.secureChatCallPostMessageJavascript
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.serialization.json.Json
import org.junit.Test

class SecureChatCallOriginTest : RobolectricTest() {
    @Test
    fun `only the local HTTPS asset origin is trusted`() {
        assertThat(isSecureChatCallOrigin("https://appassets.androidplatform.net")).isTrue()
        assertThat(isSecureChatCallOrigin("https://appassets.androidplatform.net:443")).isTrue()
        assertThat(isSecureChatCallOrigin("http://appassets.androidplatform.net")).isFalse()
        assertThat(isSecureChatCallOrigin("https://appassets.androidplatform.net.attacker.invalid")).isFalse()
        assertThat(isSecureChatCallOrigin("https://user@appassets.androidplatform.net")).isFalse()
        assertThat(isSecureChatCallOrigin("https://appassets.androidplatform.net:444")).isFalse()
    }

    @Test
    fun `only the canonical bundled call document is trusted`() {
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net/securechat-call/index.html?widgetId=1#call")).isTrue()
        assertThat(
            isSecureChatCallDocumentUri(
                "https://appassets.androidplatform.net/securechat-call/index.html?widgetId=widget#roomId=!room:securechat.com.au"
            )
        ).isTrue()
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net:443/securechat-call/index.html")).isTrue()
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net/securechat-call/")).isFalse()
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net/securechat-call")).isFalse()
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net/securechat-call/assets/index.js")).isFalse()
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net/securechat-call/other.html")).isFalse()
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net/securechat-call-attacker/index.html")).isFalse()
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net/securechat-call/../attacker.html")).isFalse()
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net/securechat-call%2F..%2Fattacker.html")).isFalse()
        assertThat(isSecureChatCallDocumentUri("https://appassets.androidplatform.net/securechat-call/%69ndex.html")).isFalse()
        assertThat(isSecureChatCallDocumentUri("https://chat.securechat.com.au/securechat-call/index.html")).isFalse()
    }

    @Test
    fun `call network access is restricted to the managed homeserver`() {
        assertThat(isAllowedSecureChatCallNetworkUri("https://chat.securechat.com.au/_matrix/client/versions")).isTrue()
        assertThat(isAllowedSecureChatCallNetworkUri("https://chat.securechat.com.au:443/livekit/jwt")).isTrue()

        assertThat(isAllowedSecureChatCallNetworkUri("http://chat.securechat.com.au")).isFalse()
        assertThat(isAllowedSecureChatCallNetworkUri("https://user@chat.securechat.com.au")).isFalse()
        assertThat(isAllowedSecureChatCallNetworkUri("https://chat.securechat.com.au:444")).isFalse()
        assertThat(isAllowedSecureChatCallNetworkUri("https://sub.chat.securechat.com.au")).isFalse()
        assertThat(isAllowedSecureChatCallNetworkUri("https://chat.securechat.com.au.attacker.invalid")).isFalse()
        assertThat(isAllowedSecureChatCallNetworkUri("https://cdn.jsdelivr.net/npm/package")).isFalse()
        assertThat(isAllowedSecureChatCallNetworkUri("https://storage.googleapis.com/model")).isFalse()
        assertThat(isAllowedSecureChatCallNetworkUri("https://us.i.posthog.com/capture")).isFalse()
        assertThat(isAllowedSecureChatCallNetworkUri("https://www.recaptcha.net/recaptcha/api.js")).isFalse()
        assertThat(isAllowedSecureChatCallNetworkUri("not a URI")).isFalse()
    }

    @Test
    fun `local data and same-origin blob subresources do not open network access`() {
        assertThat(isAllowedSecureChatCallSubresourceUri("data:application/json,%7B%7D")).isTrue()
        assertThat(
            isAllowedSecureChatCallSubresourceUri("blob:https://appassets.androidplatform.net/550e8400-e29b-41d4-a716-446655440000")
        ).isTrue()
        assertThat(isAllowedSecureChatCallSubresourceUri("blob:https://attacker.invalid/id")).isFalse()
        assertThat(isAllowedSecureChatCallSubresourceUri("file:///data/local/tmp/payload.js")).isFalse()
        assertThat(isAllowedSecureChatCallSubresourceUri("content://attacker/payload")).isFalse()
    }

    @Test
    fun `outbound widget messages are encoded and use the exact local target origin`() {
        val message = """{"body":"\"); window.compromised = true; //","line":"\u2028"}"""

        val javascript = secureChatCallPostMessageJavascript(message)
        val encodedMessage = javascript
            .removePrefix("window.postMessage(JSON.parse(")
            .removeSuffix("), 'https://appassets.androidplatform.net')")

        assertThat(Json.decodeFromString<String>(encodedMessage)).isEqualTo(message)
        assertThat(javascript).doesNotContain(", '*'")
    }
}
