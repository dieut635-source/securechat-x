/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import com.google.common.truth.Truth.assertThat
import io.element.android.features.enterprise.test.FakeEnterpriseService
import io.element.android.libraries.matrix.impl.FakeClientBuilderProvider
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClient
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClientBuilder
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiHomeserverLoginDetails
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RustHomeserverLoginCompatibilityCheckerTest {
    @Test
    fun `check - is not valid if it only supports OAuth login`() = runTest {
        val sut = createChecker { FakeFfiHomeserverLoginDetails(supportsOAuthLogin = true) }
        assertThat(sut.check("https://matrix.host.org").getOrNull()).isFalse()
    }

    @Test
    fun `check - is valid if it supports password login`() = runTest {
        val sut = createChecker { FakeFfiHomeserverLoginDetails(supportsPasswordLogin = true) }
        assertThat(sut.check("https://matrix.host.org").getOrNull()).isTrue()
    }

    @Test
    fun `check - is not valid if it only supports SSO login`() = runTest {
        val sut = createChecker { FakeFfiHomeserverLoginDetails(supportsSsoLogin = true) }
        assertThat(sut.check("https://matrix.host.org").getOrNull()).isFalse()
    }

    @Test
    fun `check - is not valid if fetching the data fails`() = runTest {
        val sut = createChecker { error("Unexpected error!") }
        assertThat(sut.check("https://matrix.host.org").isFailure).isTrue()
    }

    @Test
    fun `check - rejects an unapproved homeserver before creating a client`() = runTest {
        var clientBuilderCreated = false
        val sut = RustHomeServerLoginCompatibilityChecker(
            clientBuilderProvider = FakeClientBuilderProvider {
                clientBuilderCreated = true
                FakeFfiClientBuilder()
            },
            enterpriseService = FakeEnterpriseService(
                isAllowedToConnectToHomeserverResult = { false },
            ),
        )

        assertThat(sut.check("https://attacker.invalid").getOrNull()).isFalse()
        assertThat(clientBuilderCreated).isFalse()
    }

    private fun createChecker(
        result: () -> FakeFfiHomeserverLoginDetails,
    ) = RustHomeServerLoginCompatibilityChecker(
        clientBuilderProvider = FakeClientBuilderProvider {
            FakeFfiClientBuilder {
                FakeFfiClient(homeserverLoginDetailsResult = result)
            }
        },
        enterpriseService = FakeEnterpriseService(
            isAllowedToConnectToHomeserverResult = { true },
        ),
    )
}
