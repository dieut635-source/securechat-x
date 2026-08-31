/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.lockscreen.impl.setup

import android.os.Parcelable
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.newRoot
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.features.lockscreen.impl.biometric.BiometricAuthenticatorManager
import io.element.android.features.lockscreen.impl.pin.DefaultPinCodeManagerCallback
import io.element.android.features.lockscreen.impl.pin.PinCodeManager
import io.element.android.features.lockscreen.impl.setup.biometric.SetupBiometricNode
import io.element.android.features.lockscreen.impl.setup.pin.SetupPinNode
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.di.SessionScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

@ContributesNode(SessionScope::class)
@AssistedInject
class LockScreenSetupFlowNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val pinCodeManager: PinCodeManager,
    val biometricAuthenticatorManager: BiometricAuthenticatorManager,
) : BaseFlowNode<LockScreenSetupFlowNode.NavTarget>(
    backstack = BackStack(
        initialElement = NavTarget.Pin,
        savedStateMap = buildContext.savedStateMap,
    ),
    buildContext = buildContext,
    plugins = plugins,
) {
    interface Callback : Plugin {
        fun onSetupDone()
    }

    private val callback: Callback = callback()

    sealed interface NavTarget : Parcelable {
        @Parcelize
        data object Pin : NavTarget

        /**
         * The emergency code. Mandatory: every device in the fleet must have one, so this sits
         * between the everyday code and biometrics rather than hiding in settings.
         */
        @Parcelize
        data object DuressPin : NavTarget

        @Parcelize
        data object Biometric : NavTarget
    }

    private val pinCodeManagerCallback = object : DefaultPinCodeManagerCallback() {
        override fun onPinCodeCreated() {
            backstack.newRoot(NavTarget.DuressPin)
        }
    }

    private fun onSetupComplete() {
        if (biometricAuthenticatorManager.hasAvailableAuthenticator) {
            backstack.newRoot(NavTarget.Biometric)
        } else {
            callback.onSetupDone()
        }
    }

    init {
        lifecycle.subscribe(
            onCreate = {
                pinCodeManager.addCallback(pinCodeManagerCallback)
                // Killed between the two steps, the device would be left with an everyday code and
                // no emergency one, and nothing would ever ask again. Resume where it stopped.
                lifecycleScope.launch {
                    if (pinCodeManager.hasPinCode().first() && !pinCodeManager.hasDuressPinCode()) {
                        backstack.newRoot(NavTarget.DuressPin)
                    }
                }
            },
            onDestroy = {
                pinCodeManager.removeCallback(pinCodeManagerCallback)
            }
        )
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node {
        return when (navTarget) {
            NavTarget.Pin -> {
                createNode<SetupPinNode>(buildContext, plugins = listOf(SetupPinNode.Inputs(isDuressStep = false)))
            }
            NavTarget.DuressPin -> {
                val duressCallback = object : SetupPinNode.Callback {
                    override fun onDuressPinCreated() {
                        onSetupComplete()
                    }
                }
                createNode<SetupPinNode>(
                    buildContext,
                    plugins = listOf(SetupPinNode.Inputs(isDuressStep = true), duressCallback),
                )
            }
            NavTarget.Biometric -> {
                val callback = object : SetupBiometricNode.Callback {
                    override fun onBiometricSetupDone() {
                        callback.onSetupDone()
                    }
                }
                createNode<SetupBiometricNode>(buildContext, plugins = listOf(callback))
            }
        }
    }

    @Composable
    override fun View(modifier: Modifier) {
        BackstackView()
    }
}
