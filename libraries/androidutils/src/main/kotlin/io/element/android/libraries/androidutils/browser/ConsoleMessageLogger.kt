/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.browser

import android.util.Log
import android.webkit.ConsoleMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.core.meta.BuildType
import timber.log.Timber

interface ConsoleMessageLogger {
    fun log(
        tag: String,
        consoleMessage: ConsoleMessage,
    )
}

@ContributesBinding(AppScope::class)
class DefaultConsoleMessageLogger(
    private val buildMeta: BuildMeta,
) : ConsoleMessageLogger {
    override fun log(
        tag: String,
        consoleMessage: ConsoleMessage,
    ) {
        // Web content can put credentials and widget payloads in console messages. Keep
        // console forwarding available for developer builds only.
        if (buildMeta.buildType == BuildType.RELEASE) return

        val priority = when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
            else -> Log.DEBUG
        }

        // Avoid logging any messages that contain "password" to prevent leaking sensitive information
        if (consoleMessage.message().contains("password=")) {
            return
        }

        val message = buildString {
            append(consoleMessage.sourceId())
            append(":")
            append(consoleMessage.lineNumber())
            append(" ")
            append(consoleMessage.message())
        }

        Timber.tag(tag).log(priority = priority, message = message)
    }
}
