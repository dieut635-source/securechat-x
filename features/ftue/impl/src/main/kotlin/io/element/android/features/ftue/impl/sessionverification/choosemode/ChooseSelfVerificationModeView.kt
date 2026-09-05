/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.ftue.impl.sessionverification.choosemode

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.ftue.impl.R
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.atomic.atoms.LoadingButtonAtom
import io.element.android.libraries.designsystem.atomic.molecules.ButtonColumnMolecule
import io.element.android.libraries.designsystem.atomic.molecules.IconTitleSubtitleMolecule
import io.element.android.libraries.designsystem.atomic.pages.HeaderFooterPage
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.ui.strings.CommonStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseSelfVerificationModeView(
    state: ChooseSelfVerificationModeState,
    onUseAnotherDevice: () -> Unit,
    onUseRecoveryKey: () -> Unit,
    onResetKey: () -> Unit,
    onLearnMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activity = LocalActivity.current
    BackHandler {
        activity?.finish()
    }
    HeaderFooterPage(
        modifier = modifier,
        topBar = {
            TopAppBar(title = {})
        },
        header = {
            // Khi không có cách nào để xác minh thì đừng hỏi người dùng chọn cách.
            //
            // Màn hình kế thừa hỏi "Choose how to verify" rồi hiện hai nút CÓ ĐIỀU KIỆN. Trong
            // sản phẩm này cả hai điều kiện đều sai vĩnh viễn: chính sách một tài khoản một máy
            // nên không có thiết bị thứ hai để xác minh chéo, và đã chốt không sao lưu khoá nên
            // không có recovery key. Người dùng nhận được một câu hỏi không có lựa chọn nào.
            val hasVerificationOption = (state.buttonsState as? AsyncData.Success)?.data
                ?.let { it.canUseAnotherDevice || it.canUseRecoveryKey } == true
            IconTitleSubtitleMolecule(
                modifier = Modifier.padding(bottom = 16.dp),
                iconStyle = BigIcon.Style.Default(CompoundIcons.LockSolid()),
                title = stringResource(
                    id = if (hasVerificationOption) {
                        R.string.screen_identity_confirmation_title
                    } else {
                        R.string.securechat_identity_setup_title
                    }
                ),
                subTitle = stringResource(
                    id = if (hasVerificationOption) {
                        R.string.screen_identity_confirmation_subtitle
                    } else {
                        R.string.securechat_identity_setup_subtitle
                    }
                )
            )
        },
        footer = {
            ChooseSelfVerificationModeButtons(
                state = state,
                onUseAnotherDevice = onUseAnotherDevice,
                onUseRecoveryKey = onUseRecoveryKey,
                onResetKey = onResetKey,
            )
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                modifier = Modifier
                    .clickable(onClick = onLearnMore)
                    .padding(vertical = 4.dp, horizontal = 16.dp)
                    .semantics {
                        // Note: there is no Role.Link, so we use Role.Button for better accessibility support
                        role = Role.Button
                    },
                text = stringResource(CommonStrings.action_learn_more),
                style = ElementTheme.typography.fontBodyLgMedium
            )
        }
    }
}

@Composable
private fun ChooseSelfVerificationModeButtons(
    state: ChooseSelfVerificationModeState,
    onUseAnotherDevice: () -> Unit,
    onUseRecoveryKey: () -> Unit,
    onResetKey: () -> Unit,
) {
    ButtonColumnMolecule(
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        when (state.buttonsState) {
            AsyncData.Uninitialized,
            is AsyncData.Failure,
            is AsyncData.Loading -> {
                LoadingButtonAtom()
            }
            is AsyncData.Success -> {
                if (state.buttonsState.data.canUseAnotherDevice) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.screen_identity_use_another_device),
                        onClick = onUseAnotherDevice,
                    )
                }
                if (state.buttonsState.data.canUseRecoveryKey) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.screen_identity_confirmation_use_recovery_key),
                        onClick = onUseRecoveryKey,
                    )
                }
                val hasVerificationOption = state.buttonsState.data.canUseAnotherDevice ||
                    state.buttonsState.data.canUseRecoveryKey
                if (hasVerificationOption) {
                    // Có cách xác minh: đặt lại khoá là phương án cuối, giữ đúng vai trò phụ.
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.screen_identity_confirmation_cannot_confirm),
                        onClick = onResetKey,
                    )
                } else {
                    // Không có cách nào khác: đây là con đường DUY NHẤT, nên nó là nút chính và
                    // mang nhãn nói việc nó làm. Gắn nhãn "Can't confirm?" lên lối đi duy nhất
                    // là bảo người dùng rằng họ vừa thất bại, trong khi họ chỉ đang làm đúng
                    // việc phải làm.
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.securechat_identity_setup_action),
                        onClick = onResetKey,
                    )
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun ChooseSelfVerificationModeViewPreview(
    @PreviewParameter(ChooseSelfVerificationModeStatePreviewParam::class) state: ChooseSelfVerificationModeState
) = ElementPreview {
    ChooseSelfVerificationModeView(
        state = state,
        onUseAnotherDevice = {},
        onUseRecoveryKey = {},
        onResetKey = {},
        onLearnMore = {},
    )
}
