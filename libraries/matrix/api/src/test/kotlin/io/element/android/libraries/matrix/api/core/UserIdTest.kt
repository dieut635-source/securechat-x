/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserIdTest {
    @Test
    fun `valid user id`() {
        val userId = UserId("@alice:example.org")
        assertThat(userId.extractedDisplayName).isEqualTo("alice")
        assertThat(userId.domainName).isEqualTo("example.org")
    }

    /**
     * Định danh đem hiển thị không được mang theo tên miền máy chủ.
     *
     * Đây là chỗ duy nhất quy tắc đó được viết ra, nên nó phải được kiểm ở đây: mọi màn hình
     * đều gọi vào [UserId.displayLabel], và KonsistSecureChatTest cấm màn hình in thẳng
     * `userId.value`.
     */
    @Test
    fun `display label keeps the handle and drops the server address`() {
        assertThat(UserId("@alice:example.org").displayLabel).isEqualTo("@alice")
        assertThat(UserId("@test1:chat.securechat.com.au").displayLabel).isEqualTo("@test1")
        // Cổng trong tên miền cũng phải biến mất — nó cũng là địa chỉ máy chủ.
        assertThat(UserId("@bob:example.org:8448").displayLabel).isEqualTo("@bob")
    }
}
