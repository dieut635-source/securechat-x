/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * The component Android hands device administration to.
 *
 * Its name is part of the provisioning command an operator types against a factory-reset handset,
 * so renaming or moving this class breaks every enrolment instruction ever written down. Note that
 * debug builds carry an `.debug` application id suffix, which means the component name differs
 * between the build an operator tests with and the one they deploy - see `docs/DEVICE-OWNER.md`.
 *
 * The class stays deliberately thin. Everything Android calls here happens on the main thread
 * during sensitive device lifecycle moments, and the useful behaviour lives in [SecureChatDeviceOwner]
 * where it can be tested without a device.
 */
class SecureChatDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // The single most useful line in a support transcript: it separates "provisioning never
        // ran" from "provisioning ran and something later removed it".
        Timber.i("SecureChat device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Timber.w("SecureChat device admin disabled - managed configuration can no longer be applied")
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, SecureChatDeviceAdminReceiver::class.java)
    }
}
