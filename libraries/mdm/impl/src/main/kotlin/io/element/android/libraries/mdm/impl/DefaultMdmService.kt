/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.impl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.api.MdmService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

@ContributesBinding(scope = AppScope::class)
@SingleIn(AppScope::class)
class DefaultMdmService(
    @ApplicationContext private val context: Context,
) : MdmService {
    private val restrictionsManager: RestrictionsManager? =
        context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager

    private val _config = MutableStateFlow(readConfig())
    override val config: StateFlow<MdmConfig> = _config.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val new = readConfig()
            if (new != _config.value) {
                Timber.i("MDM configuration changed")
                _config.value = new
            }
        }
    }

    init {
        // The administrator can change the policy while the app is running, so keep listening.
        // Not exported: the system is the only sender of this broadcast.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Timber.i("MDM configuration loaded: managed=${restrictionsManager?.applicationRestrictions?.isEmpty == false}")
    }

    private fun readConfig(): MdmConfig {
        val restrictions: Bundle = try {
            restrictionsManager?.applicationRestrictions ?: Bundle.EMPTY
        } catch (throwable: Throwable) {
            // Reading restrictions goes through the system; a misbehaving MDM must not stop the app booting.
            Timber.w(throwable, "Could not read application restrictions, falling back to defaults")
            Bundle.EMPTY
        }
        @Suppress("DEPRECATION")
        val raw = restrictions.keySet().orEmpty().associateWith { key -> restrictions.get(key) }
        return MdmConfigParser.parse(raw)
    }
}
