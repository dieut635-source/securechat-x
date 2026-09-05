/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.tests.konsist

import com.google.common.truth.Truth.assertThat
import com.lemonappdev.konsist.api.Konsist
import org.junit.Test

class KonsistSecureChatTest {
    /**
     * Ghim số lối vào màn hình đặt lại danh tính.
     *
     * `ResetIdentityRootView` đã được viết lại thành bước THIẾT LẬP máy mới (Q12): biểu tượng
     * khoá thay cho biểu tượng lỗi, "Set up encryption on this device" thay cho "Can't confirm?
     * You'll need to reset your digital identity."
     *
     * Cách viết đó chỉ đúng vì đo được rằng màn hình chỉ tới được từ FTUE. Từ Settings chỉ tới
     * được `Root` và `EnterRecoveryKey`. Nếu sau này ai đó mở thêm lối vào — ví dụ Settings →
     * Encryption → reset — thì cùng màn hình sẽ nói sai: người dùng chủ động đặt lại sau sự cố
     * sẽ được chào bằng "Set up encryption on this device".
     *
     * Giả định làm cho bản sửa đúng thì phải chạy được, không phải nằm trong chú thích. Test này
     * đỏ đúng lúc giả định đó hết đúng.
     *
     * Đã kiểm bằng đột biến: thêm `InitialTarget.ResetIdentity` vào LoggedInFlowNode thì test đỏ.
     * Lưu ý khi chạy tay: build.gradle.kts của module này cho phép Gradle đánh dấu UP-TO-DATE khi
     * tên task không chứa "check", mà Konsist đọc mã nguồn lúc chạy chứ không qua input của Gradle
     * — nên `./gradlew :tests:konsist:test` sau khi sửa module khác có thể KHÔNG chạy lại. Dùng
     * `check` hoặc `--rerun-tasks`. Lần đầu tôi kết luận sai là "test không bắt được" vì lý do này.
     */
    @Test
    fun `reset identity screen is reachable only from the FTUE flow`() {
        val files = Konsist
            .scopeFromProduction()
            .files
            .filter { it.text.contains("InitialTarget.ResetIdentity") }
            .map { it.name }
            .toSet()

        assertThat(files).isEqualTo(
            setOf(
                // Nơi phân giải đích thành node — không phải lối vào.
                "SecureBackupFlowNode",
                // Lối vào DUY NHẤT.
                "FtueSessionVerificationFlowNode",
            )
        )
    }
}
