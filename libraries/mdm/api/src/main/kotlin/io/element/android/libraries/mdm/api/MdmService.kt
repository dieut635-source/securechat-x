/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Reads the managed configuration an administrator has pushed to this device.
 *
 * The value updates while the app is running: Android broadcasts a change when the administrator
 * edits the configuration, so callers should read [config] as a flow rather than once at startup.
 */
interface MdmService {
    val config: StateFlow<MdmConfig>
}
