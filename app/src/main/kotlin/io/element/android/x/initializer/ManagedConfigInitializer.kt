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
 * Publishes managed configuration before anything reads it.
 *
 * Ordering matters and is the only reason this runs so early. `DefaultMdmService` takes its first
 * reading when it is constructed; publishing afterwards relies on the framework broadcasting the
 * change back to the app that made it, which would leave the app running on the previous policy for
 * the rest of the launch if that broadcast never arrived. Writing first removes the dependency on
 * that behaviour entirely.
 *
 * On a handset this app does not manage, this does nothing at all.
 */
class ManagedConfigInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val bindings = context.bindings<SecureChatBindings>()
        bindings.secureChatPolicyPublisher().publish()
        // Bắt đầu hỏi lệnh ngay từ lúc khởi động: một chiếc máy đã mất thì mỗi
        // phút chờ là một phút dữ liệu còn nằm trong tay người khác.
        bindings.secureChatRemoteCommandPoller().start()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
