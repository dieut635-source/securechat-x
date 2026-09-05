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
 * Bảng màu SecureChat — phong cách "bảo mật hiện đại": nền tối, ít màu, tương phản cao.
 *
 * Giao diện TỐI là bản thiết kế chính; giao diện sáng được suy ra để người dùng đổi sang vẫn
 * dùng được chứ không phải bản song song ngang hàng.
 *
 * Hai màu thương hiệu có vai trò khác nhau, cố ý không dùng lẫn:
 *  - [primary] xanh dương: hành động (nút chính, bong bóng tin nhắn gửi đi, số tin chưa đọc)
 *  - [accent] xanh ngọc: nhấn mạnh (liên kết, biểu tượng nhấn) — chỉ đặt trên nền tối, nơi nó
 *    tương phản mạnh. KHÔNG dùng làm nền có chữ trắng đè lên: trắng trên #00C2A8 chỉ đạt
 *    khoảng 2,2:1, dưới ngưỡng đọc được.
 */
object SecureChatColors {
    /** Xanh dương thương hiệu. Cũng là màu nền icon và màu accent của thông báo. */
    val brand = Color(0xFF2E6BFF)

    /** Xanh ngọc nhấn mạnh. */
    val accent = Color(0xFF00C2A8)

    // --- Nền và chữ (giao diện tối) ---
    private val bgBase = Color(0xFF0B0E14) // nền chính
    private val bgSurface = Color(0xFF151A23) // thẻ, bong bóng nhận, ô nhập
    private val bgSurfaceHi = Color(0xFF1C2230)
    private val textHi = Color(0xFFE6EAF0)
    private val textLo = Color(0xFF8A94A6)
    private val borderLo = Color(0xFF232A38)
    private val borderHi = Color(0xFF313A4C)

    // --- Ramp xanh dương ---
    private val bluePressed = Color(0xFF1E47AE)
    private val blueHovered = Color(0xFF2559D6)
    private val blueSubtleDark = Color(0xFF13203A)
    private val blueBadgeDark = Color(0xFF1A2C4F)
    private val blueLight = Color(0xFF8FB0FF)

    // --- Ramp cho giao diện sáng ---
    private val blueSubtleLight = Color(0xFFEAF0FF)
    private val blueBadgeLight = Color(0xFFD3E0FF)
    private val tealOnLight = Color(0xFF00796B) // #00C2A8 trên nền trắng quá nhạt để đọc

    val semanticColors = SemanticColorsLightDark(
        light = compoundColorsLight.copy(
            bgActionPrimaryRest = brand,
            bgActionPrimaryHovered = blueHovered,
            bgActionPrimaryPressed = bluePressed,
            bgAccentRest = brand,
            bgAccentHovered = blueHovered,
            bgAccentPressed = bluePressed,
            bgAccentSelected = Color(0x1C2E6BFF),
            bgAccentSubtle = blueSubtleLight,
            bgBadgeAccent = blueBadgeLight,
            borderAccentPrimary = brand,
            borderAccentSubtle = tealOnLight,
            iconAccentPrimary = brand,
            iconAccentTertiary = tealOnLight,
            textActionAccent = tealOnLight,
            textBadgeAccent = bluePressed,
            // textOnSolidPrimary ở giao diện sáng vốn đã là trắng — chữ trắng trên nút xanh: đúng.
        ),
        dark = compoundColorsDark.copy(
            // Nền và chữ
            bgCanvasDefault = bgBase,
            bgSubtlePrimary = bgSurface,
            bgSubtleSecondary = bgSurfaceHi,
            bgSubtleTertiary = bgSurfaceHi,
            textPrimary = textHi,
            textSecondary = textLo,
            iconPrimary = textHi,
            iconSecondary = textLo,
            iconTertiary = textLo,
            separatorPrimary = borderLo,
            separatorSecondary = borderLo,
            borderInteractivePrimary = borderHi,
            borderInteractiveSecondary = borderLo,
            // Hành động — nút chính, bong bóng gửi đi, tooltip, chip đang chọn.
            // Ở giao diện tối gốc, nút là nền gần-trắng + chữ gần-đen. Ta đổi sang nền xanh nên
            // BẮT BUỘC đổi luôn chữ/icon trên nền đó sang trắng, nếu không sẽ là đen trên xanh.
            bgActionPrimaryRest = brand,
            bgActionPrimaryHovered = blueHovered,
            bgActionPrimaryPressed = bluePressed,
            textOnSolidPrimary = Color(0xFFFFFFFF),
            iconOnSolidPrimary = Color(0xFFFFFFFF),
            // Nhấn mạnh
            bgAccentRest = brand,
            bgAccentHovered = blueHovered,
            bgAccentPressed = bluePressed,
            bgAccentSelected = blueSubtleDark,
            bgAccentSubtle = blueSubtleDark,
            bgBadgeAccent = blueBadgeDark,
            borderAccentPrimary = accent,
            borderAccentSubtle = accent,
            // iconAccentPrimary là NỀN của số tin chưa đọc, chữ trắng đè lên -> phải là xanh dương.
            iconAccentPrimary = brand,
            iconAccentTertiary = accent,
            // Liên kết và chữ nhấn nằm trên nền tối -> xanh ngọc tương phản rất tốt ở đây.
            textActionAccent = accent,
            textBadgeAccent = blueLight,
        ),
    )
}
