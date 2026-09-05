/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.features.logout.api.direct.DirectLogoutState
import io.element.android.features.preferences.impl.userstatus.UserStatusState
import io.element.android.features.preferences.impl.utils.ShowDeveloperSettingsProvider
import io.element.android.features.rageshake.api.RageshakeFeatureAvailability
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.collectSnackbarMessageAsState
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.indicator.api.IndicatorService
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.verification.SessionVerificationService
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@Inject
class PreferencesRootPresenter(
    private val matrixClient: MatrixClient,
    private val sessionVerificationService: SessionVerificationService,
    private val analyticsService: AnalyticsService,
    private val versionFormatter: VersionFormatter,
    private val snackbarDispatcher: SnackbarDispatcher,
    private val indicatorService: IndicatorService,
    private val directLogoutPresenter: Presenter<DirectLogoutState>,
    private val showDeveloperSettingsProvider: ShowDeveloperSettingsProvider,
    private val rageshakeFeatureAvailability: RageshakeFeatureAvailability,
    private val featureFlagService: FeatureFlagService,
    private val userStatusPresenter: Presenter<UserStatusState>,
) : Presenter<PreferencesRootState> {
    @Composable
    override fun present(): PreferencesRootState {
        val coroutineScope = rememberCoroutineScope()
        val matrixUser = matrixClient.userProfile.collectAsState()
        LaunchedEffect(Unit) {
            // Force a refresh of the profile
            matrixClient.getUserProfile()
        }
        val isUserStatusSupported by produceState(false) {
            value = matrixClient.isUserStatusSupported().getOrDefault(false)
        }
        val userStatusState = if (isUserStatusSupported) userStatusPresenter.present() else null

        val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()
        val hasAnalyticsProviders = remember { analyticsService.getAvailableAnalyticsProviders().isNotEmpty() }

        // We should display the 'complete verification' option if the current session can be verified
        val canVerifyUserSession by sessionVerificationService.needsSessionVerification.collectAsState(false)

        val showSecureBackupIndicator by indicatorService.showSettingChatBackupIndicator()

        var canDeactivateAccount by remember {
            mutableStateOf(false)
        }
        val canReportBug by remember { rageshakeFeatureAvailability.isAvailable() }.collectAsState(false)
        LaunchedEffect(Unit) {
            canDeactivateAccount = matrixClient.canDeactivateAccount()
        }

        val nbOfBlockedUsers by produceState(initialValue = 0) {
            matrixClient.ignoredUsersFlow
                .onEach { value = it.size }
                .launchIn(this)
        }

        val showLabsItem = remember { featureFlagService.getAvailableFeatures(isInLabs = true).isNotEmpty() }

        val directLogoutState = directLogoutPresenter.present()

        val showDeveloperSettings by showDeveloperSettingsProvider.showDeveloperSettings.collectAsState()

        fun handleEvent(event: PreferencesRootEvent) {
            when (event) {
                is PreferencesRootEvent.OnVersionInfoClick -> {
                    showDeveloperSettingsProvider.unlockDeveloperSettings(coroutineScope)
                }
                // Kept for upstream API compatibility. SecureChat never exposes multi-account UI
                // and ignores synthetic/stale switch-account events.
                is PreferencesRootEvent.SwitchToSession -> Unit
            }
        }

        return PreferencesRootState(
            myUser = matrixUser.value,
            userStatusState = userStatusState,
            version = remember { versionFormatter.get() },
            isMultiAccountEnabled = false,
            otherSessions = persistentListOf(),
            showSecureBackup = !canVerifyUserSession,
            showSecureBackupBadge = showSecureBackupIndicator,
            // Browser account/device management is intentionally unavailable in app-only mode.
            accountManagementUrl = null,
            showAnalyticsSettings = hasAnalyticsProviders,
            canReportBug = canReportBug,
            showLinkNewDevice = false,
            showDeveloperSettings = showDeveloperSettings,
            canDeactivateAccount = canDeactivateAccount,
            nbOfBlockedUsers = nbOfBlockedUsers,
            showLabsItem = showLabsItem,
            // Có nút đăng xuất. Trước đây tắt vì "đăng ký máy chủ dùng một lần", nhưng đo lại
            // thì cái giá không phải là mất quyền dùng: đăng nhập lại sinh device_id mới nằm
            // ngoài danh sách duyệt, và quản trị viên duyệt nó trên dashboard — đúng quy trình
            // đang chạy hằng ngày. Đổi lại, không có nút đăng xuất thì người dùng KHÔNG có cách
            // nào rời tài khoản khỏi máy của chính họ, kể cả khi trả máy hay đổi người dùng.
            //
            // Hộp thoại xác nhận nói thẳng cái giá đó (xem securechat_strings.xml của module
            // logout), vì nó không hiển nhiên: ở sản phẩm khác, đăng xuất rồi đăng nhập lại là
            // việc tự làm được.
            showSignOut = true,
            directLogoutState = directLogoutState,
            snackbarMessage = snackbarMessage,
            eventSink = ::handleEvent,
        )
    }
}
