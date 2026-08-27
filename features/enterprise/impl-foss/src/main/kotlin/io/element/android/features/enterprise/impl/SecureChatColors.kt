/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.enterprise.impl

import androidx.compose.ui.graphics.Color
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.tokens.generated.compoundColorsDark
import io.element.android.compound.tokens.generated.compoundColorsLight

/**
 * SecureChat brand palette.
 *
 * Compound ships a green accent; SecureChat is blue. Only the *accent* tokens are overridden: they are
 * the group Compound reserves for brand colour, and every other token (backgrounds, text, action
 * primary) keeps the contrast pairing it was designed with. In particular [SecureChatColors] does not
 * touch `textOnSolidPrimary`, which is what the accent backgrounds below are read against, so the dark
 * ramp is deliberately lighter than the light one - the same way Compound's own green ramp is.
 */
object SecureChatColors {
    /** SecureChat primary blue, also used for the launcher icon background and notification accent. */
    val brand = Color(0xFF1A73E8)

    // Light theme ramp, from the lightest tint to the darkest shade.
    private val lightSubtle = Color(0xFFE8F0FE)
    private val lightBadge = Color(0xFFC6DCFB)
    private val lightTertiary = Color(0xFF2F7FE0)
    private val lightBorderSubtle = Color(0xFF4285F4)
    private val lightHovered = Color(0xFF1765CC)
    private val lightPressed = Color(0xFF0F4C99)
    private val lightSelected = Color(0x1C1A73E8)

    // Dark theme ramp. Brighter than the light one, because it is read against a dark canvas.
    private val darkSubtle = Color(0xFF0A1F38)
    private val darkBadge = Color(0xFF10294A)
    private val darkBorderSubtle = Color(0xFF1B4F8C)
    private val darkTertiary = Color(0xFF2F7FE0)
    private val darkRest = Color(0xFF3B8AEE)
    private val darkHovered = Color(0xFF5C9FF2)
    private val darkPressed = Color(0xFF7FB4F6)

    val semanticColors = SemanticColorsLightDark(
        light = compoundColorsLight.copy(
            bgAccentHovered = lightHovered,
            bgAccentPressed = lightPressed,
            bgAccentRest = brand,
            bgAccentSelected = lightSelected,
            bgAccentSubtle = lightSubtle,
            bgBadgeAccent = lightBadge,
            borderAccentPrimary = brand,
            borderAccentSubtle = lightBorderSubtle,
            iconAccentPrimary = brand,
            iconAccentTertiary = lightTertiary,
            textActionAccent = brand,
            textBadgeAccent = lightPressed,
        ),
        dark = compoundColorsDark.copy(
            bgAccentHovered = darkHovered,
            bgAccentPressed = darkPressed,
            bgAccentRest = darkRest,
            bgAccentSelected = darkSubtle,
            bgAccentSubtle = darkSubtle,
            bgBadgeAccent = darkBadge,
            borderAccentPrimary = darkRest,
            borderAccentSubtle = darkBorderSubtle,
            iconAccentPrimary = darkRest,
            iconAccentTertiary = darkTertiary,
            textActionAccent = darkRest,
            textBadgeAccent = darkPressed,
        ),
    )
}
