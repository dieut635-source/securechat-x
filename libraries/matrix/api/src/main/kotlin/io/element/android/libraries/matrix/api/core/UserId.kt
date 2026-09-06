/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.core

import io.element.android.libraries.androidutils.metadata.isInDebug
import java.io.Serializable

/**
 * A [String] holding a valid Matrix user ID.
 *
 * https://spec.matrix.org/v1.8/appendices/#user-identifiers
 */
@JvmInline
value class UserId(val value: String) : Serializable {
    init {
        if (isInDebug && !MatrixPatterns.isUserId(value)) {
            error("`$value` is not a valid user id.\nExample user id: `@name:domain`.")
        }
    }

    override fun toString(): String = value

    val extractedDisplayName: String
        get() = value
            .removePrefix("@")
            .substringBefore(":")

    val domainName: String?
        get() = value.substringAfter(":").takeIf { it.isNotEmpty() }

    /**
     * Định danh để HIỂN THỊ cho người dùng cuối: giữ "@" và tên, bỏ tên miền máy chủ.
     *
     * Ở SecureChat, tên miền trong định danh không mang thông tin gì cho người dùng — liên kết
     * liên máy chủ đang TẮT (`federation_domain_whitelist: []`), nên mọi tài khoản đều ở cùng
     * một máy chủ và phần sau dấu ":" luôn giống hệt nhau. Nhưng nó mang thông tin cho người
     * KHÁC: nó in địa chỉ máy chủ ra màn hình, ở phần cài đặt, hồ sơ, danh sách thành viên
     * phòng, kết quả tìm người... Khách hàng nhìn thấy địa chỉ đó có thể mở trình duyệt vào
     * thẳng trang web, đúng thứ mà chính sách "chỉ dùng trên app" muốn chặn.
     *
     * Dùng [value] khi cần định danh THẬT (gọi API, so sánh, ghi log). Dùng cái này khi chữ đó
     * sẽ hiện ra màn hình.
     *
     * ⚠️ Nếu sau này BẬT liên kết liên máy chủ thì phải xem lại: lúc đó hai người khác máy chủ
     * có thể trùng phần tên, và bỏ tên miền sẽ làm họ trông giống nhau.
     */
    val displayLabel: String
        get() = "@$extractedDisplayName"
}
