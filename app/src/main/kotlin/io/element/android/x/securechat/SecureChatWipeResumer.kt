/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.logout.api.SecureChatDataWiper
import io.element.android.libraries.di.annotations.AppCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Finishes an erasure that was interrupted.
 *
 * A wipe can stop halfway for reasons that have nothing to do with the app: the phone is powered
 * off, the process is killed, a file is briefly locked. Before [SecureChatWipeMarker] existed there
 * was no record of that, so the data simply stayed - and for the duress PIN nothing would ever ask
 * again, because a duress unlock looks exactly like a normal one.
 *
 * Runs on every start. When the marker is set it erases everything again, which is safe: erasure is
 * idempotent, and doing it twice costs nothing next to leaving it half done.
 */
@SingleIn(AppScope::class)
@Inject
class SecureChatWipeResumer(
    private val wipeMarker: SecureChatWipeMarker,
    private val dataWiper: SecureChatDataWiper,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) {
    fun start() {
        if (!wipeMarker.isPending()) return
        val reason = wipeMarker.reason() ?: "unknown"
        Timber.w("Wipe: an earlier erasure did not complete ($reason), resuming")
        appCoroutineScope.launch {
            dataWiper.wipeEverything(reason = "resumed after interruption: $reason")
        }
    }
}
