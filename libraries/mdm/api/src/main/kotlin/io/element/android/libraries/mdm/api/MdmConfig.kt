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
    /** Homeserver the app signs in to. Key: `homeserver_url`. */
    val homeserverUrl: String,
    /** Whether "Create account" is offered. Key: `allow_registration`. */
    val allowRegistration: Boolean,
    /** Whether the user may send files, images, voice messages or shared content. Key: `allow_file_send`. */
    val allowFileSend: Boolean,
    /** Sign the user out after this many minutes in the background. 0 disables it. Key: `auto_logout_minutes`. */
    val autoLogoutMinutes: Int,
) {
    /**
     * Một dòng gọn để ghi log. Cả bốn khoá đều là chính sách của quản trị viên, không phải
     * dữ liệu cá nhân, nên in ra được. Dùng khi chẩn đoán trên máy thật:
     *
     *     adb logcat | grep "MDM configuration"
     */
    fun describe(): String =
        "homeserver_url=$homeserverUrl allow_registration=$allowRegistration " +
            "allow_file_send=$allowFileSend auto_logout_minutes=$autoLogoutMinutes"

    companion object {
        const val KEY_HOMESERVER_URL = "homeserver_url"
        const val KEY_ALLOW_REGISTRATION = "allow_registration"
        const val KEY_ALLOW_FILE_SEND = "allow_file_send"
        const val KEY_AUTO_LOGOUT_MINUTES = "auto_logout_minutes"

        const val DEFAULT_HOMESERVER_URL = "https://chat.securechat.com.au"

        /** What an unmanaged device gets, and the fallback for any key an administrator leaves unset. */
        val default = MdmConfig(
            homeserverUrl = DEFAULT_HOMESERVER_URL,
            allowRegistration = false,
            allowFileSend = true,
            autoLogoutMinutes = 0,
        )
    }
}
