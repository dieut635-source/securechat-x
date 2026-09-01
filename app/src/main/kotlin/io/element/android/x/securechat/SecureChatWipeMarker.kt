/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext

/**
 * Records that a wipe was started but is not yet known to have finished.
 *
 * Without this, an erasure interrupted by a power cut, a killed process or a filesystem error is
 * simply lost: the app restarts with no idea that data it promised to destroy is still on disk. The
 * duress PIN made that worse, because nothing about a normal-looking unlock would ever trigger a
 * retry.
 *
 * Deliberately stored in its own SharedPreferences file rather than in the session store or the
 * cache directory: both of those are the very things being erased. `apply()` is not used anywhere
 * here - the marker must reach disk before the erasure starts, so every write is committed
 * synchronously.
 */
@SingleIn(AppScope::class)
@Inject
class SecureChatWipeMarker(
    @ApplicationContext private val context: Context,
) {
    private val preferences by lazy {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    /** True when an erasure was started and has not been confirmed complete. */
    fun isPending(): Boolean = preferences.contains(KEY_REASON)

    fun reason(): String? = preferences.getString(KEY_REASON, null)

    /**
     * Commits the marker to disk. Returns only once the write is durable, so a crash immediately
     * afterwards still leaves evidence that data must be destroyed.
     */
    fun markPending(reason: String) {
        preferences.edit(commit = true) { putString(KEY_REASON, reason) }
    }

    /**
     * Clears the marker. Must only be called after a verification pass found nothing left to erase:
     * clearing it on a best-effort basis is exactly the silent failure this class exists to prevent.
     */
    fun clear() {
        preferences.edit(commit = true) { remove(KEY_REASON) }
    }

    private companion object {
        // Anodyne name: on a seized device the file list should not announce what happened.
        const val FILE_NAME = "sc_state"
        const val KEY_REASON = "pending_reason"
    }
}
