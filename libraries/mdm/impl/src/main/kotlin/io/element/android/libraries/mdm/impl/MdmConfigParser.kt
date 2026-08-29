/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.impl

import io.element.android.libraries.mdm.api.MdmConfig
import java.net.URI

/**
 * Turns the raw key/value map an MDM pushes into an [MdmConfig].
 *
 * Kept free of Android types so it can be unit tested directly. It is deliberately lenient: MDM
 * consoles are hand-edited and routinely deliver the wrong type - a checkbox arrives as the string
 * "true", a number as "30 " with a stray space. Anything that cannot be understood falls back to the
 * default for that key rather than failing the whole configuration, because a device that refuses to
 * parse its policy is a device an administrator cannot fix remotely.
 */
object MdmConfigParser {
    fun parse(raw: Map<String, Any?>): MdmConfig {
        val default = MdmConfig.default
        return MdmConfig(
            homeserverUrl = parseHomeserverUrl(raw[MdmConfig.KEY_HOMESERVER_URL]) ?: default.homeserverUrl,
            allowRegistration = parseBoolean(raw[MdmConfig.KEY_ALLOW_REGISTRATION]) ?: default.allowRegistration,
            allowFileSend = parseBoolean(raw[MdmConfig.KEY_ALLOW_FILE_SEND]) ?: default.allowFileSend,
            autoLogoutMinutes = parseMinutes(raw[MdmConfig.KEY_AUTO_LOGOUT_MINUTES]) ?: default.autoLogoutMinutes,
        )
    }

    /**
     * Returns null - meaning "use the default" - for anything that is not a usable https URL.
     * A plain host is accepted and https:// is added, since that is what an administrator is most
     * likely to type. http:// is rejected outright: sending corporate messages in the clear because
     * of a typo in a console field is not a failure mode worth supporting.
     */
    internal fun parseHomeserverUrl(value: Any?): String? {
        val text = (value as? String)?.trim().orEmpty()
        if (text.isEmpty()) return null
        val withScheme = when {
            text.startsWith("https://", ignoreCase = true) -> "https://${text.substring(8)}"
            text.startsWith("http://", ignoreCase = true) -> return null
            text.contains("://") -> return null
            else -> "https://$text"
        }
        val uri = runCatching { URI(withScheme).parseServerAuthority() }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque || uri.rawAuthority == null) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        // URI reports -1 for both an absent port and some empty/malformed authorities. The strict
        // server-authority parse rejects non-numeric ports; explicitly reject an empty trailing one.
        if (uri.rawAuthority.endsWith(':')) return null
        if (uri.port != -1 && uri.port !in 1..65_535) return null
        val host = uri.host ?: return null
        if (!host.isValidHomeserverHost()) return null
        return withScheme.trimEnd('/')
    }

    private fun String.isValidHomeserverHost(): Boolean {
        val unwrapped = removePrefix("[").removeSuffix("]")
        // parseServerAuthority() has already validated a bracketed IPv6 literal.
        if (':' in unwrapped) return true
        if (length > 253 || !contains('.')) return false

        val labels = split('.')
        if (labels.all { label -> label.all(Char::isDigit) }) {
            return labels.size == 4 && labels.all { label ->
                label.isNotEmpty() && label.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
        return labels.all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { character -> character.isLetterOrDigit() || character == '-' }
        }
    }

    internal fun parseBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        is Int -> value != 0
        else -> null
    }

    /** Negative values are treated as "not set"; they would otherwise mean "log out immediately, always". */
    internal fun parseMinutes(value: Any?): Int? = when (value) {
        is Int -> value.takeIf { it >= 0 }
        is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        is String -> value.trim().toIntOrNull()?.takeIf { it >= 0 }
        else -> null
    }
}
