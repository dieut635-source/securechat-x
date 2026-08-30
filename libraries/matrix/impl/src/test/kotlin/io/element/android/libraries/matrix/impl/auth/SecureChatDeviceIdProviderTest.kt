/*
 * Copyright (c) 2026 SecureChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import io.element.android.libraries.preferences.test.FakePreferenceDataStoreFactory
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import androidx.datastore.preferences.core.PreferenceDataStoreFactory as AndroidPreferenceDataStoreFactory

class SecureChatDeviceIdProviderTest : RobolectricTest() {
    @Test
    fun `device id is random-shaped and stable for the app installation`() = runTest {
        val dataStoreFile = File.createTempFile("securechat-device-id", ".preferences_pb").also {
            check(it.delete())
        }
        val firstProcessJob = SupervisorJob()
        val firstProcessDataStore = AndroidPreferenceDataStoreFactory.create(
            scope = CoroutineScope(firstProcessJob + Dispatchers.IO),
            produceFile = { dataStoreFile },
        )
        val firstProcess = DataStoreSecureChatDeviceIdProvider(SingleDataStoreFactory(firstProcessDataStore))

        val first = firstProcess.getOrCreate()
        firstProcessJob.cancelAndJoin()

        val secondProcessJob = SupervisorJob()
        val secondProcessDataStore = AndroidPreferenceDataStoreFactory.create(
            scope = CoroutineScope(secondProcessJob + Dispatchers.IO),
            produceFile = { dataStoreFile },
        )
        val secondProcess = DataStoreSecureChatDeviceIdProvider(SingleDataStoreFactory(secondProcessDataStore))
        val afterProcessRestart = try {
            secondProcess.getOrCreate()
        } finally {
            secondProcessJob.cancelAndJoin()
        }

        assertThat(first).matches("^SC-[A-Za-z0-9_-]{22}$")
        assertThat(afterProcessRestart).isEqualTo(first)
    }

    @Test
    fun `concurrent callers receive the same persisted device id`() = runTest {
        val sut = DataStoreSecureChatDeviceIdProvider(FakePreferenceDataStoreFactory())

        val deviceIds = coroutineScope {
            List(20) { async { sut.getOrCreate() } }.awaitAll()
        }

        assertThat(deviceIds.toSet()).hasSize(1)
    }

    @Test
    fun `legacy id seed is used when no device id exists`() = runTest {
        val dataStore = AndroidPreferenceDataStoreFactory.create(
            produceFile = { File.createTempFile("securechat-device-id-legacy", ".preferences_pb") },
        )
        val sut = DataStoreSecureChatDeviceIdProvider(SingleDataStoreFactory(dataStore))

        sut.seedFromLegacyDeviceId("a-legacy-device")

        assertThat(sut.getOrCreate()).isEqualTo("a-legacy-device")
    }

    @Test
    fun `legacy id seed does not overwrite existing id`() = runTest {
        val dataStoreFile = File.createTempFile("securechat-device-id-existing", ".preferences_pb").also {
            check(it.delete())
        }
        val dataStore = AndroidPreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { dataStoreFile },
        )
        val firstProvider = DataStoreSecureChatDeviceIdProvider(SingleDataStoreFactory(dataStore))

        val first = firstProvider.getOrCreate()
        firstProvider.seedFromLegacyDeviceId("legacy-device-id")

        val secondProvider = DataStoreSecureChatDeviceIdProvider(SingleDataStoreFactory(dataStore))
        val second = secondProvider.getOrCreate()

        assertThat(second).isEqualTo(first)
        assertThat(second).isNotEqualTo("legacy-device-id")
    }

    private class SingleDataStoreFactory(
        private val dataStore: DataStore<Preferences>,
    ) : PreferenceDataStoreFactory {
        override fun create(name: String): DataStore<Preferences> = dataStore
    }
}
