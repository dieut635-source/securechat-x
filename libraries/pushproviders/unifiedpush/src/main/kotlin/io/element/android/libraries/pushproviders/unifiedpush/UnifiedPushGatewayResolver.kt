/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.core.log.logger.LoggerTag
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

sealed interface UnifiedPushGatewayResolverResult {
    data class Success(val gateway: String) : UnifiedPushGatewayResolverResult
    data class Error(val gateway: String) : UnifiedPushGatewayResolverResult
    data object NoMatrixGateway : UnifiedPushGatewayResolverResult
    data object ErrorInvalidUrl : UnifiedPushGatewayResolverResult
}

interface UnifiedPushGatewayResolver {
    suspend fun getGateway(endpoint: String): UnifiedPushGatewayResolverResult
}

private const val HTTPS_PORT = 443

private val loggerTag = LoggerTag("DefaultUnifiedPushGatewayResolver")

@ContributesBinding(AppScope::class)
class DefaultUnifiedPushGatewayResolver(
    private val unifiedPushApiFactory: UnifiedPushApiFactory,
    private val coroutineDispatchers: CoroutineDispatchers,
) : UnifiedPushGatewayResolver {
    override suspend fun getGateway(endpoint: String): UnifiedPushGatewayResolverResult {
        val url = tryOrNull(
            onException = { Timber.tag(loggerTag.value).d(it, "Cannot parse endpoint as an URL") }
        ) {
            URL(endpoint)
        }
        return if (url == null) {
            Timber.tag(loggerTag.value).d("ErrorInvalidUrl")
            UnifiedPushGatewayResolverResult.ErrorInvalidUrl
        } else {
            // Từ chối mọi host không phải của SecureChat TRƯỚC khi hỏi nó bất cứ điều gì.
            // Máy nào chưa trỏ ntfy về máy chủ của mình sẽ giữ mặc định ntfy.sh công cộng,
            // mà chỗ đó CÓ quảng bá Matrix gateway hợp lệ — nên nếu không chặn ở đây thì
            // toàn bộ siêu dữ liệu push đi qua bên thứ ba mà không ai hay biết.
            if (!url.host.equals(UnifiedPushConfig.ALLOWED_GATEWAY_HOST, ignoreCase = true)) {
                Timber.tag(loggerTag.value).w(
                    "Refusing endpoint on host '${url.host}': only ${UnifiedPushConfig.ALLOWED_GATEWAY_HOST} is allowed"
                )
                return UnifiedPushGatewayResolverResult.ErrorInvalidUrl
            }
            // Kiểm tra host thôi thì chưa đủ. Bản trước giữ nguyên protocol và port của endpoint,
            // nên https://push...:8443 hay http://push... đều được chấp nhận và đi tiếp. Cổng tuỳ ý
            // trên đúng tên miền vẫn là một dịch vụ khác; và http thì siêu dữ liệu push đi ra ngoài
            // dạng đọc được, bất kể tên miền có đúng hay không.
            if (!url.protocol.equals("https", ignoreCase = true)) {
                Timber.tag(loggerTag.value).w("Refusing endpoint with scheme '${url.protocol}': only https is allowed")
                return UnifiedPushGatewayResolverResult.ErrorInvalidUrl
            }
            if (url.port != -1 && url.port != HTTPS_PORT) {
                Timber.tag(loggerTag.value).w("Refusing endpoint on port ${url.port}: only $HTTPS_PORT is allowed")
                return UnifiedPushGatewayResolverResult.ErrorInvalidUrl
            }
            if (!url.userInfo.isNullOrEmpty()) {
                // https://push.securechat.com.au@evil.example đọc lướt qua trông giống tên miền của
                // mình. URL.host phân giải đúng, nhưng thông tin đăng nhập nhúng trong URL không có
                // lý do gì xuất hiện ở một endpoint push.
                Timber.tag(loggerTag.value).w("Refusing endpoint carrying userinfo")
                return UnifiedPushGatewayResolverResult.ErrorInvalidUrl
            }
            // Dựng lại từ hằng số thay vì từ chuỗi nhận được: những gì đi tiếp không còn mang theo
            // mảnh nào của endpoint bên ngoài.
            val customBase = "https://${UnifiedPushConfig.ALLOWED_GATEWAY_HOST}"
            val customUrl = "$customBase/_matrix/push/v1/notify"
            Timber.tag(loggerTag.value).i("Testing $customUrl")
            return withContext(coroutineDispatchers.io) {
                val api = unifiedPushApiFactory.create(customBase)
                try {
                    val discoveryResponse = api.discover()
                    if (discoveryResponse.unifiedpush.gateway == "matrix") {
                        Timber.tag(loggerTag.value).d("The endpoint seems to be a valid UnifiedPush gateway")
                        UnifiedPushGatewayResolverResult.Success(customUrl)
                    } else {
                        // The endpoint returned a 200 OK but didn't promote an actual matrix gateway, which means it doesn't have any
                        Timber.tag(loggerTag.value).w("The endpoint does not seem to be a valid UnifiedPush gateway, using fallback")
                        UnifiedPushGatewayResolverResult.NoMatrixGateway
                    }
                } catch (throwable: Throwable) {
                    val code = (throwable as? HttpException)?.code()
                    if (code in NoMatrixGatewayResp) {
                        Timber.tag(loggerTag.value).i("Checking for UnifiedPush endpoint yielded $code, using fallback")
                        UnifiedPushGatewayResolverResult.NoMatrixGateway
                    } else {
                        Timber.tag(loggerTag.value).e(throwable, "Error checking for UnifiedPush endpoint")
                        UnifiedPushGatewayResolverResult.Error(customUrl)
                    }
                }
            }
        }
    }

    companion object {
        private val NoMatrixGatewayResp = listOf<Int>(
            HttpURLConnection.HTTP_UNAUTHORIZED,
            HttpURLConnection.HTTP_FORBIDDEN,
            HttpURLConnection.HTTP_NOT_FOUND,
            HttpURLConnection.HTTP_BAD_METHOD,
            HttpURLConnection.HTTP_NOT_ACCEPTABLE
        )
    }
}
