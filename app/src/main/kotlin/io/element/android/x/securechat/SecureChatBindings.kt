/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.x.securechat.dpc.SecureChatPolicyPublisher
import io.element.android.x.securechat.dpc.SecureChatRemoteCommandPoller
import io.element.android.x.securechat.dpc.DevicePolicyGateway

@ContributesTo(AppScope::class)
interface SecureChatBindings {
    fun devicePolicyGateway(): DevicePolicyGateway

    fun secureChatPolicyPublisher(): SecureChatPolicyPublisher

    fun secureChatRemoteCommandPoller(): SecureChatRemoteCommandPoller

    fun secureChatRemoteWipe(): SecureChatRemoteWipe

    fun secureChatWipeResumer(): SecureChatWipeResumer
}
