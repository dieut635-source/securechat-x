/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl

import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.login.api.LoginIntentResolver
import io.element.android.features.login.api.LoginParams

@ContributesBinding(AppScope::class)
class DefaultLoginIntentResolver : LoginIntentResolver {
    override fun parse(uriString: String): LoginParams? {
        val uri = uriString.toUri()
        if (uri.scheme != "https") return null
        if (uri.host != SECURECHAT_HOST) return null
        if (uri.path.orEmpty().trimEnd('/') != SECURECHAT_LOGIN_PATH) return null
        val accountProvider = uri.getQueryParameter("account_provider") ?: return null
        val loginHint = uri.getQueryParameter("login_hint")
        return LoginParams(
            accountProvider = accountProvider,
            loginHint = loginHint,
        )
    }

    private companion object {
        const val SECURECHAT_HOST = "chat.securechat.com.au"
        const val SECURECHAT_LOGIN_PATH = "/securechat"
    }
}
