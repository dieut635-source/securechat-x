/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

/**
 * Những lối vào phòng mà triển khai kín này không dùng.
 *
 * Quy tắc do chủ sản phẩm đặt: **chỉ quản trị viên tạo phòng và gửi lời mời, có lời mời thì mới
 * vào được**. Hai lối dưới đây đều đi vòng qua quy tắc đó.
 *
 * Lối thứ ba — "Invite people to SecureChat" — không có cờ ở đây vì đã **xoá hẳn** khỏi mã
 * nguồn: nó gửi permalink chứa địa chỉ máy chủ ra SMS/WhatsApp, tức ra ngoài tầm kiểm soát,
 * và vô nghĩa khi tài khoản do quản trị viên tạo. Một cờ tắt vẫn để lại đường dẫn còn sống
 * trong bản build; xoá thì không.
 *
 * ⚠️ Đây là **giấu lối vào trên giao diện, KHÔNG phải chặn**. Một client khác — Element Web, hay
 * chính app này bị sửa — vẫn gọi được API. Chặn thật phải làm ở máy chủ (quy tắc join của phòng
 * và `room_list_publication_rules`). Xem docs/SERVER.md.
 */
object StartChatConfig {
    /**
     * Gõ địa chỉ phòng để vào thẳng.
     *
     * Vừa đi vòng qua lời mời, vừa buộc gợi ý phải in `:chat.securechat.com.au` ra màn hình vì
     * [io.element.android.libraries.matrix.api.room.alias.RoomAliasHelper] đòi địa chỉ đầy đủ.
     */
    const val CAN_JOIN_ROOM_BY_ADDRESS = false

    /**
     * Duyệt danh bạ phòng công khai rồi vào.
     *
     * Cùng một lỗ với trên, chỉ khác là không phải gõ gì: nếu có phòng nào lỡ để công khai thì
     * ai cũng vào được mà không cần ai mời.
     */
    const val CAN_SEARCH_ROOM_DIRECTORY = false
}
