/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.securebackup.impl.reset.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.securebackup.impl.R
import io.element.android.libraries.designsystem.atomic.pages.FlowStepPage
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.visuallist.VisualList
import io.element.android.libraries.designsystem.components.visuallist.VisualListItemData
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.collections.immutable.persistentListOf

@Composable
fun ResetIdentityRootView(
    state: ResetIdentityRootState,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Đây KHÔNG phải màn hình sự cố, dù mã kế thừa dựng nó như vậy.
    //
    // Bản gốc dùng BigIcon.Style.AlertSolid: nền đỏ, biểu tượng ErrorSolid, và nhãn trợ năng
    // là CommonStrings.common_error — trình đọc màn hình đọc thành "Error". Cộng với tiêu đề
    // "Can't confirm? You'll need to reset your digital identity." và nút đỏ "Continue reset",
    // khách hàng vừa được cấp máy bị nói rằng họ đã làm sai một việc mà họ không hề làm sai.
    //
    // Trong sản phẩm này màn hình chỉ tới được từ FTUE (xem securechat_strings.xml và
    // KonsistSecureChatTest), nên nó luôn là bước thiết lập, không bao giờ là cứu hộ.
    // Biểu tượng khoá, cùng biểu tượng với màn hình trước, giữ hai bước liền mạch.
    FlowStepPage(
        modifier = modifier,
        iconStyle = BigIcon.Style.Default(CompoundIcons.LockSolid()),
        title = stringResource(R.string.securechat_identity_setup_details_title),
        isScrollable = true,
        content = { Content() },
        buttons = {
            Button(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(id = R.string.securechat_identity_setup_details_action),
                onClick = { state.eventSink(ResetIdentityRootEvent.Continue) },
            )
        },
        onBackClick = onBack,
    )

    if (state.displayConfirmationDialog) {
        // Hộp xác nhận GIỮ LẠI: việc này không quay lại được, và một lần hỏi trước điểm
        // không quay lại là đúng. Cái bỏ đi là kiểu trình bày nguy hiểm (destructiveSubmit)
        // và chữ "Are you sure you want to reset…" — nó hợp với người lỡ tay, không hợp với
        // người đang làm đúng bước bắt buộc duy nhất.
        ConfirmationDialog(
            title = stringResource(R.string.securechat_identity_setup_confirm_title),
            content = stringResource(R.string.securechat_identity_setup_confirm_subtitle),
            submitText = stringResource(R.string.securechat_identity_setup_confirm_action),
            onSubmitClick = {
                state.eventSink(ResetIdentityRootEvent.DismissDialog)
                onContinue()
            },
            onDismiss = { state.eventSink(ResetIdentityRootEvent.DismissDialog) }
        )
    }
}

@Composable
private fun Content() {
    Column(
        modifier = Modifier.padding(top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        VisualList(
            modifier = Modifier.fillMaxWidth(),
            items = persistentListOf(
                VisualListItemData(
                    message = stringResource(R.string.securechat_identity_setup_details_bullet_1),
                    iconComposable = {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            imageVector = CompoundIcons.Check(),
                            contentDescription = null,
                            tint = ElementTheme.colors.iconSuccessPrimary,
                        )
                    },
                ),
                VisualListItemData(
                    message = stringResource(R.string.securechat_identity_setup_details_bullet_2),
                    iconComposable = {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            imageVector = CompoundIcons.Info(),
                            contentDescription = null,
                            tint = ElementTheme.colors.iconSecondary,
                        )
                    },
                ),
                VisualListItemData(
                    message = stringResource(R.string.securechat_identity_setup_details_bullet_3),
                    iconComposable = {
                        Icon(
                            modifier = Modifier.size(20.dp),
                            imageVector = CompoundIcons.Info(),
                            contentDescription = null,
                            tint = ElementTheme.colors.iconSecondary,
                        )
                    },
                ),
            ),
        )

        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.securechat_identity_setup_details_footer),
            style = ElementTheme.typography.fontBodyMdMedium,
            color = ElementTheme.colors.textActionPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun ResetIdentityRootViewPreview(@PreviewParameter(ResetIdentityRootStatePreviewParam::class) state: ResetIdentityRootState) {
    ElementPreview {
        ResetIdentityRootView(
            state = state,
            onContinue = {},
            onBack = {},
        )
    }
}
