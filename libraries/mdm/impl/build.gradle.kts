import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-library")
}

setupDependencyInjection()

android {
    namespace = "io.element.android.libraries.mdm.impl"
}

dependencies {
    implementation(libs.androidx.corektx)
    implementation(libs.coroutines.core)
    implementation(projects.libraries.core)
    implementation(projects.libraries.di)
    implementation(libs.timber)
    api(projects.libraries.mdm.api)

    testCommonDependencies(libs)
}
