/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package extension

import ModulesConfig
import config.AnalyticsConfig
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.closureOf
import org.gradle.kotlin.dsl.project

private fun DependencyHandlerScope.implementation(dependency: Any) = dependencies.add("implementation", dependency)
private fun DependencyHandlerScope.testImplementation(dependency: Any) = dependencies.add("testImplementation", dependency)
private fun DependencyHandlerScope.testReleaseImplementation(dependency: Any) = dependencies.add("testReleaseImplementation", dependency)
// Implementation + config block
private fun DependencyHandlerScope.implementation(
    dependency: Any,
    config: Action<ExternalModuleDependency>
) = dependencies.add("implementation", dependency, closureOf<ExternalModuleDependency> { config.execute(this) })

private fun DependencyHandlerScope.androidTestImplementation(dependency: Any) = dependencies.add("androidTestImplementation", dependency)

private fun DependencyHandlerScope.debugImplementation(dependency: Any) = dependencies.add("debugImplementation", dependency)
private fun DependencyHandlerScope.releaseImplementation(dependency: Any) = dependencies.add("releaseImplementation", dependency)

/**
 * Dependencies used for unit tests.
 */
fun DependencyHandlerScope.testCommonDependencies(
    libs: LibrariesForLibs,
    includeTestComposeView: Boolean = false,
) {
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.molecule.runtime)
    testImplementation(libs.test.appyx.junit)
    testImplementation(libs.test.arch.core)
    testImplementation(libs.test.junit)
    testImplementation(libs.test.mockk)
    testImplementation(libs.test.parameter.injector)
    testImplementation(libs.test.robolectric)
    testImplementation(libs.test.truth)
    testImplementation(libs.test.turbine)
    testImplementation(dependencies.project(":tests:testutils"))
    if (includeTestComposeView) {
        testImplementation(libs.androidx.compose.ui.test.junit)
        testReleaseImplementation(libs.androidx.compose.ui.test.manifest)
    }
}

/**
 * Dependencies used by all the modules
 */
fun DependencyHandlerScope.commonDependencies(libs: LibrariesForLibs) {
    implementation(libs.timber)
}

/**
 * Dependencies used by all the modules with composable items
 */
fun DependencyHandlerScope.composeDependencies(libs: LibrariesForLibs) {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.kotlinx.collections.immutable)
}

fun DependencyHandlerScope.allLibrariesImpl() {
    implementation(dependencies.project(":libraries:androidutils"))
    implementation(dependencies.project(":libraries:deeplink:impl"))
    implementation(dependencies.project(":libraries:designsystem"))
    implementation(dependencies.project(":libraries:matrix:impl"))
    implementation(dependencies.project(":libraries:mdm:impl"))
    implementation(dependencies.project(":libraries:matrixui"))
    implementation(dependencies.project(":libraries:matrixmedia:impl"))
    implementation(dependencies.project(":libraries:network"))
    implementation(dependencies.project(":libraries:core"))
    implementation(dependencies.project(":libraries:eventformatter:impl"))
    implementation(dependencies.project(":libraries:indicator:impl"))
    implementation(dependencies.project(":libraries:permissions:impl"))
    implementation(dependencies.project(":libraries:audio:impl"))
    implementation(dependencies.project(":libraries:push:impl"))
    implementation(dependencies.project(":libraries:featureflag:impl"))
    implementation(dependencies.project(":libraries:pushstore:impl"))
    implementation(dependencies.project(":libraries:preferences:impl"))
    implementation(dependencies.project(":libraries:architecture"))
    implementation(dependencies.project(":libraries:dateformatter:impl"))
    implementation(dependencies.project(":libraries:di"))
    implementation(dependencies.project(":libraries:cachestore:impl"))
    implementation(dependencies.project(":libraries:session-storage:impl"))
    implementation(dependencies.project(":libraries:mediapickers:impl"))
    implementation(dependencies.project(":libraries:mediaupload:impl"))
    implementation(dependencies.project(":libraries:slashcommands:impl"))
    implementation(dependencies.project(":libraries:usersearch:impl"))
    implementation(dependencies.project(":libraries:textcomposer:impl"))
    implementation(dependencies.project(":libraries:accountselect:impl"))
    implementation(dependencies.project(":libraries:roomselect:impl"))
    implementation(dependencies.project(":libraries:cryptography:impl"))
    implementation(dependencies.project(":libraries:voiceplayer:impl"))
    implementation(dependencies.project(":libraries:voicerecorder:impl"))
    implementation(dependencies.project(":libraries:mediaplayer:impl"))
    implementation(dependencies.project(":libraries:mediaviewer:impl"))
    implementation(dependencies.project(":libraries:troubleshoot:impl"))
    implementation(dependencies.project(":libraries:fullscreenintent:impl"))
    implementation(dependencies.project(":libraries:wellknown:impl"))
    implementation(dependencies.project(":libraries:oauth:impl"))
    implementation(dependencies.project(":libraries:workmanager:impl"))
    implementation(dependencies.project(":libraries:emoji:impl"))
}

fun DependencyHandlerScope.allServicesImpl() {
    implementation(dependencies.project(":services:analytics:compose"))
    when (ModulesConfig.analyticsConfig) {
        AnalyticsConfig.Disabled -> {
            implementation(dependencies.project(":services:analytics:noop"))
        }
        is AnalyticsConfig.Enabled -> {
            implementation(dependencies.project(":services:analytics:impl"))
            if (ModulesConfig.analyticsConfig.withPosthog) {
                implementation(dependencies.project(":services:analyticsproviders:posthog"))
            }
            if (ModulesConfig.analyticsConfig.withSentry) {
                implementation(dependencies.project(":services:analyticsproviders:sentry"))
            }
        }
    }

    implementation(dependencies.project(":services:apperror:impl"))
    implementation(dependencies.project(":services:appnavstate:impl"))
    implementation(dependencies.project(":services:toolbox:impl"))
}

fun DependencyHandlerScope.allEnterpriseImpl(project: Project) = addAll(
    project = project,
    modulePrefix = ":enterprise:features",
    moduleSuffix = ":impl",
)

fun DependencyHandlerScope.allFeaturesImpl(project: Project) = addAll(
    project = project,
    modulePrefix = ":features",
    moduleSuffix = ":impl",
)

fun DependencyHandlerScope.allFeaturesApi(project: Project) = addAll(
    project = project,
    modulePrefix = ":features",
    moduleSuffix = ":api",
)

private fun DependencyHandlerScope.addAll(
    project: Project,
    modulePrefix: String,
    moduleSuffix: String,
) {
    val subProjects = project.rootProject.subprojects.filter { it.path.startsWith(modulePrefix) && it.path.endsWith(moduleSuffix) }
    for (p in subProjects) {
        // Passing a Project instance as dependency notation is removed in Gradle 10.
        // Resolve it explicitly as a project dependency so the production build does not
        // silently depend on deprecated coercion behaviour.
        implementation(dependencies.project(p.path))
    }
}
