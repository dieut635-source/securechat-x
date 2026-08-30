/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.getSystemService

class CopyToClipboardUseCase(
    private val context: Context,
) {
    fun execute(
        text: CharSequence,
        isSensitive: Boolean = false,
    ) {
        val clipboardManager = context.getSystemService<ClipboardManager>() ?: return
        val clip = ClipData.newPlainText("", text)
        if (isSensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboardManager.setPrimaryClip(clip)
        if (isSensitive) {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    val currentText = clipboardManager.primaryClip
                        ?.takeIf { it.itemCount == 1 }
                        ?.getItemAt(0)
                        ?.text
                    if (currentText == text) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            clipboardManager.clearPrimaryClip()
                        } else {
                            clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                },
                SENSITIVE_CLIPBOARD_TIMEOUT_MILLIS,
            )
        }
    }

    private companion object {
        // ClipDescription.EXTRA_IS_SENSITIVE, kept as a literal for Android 7-12 compatibility.
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        const val SENSITIVE_CLIPBOARD_TIMEOUT_MILLIS = 30_000L
    }
}
