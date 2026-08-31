/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.pushproviders.unifiedpush.network.DiscoveryResponse
import io.element.android.libraries.pushproviders.unifiedpush.network.DiscoveryUnifiedPush
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.HttpURLConnection

internal val matrixDiscoveryResponse = {
    DiscoveryResponse(
        unifiedpush = DiscoveryUnifiedPush(
            gateway = "matrix"
        )
    )
}

internal val invalidDiscoveryResponse = {
    DiscoveryResponse(
        unifiedpush = DiscoveryUnifiedPush(
            gateway = ""
        )
    )
}

class DefaultUnifiedPushGatewayResolverTest {
    @Test
    fun `when a custom url provide a correct matrix gateway, the custom url is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = matrixDiscoveryResponse
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("https://push.securechat.com.au")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("https://push.securechat.com.au")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.Success("https://push.securechat.com.au/_matrix/push/v1/notify"))
    }

    @Test
    fun `when a custom url with port provides a correct matrix gateway, the custom url is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = matrixDiscoveryResponse
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("https://push.securechat.com.au:123")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("https://push.securechat.com.au:123")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.Success("https://push.securechat.com.au:123/_matrix/push/v1/notify"))
    }

    @Test
    fun `when a custom url with port and path provides a correct matrix gateway, the custom url is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = matrixDiscoveryResponse
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("https://push.securechat.com.au:123/some/path")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("https://push.securechat.com.au:123")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.Success("https://push.securechat.com.au:123/_matrix/push/v1/notify"))
    }

    @Test
    fun `when a custom url with http scheme provides a correct matrix gateway, the custom url is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = matrixDiscoveryResponse
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("http://push.securechat.com.au:123/some/path")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("http://push.securechat.com.au:123")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.Success("http://push.securechat.com.au:123/_matrix/push/v1/notify"))
    }

    @Test
    fun `when a custom url is not reachable, the custom url is still returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = { throw AN_EXCEPTION }
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("http://push.securechat.com.au")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("http://push.securechat.com.au")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.Error("http://push.securechat.com.au/_matrix/push/v1/notify"))
    }

    @Test
    fun `when a custom url is not found (404), NoMatrixGateway is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = {
                throw HttpException(Response.error<Unit>(HttpURLConnection.HTTP_NOT_FOUND, "".toResponseBody()))
            }
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("http://push.securechat.com.au")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("http://push.securechat.com.au")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.NoMatrixGateway)
    }

    @Test
    fun `when a custom url is forbidden (403), NoMatrixGateway is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = {
                throw HttpException(Response.error<Unit>(HttpURLConnection.HTTP_FORBIDDEN, "".toResponseBody()))
            }
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("http://push.securechat.com.au")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("http://push.securechat.com.au")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.NoMatrixGateway)
    }

    @Test
    fun `when a custom url is not acceptable (406), NoMatrixGateway is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = {
                throw HttpException(Response.error<Unit>(HttpURLConnection.HTTP_NOT_ACCEPTABLE, "".toResponseBody()))
            }
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("http://push.securechat.com.au")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("http://push.securechat.com.au")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.NoMatrixGateway)
    }

    @Test
    fun `when a custom url is internal error (500), Error is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = {
                throw HttpException(Response.error<Unit>(HttpURLConnection.HTTP_INTERNAL_ERROR, "".toResponseBody()))
            }
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("http://push.securechat.com.au")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("http://push.securechat.com.au")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.Error("http://push.securechat.com.au/_matrix/push/v1/notify"))
    }

    /**
     * SecureChat: refuse any gateway that is not SecureChat's own.
     *
     * A device whose ntfy app was never pointed at SecureChat's server keeps ntfy's default,
     * https://ntfy.sh. That public host really does advertise a Matrix gateway, so without this
     * check resolution succeeds and every push is routed through a third party, leaking who got a
     * message, in which room, and when. ntfy has no managed-configuration support, so no MDM can
     * stop that on the device; it has to be refused here.
     *
     * The refusal must happen BEFORE any request, so a wrong host is never even contacted.
     */
    @Test
    fun `SecureChat - the public ntfy dot sh gateway is refused and never contacted`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = matrixDiscoveryResponse
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("https://ntfy.sh/upAbCdEf?up=1")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.ErrorInvalidUrl)
        // Never contacted: the host was rejected before any network call.
        assertThat(unifiedPushApiFactory.baseUrlParameter).isNull()
    }

    @Test
    fun `SecureChat - a host merely ending with the allowed one is refused`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = matrixDiscoveryResponse
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("https://push.securechat.com.au.attacker.invalid/upAbCdEf")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.ErrorInvalidUrl)
        assertThat(unifiedPushApiFactory.baseUrlParameter).isNull()
    }

    @Test
    fun `SecureChat - the allowed host is matched case-insensitively`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = matrixDiscoveryResponse
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("https://PUSH.SecureChat.COM.AU/upAbCdEf")
        assertThat(result).isInstanceOf(UnifiedPushGatewayResolverResult.Success::class.java)
    }

    @Test
    fun `when a custom url is invalid, ErrorInvalidUrl is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = matrixDiscoveryResponse
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("invalid")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isNull()
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.ErrorInvalidUrl)
    }

    @Test
    fun `when a custom url provides a invalid matrix gateway, NoMatrixGateway is returned`() = runTest {
        val unifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = invalidDiscoveryResponse
        )
        val sut = createDefaultUnifiedPushGatewayResolver(
            unifiedPushApiFactory = unifiedPushApiFactory
        )
        val result = sut.getGateway("https://push.securechat.com.au")
        assertThat(unifiedPushApiFactory.baseUrlParameter).isEqualTo("https://push.securechat.com.au")
        assertThat(result).isEqualTo(UnifiedPushGatewayResolverResult.NoMatrixGateway)
    }

    private fun TestScope.createDefaultUnifiedPushGatewayResolver(
        unifiedPushApiFactory: UnifiedPushApiFactory = FakeUnifiedPushApiFactory(
            discoveryResponse = { DiscoveryResponse() }
        )
    ) = DefaultUnifiedPushGatewayResolver(
        unifiedPushApiFactory = unifiedPushApiFactory,
        coroutineDispatchers = testCoroutineDispatchers()
    )
}
