/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.network.RetrofitFactory
import io.element.android.libraries.pushproviders.unifiedpush.network.UnifiedPushApi
import okhttp3.Call
import okhttp3.OkHttpClient

interface UnifiedPushApiFactory {
    fun create(baseUrl: String): UnifiedPushApi
}

@ContributesBinding(AppScope::class)
class DefaultUnifiedPushApiFactory(
    private val retrofitFactory: RetrofitFactory,
    private val okHttpClient: OkHttpClient,
) : UnifiedPushApiFactory {
    /**
     * Redirects are refused for push traffic.
     *
     * The resolver checks the host, scheme and port of the endpoint before using it, but a redirect
     * happens after all of that and lands wherever the response says. A push gateway that has been
     * taken over could answer the discovery call with a 302 to somewhere else and collect the push
     * metadata that follows. Blocking redirects here rather than on the shared client keeps the rest
     * of the app - which legitimately follows them - unchanged.
     *
     * newBuilder() reuses the shared connection pool and dispatcher, so this is not a second stack.
     */
    private val noRedirectClient by lazy {
        okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    override fun create(baseUrl: String): UnifiedPushApi {
        return retrofitFactory.create(baseUrl, Call.Factory { noRedirectClient.newCall(it) })
            .create(UnifiedPushApi::class.java)
    }
}
