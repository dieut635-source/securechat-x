/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.element.android.features.logout.impl.R
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog

@Composable
fun LogoutConfirmationDialog(
    onSubmitClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Chuỗi SecureChat, không phải chuỗi kế thừa. Bản gốc chỉ hỏi "Are you sure you want to
    // sign out?" — đúng ở sản phẩm mà đăng nhập lại là việc mười giây tự làm được. Ở đây thì
    // không: máy sinh device_id mới nằm ngoài danh sách duyệt và phải có quản trị viên duyệt.
    // Xem securechat_strings.xml của module này.
    ConfirmationDialog(
        title = stringResource(id = R.string.securechat_signout_confirm_title),
        content = stringResource(id = R.string.securechat_signout_confirm_content),
        submitText = stringResource(id = R.string.securechat_signout_confirm_submit),
        onSubmitClick = onSubmitClick,
        onDismiss = onDismiss,
    )
}
