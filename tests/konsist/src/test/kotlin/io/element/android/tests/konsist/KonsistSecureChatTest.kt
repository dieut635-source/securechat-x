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
    private companion object {
        /** `text = ...userId.value` / `subtext = ...userId.value` — gán thẳng vào chỗ hiển thị. */
        private val DISPLAY_ASSIGNMENT = Regex("""(?:text|subtext|subtitle|label)\s*=\s*[^\n]*\buserId\.value\b""", RegexOption.IGNORE_CASE)
    }

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
    /**
     * Không màn hình nào được in định danh Matrix đầy đủ ra giao diện.
     *
     * `@test1:chat.securechat.com.au` in ra màn hình là in địa chỉ máy chủ ra màn hình. Khách
     * hàng đọc được nó có thể mở trình duyệt vào thẳng trang web — đúng thứ mà chính sách
     * "chỉ dùng trên app" muốn chặn. Phần sau dấu ":" cũng không mang thông tin gì cho họ:
     * liên kết liên máy chủ đang tắt nên mọi tài khoản đều cùng một máy chủ.
     *
     * Ben tìm ra hai chỗ (cài đặt và sửa hồ sơ). Rà ra thì có mười tám. Test này giữ cho con
     * số đó không mọc lại: dùng [UserId.displayLabel] khi chữ sẽ hiện ra màn hình, `value`
     * chỉ khi cần định danh thật để gọi API hoặc so sánh.
     */
    @Test
    fun `no screen prints the full Matrix id`() {
        val offenders = Konsist
            .scopeFromProduction()
            .files
            .filter { file ->
                // Chỉ bắt chỗ ĐEM HIỂN THỊ. Lớp SDK vẫn phải dùng value để gọi API, và việc
                // đó đúng — cấm hết sẽ thành cấm cả cái phải làm.
                DISPLAY_ASSIGNMENT.containsMatchIn(file.text)
            }
            .map { it.name }
            .toSet()

        assertThat(offenders).isEmpty()
    }

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
