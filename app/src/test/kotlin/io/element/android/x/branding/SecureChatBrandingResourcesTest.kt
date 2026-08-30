/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.branding

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.annotation.StringRes
import com.google.common.truth.Truth.assertWithMessage
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.x.R
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@Config(application = Application::class)
class SecureChatBrandingResourcesTest : RobolectricTest() {
    @Suppress("DEPRECATION")
    @Test
    fun `unverified HTTPS login links do not resolve to the app`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://chat.securechat.com.au/securechat/?account_provider=chat.securechat.com.au"),
        )

        val resolvedActivity = context.packageManager.resolveActivity(intent, 0)

        assertWithMessage("unverified HTTPS login link must stay disabled")
            .that(resolvedActivity?.activityInfo?.name)
            .isNull()
    }

    @Test
    fun `merged user-facing product strings contain no upstream branding`() {
        val context = RuntimeEnvironment.getApplication()
        val brandingResources = listOf(
            BrandingResource(R.string.element_call, requiredText = "SecureChat"),
            BrandingResource(R.string.screen_incoming_call_subtitle_android),
            BrandingResource(R.string.call_invalid_audio_device_bluetooth_devices_disabled),
            BrandingResource(R.string.screen_advanced_settings_element_call_base_url),
            BrandingResource(R.string.screen_advanced_settings_element_call_base_url_description),
            BrandingResource(R.string.screen_notification_settings_sound_element_default, requiredText = "SecureChat"),
            BrandingResource(R.string.screen_notification_settings_sound_element_fade, requiredText = "SecureChat"),
            BrandingResource(R.string.screen_room_timeline_legacy_call, requiredText = "SecureChat"),
            BrandingResource(R.string.screen_change_server_error_element_pro_required_title),
            BrandingResource(
                R.string.screen_change_server_error_element_pro_required_message,
                formatArgs = arrayOf("example.org"),
                requiredText = "SecureChat",
            ),
            BrandingResource(R.string.screen_missing_key_backup_open_element_classic),
            BrandingResource(R.string.screen_missing_key_backup_step_1),
            BrandingResource(R.string.screen_onboarding_welcome_title, requiredText = "SecureChat"),
            BrandingResource(R.string.screen_server_confirmation_message_login_element_dot_io),
            BrandingResource(
                R.string.screen_change_account_provider_matrix_org_subtitle,
                requiredText = SECURECHAT_HOMESERVER,
            ),
            BrandingResource(
                R.string.screen_change_server_textfield_footer_register,
                requiredText = SECURECHAT_HOMESERVER,
            ),
            BrandingResource(
                R.string.screen_start_chat_join_room_by_address_supporting_text,
                requiredText = SECURECHAT_HOMESERVER,
            ),
        )

        context.availableLocaleContexts().forEach { (localeTag, localizedContext) ->
            brandingResources.forEach { resource ->
                val resourceName = localizedContext.resources.getResourceEntryName(resource.id)
                val value = localizedContext.getString(resource.id, *resource.formatArgs)
                assertWithMessage("$resourceName in locale $localeTag")
                    .that(DEFINITIVE_UPSTREAM_BRAND.containsMatchIn(value))
                    .isFalse()
                resource.requiredText?.let { requiredText ->
                    assertWithMessage("$resourceName in locale $localeTag")
                        .that(value)
                        .contains(requiredText)
                }
            }
        }
    }

    private fun Context.availableLocaleContexts(): Map<String, Context> = buildMap {
        put("default", this@availableLocaleContexts)
        resources.assets.locales
            .filter(String::isNotBlank)
            .distinct()
            .forEach { localeTag ->
                val configuration = Configuration(resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(localeTag.replace('_', '-')))
                }
                put(localeTag, createConfigurationContext(configuration))
            }
    }

    private data class BrandingResource(
        @StringRes val id: Int,
        val formatArgs: Array<out Any> = emptyArray(),
        val requiredText: String? = null,
    )

    private companion object {
        const val SECURECHAT_HOMESERVER = "chat.securechat.com.au"

        // Use product-specific phrases and domains rather than a standalone "element" match.
        // Some translations legitimately use that word to mean an item or component.
        val DEFINITIVE_UPSTREAM_BRAND = Regex(
            "\\belement\\s+(x|pro|classic|call|android|default|fade)\\b|" +
                "element\\.(io|dev)|vector\\.im|riot\\.im|matrix\\.org",
            RegexOption.IGNORE_CASE,
        )
    }
}
