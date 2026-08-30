/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.impl

import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.api.areHomeserverUrlsEquivalent
import java.net.URI

/**
 * Turns the raw key/value map an MDM pushes into an [MdmConfig].
 *
 * Kept free of Android types so it can be unit tested directly. A missing key inherits the
 * documented manual-install default. A present key must have exactly the Android type and value
 * declared in `app_restrictions.xml`; otherwise the entire snapshot stays fail-closed until the
 * administrator fixes it. This prevents a malformed partial policy from silently becoming more
 * permissive than intended.
 */
object MdmConfigParser {
    fun parse(raw: Map<String, Any?>): MdmConfig {
        val default = MdmConfig.default

        val pending = parseOptional(
            raw = raw,
            key = MdmConfig.KEY_RESTRICTIONS_PENDING,
            default = false,
            parser = ::parseBoolean,
        ) ?: return MdmConfig.restrictionsPending
        if (pending) {
            // Do not briefly expose permissive defaults while a DPC has explicitly announced that
            // the final application restrictions are still in flight.
            return MdmConfig.restrictionsPending
        }

        val homeserverUrl = parseOptional(
            raw = raw,
            key = MdmConfig.KEY_HOMESERVER_URL,
            default = default.homeserverUrl,
            parser = ::parseHomeserverUrl,
        ) ?: return MdmConfig.restrictionsPending
        val allowRegistration = parseOptional(
            raw = raw,
            key = MdmConfig.KEY_ALLOW_REGISTRATION,
            default = default.allowRegistration,
            parser = ::parseBoolean,
        ) ?: return MdmConfig.restrictionsPending
        // Registration is deliberately non-relaxable in this closed-distribution build. Treat an
        // attempted override as an invalid policy instead of pretending it was applied.
        if (allowRegistration) return MdmConfig.restrictionsPending
        val allowFileSend = parseOptional(
            raw = raw,
            key = MdmConfig.KEY_ALLOW_FILE_SEND,
            default = default.allowFileSend,
            parser = ::parseBoolean,
        ) ?: return MdmConfig.restrictionsPending
        val autoLogoutMinutes = parseOptional(
            raw = raw,
            key = MdmConfig.KEY_AUTO_LOGOUT_MINUTES,
            default = default.autoLogoutMinutes,
            parser = ::parseMinutes,
        ) ?: return MdmConfig.restrictionsPending
        // A server-bound single session cannot safely be recreated by the user. Automatic logout
        // is therefore non-relaxable: local PIN lock is the only background access control.
        if (autoLogoutMinutes != 0) return MdmConfig.restrictionsPending

        return MdmConfig(
            homeserverUrl = homeserverUrl,
            allowRegistration = allowRegistration,
            allowFileSend = allowFileSend,
            autoLogoutMinutes = autoLogoutMinutes,
            restrictionsPending = false,
        )
    }

    private inline fun <T> parseOptional(
        raw: Map<String, Any?>,
        key: String,
        default: T,
        parser: (Any?) -> T?,
    ): T? = if (raw.containsKey(key)) parser(raw[key]) else default

    /**
     * Accepts only URL spellings that identify the locked SecureChat homeserver. Returning the
     * canonical value prevents case, default-port and trailing-slash differences from leaking into
     * session policy. Any other valid HTTPS server still fails closed.
     */
    internal fun parseHomeserverUrl(value: Any?): String? {
        val text = (value as? String)?.trim().orEmpty()
        // Unlike interactive login input, an MDM policy must be explicit: accepting a bare host and
        // adding a scheme would hide a provisioning error from the administrator.
        if (!text.startsWith("https://", ignoreCase = true)) return null
        val uri = try {
            URI(text).parseServerAuthority()
        } catch (_: Exception) {
            return null
        }
        // java.net.URI normalizes an empty trailing port ("host:") to no port. Reject the
        // ambiguous spelling before canonical comparison instead of silently accepting it.
        if (uri.rawAuthority?.endsWith(':') != false) return null
        // Only the origin is a valid homeserver policy. One conventional trailing slash is
        // normalized, but paths and repeated slashes are rejected rather than reinterpreted.
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") return null
        return MdmConfig.DEFAULT_HOMESERVER_URL.takeIf {
            areHomeserverUrlsEquivalent(text, MdmConfig.DEFAULT_HOMESERVER_URL)
        }
    }

    /** Android `restrictionType="bool"` values must arrive as a real Bundle Boolean. */
    internal fun parseBoolean(value: Any?): Boolean? = value as? Boolean

    /** Android `restrictionType="integer"` values must be a bounded Bundle Int. */
    internal fun parseMinutes(value: Any?): Int? =
        (value as? Int)?.takeIf { it in 0..MAX_AUTO_LOGOUT_MINUTES }

    /** Avoid accepting an accidentally enormous timeout that is operationally equivalent to off. */
    internal const val MAX_AUTO_LOGOUT_MINUTES = 43_200 // 30 days
}
