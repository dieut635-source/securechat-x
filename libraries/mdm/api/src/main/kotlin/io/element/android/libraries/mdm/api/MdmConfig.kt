/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.api

/**
 * The SecureChat settings an administrator can push with Android Enterprise managed configurations
 * (Samsung Knox Manage, in our deployment).
 *
 * The four key names are part of the contract with Knox and must not be renamed: they are typed into
 * the Knox console by hand, and renaming one silently reverts that setting to its default on every
 * managed device.
 */
data class MdmConfig(
    /** Homeserver the app signs in to. The compatibility key cannot override the locked URL. */
    val homeserverUrl: String,
    /** Registration is always false in the closed build; the compatibility key cannot enable it. */
    val allowRegistration: Boolean,
    /** Whether the user may send files, images, voice messages or shared content. Key: `allow_file_send`. */
    val allowFileSend: Boolean,
    /** Managed restrictions are pending, unreadable, or malformed and therefore cannot be trusted. */
    val restrictionsPending: Boolean = false,
) {
    /**
     * Một dòng gọn để ghi log. Cả bốn khoá đều là chính sách của quản trị viên, không phải
     * dữ liệu cá nhân, nên in ra được. Dùng khi chẩn đoán trên máy thật:
     *
     *     adb logcat | grep "MDM configuration"
     */
    fun describe(): String =
        "homeserver_url=$homeserverUrl allow_registration=$allowRegistration " +
            "allow_file_send=$allowFileSend " +
            "restrictions_pending=$restrictionsPending"

    companion object {
        const val KEY_HOMESERVER_URL = "homeserver_url"
        const val KEY_ALLOW_REGISTRATION = "allow_registration"
        const val KEY_ALLOW_FILE_SEND = "allow_file_send"
        // System-owned sentinel from UserManager.KEY_RESTRICTIONS_PENDING. It is intentionally not
        // declared in app_restrictions.xml because administrators must not configure it manually.
        const val KEY_RESTRICTIONS_PENDING = "restrictions_pending"

        const val DEFAULT_HOMESERVER_URL = "https://chat.securechat.com.au"

        /**
         * The non-relaxable security baseline. Managed configuration may further restrict file
         * sending or set auto logout, but cannot redirect authentication or enable registration.
         */
        val default = MdmConfig(
            homeserverUrl = DEFAULT_HOMESERVER_URL,
            allowRegistration = false,
            allowFileSend = true,
            restrictionsPending = false,
        )

        /** Fail-closed state until Android supplies a complete, valid restrictions snapshot. */
        val restrictionsPending = default.copy(
            allowFileSend = false,
            restrictionsPending = true,
        )
    }
}
