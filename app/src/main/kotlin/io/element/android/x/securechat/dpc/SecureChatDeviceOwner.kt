/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.mapCatchingExceptions
import io.element.android.libraries.mdm.api.MdmConfig
import timber.log.Timber

/**
 * SecureChat acting as its own device policy controller.
 *
 * Headwind MDM cannot deliver managed configuration at all - `setApplicationRestrictions` appears
 * nowhere in its source, in any edition - so the three keys the app already knows how to read have
 * never had anything to supply them. This class is the supply side, and it removes the need for a
 * third-party MDM to hold device owner on the handsets this app runs on.
 *
 * Two capabilities live here and they are treated very differently. Applying configuration is
 * routine and idempotent, so it just runs. Wiping a handset is the one irreversible thing this app
 * can do, so it has to pass [wipeAuthority] first, and this class refuses on its own account as
 * well: no device owner means no wipe, whatever the caller believes.
 */
@SingleIn(AppScope::class)
@Inject
class SecureChatDeviceOwner(
    private val gateway: DevicePolicyGateway,
) {
    /**
     * Held here rather than injected separately so arming and spending always happen against the
     * same instance. An authority handed out per call site would be no authority at all.
     */
    val wipeAuthority = DeviceWipeAuthority()

    /** Reasons a device-level action was refused, all of which mean nothing was changed. */
    sealed class Refusal(message: String) : Exception(message) {
        data object NotDeviceOwner : Refusal("this app does not hold device owner on this handset")
    }

    /** What [applyManagedConfiguration] did. */
    enum class ApplyOutcome {
        /** The published policy differed from the intended one and has been replaced. */
        Applied,

        /** The published policy already matched. Nothing was written and nothing was broadcast. */
        Unchanged,
    }

    val isDeviceOwner: Boolean get() = gateway.isDeviceOwner

    /**
     * Publish [config] as this app's own managed configuration.
     *
     * The app reads these back through the standard `RestrictionsManager` path, the same one any
     * conforming Android Enterprise MDM would write to. Keeping the standard API as the only reader
     * means SecureChat is not locked to being its own DPC: a real Android Enterprise MDM can take
     * over later without touching a line of the reading code.
     */
    fun applyManagedConfiguration(config: MdmConfig): Result<ApplyOutcome> {
        if (!gateway.isDeviceOwner) {
            return Result.failure(Refusal.NotDeviceOwner)
        }
        // restrictions_pending is owned by the Android framework and deliberately never written
        // here; sending it would let this app forge a state the system uses to mean "the admin has
        // not answered yet".
        val desired = mapOf<String, Any>(
            MdmConfig.KEY_HOMESERVER_URL to config.homeserverUrl,
            MdmConfig.KEY_ALLOW_REGISTRATION to config.allowRegistration,
            MdmConfig.KEY_ALLOW_FILE_SEND to config.allowFileSend,
        )
        // A failed read must not be mistaken for "already correct". Writing an identical policy
        // costs one broadcast; skipping a needed write leaves the handset running on whatever it
        // had, which is the failure that matters. So an unreadable current policy means write.
        val published = gateway.readRestrictions().getOrElse { throwable ->
            Timber.w(throwable, "Could not read the published configuration; republishing")
            null
        }
        if (published == desired) {
            return Result.success(ApplyOutcome.Unchanged)
        }
        return gateway.applyRestrictions(desired)
            .map { ApplyOutcome.Applied }
            .onSuccess { Timber.i("Managed configuration applied: ${config.describe()}") }
            .onFailure { Timber.w(it, "Could not apply managed configuration") }
    }

    /**
     * Ask for permission to wipe this handset, stating why. See [DeviceWipeAuthority].
     *
     * Refused outright when this app is not the device owner, so that an operator finds out at the
     * moment they ask rather than after they believe a wipe is under way.
     */
    fun armWipe(reason: String): Result<String> {
        if (!gateway.isDeviceOwner) {
            Timber.w("Device wipe arming refused: not device owner")
            return Result.failure(Refusal.NotDeviceOwner)
        }
        return wipeAuthority.arm(reason)
    }

    /**
     * Destroy user data on this handset. There is no way back from a success.
     *
     * Device owner is re-checked here rather than trusted from [armWipe]: the two calls are
     * separated in time, and device owner can be taken away in between.
     */
    fun wipeDevice(challenge: String, reason: String): Result<Unit> {
        // Logged before anything is checked or destroyed. On a handset that is about to lose
        // everything, a line written after the fact is a line that was never written.
        Timber.w("Device wipe attempt: $reason")
        if (!gateway.isDeviceOwner) {
            Timber.w("Device wipe refused: not device owner")
            // The authority is still spent. An attempt is an attempt, and leaving a live challenge
            // behind a failed wipe is exactly the loaded gun this class exists to prevent.
            wipeAuthority.disarm()
            return Result.failure(Refusal.NotDeviceOwner)
        }
        return wipeAuthority.consume(challenge, reason)
            .onFailure { Timber.w("Device wipe refused: ${it.message}") }
            .mapCatchingExceptions { gateway.wipeDevice().getOrThrow() }
    }
}
