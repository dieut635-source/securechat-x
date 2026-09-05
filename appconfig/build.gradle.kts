import config.BuildTimeConfig
import extension.buildConfigFieldStr

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.appconfig"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigFieldStr(
            name = "URL_POLICY",
            value = BuildTimeConfig.URL_POLICY ?: "",
        )
        buildConfigFieldStr(
            name = "BUG_REPORT_URL",
            // Empty disables remote bug-report uploads until a SecureChat endpoint is configured.
            value = BuildTimeConfig.BUG_REPORT_URL ?: "",
        )
        buildConfigFieldStr(
            name = "BUG_REPORT_APP_NAME",
            value = BuildTimeConfig.BUG_REPORT_APP_NAME ?: "securechat-android",
        )
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.androidx.annotationjvm)
    implementation(libs.androidx.corektx)
    implementation(projects.libraries.matrix.api)
}
