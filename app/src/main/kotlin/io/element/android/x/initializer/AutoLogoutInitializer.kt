/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.initializer

import android.content.Context
import androidx.startup.Initializer
import io.element.android.libraries.architecture.bindings
import io.element.android.x.securechat.AutoLogoutObserver
import io.element.android.x.securechat.SecureChatBindings

class AutoLogoutInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val bindings = context.bindings<SecureChatBindings>()
        AutoLogoutObserver(
            context = context,
            mdmService = bindings.mdmService(),
            logoutUseCase = bindings.logoutUseCase(),
        ).start()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
