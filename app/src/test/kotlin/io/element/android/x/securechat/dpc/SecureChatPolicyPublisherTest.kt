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
 * Publishing policy at launch.
 *
 * The outcomes that matter most here are the quiet ones: a handset this app does not manage, and a
 * launch where the policy was already correct. Both have to do nothing, and both have to be
 * distinguishable from a failure - a publisher that reports success when it published nothing is
 * how a fleet ends up unmanaged without anybody noticing.
 */
class SecureChatPolicyPublisherTest {
    private class FakeDevicePolicyGateway(
        override var isDeviceOwner: Boolean = true,
        private val applyResult: Result<Unit> = Result.success(Unit),
    ) : DevicePolicyGateway {
        var published: Map<String, Any>? = null
        var applyCount = 0

        override fun applyRestrictions(restrictions: Map<String, Any>): Result<Unit> {
            applyCount++
            return applyResult.onSuccess { published = restrictions }
        }

        override fun readRestrictions(): Result<Map<String, Any>> = Result.success(published.orEmpty())
        override fun wipeDevice(): Result<Unit> = error("must never be reached")
        override fun relinquishDeviceOwner(): Result<Unit> = error("must never be reached")
    }

    private fun publisher(
        gateway: FakeDevicePolicyGateway,
        policy: MdmConfig = MdmConfig.default,
    ) = SecureChatPolicyPublisher(
        deviceOwner = SecureChatDeviceOwner(gateway),
        policySource = { policy },
    )

    @Test
    fun `policy is published on a managed handset`() {
        val gateway = FakeDevicePolicyGateway()

        val outcome = publisher(gateway).publish()

        assertThat(outcome).isEqualTo(SecureChatPolicyPublisher.Outcome.Published)
        assertThat(gateway.published).containsEntry(MdmConfig.KEY_ALLOW_REGISTRATION, false)
    }

    @Test
    fun `nothing is published on a handset this app does not manage`() {
        // The normal state of every hand-installed device, and of every device still enrolled in
        // Headwind. Not an error, and it must not overwrite whoever does manage the handset.
        val gateway = FakeDevicePolicyGateway(isDeviceOwner = false)

        val outcome = publisher(gateway).publish()

        assertThat(outcome).isEqualTo(SecureChatPolicyPublisher.Outcome.NotManaging)
        assertThat(gateway.applyCount).isEqualTo(0)
        assertThat(gateway.published).isNull()
    }

    @Test
    fun `a second launch with the same policy writes nothing`() {
        val gateway = FakeDevicePolicyGateway()
        val publisher = publisher(gateway)
        publisher.publish()

        val outcome = publisher.publish()

        assertThat(outcome).isEqualTo(SecureChatPolicyPublisher.Outcome.AlreadyCurrent)
        assertThat(gateway.applyCount).isEqualTo(1)
    }

    @Test
    fun `a changed policy is republished on the next launch`() {
        // The reason this runs at every launch rather than once at enrolment: policy that is only
        // applied during provisioning silently stops being true when the intended values change.
        val gateway = FakeDevicePolicyGateway()
        publisher(gateway).publish()

        val outcome = publisher(gateway, MdmConfig.default.copy(allowFileSend = false)).publish()

        assertThat(outcome).isEqualTo(SecureChatPolicyPublisher.Outcome.Published)
        assertThat(gateway.published).containsEntry(MdmConfig.KEY_ALLOW_FILE_SEND, false)
    }

    @Test
    fun `a failure to publish is reported and does not throw`() {
        // This runs during application startup. Throwing here would turn a policy problem into a
        // messenger that will not open.
        val gateway = FakeDevicePolicyGateway(applyResult = Result.failure(IllegalStateException("binder died")))

        val outcome = publisher(gateway).publish()

        assertThat(outcome).isEqualTo(SecureChatPolicyPublisher.Outcome.Failed)
    }

    @Test
    fun `the build-time source publishes exactly the three agreed keys`() {
        // The key names are contractual: an MDM console is configured against them, and a rename
        // here is silent breakage everywhere. The count is asserted too, so an added key has to be
        // a deliberate change rather than an accident.
        val gateway = FakeDevicePolicyGateway()

        SecureChatPolicyPublisher(SecureChatDeviceOwner(gateway), DefaultSecureChatPolicySource()).publish()

        assertThat(gateway.published!!.keys).containsExactly(
            "homeserver_url",
            "allow_registration",
            "allow_file_send",
        )
    }

    @Test
    fun `the build-time source does not drift from the app's own defaults`() {
        // Two lists of defaults drift, and the one that drifts silently is the one nobody reads.
        assertThat(DefaultSecureChatPolicySource().currentPolicy()).isEqualTo(MdmConfig.default)
    }
}
