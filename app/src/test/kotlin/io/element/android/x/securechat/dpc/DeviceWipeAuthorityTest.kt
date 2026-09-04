/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The safeguard in front of the only irreversible thing this app can do.
 *
 * These tests are written to make it fail. A guard that has only ever been observed to permit has
 * not been shown to guard anything, so every refusal path below is exercised deliberately, and the
 * happy path is here mainly to prove the refusals are not simply refusing everything.
 */
class DeviceWipeAuthorityTest {
    private var clock = 1_000L
    private var nextChallenge = 0

    private fun authority() = DeviceWipeAuthority(
        now = { clock },
        generateChallenge = { "challenge-${nextChallenge++}" },
    )

    private val reason = "lost handset, ticket 4471"

    @Test
    fun `an armed challenge authorises exactly one wipe`() {
        val authority = authority()
        val challenge = authority.arm(reason).getOrThrow()

        assertThat(authority.consume(challenge, reason).isSuccess).isTrue()
    }

    @Test
    fun `nothing is armed by default so a stray call cannot wipe`() {
        val authority = authority()

        val result = authority.consume("challenge-0", reason)

        assertThat(result.exceptionOrNull()).isInstanceOf(DeviceWipeAuthority.Refusal.NotArmed::class.java)
    }

    @Test
    fun `a challenge cannot be spent twice`() {
        val authority = authority()
        val challenge = authority.arm(reason).getOrThrow()
        authority.consume(challenge, reason).getOrThrow()

        // The retried coroutine, the double-tapped button, the replayed command.
        val second = authority.consume(challenge, reason)

        assertThat(second.exceptionOrNull()).isInstanceOf(DeviceWipeAuthority.Refusal.NotArmed::class.java)
    }

    @Test
    fun `a failed attempt spends the authority so guessing is impossible`() {
        val authority = authority()
        val challenge = authority.arm(reason).getOrThrow()

        val wrong = authority.consume("not-the-challenge", reason)
        val thenRight = authority.consume(challenge, reason)

        assertThat(wrong.exceptionOrNull()).isInstanceOf(DeviceWipeAuthority.Refusal.ChallengeMismatch::class.java)
        // Even the correct challenge is now worthless: one attempt is all there is.
        assertThat(thenRight.exceptionOrNull()).isInstanceOf(DeviceWipeAuthority.Refusal.NotArmed::class.java)
    }

    @Test
    fun `authority expires so this morning's permission cannot fire tonight`() {
        val authority = authority()
        val challenge = authority.arm(reason).getOrThrow()

        clock += DeviceWipeAuthority.CHALLENGE_TTL_MS

        val result = authority.consume(challenge, reason)

        assertThat(result.exceptionOrNull()).isInstanceOf(DeviceWipeAuthority.Refusal.Expired::class.java)
    }

    @Test
    fun `authority is still valid one millisecond before it expires`() {
        val authority = authority()
        val challenge = authority.arm(reason).getOrThrow()

        clock += DeviceWipeAuthority.CHALLENGE_TTL_MS - 1

        assertThat(authority.consume(challenge, reason).isSuccess).isTrue()
    }

    @Test
    fun `permission granted for one purpose cannot be spent on another`() {
        val authority = authority()
        val challenge = authority.arm(reason).getOrThrow()

        val result = authority.consume(challenge, "returned to stock, ticket 9002")

        assertThat(result.exceptionOrNull()).isInstanceOf(DeviceWipeAuthority.Refusal.ReasonMismatch::class.java)
    }

    @Test
    fun `a reason too short to mean anything is refused`() {
        val authority = authority()

        val result = authority.arm("oops")

        assertThat(result.exceptionOrNull()).isInstanceOf(DeviceWipeAuthority.Refusal.ReasonTooShort::class.java)
        assertThat(authority.isArmed).isFalse()
    }

    @Test
    fun `whitespace does not pad a reason into acceptability`() {
        val authority = authority()

        assertThat(authority.arm("   x    ").isFailure).isTrue()
    }

    @Test
    fun `re-arming voids the earlier challenge`() {
        val authority = authority()
        val first = authority.arm(reason).getOrThrow()
        val second = authority.arm(reason).getOrThrow()

        assertThat(first).isNotEqualTo(second)
        assertThat(authority.consume(first, reason).exceptionOrNull())
            .isInstanceOf(DeviceWipeAuthority.Refusal.ChallengeMismatch::class.java)
    }

    @Test
    fun `disarming leaves nothing to spend`() {
        val authority = authority()
        val challenge = authority.arm(reason).getOrThrow()

        authority.disarm()

        assertThat(authority.isArmed).isFalse()
        assertThat(authority.consume(challenge, reason).exceptionOrNull())
            .isInstanceOf(DeviceWipeAuthority.Refusal.NotArmed::class.java)
    }

    @Test
    fun `an expired challenge does not count as armed`() {
        val authority = authority()
        authority.arm(reason).getOrThrow()

        assertThat(authority.isArmed).isTrue()
        clock += DeviceWipeAuthority.CHALLENGE_TTL_MS
        assertThat(authority.isArmed).isFalse()
    }

    @Test
    fun `real challenges are unpredictable and not reused`() {
        // The production generator, not the test's counter: this is the one property the fake
        // cannot check, and a challenge that repeats or is guessable defeats the whole class.
        val real = DeviceWipeAuthority()
        val seen = buildSet {
            repeat(200) {
                add(real.arm(reason).getOrThrow())
            }
        }

        assertThat(seen).hasSize(200)
        assertThat(seen.all { it.length >= 64 }).isTrue()
    }
}
