/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import android.content.Context
import coil3.SingletonImageLoader
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

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
    @ApplicationContext private val context: Context,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
    private val sessionStore: SessionStore,
    private val dispatchers: CoroutineDispatchers,
) {
    fun start() {
        sessionStore.sessionsFlow()
            .map { sessions -> sessions.filter { !it.isTokenValid } }
            .distinctUntilChanged()
            .onEach { revoked -> revoked.forEach { wipe(it) } }
            .launchIn(coroutineScope)
    }

    private suspend fun wipe(session: SessionData) = withContext(dispatchers.io) {
        Timber.w("Remote wipe: homeserver revoked this session, erasing local data")

        // Every step is best-effort and independent: one failure must not stop the rest, and the
        // session row is removed last so an interrupted wipe is retried on the next app start.
        deleteQuietly("session directory") { File(session.sessionPath).deleteRecursively() }
        deleteQuietly("session cache") { File(session.cachePath).deleteRecursively() }

        deleteQuietly("image cache") {
            SingletonImageLoader.get(context).run {
                diskCache?.clear()
                memoryCache?.clear()
            }
            true
        }

        // The whole cache directory, logs included: on a lost device the logs are evidence of who
        // used it and when.
        deleteQuietly("app cache directory") {
            context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            true
        }

        runCatching { sessionStore.removeSession(session.userId) }
            .onSuccess { Timber.w("Remote wipe: complete") }
            .onFailure { Timber.e(it, "Remote wipe: could not remove the session row; will retry on next start") }
    }

    private inline fun deleteQuietly(what: String, block: () -> Any?) {
        runCatching { block() }.onFailure { Timber.e(it, "Remote wipe: failed to clear $what") }
    }
}
