/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.pip

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding

interface PipSupportProvider {
    @ChecksSdkIntAtLeast(Build.VERSION_CODES.O)
    fun isPipSupported(): Boolean
}

@ContributesBinding(AppScope::class)
class DefaultPipSupportProvider : PipSupportProvider {
    // PiP can keep participant video visible after the application PIN has locked. SecureChat's
    // closed-distribution security profile therefore disables it independently of OS support.
    override fun isPipSupported(): Boolean = false
}
