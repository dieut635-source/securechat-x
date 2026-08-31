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
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Erases everything SecureChat holds for an account the homeserver has revoked.
 *
 * How a wipe is triggered: an administrator deletes the device through the Synapse admin API. The
 * homeserver then rejects the device's access token, the Rust SDK reports it through
 * `didReceiveAuthError`, and the session is flagged `isTokenValid = false`. That flag is written in
 * exactly one place — [io.element.android.libraries.matrix.impl.RustClientSessionDelegate] — and
 * only when the server rejected the token, never on a transient network failure. This class watches
 * for that flag.
 *
 * Upstream already deletes the session directory on a forced logout, so messages and crypto stores
 * go. Two things survive, and this class removes them:
 *
 *  - the row in [SessionStore], which still holds the user id, device id, homeserver, the (now
 *    rejected) access and refresh tokens, and the database `passphrase`;
 *  - Coil's image cache, which lives outside the session directories and therefore keeps avatars
 *    and picture thumbnails from conversations.
 *
 * Deliberately different from upstream: upstream shows a "you were signed out" screen, which tells
 * whoever holds the device that this account existed on it. Removing the session row instead sends
 * the app straight to the login screen, leaving nothing behind.
 *
 * Limits, stated plainly. This only works while the device is online and can reach the homeserver:
 * a phone kept offline never learns it was revoked. Remote wipe is a second line of defence, not
 * the first — data at rest is protected by the PIN lock and by full-disk encryption.
 */
@SingleIn(AppScope::class)
@Inject
class SecureChatRemoteWipe(
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
    private val sessionStore: SessionStore,
    private val dataWiper: SecureChatDataWiper,
) {
    fun start() {
        sessionStore.sessionsFlow()
            .map { sessions -> sessions.filter { !it.isTokenValid } }
            .distinctUntilChanged()
            .onEach { revoked -> revoked.forEach { wipe(it.userId) } }
            .launchIn(coroutineScope)
    }

    private suspend fun wipe(userId: String) {
        // Only this account: the phone may legitimately hold another one, and a revocation says
        // nothing about the others. Duress is the case where everything goes.
        dataWiper.wipeSession(userId, reason = "homeserver revoked this session")
    }
}
