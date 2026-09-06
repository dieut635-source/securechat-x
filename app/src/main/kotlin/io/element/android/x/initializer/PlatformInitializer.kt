/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.initializer

import android.content.Context
import android.system.Os
import androidx.startup.Initializer
import io.element.android.features.rageshake.api.logs.createWriteToFilesConfiguration
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.meta.BuildType
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.tracing.LogLevel
import io.element.android.libraries.matrix.api.tracing.TracingConfiguration
import io.element.android.libraries.matrix.api.tracing.WriteToFilesConfiguration
import io.element.android.x.di.AppBindings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

private const val SECURECHAT_TARGET = "securechat"

class PlatformInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val appBindings = context.bindings<AppBindings>()
        val tracingService = appBindings.tracingService()
        val platformService = appBindings.platformService()
        val bugReporter = appBindings.bugReporter()
        Timber.plant(tracingService.createTimberTree(SECURECHAT_TARGET))
        val preferencesStore = appBindings.preferencesStore()
        val featureFlagService = appBindings.featureFlagService()
        val isReleaseBuild = appBindings.buildMeta().buildType == BuildType.RELEASE
        val logLevel = if (isReleaseBuild) {
            LogLevel.ERROR
        } else {
            runBlocking { preferencesStore.getTracingLogLevelFlow().first() }
        }
        val tracingConfiguration = TracingConfiguration(
            writesToLogcat = !isReleaseBuild && runBlocking { featureFlagService.isFeatureEnabled(FeatureFlags.PrintLogsToLogcat) },
            writesToFilesConfiguration = if (isReleaseBuild) {
                WriteToFilesConfiguration.Disabled
            } else {
                bugReporter.createWriteToFilesConfiguration()
            },
            logLevel = logLevel,
            extraTargets = listOf(SECURECHAT_TARGET),
            traceLogPacks = if (isReleaseBuild) emptySet() else runBlocking { preferencesStore.getTracingLogPacksFlow().first() },
            sdkSentryDsn = if (isReleaseBuild) null else appBindings.sentrySdkDsn()?.value?.takeIf { it.isNotBlank() },
        )
        bugReporter.setCurrentTracingLogLevel(logLevel.name)
        platformService.init(tracingConfiguration)
        // Backtraces are useful to local developers but can retain sensitive runtime context.
        Os.setenv("RUST_BACKTRACE", if (isReleaseBuild) "0" else "1", true)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = mutableListOf()
}
