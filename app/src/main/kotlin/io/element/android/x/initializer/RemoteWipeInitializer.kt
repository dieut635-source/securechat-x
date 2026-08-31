/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.initializer

import android.content.Context
import androidx.startup.Initializer
import io.element.android.libraries.architecture.bindings
import io.element.android.x.securechat.SecureChatBindings

/**
 * Starts watching for a revoked session as early as possible, so a wipe left half-finished by the
 * app being killed resumes on the next launch.
 */
class RemoteWipeInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        context.bindings<SecureChatBindings>().secureChatRemoteWipe().start()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
