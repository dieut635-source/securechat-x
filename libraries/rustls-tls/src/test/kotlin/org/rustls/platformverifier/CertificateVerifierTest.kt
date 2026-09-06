/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package org.rustls.platformverifier

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Collections

class CertificateVerifierTest {
    @Test
    fun `system certificate selector excludes user and unknown aliases`() {
        val aliases = Collections.enumeration(
            listOf(
                "user:securechat-proxy.0",
                "system:12345678.0",
                "unexpected:12345678.0",
                "system:abcdef01.1",
            )
        )

        assertThat(selectSystemCertificateAliases(aliases)).containsExactly(
            "system:12345678.0",
            "system:abcdef01.1",
        ).inOrder()
    }

    @Test
    fun `system certificate selector fails closed for an empty alias list`() {
        val aliases = Collections.enumeration(emptyList<String>())

        assertThat(selectSystemCertificateAliases(aliases)).isEmpty()
    }
}
