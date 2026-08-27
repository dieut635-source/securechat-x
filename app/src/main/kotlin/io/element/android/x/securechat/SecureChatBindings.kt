/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.features.logout.api.LogoutUseCase
import io.element.android.libraries.mdm.api.MdmService

@ContributesTo(AppScope::class)
interface SecureChatBindings {
    fun mdmService(): MdmService
    fun logoutUseCase(): LogoutUseCase
}
