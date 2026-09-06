/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mdm.impl

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.RestrictionsManager
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(application = Application::class)
class DefaultMdmServiceTest : RobolectricTest() {
    @Test
    fun `an unavailable restrictions service publishes restrictions pending`() {
        val service = DefaultMdmService(contextWith(restrictionsManager = null))

        assertThat(service.config.value).isEqualTo(MdmConfig.restrictionsPending)
    }

    @Test
    fun `a null restrictions snapshot uses manual install defaults`() {
        val restrictionsManager = mockk<RestrictionsManager> {
            every { applicationRestrictions } returns null
        }

        val service = DefaultMdmService(contextWith(restrictionsManager))

        assertThat(service.config.value).isEqualTo(MdmConfig.default)
    }

    @Test
    fun `an empty restrictions snapshot uses manual install defaults`() {
        val restrictionsManager = mockk<RestrictionsManager> {
            every { applicationRestrictions } returns Bundle.EMPTY
        }

        val service = DefaultMdmService(contextWith(restrictionsManager))

        assertThat(service.config.value).isEqualTo(MdmConfig.default)
    }

    @Test
    fun `a malformed restrictions snapshot publishes restrictions pending`() {
        val restrictions = Bundle().apply {
            putString(MdmConfig.KEY_ALLOW_FILE_SEND, "true")
        }
        val restrictionsManager = mockk<RestrictionsManager> {
            every { applicationRestrictions } returns restrictions
        }

        val service = DefaultMdmService(contextWith(restrictionsManager))

        assertThat(service.config.value).isEqualTo(MdmConfig.restrictionsPending)
    }

    @Test
    fun `a startup reconciliation read failure overrides an earlier valid snapshot`() {
        val restrictions = Bundle().apply {
            putBoolean(MdmConfig.KEY_ALLOW_FILE_SEND, false)
        }
        var reads = 0
        val restrictionsManager = mockk<RestrictionsManager> {
            every { applicationRestrictions } answers {
                reads++
                if (reads == 1) restrictions else error("DPC temporarily unavailable")
            }
        }

        val service = DefaultMdmService(contextWith(restrictionsManager))

        assertThat(service.config.value).isEqualTo(MdmConfig.restrictionsPending)
        assertThat(reads).isEqualTo(2)
        verify(exactly = 2) { restrictionsManager.applicationRestrictions }
    }

    @Test
    fun `a failure while traversing the restrictions bundle publishes restrictions pending`() {
        val restrictions = mockk<Bundle> {
            every { keySet() } throws IllegalStateException("could not unparcel restrictions")
        }
        val restrictionsManager = mockk<RestrictionsManager> {
            every { applicationRestrictions } returns restrictions
        }

        val service = DefaultMdmService(contextWith(restrictionsManager))

        assertThat(service.config.value).isEqualTo(MdmConfig.restrictionsPending)
    }

    @Test
    fun `a failure while reading a restrictions value publishes restrictions pending`() {
        val restrictions = mockk<Bundle>()
        every { restrictions.keySet() } returns setOf(MdmConfig.KEY_ALLOW_FILE_SEND)
        @Suppress("DEPRECATION")
        every { restrictions.get(MdmConfig.KEY_ALLOW_FILE_SEND) } throws IllegalStateException("could not unparcel value")
        val restrictionsManager = mockk<RestrictionsManager> {
            every { applicationRestrictions } returns restrictions
        }

        val service = DefaultMdmService(contextWith(restrictionsManager))

        assertThat(service.config.value).isEqualTo(MdmConfig.restrictionsPending)
    }

    private fun contextWith(restrictionsManager: RestrictionsManager?): Context = object : ContextWrapper(
        RuntimeEnvironment.getApplication(),
    ) {
        override fun getSystemService(name: String): Any? = when (name) {
            Context.RESTRICTIONS_SERVICE -> restrictionsManager
            else -> super.getSystemService(name)
        }
    }
}
