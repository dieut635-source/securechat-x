/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import timber.log.Timber

/**
 * The whole of the Android device-administration surface this app touches, behind one seam.
 *
 * It exists so [SecureChatDeviceOwner] - which decides *whether* to wipe a handset - can be tested
 * exhaustively against a fake, on a laptop, without a device to lose. The real implementation below
 * is kept free of decisions for exactly that reason: everything here is a call across the Android
 * boundary, and nothing here chooses to do anything.
 */
interface DevicePolicyGateway {
    /**
     * Whether this app currently holds device owner. Anything that changes the device depends on
     * it, and it is false on every handset where provisioning was not run.
     */
    val isDeviceOwner: Boolean

    /** Publish managed configuration for this app's own package. */
    fun applyRestrictions(restrictions: Map<String, Any>): Result<Unit>

    /**
     * Read back the managed configuration currently published for this app.
     *
     * Used to avoid rewriting an identical policy on every launch. Each write makes the framework
     * broadcast a configuration change to the app, so republishing unchanged values is not free:
     * it churns state that other components react to, for no gain.
     */
    fun readRestrictions(): Result<Map<String, Any>>

    /** Destroy user data on this handset. Irreversible. */
    fun wipeDevice(): Result<Unit>

    /**
     * Give up device owner without a factory reset.
     *
     * The escape hatch for the risk this app takes on by being the device owner itself: if a
     * released build cannot start, the component holding device policy is a crashing app, and
     * without this the only way back is wiping every handset in the field. Deliberately never
     * called by the app itself.
     */
    fun relinquishDeviceOwner(): Result<Unit>
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidDevicePolicyGateway(
    @ApplicationContext private val context: Context,
) : DevicePolicyGateway {
    private val manager: DevicePolicyManager?
        get() = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    override val isDeviceOwner: Boolean
        get() = try {
            manager?.isDeviceOwnerApp(context.packageName) == true
        } catch (throwable: Throwable) {
            // Fail closed. Every caller reads this to decide whether it is allowed to change the
            // device, so an unreadable answer has to mean "no", never "probably".
            Timber.w(throwable, "Could not determine device owner status; assuming not owner")
            false
        }

    override fun applyRestrictions(restrictions: Map<String, Any>): Result<Unit> = runCatchingExceptions {
        val dpm = manager ?: error("DevicePolicyManager unavailable")
        val bundle = Bundle().apply {
            restrictions.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    // Anything else would arrive at RestrictionsManager as a type the parser does
                    // not expect. Refusing loudly here beats shipping a silently ignored key.
                    else -> error("Unsupported restriction type for '$key': ${value::class.java.name}")
                }
            }
        }
        dpm.setApplicationRestrictions(
            SecureChatDeviceAdminReceiver.componentName(context),
            context.packageName,
            bundle,
        )
    }

    override fun readRestrictions(): Result<Map<String, Any>> = runCatchingExceptions {
        val dpm = manager ?: error("DevicePolicyManager unavailable")
        val bundle = dpm.getApplicationRestrictions(
            SecureChatDeviceAdminReceiver.componentName(context),
            context.packageName,
        )
        @Suppress("DEPRECATION")
        bundle.keySet().orEmpty().mapNotNull { key ->
            bundle.get(key)?.let { key to it }
        }.toMap()
    }

    override fun wipeDevice(): Result<Unit> = runCatchingExceptions {
        val dpm = manager ?: error("DevicePolicyManager unavailable")
        // Flags 0: user data only. External storage and factory-reset protection are deliberately
        // left alone - this app's job is to destroy its own custody of data, not to brick hardware
        // or lock an owner out of a handset they paid for.
        dpm.wipeData(0)
    }

    override fun relinquishDeviceOwner(): Result<Unit> = runCatchingExceptions {
        val dpm = manager ?: error("DevicePolicyManager unavailable")
        dpm.clearDeviceOwnerApp(context.packageName)
    }
}
