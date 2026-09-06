/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.oauth.impl

import android.net.Uri
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.matrix.api.auth.OAuthRedirectUrlProvider
import io.element.android.libraries.oauth.api.OAuthAction

fun interface OAuthUrlParser {
    fun parse(url: String): OAuthAction?
}

/**
 * Strict parser for OAuth callbacks.
 *
 * A custom URI scheme cannot prove which Android application owns it. Restricting the endpoint and
 * callback shape does not replace HTTPS App Links, but it prevents unrelated URIs sharing the same
 * scheme from being accepted and leaves state/PKCE verification to the Matrix SDK.
 */
@ContributesBinding(AppScope::class)
class DefaultOAuthUrlParser(
    private val oAuthRedirectUrlProvider: OAuthRedirectUrlProvider,
) : OAuthUrlParser {
    /**
     * Return a [OAuthAction], or null if [url] is not the exact configured callback endpoint.
     */
    override fun parse(url: String): OAuthAction? {
        val callback = url.toUri()
        val expectedCallback = oAuthRedirectUrlProvider.provide().toUri()
        if (!callback.hasSameEndpointAs(expectedCallback)) return null
        if (callback.fragment != null) return null

        val states = callback.getQueryParameters("state")
        if (states.size != 1 || states.single().isBlank()) return null

        val codes = callback.getQueryParameters("code")
        val errors = callback.getQueryParameters("error")
        return when {
            codes.size == 1 && codes.single().isNotBlank() && errors.isEmpty() -> OAuthAction.Success(url)
            errors.singleOrNull() == "access_denied" && codes.isEmpty() -> OAuthAction.GoBack()
            else -> null
        }
    }
}

private fun Uri.hasSameEndpointAs(other: Uri): Boolean {
    return scheme.equals(other.scheme, ignoreCase = true) &&
        encodedAuthority.equals(other.encodedAuthority, ignoreCase = true) &&
        encodedPath == other.encodedPath
}
