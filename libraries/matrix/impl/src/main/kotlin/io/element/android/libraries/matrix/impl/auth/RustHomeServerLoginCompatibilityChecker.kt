/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.auth.HomeServerLoginCompatibilityChecker
import io.element.android.libraries.matrix.impl.ClientBuilderProvider
import timber.log.Timber

@ContributesBinding(AppScope::class)
class RustHomeServerLoginCompatibilityChecker(
    private val clientBuilderProvider: ClientBuilderProvider,
    private val enterpriseService: EnterpriseService,
) : HomeServerLoginCompatibilityChecker {
    override suspend fun check(url: String): Result<Boolean> = runCatchingExceptions {
        if (!enterpriseService.isAllowedToConnectToHomeserver(url)) {
            Timber.w("Refusing compatibility check for a homeserver outside the SecureChat allowlist")
            return@runCatchingExceptions false
        }
        clientBuilderProvider.provide()
            .inMemoryStore()
            .serverNameOrHomeserverUrl(url)
            .build()
            .use {
                it.homeserverLoginDetails()
            }
            .use {
                Timber.d("Homeserver $url | Password authentication available: ${it.supportsPasswordLogin()}")
                it.supportsPasswordLogin()
            }
    }
}
