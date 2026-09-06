/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.security

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.pip.DefaultPipSupportProvider
import io.element.android.features.call.impl.ui.ElementCallActivity
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class CallManifestSecurityTest : RobolectricTest() {
    @Suppress("DEPRECATION")
    @Test
    fun `call task is private absent from recents and runtime picture in picture is disabled`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, ElementCallActivity::class.java),
            0,
        )

        assertThat(activityInfo.exported).isFalse()
        assertThat(activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS).isNotEqualTo(0)
        assertThat(DefaultPipSupportProvider().isPipSupported()).isFalse()
    }
}
