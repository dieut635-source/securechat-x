/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.compound.tokens

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.element.android.compound.R

/**
 * Bộ chữ của SecureChat.
 *
 * Element X dùng [FontFamily.Default], tức là font hệ thống — trên Android là Roboto, nhưng trên
 * máy Samsung mặc định lại là One UI Sans, và trên máy Xiaomi là MiSans. Giao diện vì thế khác
 * nhau tuỳ hãng. Nhúng font vào app để mọi máy hiển thị giống nhau.
 *
 * Cả hai font đều giấy phép SIL Open Font License 1.1 (bản gốc ở res/raw/license_*.txt).
 */
object SecureChatFonts {
    /** Inter — dùng cho toàn bộ chữ trong app. */
    val sans = FontFamily(
        Font(R.font.securechat_sans_regular, FontWeight.W400),
        Font(R.font.securechat_sans_semibold, FontWeight.W500),
        Font(R.font.securechat_sans_semibold, FontWeight.W600),
        Font(R.font.securechat_sans_bold, FontWeight.W700),
    )

    /**
     * JetBrains Mono — dùng cho mã xác minh và khoá khôi phục.
     * Chữ đều bề rộng nên dễ đọc từng ký tự, và phân biệt rõ 0/O, 1/l/I — thứ quyết định khi
     * người dùng phải đối chiếu mã giữa hai thiết bị.
     */
    val mono = FontFamily(
        Font(R.font.securechat_mono_regular, FontWeight.W400),
    )
}
