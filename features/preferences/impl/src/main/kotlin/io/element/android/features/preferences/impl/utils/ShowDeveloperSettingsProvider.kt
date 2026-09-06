/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.utils

import dev.zacsweers.metro.Inject
import io.element.android.libraries.ui.utils.MultipleTapToUnlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Inject
class ShowDeveloperSettingsProvider {
    companion object {
        const val DEVELOPER_SETTINGS_COUNTER = 7
    }

    private val multipleTapToUnlock = MultipleTapToUnlock(DEVELOPER_SETTINGS_COUNTER)

    /**
     * SecureChat ships without developer options, in every build type.
     *
     * They were already unreachable in release. The reason to close them in debug as well is that
     * the debug build is what this product is validated on before it goes out, and a build that
     * differs from the shipped one in security-relevant ways cannot validate it: a QA pass on debug
     * both misses real problems and raises ones that do not exist in production. Whichever build is
     * being tested should be the build that ships.
     *
     * What is behind the screen is not a set of diagnostics. It can point calls at an arbitrary
     * server, turn on content-level logging, index message bodies for search, weaken the
     * end-to-end encryption policy, and enable QR sign-in - which alone would let a second handset
     * join a session the server-side one-device rule exists to prevent.
     *
     * A developer who genuinely needs the screen can flip this constant locally; that is a
     * deliberate, visible act rather than something a user can reach by tapping seven times.
     */
    private val isDeveloperBuild = false

    private val _showDeveloperSettings = MutableStateFlow(isDeveloperBuild)
    val showDeveloperSettings: StateFlow<Boolean> = _showDeveloperSettings

    fun unlockDeveloperSettings(scope: CoroutineScope) {
        // Developer settings expose diagnostic logging, crash tools, and endpoint overrides.
        // They must never be unlockable in a production build, even when a user knows the
        // historical multi-tap gesture.
        if (!isDeveloperBuild) return
        if (multipleTapToUnlock.unlock(scope)) {
            _showDeveloperSettings.value = true
        }
    }
}
