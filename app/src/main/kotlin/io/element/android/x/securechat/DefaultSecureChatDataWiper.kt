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
import dev.zacsweers.metro.SingleIn
import io.element.android.features.logout.api.SecureChatDataWiper
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Lives in the app module because it is the only place that can reach every store at once: session
 * storage, Coil's image cache, and the process cache directory.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSecureChatDataWiper(
    @ApplicationContext private val context: Context,
    private val sessionStore: SessionStore,
    private val dispatchers: CoroutineDispatchers,
    private val wipeMarker: SecureChatWipeMarker,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : SecureChatDataWiper {
    override suspend fun wipeSession(userId: String, reason: String) {
        runWipe(reason) {
            val session = sessionStore.getSession(userId)
            if (session == null) {
                Timber.w("Wipe: nothing stored for that account, nothing to erase")
                true
            } else {
                eraseSession(session)
            }
        }
    }

    override suspend fun wipeEverything(reason: String) {
        runWipe(reason) { eraseAllSessions() }
    }

    override suspend fun beginWipeEverything(reason: String) {
        // Two steps only, both cheap, both awaited: record the intent, then destroy the keys. After
        // this returns the stored data is already cryptographically useless, and a crash cannot lose
        // the fact that the rest still has to go.
        //
        // The paths are read BEFORE the rows are removed - once a row is gone there is nothing left
        // to say which directory belonged to it.
        val sessions = withContext(dispatchers.io + NonCancellable) {
            wipeMarker.markPending(reason)
            val known = sessionStore.getAllSessions()
            shredKeys(known)
            known
        }
        // The expensive part - directories, caches, thumbnails - runs detached. Awaiting it here is
        // what made the duress PIN observable: erasing gigabytes before unlocking left the screen
        // frozen for seconds, so the person forcing the unlock could see which code had been typed.
        appCoroutineScope.launch {
            runWipe(reason) { sessions.all { eraseSessionFiles(it) } }
        }
    }

    /**
     * Runs an erasure under the marker and clears it only when [block] reports full success.
     *
     * Anything left behind - a locked file, an I/O error, a killed process - keeps the marker in
     * place, so [SecureChatWipeResumer] finishes the job on the next start instead of the app
     * quietly believing the data is gone.
     */
    private suspend fun runWipe(reason: String, block: suspend () -> Boolean) =
        withContext(dispatchers.io + NonCancellable) {
            wipeMarker.markPending(reason)
            Timber.w("Wipe: erasing local data ($reason)")
            val sessionsErased = block()
            val cachesErased = clearSharedCaches()
            if (sessionsErased && cachesErased) {
                wipeMarker.clear()
                Timber.w("Wipe: finished ($reason)")
            } else {
                Timber.e("Wipe: incomplete ($reason) - marker kept, will resume on next start")
            }
        }

    /**
     * Deletes the session rows, and with them the database passphrase and the tokens.
     *
     * Done before the bulk deletion on purpose: the row is small, so this finishes in milliseconds,
     * and once the passphrase is gone the encrypted stores on disk cannot be read even if the files
     * survive. Destroying the key first is what makes the slow part safe to defer.
     */
    private suspend fun shredKeys(sessions: List<SessionData>): Boolean =
        sessions.all { session ->
            attempt("session row") { sessionStore.removeSession(session.userId) }
        }

    private suspend fun eraseAllSessions(): Boolean {
        // Read the list once, up front: shredKeys removes the rows, and with them the only record of
        // where each session stored its files.
        val sessions = sessionStore.getAllSessions()
        val rowsGone = shredKeys(sessions)
        val filesGone = sessions.all { eraseSessionFiles(it) }
        return rowsGone && filesGone
    }

    private fun eraseSessionFiles(session: SessionData): Boolean {
        val dirGone = attempt("session directory") {
            require(File(session.sessionPath).deleteRecursively()) { "session directory not fully removed" }
        }
        val cacheGone = attempt("session cache") {
            require(File(session.cachePath).deleteRecursively()) { "session cache not fully removed" }
        }
        return dirGone && cacheGone
    }

    private suspend fun eraseSession(session: SessionData): Boolean {
        // Row first: it carries the passphrase, so removing it renders the files unreadable straight
        // away. The marker, not the row, is what makes an interrupted wipe resumable.
        val rowGone = attempt("session row") { sessionStore.removeSession(session.userId) }
        val filesGone = eraseSessionFiles(session)
        return rowGone && filesGone
    }

    private fun clearSharedCaches(): Boolean {
        // Coil keeps avatars and picture thumbnails outside the session directories, so they would
        // otherwise survive a wipe.
        val imagesGone = attempt("image cache") {
            SingletonImageLoader.get(context).run {
                diskCache?.clear()
                memoryCache?.clear()
            }
        }
        // Logs included: on a seized phone they show who used it and when.
        val cacheDirGone = attempt("app cache directory") {
            val failures = context.cacheDir?.listFiles()?.count { !it.deleteRecursively() } ?: 0
            require(failures == 0) { "$failures cache entries not removed" }
        }
        return imagesGone && cacheDirGone
    }

    /**
     * Runs one erasure step and reports whether it actually succeeded.
     *
     * The previous version swallowed both exceptions and the Boolean that [File.deleteRecursively]
     * returns, then logged "finished" regardless - so a partial wipe was indistinguishable from a
     * complete one.
     */
    private inline fun attempt(what: String, block: () -> Unit): Boolean =
        runCatchingExceptions(block)
            .onFailure { Timber.e(it, "Wipe: failed to erase $what") }
            .isSuccess

}
