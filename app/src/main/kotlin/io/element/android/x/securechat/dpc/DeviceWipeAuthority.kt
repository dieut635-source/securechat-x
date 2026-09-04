/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import timber.log.Timber
import java.security.SecureRandom

/**
 * The gate every device wipe has to pass through.
 *
 * Wiping a device is the only thing this application does that cannot be undone, cannot be
 * recovered from a backup, and destroys property that belongs to somebody else. A boolean argument
 * is not a proportionate guard for that: a wrong branch, a copy-pasted call, a retried coroutine or
 * a spoofed remote command are all one boolean away from wiping a phone in the field.
 *
 * So a wipe cannot be requested in a single call. The caller has to [arm] first, receive a
 * challenge that exists nowhere else, and hand that exact challenge back to [consume] together with
 * the same reason. Four properties make accidental and replayed wipes impossible rather than
 * unlikely:
 *
 *  - **Nothing is armed by default.** Code that has not called [arm] cannot produce a challenge, so
 *    a stray call to the wipe path fails closed no matter what state the app is in.
 *  - **One shot.** Any [consume] attempt clears the pending challenge, right or wrong. There is no
 *    grinding, and a retry loop cannot fire the wipe twice.
 *  - **It expires.** A challenge armed and then forgotten stops being valid after
 *    [CHALLENGE_TTL_MS]. Authority to wipe should not survive the moment it was granted in.
 *  - **The reason is part of the credential.** Arming for one purpose cannot be spent on another,
 *    and every attempt is logged with that reason before anything is destroyed.
 *
 * This class is deliberately free of Android types so the whole of it can be tested, including the
 * failure paths. A safeguard that has only ever been seen to allow is not a safeguard.
 */
class DeviceWipeAuthority(
    private val now: () -> Long = System::currentTimeMillis,
    private val generateChallenge: () -> String = ::randomChallenge,
) {
    private val lock = Any()
    private var pending: Pending? = null

    /** Reasons why a wipe was refused. Every one of them means no data was destroyed. */
    sealed class Refusal(message: String) : Exception(message) {
        data object NotArmed : Refusal("no wipe was armed")
        data object Expired : Refusal("the armed wipe expired")
        data object ChallengeMismatch : Refusal("the challenge did not match")
        data object ReasonMismatch : Refusal("the reason did not match the armed reason")
        data class ReasonTooShort(val length: Int) : Refusal("reason must be at least $MIN_REASON_LENGTH characters, got $length")
    }

    /** True while a challenge is outstanding and still valid. Intended for diagnostics only. */
    val isArmed: Boolean
        get() = synchronized(lock) { pending?.let { now() < it.expiresAtMs } == true }

    /**
     * Ask for permission to wipe, stating why.
     *
     * Returns a single-use challenge that has to be presented to [consume]. Arming twice discards
     * the earlier challenge: only one wipe can ever be outstanding, so a forgotten arm cannot be
     * spent later by unrelated code.
     */
    fun arm(reason: String): Result<String> {
        val trimmed = reason.trim()
        if (trimmed.length < MIN_REASON_LENGTH) {
            return Result.failure(Refusal.ReasonTooShort(trimmed.length))
        }
        return synchronized(lock) {
            if (pending != null) {
                Timber.w("Device wipe re-armed; the previous challenge is now void")
            }
            val challenge = generateChallenge()
            pending = Pending(
                challenge = challenge,
                reason = trimmed,
                expiresAtMs = now() + CHALLENGE_TTL_MS,
            )
            Timber.w("Device wipe armed: $trimmed")
            Result.success(challenge)
        }
    }

    /** Abandon any outstanding authority. Safe to call when nothing is armed. */
    fun disarm() {
        synchronized(lock) {
            if (pending != null) {
                Timber.i("Device wipe disarmed before it was used")
                pending = null
            }
        }
    }

    /**
     * Spend the authority granted by [arm].
     *
     * Success means the caller may wipe, and that this authority is now gone. Failure means no
     * authority existed or it did not match, and the caller must not wipe.
     */
    fun consume(challenge: String, reason: String): Result<Unit> {
        val trimmed = reason.trim()
        return synchronized(lock) {
            // Taken before any check: an attempt spends the authority whether or not it succeeds.
            // Without this a caller could sit and guess, and a retry loop could fire twice.
            val outstanding = pending
            pending = null

            when {
                outstanding == null -> Result.failure(Refusal.NotArmed)
                now() >= outstanding.expiresAtMs -> Result.failure(Refusal.Expired)
                // Constant-time compare. The challenge never leaves the device today, so timing is
                // not currently a threat - but this costs nothing and stays correct if a future
                // remote trigger ever does put it on the wire.
                !constantTimeEquals(outstanding.challenge, challenge) -> Result.failure(Refusal.ChallengeMismatch)
                outstanding.reason != trimmed -> Result.failure(Refusal.ReasonMismatch)
                else -> {
                    Timber.w("Device wipe authorised: $trimmed")
                    Result.success(Unit)
                }
            }
        }
    }

    private data class Pending(
        val challenge: String,
        val reason: String,
        val expiresAtMs: Long,
    )

    companion object {
        /**
         * How long an armed wipe stays valid. Long enough for an operator to confirm, far too short
         * for authority granted this morning to fire this evening.
         */
        const val CHALLENGE_TTL_MS = 2 * 60 * 1000L

        /**
         * Forces the caller to say something. "x" is not a reason, and this string is the only
         * record of intent that survives into the log of a device that is about to lose everything.
         */
        const val MIN_REASON_LENGTH = 8

        private const val CHALLENGE_BYTES = 32

        private fun randomChallenge(): String {
            val bytes = ByteArray(CHALLENGE_BYTES)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var difference = 0
            for (index in a.indices) {
                difference = difference or (a[index].code xor b[index].code)
            }
            return difference == 0
        }
    }
}
