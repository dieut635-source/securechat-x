/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.impl.setup.pin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.di.SessionScope

@ContributesNode(SessionScope::class)
@AssistedInject
class SetupPinNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val presenter: SetupPinPresenter,
) : Node(buildContext, plugins = plugins) {
    /** [isDuressStep] chooses which of the two codes this screen is collecting. */
    data class Inputs(val isDuressStep: Boolean = false) : NodeInputs

    interface Callback : Plugin {
        fun onDuressPinCreated()
    }

    private val nodeInputs: Inputs = inputs<Inputs>()

    @Composable
    override fun View(modifier: Modifier) {
        if (nodeInputs.isDuressStep) {
            presenter.onDuressPinCreated = {
                plugins.filterIsInstance<Callback>().forEach { it.onDuressPinCreated() }
            }
        }
        val state = presenter.present(isDuressStep = nodeInputs.isDuressStep)
        SetupPinView(
            state = state,
            onBackClick = this::navigateUp,
            modifier = modifier
        )
    }
}
