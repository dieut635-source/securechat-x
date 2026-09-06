/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.di

import android.content.Context
import androidx.work.ListenableWorker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import io.element.android.libraries.architecture.NodeFactoriesBindings
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.pushproviders.api.PushProvider
import io.element.android.libraries.workmanager.api.di.MetroWorkerFactory
import kotlin.reflect.KClass

@DependencyGraph(AppScope::class)
interface AppGraph : NodeFactoriesBindings {
    val sessionGraphFactory: SessionGraph.Factory

    @Multibinds
    val workerProviders:
        Map<KClass<out ListenableWorker>, MetroWorkerFactory.WorkerInstanceFactory<*>>

    /**
     * A closed SecureChat build intentionally has no remote push provider. Declaring the empty set
     * keeps dependency injection valid without compiling Firebase or a public UnifiedPush client.
     */
    @Multibinds(allowEmpty = true)
    val pushProviders: Set<PushProvider>

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @ApplicationContext @Provides
            context: Context
        ): AppGraph
    }
}
