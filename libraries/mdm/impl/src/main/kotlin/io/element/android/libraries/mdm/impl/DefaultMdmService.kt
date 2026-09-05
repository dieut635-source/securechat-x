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
    private val _config = MutableStateFlow(readConfig().config)
    override val config: StateFlow<MdmConfig> = _config.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val read = readConfig()
            if (read.config != _config.value) {
                Timber.i("MDM configuration changed -> ${read.config.describe()}")
                _config.value = read.config
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
        // Close the startup race between the StateFlow's initial read and receiver registration.
        // Any change before registration is captured here; later changes are delivered to receiver.
        val startupRead = readConfig()
        _config.value = startupRead.config
        // In ra CẢ BỐN giá trị, không chỉ "có bị quản lý hay không".
        // Khi test trên máy thật, đây là cách duy nhất phân biệt "app đọc được MDM" với
        // "app đang dùng giá trị mặc định trùng với giá trị quản trị viên đặt" — hai thứ
        // nhìn từ giao diện thì giống hệt nhau. Không có giá trị nào ở đây là bí mật.
        Timber.i("MDM configuration loaded: managed=${startupRead.isManaged} ${startupRead.config.describe()}")
    }

    private fun readConfig(): RestrictionsRead {
        return try {
            val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
                ?: return unavailableRestrictions()
            val restrictions: Bundle = restrictionsManager.applicationRestrictions
                // A null snapshot is how Android represents "this app has no managed
                // restrictions" on some unmanaged/manual-install devices. It is different from a
                // missing service or a failed binder read, neither of which can be trusted.
                ?: return unmanagedRestrictions()
            @Suppress("DEPRECATION")
            val raw = restrictions.keySet().orEmpty().associateWith { key -> restrictions.get(key) }
            RestrictionsRead(
                config = MdmConfigParser.parse(raw),
                isManaged = raw.isNotEmpty(),
            )
        } catch (throwable: Throwable) {
            // Every step above crosses the Android/MDM boundary, including unparcelling Bundle values.
            // A broken or temporarily unavailable DPC must not crash startup or expose permissive defaults.
            Timber.w(throwable, "Could not read application restrictions; keeping restrictions pending")
            RestrictionsRead(
                config = MdmConfig.restrictionsPending,
                isManaged = null,
            )
        }
    }

    private fun unavailableRestrictions(): RestrictionsRead {
        Timber.w("Application restrictions are unavailable; keeping restrictions pending")
        return RestrictionsRead(
            config = MdmConfig.restrictionsPending,
            isManaged = null,
        )
    }

    private fun unmanagedRestrictions(): RestrictionsRead {
        Timber.i("No application restrictions were supplied; using the manual-install defaults")
        return RestrictionsRead(
            config = MdmConfig.default,
            isManaged = false,
        )
    }

    private data class RestrictionsRead(
        val config: MdmConfig,
        /** `null` means Android could not provide a trustworthy restrictions snapshot. */
        val isManaged: Boolean?,
    )
}
