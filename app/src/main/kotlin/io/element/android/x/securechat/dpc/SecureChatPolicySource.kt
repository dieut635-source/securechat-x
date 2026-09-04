/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.mdm.api.MdmConfig

/**
 * Where the managed configuration this app publishes to itself comes from.
 *
 * A seam rather than a constant, because the source is expected to change and the readers must not
 * have to. Everything downstream reads through the standard `RestrictionsManager` path, so swapping
 * this implementation is the whole of the work needed to move from build-time policy to
 * administrator-controlled policy.
 */
fun interface SecureChatPolicySource {
    fun currentPolicy(): MdmConfig
}

/**
 * The policy compiled into this build.
 *
 * **What this does and does not buy.** Publishing build-time values makes the handset genuinely
 * managed: the keys are really present, `RestrictionsManager` really returns them, and the
 * difference between "managed with these values" and "unmanaged, falling back to identical
 * defaults" becomes observable instead of indistinguishable. It does not yet give an administrator
 * any way to change a value without a new build - that needs a server-backed source, and the
 * homeserver URL cannot come from the server it is needed to reach, so that key stays here.
 *
 * Deliberately built from [MdmConfig.default] rather than repeating the values: two lists of
 * defaults drift, and the one that drifts silently is the one nobody reads.
 */
@ContributesBinding(AppScope::class)
class BuildTimePolicySource : SecureChatPolicySource {
    override fun currentPolicy(): MdmConfig = MdmConfig.default
}
