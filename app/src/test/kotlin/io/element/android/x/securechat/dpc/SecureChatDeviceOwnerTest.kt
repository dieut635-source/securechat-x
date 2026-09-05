/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mdm.api.MdmConfig
import org.junit.Test

/**
 * What the app is and is not allowed to do to a handset.
 *
 * The fake gateway records calls instead of making them, so "did this wipe a device" is an
 * assertion rather than a hope. Most of what follows checks that it did *not*.
 */
class SecureChatDeviceOwnerTest {
    private class FakeDevicePolicyGateway(
        override var isDeviceOwner: Boolean = true,
        private val applyResult: Result<Unit> = Result.success(Unit),
        private val wipeResult: Result<Unit> = Result.success(Unit),
    ) : DevicePolicyGateway {
        var wipeCount = 0
        var relinquishCount = 0
        var lastRestrictions: Map<String, Any>? = null

        var published: Map<String, Any>? = null
        var applyCount = 0
        var readFails = false

        override fun applyRestrictions(restrictions: Map<String, Any>): Result<Unit> {
            applyCount++
            lastRestrictions = restrictions
            return applyResult.onSuccess { published = restrictions }
        }

        override fun readRestrictions(): Result<Map<String, Any>> =
            if (readFails) Result.failure(IllegalStateException("binder died")) else Result.success(published.orEmpty())

        override fun wipeDevice(): Result<Unit> {
            wipeCount++
            return wipeResult
        }

        override fun capabilities(): Map<String, String> = emptyMap()


        override fun relinquishDeviceOwner(): Result<Unit> {
            relinquishCount++
            return Result.success(Unit)
        }
    }

    private val reason = "lost handset, ticket 4471"

    private fun owner(gateway: FakeDevicePolicyGateway) = SecureChatDeviceOwner(gateway = gateway)

    // --- wiping -----------------------------------------------------------------------------

    @Test
    fun `an authorised wipe reaches the device`() {
        val gateway = FakeDevicePolicyGateway()
        val owner = owner(gateway)
        val challenge = owner.armWipe(reason).getOrThrow()

        val result = owner.wipeDevice(challenge, reason)

        assertThat(result.isSuccess).isTrue()
        assertThat(gateway.wipeCount).isEqualTo(1)
    }

    @Test
    fun `an unauthorised wipe never reaches the device`() {
        val gateway = FakeDevicePolicyGateway()

        val result = owner(gateway).wipeDevice("any-challenge", reason)

        assertThat(result.isFailure).isTrue()
        assertThat(gateway.wipeCount).isEqualTo(0)
    }

    @Test
    fun `without device owner nothing can be wiped`() {
        val gateway = FakeDevicePolicyGateway(isDeviceOwner = false)
        val owner = owner(gateway)

        val armed = owner.armWipe(reason)
        val wiped = owner.wipeDevice("any-challenge", reason)

        assertThat(armed.exceptionOrNull()).isInstanceOf(SecureChatDeviceOwner.Refusal.NotDeviceOwner::class.java)
        assertThat(wiped.exceptionOrNull()).isInstanceOf(SecureChatDeviceOwner.Refusal.NotDeviceOwner::class.java)
        assertThat(gateway.wipeCount).isEqualTo(0)
    }

    @Test
    fun `losing device owner between arming and wiping stops the wipe`() {
        // Device owner is not a fact that stays true. It is re-read at the moment it matters.
        val gateway = FakeDevicePolicyGateway(isDeviceOwner = true)
        val owner = owner(gateway)
        val challenge = owner.armWipe(reason).getOrThrow()

        gateway.isDeviceOwner = false
        val result = owner.wipeDevice(challenge, reason)

        assertThat(result.exceptionOrNull()).isInstanceOf(SecureChatDeviceOwner.Refusal.NotDeviceOwner::class.java)
        assertThat(gateway.wipeCount).isEqualTo(0)
    }

    @Test
    fun `a wipe refused for lack of device owner does not leave live authority behind`() {
        val gateway = FakeDevicePolicyGateway(isDeviceOwner = true)
        val owner = owner(gateway)
        val challenge = owner.armWipe(reason).getOrThrow()

        gateway.isDeviceOwner = false
        owner.wipeDevice(challenge, reason)
        // Device owner comes back - re-provisioning, a race, anything. The old challenge must be
        // dead, or a refusal has quietly become a wipe waiting to happen.
        gateway.isDeviceOwner = true
        val retried = owner.wipeDevice(challenge, reason)

        assertThat(retried.isFailure).isTrue()
        assertThat(gateway.wipeCount).isEqualTo(0)
    }

    @Test
    fun `a failure inside Android is reported and not swallowed`() {
        val gateway = FakeDevicePolicyGateway(wipeResult = Result.failure(IllegalStateException("binder died")))
        val owner = owner(gateway)
        val challenge = owner.armWipe(reason).getOrThrow()

        val result = owner.wipeDevice(challenge, reason)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("binder died")
    }

    // --- managed configuration --------------------------------------------------------------

    @Test
    fun `configuration is published under the keys the app reads`() {
        val gateway = FakeDevicePolicyGateway()

        val result = owner(gateway).applyManagedConfiguration(
            MdmConfig(
                homeserverUrl = "https://chat.securechat.com.au",
                allowRegistration = false,
                allowFileSend = true,
            )
        )

        assertThat(result.getOrThrow()).isEqualTo(SecureChatDeviceOwner.ApplyOutcome.Applied)
        assertThat(gateway.lastRestrictions).isEqualTo(
            mapOf(
                MdmConfig.KEY_HOMESERVER_URL to "https://chat.securechat.com.au",
                MdmConfig.KEY_ALLOW_REGISTRATION to false,
                MdmConfig.KEY_ALLOW_FILE_SEND to true,
            )
        )
    }

    @Test
    fun `the framework-owned pending sentinel is never forged`() {
        val gateway = FakeDevicePolicyGateway()

        owner(gateway).applyManagedConfiguration(MdmConfig.restrictionsPending)

        assertThat(gateway.lastRestrictions).doesNotContainKey(MdmConfig.KEY_RESTRICTIONS_PENDING)
    }

    @Test
    fun `configuration is not published without device owner`() {
        val gateway = FakeDevicePolicyGateway(isDeviceOwner = false)

        val result = owner(gateway).applyManagedConfiguration(MdmConfig.default)

        assertThat(result.exceptionOrNull()).isInstanceOf(SecureChatDeviceOwner.Refusal.NotDeviceOwner::class.java)
        assertThat(gateway.lastRestrictions).isNull()
    }

    @Test
    fun `an unchanged policy is not rewritten`() {
        // Every write makes the framework broadcast a change to the app. Republishing identical
        // values on each launch would churn state other components react to, for nothing.
        val gateway = FakeDevicePolicyGateway()
        val owner = owner(gateway)
        owner.applyManagedConfiguration(MdmConfig.default)

        val second = owner.applyManagedConfiguration(MdmConfig.default)

        assertThat(second.getOrThrow()).isEqualTo(SecureChatDeviceOwner.ApplyOutcome.Unchanged)
        assertThat(gateway.applyCount).isEqualTo(1)
    }

    @Test
    fun `a changed policy is rewritten`() {
        val gateway = FakeDevicePolicyGateway()
        val owner = owner(gateway)
        owner.applyManagedConfiguration(MdmConfig.default)

        val second = owner.applyManagedConfiguration(MdmConfig.default.copy(allowFileSend = false))

        assertThat(second.getOrThrow()).isEqualTo(SecureChatDeviceOwner.ApplyOutcome.Applied)
        assertThat(gateway.applyCount).isEqualTo(2)
        assertThat(gateway.lastRestrictions).containsEntry(MdmConfig.KEY_ALLOW_FILE_SEND, false)
    }

    @Test
    fun `an unreadable current policy is republished rather than assumed correct`() {
        // The dangerous reading of a failed read is "probably already right". A handset left on a
        // stale policy because the check failed is the outcome that matters.
        val gateway = FakeDevicePolicyGateway()
        val owner = owner(gateway)
        owner.applyManagedConfiguration(MdmConfig.default)
        gateway.readFails = true

        val second = owner.applyManagedConfiguration(MdmConfig.default)

        assertThat(second.getOrThrow()).isEqualTo(SecureChatDeviceOwner.ApplyOutcome.Applied)
        assertThat(gateway.applyCount).isEqualTo(2)
    }

    @Test
    fun `applying configuration never wipes anything`() {
        // Guards the one confusion that would be catastrophic and is entirely plausible: these two
        // capabilities live on the same class and both go through the same gateway.
        val gateway = FakeDevicePolicyGateway()

        owner(gateway).applyManagedConfiguration(MdmConfig.default)

        assertThat(gateway.wipeCount).isEqualTo(0)
        assertThat(gateway.relinquishCount).isEqualTo(0)
    }

    @Test
    fun `the app never gives up device owner on its own`() {
        // Relinquishing exists as a recovery route for an operator holding a cable, not as
        // something the app may decide to do. Nothing in the normal paths may call it.
        val gateway = FakeDevicePolicyGateway()
        val owner = owner(gateway)

        owner.applyManagedConfiguration(MdmConfig.default)
        val challenge = owner.armWipe(reason).getOrThrow()
        owner.wipeDevice(challenge, reason)

        assertThat(gateway.relinquishCount).isEqualTo(0)
    }
}
