/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.intent

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.x.R
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(application = Application::class)
class InboundLinkManifestTest : RobolectricTest() {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `OAuth callback endpoint is not claimed`() {
        val scheme = context.getString(R.string.login_redirect_scheme)
        assertThat(resolve("$scheme://oauth/callback?state=state&code=code")).isNull()
    }

    @Test
    fun `other paths on the OAuth scheme are not claimed`() {
        val scheme = context.getString(R.string.login_redirect_scheme)

        assertThat(resolve("$scheme://oauth/other?state=state&code=code")).isNull()
        assertThat(resolve("$scheme:/?state=state&code=code")).isNull()
    }

    @Test
    fun `unverified HTTPS login configuration links are not claimed`() {
        assertThat(
            resolve("https://chat.securechat.com.au/securechat/?account_provider=chat.securechat.com.au")
        ).isNull()
    }

    private fun resolve(uri: String): String? {
        val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
        return context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name
    }
}
