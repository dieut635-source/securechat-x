/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

fun interface SecureChatDeviceIdProvider {
    suspend fun getOrCreate(): String
}

interface SeedableSecureChatDeviceIdProvider : SecureChatDeviceIdProvider {
    suspend fun seedFromLegacyDeviceId(legacyDeviceId: String)
}

suspend fun SecureChatDeviceIdProvider.seedDeviceIdFromLegacySessionIfNeeded(sessionDeviceId: String) {
    if (sessionDeviceId.isBlank()) return
    if (this is SeedableSecureChatDeviceIdProvider) {
        this.seedFromLegacyDeviceId(sessionDeviceId)
    }
}

/**
 * Owns the stable, app-install-scoped Matrix device identifier used during password login.
 *
 * The identifier is random rather than hardware-derived and is persisted before being returned.
 * Android backup is disabled for SecureChat, so it is not restored onto a different installation.
 */
// Phải chỉ rõ kiểu: mặc định Metro gắn binding vào kiểu cha trực tiếp, ở đây là
// SeedableSecureChatDeviceIdProvider. Nhưng RustMatrixAuthenticationService tiêm
// SecureChatDeviceIdProvider, nên nếu không chỉ rõ thì đồ thị phụ thuộc thiếu
// binding và :app không biên dịch được.
@ContributesBinding(AppScope::class, binding = binding<SecureChatDeviceIdProvider>())
@SingleIn(AppScope::class)
class DataStoreSecureChatDeviceIdProvider(
    preferenceDataStoreFactory: PreferenceDataStoreFactory,
) : SeedableSecureChatDeviceIdProvider {
    private val dataStore = preferenceDataStoreFactory.create(DATA_STORE_NAME)
    private val mutex = Mutex()

    override suspend fun getOrCreate(): String = mutex.withLock {
        val storedDeviceId = dataStore.data.first()[DEVICE_ID_KEY]
        if (storedDeviceId != null) {
            return@withLock storedDeviceId
        }

        val deviceId = generateDeviceId()
        dataStore.edit { preferences ->
            preferences[DEVICE_ID_KEY] = deviceId
        }
        deviceId
    }

    override suspend fun seedFromLegacyDeviceId(legacyDeviceId: String) = mutex.withLock {
        if (legacyDeviceId.isBlank()) return@withLock

        val storedDeviceId = dataStore.data.first()[DEVICE_ID_KEY]
        if (storedDeviceId == null) {
            dataStore.edit { preferences ->
                preferences[DEVICE_ID_KEY] = legacyDeviceId
            }
        }
    }

    private fun generateDeviceId(): String {
        val randomBytes = ByteArray(DEVICE_ID_RANDOM_BYTES)
        SecureRandom().nextBytes(randomBytes)
        val encoded = Base64.encodeToString(
            randomBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return "$DEVICE_ID_PREFIX$encoded"
    }

    private companion object {
        const val DATA_STORE_NAME = "securechat_device_identity"
        const val DEVICE_ID_PREFIX = "SC-"
        const val DEVICE_ID_RANDOM_BYTES = 16
        val DEVICE_ID_KEY = stringPreferencesKey("matrix_device_id")
    }
}
