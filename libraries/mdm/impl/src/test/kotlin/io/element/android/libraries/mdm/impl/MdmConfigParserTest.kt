/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mdm.api.MdmConfig
import org.junit.Test

class MdmConfigParserTest {
    @Test
    fun `no managed configuration gives the defaults`() {
        val config = MdmConfigParser.parse(emptyMap())
        assertThat(config).isEqualTo(MdmConfig.default)
        assertThat(config.homeserverUrl).isEqualTo("https://chat.securechat.com.au")
        assertThat(config.allowRegistration).isFalse()
        assertThat(config.allowFileSend).isTrue()
        assertThat(config.autoLogoutMinutes).isEqualTo(0)
    }

    @Test
    fun `values of the declared types are read as-is`() {
        val config = MdmConfigParser.parse(
            mapOf(
                MdmConfig.KEY_HOMESERVER_URL to "https://matrix.example.com",
                MdmConfig.KEY_ALLOW_REGISTRATION to true,
                MdmConfig.KEY_ALLOW_FILE_SEND to false,
                MdmConfig.KEY_AUTO_LOGOUT_MINUTES to 15,
            )
        )
        assertThat(config).isEqualTo(
            MdmConfig(
                homeserverUrl = "https://matrix.example.com",
                allowRegistration = true,
                allowFileSend = false,
                autoLogoutMinutes = 15,
            )
        )
    }

    @Test
    fun `booleans pushed as strings are understood`() {
        for (value in listOf("true", "TRUE", " true ", "1", "yes", "on")) {
            assertThat(MdmConfigParser.parseBoolean(value)).isTrue()
        }
        for (value in listOf("false", "FALSE", " false ", "0", "no", "off")) {
            assertThat(MdmConfigParser.parseBoolean(value)).isFalse()
        }
    }

    @Test
    fun `a boolean that makes no sense falls back to the default`() {
        assertThat(MdmConfigParser.parseBoolean("maybe")).isNull()
        assertThat(MdmConfigParser.parseBoolean("")).isNull()
        assertThat(MdmConfigParser.parseBoolean(null)).isNull()
        assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_ALLOW_FILE_SEND to "maybe")).allowFileSend).isTrue()
    }

    @Test
    fun `minutes pushed as a string are understood, and nonsense falls back`() {
        assertThat(MdmConfigParser.parseMinutes("30")).isEqualTo(30)
        assertThat(MdmConfigParser.parseMinutes(" 30 ")).isEqualTo(30)
        assertThat(MdmConfigParser.parseMinutes(0)).isEqualTo(0)
        assertThat(MdmConfigParser.parseMinutes("soon")).isNull()
        assertThat(MdmConfigParser.parseMinutes(2_147_483_647L)).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `a negative timeout is ignored rather than logging the user out at once`() {
        assertThat(MdmConfigParser.parseMinutes(-1)).isNull()
        assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_AUTO_LOGOUT_MINUTES to -5)).autoLogoutMinutes).isEqualTo(0)
    }

    @Test
    fun `a bare host gets https added`() {
        assertThat(MdmConfigParser.parseHomeserverUrl("matrix.example.com")).isEqualTo("https://matrix.example.com")
        assertThat(MdmConfigParser.parseHomeserverUrl(" matrix.example.com/ ")).isEqualTo("https://matrix.example.com")
    }

    @Test
    fun `an http url is rejected rather than downgrading the connection`() {
        assertThat(MdmConfigParser.parseHomeserverUrl("http://matrix.example.com")).isNull()
        assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_HOMESERVER_URL to "http://insecure.example.com")).homeserverUrl)
            .isEqualTo(MdmConfig.DEFAULT_HOMESERVER_URL)
    }

    @Test
    fun `a homeserver url that is not usable falls back to the default`() {
        for (value in listOf("", "   ", "not a host", "ftp://example.com", "localhost", 42)) {
            assertThat(MdmConfigParser.parseHomeserverUrl(value)).isNull()
        }
    }

    @Test
    fun `a trailing slash is removed so the url matches what the app stores`() {
        assertThat(MdmConfigParser.parseHomeserverUrl("https://matrix.example.com/")).isEqualTo("https://matrix.example.com")
    }
}
