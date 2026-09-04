/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import timber.log.Timber

/**
 * Publishes the organisation's managed configuration at every launch.
 *
 * Running on each start rather than once at provisioning is the point: a policy applied only during
 * enrolment quietly stops being true the moment the intended values change, and nobody finds out
 * until a handset in the field behaves differently from the rest of the fleet. Re-asserting it is
 * cheap and self-healing.
 *
 * Doing nothing is a first-class outcome here. On a handset where this app is not the device owner
 * - every manual install, every device still enrolled in Headwind - there is nothing to publish and
 * that is not an error.
 */
@SingleIn(AppScope::class)
@Inject
class SecureChatPolicyPublisher(
    private val deviceOwner: SecureChatDeviceOwner,
    private val policySource: SecureChatPolicySource,
) {
    /** What a call to [publish] did. Returned rather than only logged so it can be asserted on. */
    enum class Outcome {
        /** The published policy was out of date and has been replaced. */
        Published,

        /** The published policy already matched; nothing was written. */
        AlreadyCurrent,

        /** This app is not the device owner, so it has no configuration to publish. */
        NotManaging,

        /** Publishing was attempted and failed. The handset keeps whatever policy it had. */
        Failed,
    }

    fun publish(): Outcome {
        if (!deviceOwner.isDeviceOwner) {
            // Not a warning. This is the normal state of a handset that was installed by hand.
            Timber.i("Not device owner; leaving managed configuration to whoever is")
            return Outcome.NotManaging
        }
        val policy = policySource.currentPolicy()
        return deviceOwner.applyManagedConfiguration(policy).fold(
            onSuccess = { outcome ->
                when (outcome) {
                    SecureChatDeviceOwner.ApplyOutcome.Applied -> Outcome.Published
                    SecureChatDeviceOwner.ApplyOutcome.Unchanged -> Outcome.AlreadyCurrent
                }
            },
            onFailure = { throwable ->
                // Swallowed on purpose, and only here. Failing to publish policy is worth knowing
                // about, but it is not worth refusing to start a messenger over: the app still runs
                // under the restrictions it already had, which for an unprovisioned handset are the
                // conservative defaults.
                Timber.w(throwable, "Could not publish managed configuration")
                Outcome.Failed
            },
        )
    }
}
