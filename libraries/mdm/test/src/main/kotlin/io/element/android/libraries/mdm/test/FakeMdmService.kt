/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.test

import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.api.MdmService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An [MdmService] whose configuration the test controls, so a test can stand in for an administrator
 * pushing a policy - including changing it mid-test with [emit].
 */
class FakeMdmService(
    initialConfig: MdmConfig = MdmConfig.default,
) : MdmService {
    private val mutableConfig = MutableStateFlow(initialConfig)
    override val config: StateFlow<MdmConfig> = mutableConfig

    fun emit(config: MdmConfig) {
        mutableConfig.value = config
    }
}
