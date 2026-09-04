/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.securechat.dpc

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mdm.api.MdmConfig
import io.element.android.libraries.mdm.api.MdmService
import io.element.android.libraries.sessionstorage.test.InMemorySessionStore
import io.element.android.libraries.sessionstorage.test.aSessionData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

/**
 * Who is allowed to order this handset erased.
 *
 * Almost every test here is about refusing. A wipe channel is judged by what it turns down, not by
 * what it obeys: the one thing worse than a wipe that does not arrive is a wipe that arrives from
 * somebody who had no business sending it.
 */
class SecureChatRemoteCommandPollerTest {
    private class FakeDevicePolicyGateway(
        override var isDeviceOwner: Boolean = true,
    ) : DevicePolicyGateway {
        var wipeCount = 0
        override fun applyRestrictions(restrictions: Map<String, Any>): Result<Unit> = Result.success(Unit)
        override fun readRestrictions(): Result<Map<String, Any>> = Result.success(emptyMap())
        override fun wipeDevice(): Result<Unit> {
            wipeCount++
            return Result.success(Unit)
        }
        override fun relinquishDeviceOwner(): Result<Unit> = error("must never be reached")
    }

    private class FakeMdmService(homeserverUrl: String) : MdmService {
        override val config: StateFlow<MdmConfig> =
            MutableStateFlow(MdmConfig.default.copy(homeserverUrl = homeserverUrl))
    }

    private fun poller(
        server: MockWebServer,
        gateway: FakeDevicePolicyGateway,
        sessionHomeserver: String = server.url("/").toString().trimEnd('/'),
        managedHomeserver: String = server.url("/").toString().trimEnd('/'),
        tokenValid: Boolean = true,
    ) = SecureChatRemoteCommandPoller(
        sessionStore = InMemorySessionStore(
            listOf(
                aSessionData(
                    sessionId = "@test1:chat.securechat.com.au",
                    deviceId = "SC-DEVICE",
                    accessToken = "syt_token",
                    homeserverUrl = sessionHomeserver,
                    isTokenValid = tokenValid,
                )
            )
        ),
        deviceOwner = SecureChatDeviceOwner(gateway),
        mdmService = FakeMdmService(managedHomeserver),
        okHttpClient = OkHttpClient(),
        coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

    private fun MockWebServer.replyCommand(command: String?) = enqueue(
        MockResponse().setBody(
            if (command == null) """{"command":null}""" else """{"command":"$command","reason":"lost handset"}"""
        )
    )

    @Test
    fun `an ordered wipe from the managed homeserver is carried out`() = runTest {
        val server = MockWebServer()
        val gateway = FakeDevicePolicyGateway()
        server.replyCommand("wipe_device")

        poller(server, gateway).pollOnce()

        assertThat(gateway.wipeCount).isEqualTo(1)
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer syt_token")
        server.shutdown()
    }

    @Test
    fun `no command means nothing happens`() = runTest {
        val server = MockWebServer()
        val gateway = FakeDevicePolicyGateway()
        server.replyCommand(null)

        poller(server, gateway).pollOnce()

        assertThat(gateway.wipeCount).isEqualTo(0)
        server.shutdown()
    }

    @Test
    fun `a homeserver that is not the managed one cannot order a wipe`() = runTest {
        // Without this, any homeserver a user happened to sign into could erase a company handset.
        val server = MockWebServer()
        val gateway = FakeDevicePolicyGateway()
        server.replyCommand("wipe_device")

        poller(server, gateway, managedHomeserver = "https://someone-else.example").pollOnce()

        assertThat(gateway.wipeCount).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
        server.shutdown()
    }

    @Test
    fun `an unknown command is ignored rather than guessed at`() = runTest {
        val server = MockWebServer()
        val gateway = FakeDevicePolicyGateway()
        server.replyCommand("reboot_and_hope")

        poller(server, gateway).pollOnce()

        assertThat(gateway.wipeCount).isEqualTo(0)
        server.shutdown()
    }

    @Test
    fun `a server error is not an instruction`() = runTest {
        val server = MockWebServer()
        val gateway = FakeDevicePolicyGateway()
        // Thân phản hồi là JSON HỢP LỆ mang đúng lệnh xoá. Nếu để thân rác thì test
        // xanh vì phân tích JSON thất bại chứ không vì mã trạng thái bị kiểm — đã
        // dựng lại đúng lỗi đó bằng đột biến: bỏ kiểm isSuccessful mà test vẫn xanh.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"command":"wipe_device","reason":"lost handset"}"""))

        poller(server, gateway).pollOnce()

        assertThat(gateway.wipeCount).isEqualTo(0)
        server.shutdown()
    }

    @Test
    fun `a body that is not JSON is not an instruction`() = runTest {
        val server = MockWebServer()
        val gateway = FakeDevicePolicyGateway()
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))

        poller(server, gateway).pollOnce()

        assertThat(gateway.wipeCount).isEqualTo(0)
        server.shutdown()
    }

    @Test
    fun `without device owner nothing is even asked`() = runTest {
        // A handset this app does not manage cannot be wiped by it, so asking would only announce
        // that the app is running.
        val server = MockWebServer()
        val gateway = FakeDevicePolicyGateway(isDeviceOwner = false)
        server.replyCommand("wipe_device")

        poller(server, gateway).pollOnce()

        assertThat(gateway.wipeCount).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
        server.shutdown()
    }

    @Test
    fun `a revoked session is not polled`() = runTest {
        val server = MockWebServer()
        val gateway = FakeDevicePolicyGateway()
        server.replyCommand("wipe_device")

        poller(server, gateway, tokenValid = false).pollOnce()

        assertThat(gateway.wipeCount).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
        server.shutdown()
    }
}
