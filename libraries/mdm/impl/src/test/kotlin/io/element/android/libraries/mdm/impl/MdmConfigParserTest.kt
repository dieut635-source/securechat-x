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
        assertThat(config.restrictionsPending).isFalse()
    }

    @Test
    fun `DPC pending sentinel overrides permissive values until final restrictions arrive`() {
        val config = MdmConfigParser.parse(
            mapOf(
                MdmConfig.KEY_RESTRICTIONS_PENDING to true,
                MdmConfig.KEY_ALLOW_FILE_SEND to true,
                MdmConfig.KEY_AUTO_LOGOUT_MINUTES to 0,
            )
        )

        assertThat(config).isEqualTo(MdmConfig.restrictionsPending)
        assertThat(config.allowFileSend).isFalse()
        assertThat(config.restrictionsPending).isTrue()
    }

    @Test
    fun `values of the declared types are read`() {
        val config = MdmConfigParser.parse(
            mapOf(
                MdmConfig.KEY_HOMESERVER_URL to MdmConfig.DEFAULT_HOMESERVER_URL,
                MdmConfig.KEY_ALLOW_REGISTRATION to false,
                MdmConfig.KEY_ALLOW_FILE_SEND to false,
                MdmConfig.KEY_AUTO_LOGOUT_MINUTES to 0,
            )
        )
        assertThat(config).isEqualTo(MdmConfig.default.copy(allowFileSend = false))
    }

    @Test
    fun `only Bundle booleans are accepted`() {
        assertThat(MdmConfigParser.parseBoolean(true)).isTrue()
        assertThat(MdmConfigParser.parseBoolean(false)).isFalse()
        for (value in listOf<Any>("true", "false", 1, 0)) {
            assertThat(MdmConfigParser.parseBoolean(value)).isNull()
        }
    }

    @Test
    fun `a present malformed boolean fails the whole snapshot closed`() {
        for (value in listOf<Any?>("true", "maybe", 1, null)) {
            assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_ALLOW_FILE_SEND to value)))
                .isEqualTo(MdmConfig.restrictionsPending)
        }
    }

    @Test
    fun `only bounded Bundle integers are accepted for minutes`() {
        assertThat(MdmConfigParser.parseMinutes(0)).isEqualTo(0)
        assertThat(MdmConfigParser.parseMinutes(MdmConfigParser.MAX_AUTO_LOGOUT_MINUTES))
            .isEqualTo(MdmConfigParser.MAX_AUTO_LOGOUT_MINUTES)
        for (value in listOf<Any>("30", 30L, -1, MdmConfigParser.MAX_AUTO_LOGOUT_MINUTES + 1)) {
            assertThat(MdmConfigParser.parseMinutes(value)).isNull()
            assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_AUTO_LOGOUT_MINUTES to value)))
                .isEqualTo(MdmConfig.restrictionsPending)
        }
    }

    @Test
    fun `nonzero automatic logout is rejected because PIN lock preserves the sole session`() {
        for (minutes in listOf(1, 15, MdmConfigParser.MAX_AUTO_LOGOUT_MINUTES)) {
            assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_AUTO_LOGOUT_MINUTES to minutes)))
                .isEqualTo(MdmConfig.restrictionsPending)
        }
    }

    @Test
    fun `canonical homeserver spellings resolve to the one locked value`() {
        for (value in listOf(
            "https://chat.securechat.com.au",
            " HTTPS://CHAT.SECURECHAT.COM.AU:443/ ",
        )) {
            assertThat(MdmConfigParser.parseHomeserverUrl(value)).isEqualTo(MdmConfig.DEFAULT_HOMESERVER_URL)
            assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_HOMESERVER_URL to value)))
                .isEqualTo(MdmConfig.default)
        }
    }

    @Test
    fun `an absent scheme or http url fails the whole snapshot closed`() {
        assertThat(MdmConfigParser.parseHomeserverUrl("chat.securechat.com.au")).isNull()
        assertThat(MdmConfigParser.parseHomeserverUrl("http://matrix.example.com")).isNull()
        assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_HOMESERVER_URL to "http://insecure.example.com")))
            .isEqualTo(MdmConfig.restrictionsPending)
    }

    @Test
    fun `a different homeserver is rejected even when it is a valid HTTPS URL`() {
        for (value in listOf(
            "https://matrix.example.com",
            "matrix.example.com",
            "https://chat.securechat.com.au:8448",
            "https://chat.securechat.com.au/client",
            "https://chat.securechat.com.au.evil.example",
            "https://securechat.com.au",
        )) {
            assertThat(MdmConfigParser.parseHomeserverUrl(value)).isNull()
            assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_HOMESERVER_URL to value)))
                .isEqualTo(MdmConfig.restrictionsPending)
        }
    }

    @Test
    fun `malformed ambiguous and non HTTPS values are rejected`() {
        for (value in listOf(
            "",
            "   ",
            "not a host",
            "ftp://chat.securechat.com.au",
            "http://chat.securechat.com.au",
            "https://admin@chat.securechat.com.au",
            "https://chat.securechat.com.au?tenant=other",
            "https://chat.securechat.com.au/#fragment",
            "https://chat.securechat.com.au//",
            "https://chat.securechat.com.au:",
            "https://chat.securechat.com.au:abc",
            "https://chat.securechat.com.au:0",
            "https://chat.securechat.com.au:65536",
            "https://",
            42,
        )) {
            assertThat(MdmConfigParser.parseHomeserverUrl(value)).isNull()
            assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_HOMESERVER_URL to value)))
                .isEqualTo(MdmConfig.restrictionsPending)
        }
    }

    @Test
    fun `registration cannot be enabled or supplied with the wrong type`() {
        for (value in listOf<Any>(true, 1, "false")) {
            assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_ALLOW_REGISTRATION to value)))
                .isEqualTo(MdmConfig.restrictionsPending)
        }
        assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_ALLOW_REGISTRATION to false)))
            .isEqualTo(MdmConfig.default)
    }

    @Test
    fun `a malformed pending sentinel fails closed`() {
        for (value in listOf<Any?>("false", 0, null)) {
            assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_RESTRICTIONS_PENDING to value)))
                .isEqualTo(MdmConfig.restrictionsPending)
        }
        assertThat(MdmConfigParser.parse(mapOf(MdmConfig.KEY_RESTRICTIONS_PENDING to false)))
            .isEqualTo(MdmConfig.default)
    }

    @Test
    fun `describe lists all policy values so a log line is enough to diagnose a device`() {
        val text = MdmConfig.default.copy(
            homeserverUrl = "https://matrix.example.com",
            allowRegistration = true,
            allowFileSend = false,
            autoLogoutMinutes = 15,
        ).describe()
        assertThat(text).contains("homeserver_url=https://matrix.example.com")
        assertThat(text).contains("allow_registration=true")
        assertThat(text).contains("allow_file_send=false")
        assertThat(text).contains("auto_logout_minutes=15")
        assertThat(text).contains("restrictions_pending=false")
    }
}
