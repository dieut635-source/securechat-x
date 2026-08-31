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
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.logout.api.SecureChatDataWiper
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Lives in the app module because it is the only place that can reach every store at once: session
 * storage, Coil's image cache, and the process cache directory.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultSecureChatDataWiper(
    @ApplicationContext private val context: Context,
    private val sessionStore: SessionStore,
    private val dispatchers: CoroutineDispatchers,
) : SecureChatDataWiper {
    override suspend fun wipeSession(userId: String, reason: String) = wipe(reason) {
        val session = sessionStore.getSession(userId)
        if (session == null) {
            Timber.w("Wipe: nothing stored for that account, nothing to erase")
        } else {
            eraseSession(session)
        }
    }

    override suspend fun wipeEverything(reason: String) = wipe(reason) {
        sessionStore.getAllSessions().forEach { eraseSession(it) }
    }

    // NonCancellable: a half-finished wipe is the worst outcome. Once erasure starts it runs to the
    // end even if the caller's scope is torn down — which is exactly what happens when the session
    // disappears underneath us, or when the screen that triggered it is destroyed.
    private suspend fun wipe(reason: String, block: suspend () -> Unit) =
        withContext(dispatchers.io + NonCancellable) {
            Timber.w("Wipe: erasing local data ($reason)")
            block()
            clearSharedCaches()
            Timber.w("Wipe: finished ($reason)")
        }

    private suspend fun eraseSession(session: SessionData) {
        quietly("session directory") { File(session.sessionPath).deleteRecursively() }
        quietly("session cache") { File(session.cachePath).deleteRecursively() }
        // The row last: it holds the tokens and the database passphrase, and while it is still there
        // an interrupted wipe is detected and retried on the next start.
        quietly("session row") { sessionStore.removeSession(session.userId) }
    }

    private fun clearSharedCaches() {
        // Coil keeps avatars and picture thumbnails outside the session directories, so they would
        // otherwise survive a wipe.
        quietly("image cache") {
            SingletonImageLoader.get(context).run {
                diskCache?.clear()
                memoryCache?.clear()
            }
        }
        // Logs included: on a seized phone they show who used it and when.
        quietly("app cache directory") {
            context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private inline fun quietly(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Timber.e(it, "Wipe: failed to erase $what") }
    }
}
