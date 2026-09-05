/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
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
     * What this handset will actually let a device owner switch off.
     *
     * Read-only, and deliberately so: the answer to "can we turn USB data off" is a property of
     * the hardware and the OEM's build, not of the documentation. Samsung ships its own device
     * policy stack, and `canUsbDataSignalingBeDisabled()` returning false on a given model is a
     * real outcome that no amount of reading the AOSP docs will reveal.
     *
     * Nothing here changes the device. It exists so the decision to ship a restriction is taken
     * against a measurement.
     */
    fun capabilities(): Map<String, String>

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
        // wipeDevice(), NOT wipeData(). They are not synonyms and the difference is the whole
        // feature.
        //
        // wipeData() means "wipe this user". Called by a device owner running as user 0 it reaches
        // the framework as a request to remove the system user, and the framework refuses:
        //
        //   java.lang.IllegalStateException: User 0 is a system user and cannot be removed
        //
        // Nothing about that is visible from the API surface, from the docs, or from any unit test:
        // the call compiles, the permission is held, the policy is granted, and the exception only
        // appears on a real handset. It cost two failed wipes on hardware to find, the first of
        // which was misread as success because the phone had merely been unplugged.
        //
        // wipeDevice() is the device-owner factory reset, added in Android 14. Below that, wipeData
        // was the only option and did behave as a full reset, so the fallback is correct rather
        // than merely tolerated.
        //
        // Flags stay 0: user data only. External storage and factory-reset protection are
        // deliberately left alone - this app's job is to destroy its own custody of data, not to
        // brick hardware or lock an owner out of a handset they paid for.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Timber.w("Wiping device via wipeDevice()")
            dpm.wipeDevice(0)
        } else {
            Timber.w("Wiping device via legacy wipeData()")
            @Suppress("DEPRECATION")
            dpm.wipeData(0)
        }
    }

    override fun relinquishDeviceOwner(): Result<Unit> = runCatchingExceptions {
        val dpm = manager ?: error("DevicePolicyManager unavailable")
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    override fun capabilities(): Map<String, String> {
        val dpm = manager ?: return mapOf("error" to "DevicePolicyManager unavailable")
        val report = linkedMapOf(
            "sdkInt" to Build.VERSION.SDK_INT.toString(),
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "isDeviceOwner" to isDeviceOwner.toString(),
        )
        // Mỗi phép đo bọc riêng: một API ném lỗi trên máy này không được làm mất
        // kết quả của những API còn lại. Báo cáo thiếu một dòng là báo cáo sai.
        report["usbDataSignalingCanBeDisabled"] = probe {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dpm.canUsbDataSignalingBeDisabled().toString()
            } else {
                "cần API 31+"
            }
        }
        report["usbDataSignalingEnabled"] = probe {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dpm.isUsbDataSignalingEnabled.toString()
            } else {
                "cần API 31+"
            }
        }
        report["locationEnabled"] = probe {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.isLocationEnabled.toString()
        }
        report["activeUserRestrictions"] = probe {
            val um = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            val bundle = um.userRestrictions
            bundle.keySet().filter { bundle.getBoolean(it) }.sorted().joinToString(",").ifEmpty { "(không có)" }
        }
        return report
    }

    private inline fun probe(block: () -> String): String =
        try {
            block()
        } catch (throwable: Throwable) {
            "không đo được: ${throwable.javaClass.simpleName}"
        }
}
