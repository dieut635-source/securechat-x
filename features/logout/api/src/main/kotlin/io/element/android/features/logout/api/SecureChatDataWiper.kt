/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.api

/**
 * Erases local SecureChat data.
 *
 * Two callers, two different scopes, and the difference matters:
 *
 *  - the homeserver revoked one session (an administrator deleted that device) — only that account
 *    may be erased, because the phone may legitimately hold another one;
 *  - somebody entered the duress PIN — everything goes, because at that moment the phone is assumed
 *    to be in the wrong hands.
 *
 * Implementations must be best-effort per step, so one failure does not abandon the rest, and safe
 * to run twice: a wipe interrupted by the process being killed is resumed on the next start.
 */
interface SecureChatDataWiper {
    /**
     * Erases one account: its message store, crypto store, media, and its row in session storage.
     *
     * @param userId the account to erase. Other accounts on the device are left untouched.
     * @param reason short, non-sensitive text for the log. Never a PIN, a token, or anything that
     * identifies a person: on a phone that has just been seized, the log is evidence.
     */
    suspend fun wipeSession(userId: String, reason: String)

    /**
     * Erases every account plus every shared cache. Used for duress, where leaving one account
     * behind would defeat the point.
     *
     * @param reason short, non-sensitive text for the log, same rule as above.
     */
    suspend fun wipeEverything(reason: String)

    /**
     * Starts erasing everything and returns as soon as the data is unreadable, without waiting for
     * the files to go.
     *
     * Written for the duress PIN. [wipeEverything] deletes the session directories before it
     * returns, which can take seconds on a full device - long enough for whoever is forcing the
     * unlock to notice that this code behaved differently from the real one. This variant records a
     * durable marker and destroys the database passphrase first, both of which are near-instant,
     * then finishes the bulk deletion in the background and resumes it after a restart if it was
     * interrupted.
     */
    suspend fun beginWipeEverything(reason: String)
}
