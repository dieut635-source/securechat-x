/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.android.build.gradle.tasks.GenerateBuildConfig
import config.BuildTimeConfig
import extension.AssetCopyTask
import extension.GitBranchNameValueSource
import extension.GitRevisionValueSource
import extension.allEnterpriseImpl
import extension.allFeaturesImpl
import extension.allLibrariesImpl
import extension.allServicesImpl
import extension.buildConfigFieldStr
import extension.setupDependencyInjection
import extension.testCommonDependencies
import org.sonarqube.gradle.SonarResolverTask
import java.util.Locale

plugins {
    id("io.element.android-compose-application")
    id("kotlin-parcelize")
    alias(libs.plugins.licensee)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.x"

    lint {
        // Keep CI deterministic: dependency freshness is managed by Dependabot, while all
        // actionable Android lint warnings in the app remain release-blocking.
        warningsAsErrors = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
        )
    }

    defaultConfig {
        applicationId = BuildTimeConfig.APPLICATION_ID
        targetSdk = Versions.TARGET_SDK
        versionCode = Versions.VERSION_CODE
        versionName = Versions.VERSION_NAME

        // Keep abiFilter for the universalApk
        ndk {
            abiFilters += listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64")
        }

        // Ref: https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split
        splits {
            // Configures multiple APKs based on ABI.
            abi {
                val buildingAppBundle = gradle.startParameter.taskNames.any { it.contains("bundle") }

                // Enables building multiple APKs per ABI. This should be disabled when building an AAB.
                isEnable = !buildingAppBundle

                // By default all ABIs are included, so use reset() and include to specify that we only
                // want APKs for armeabi-v7a, x86, arm64-v8a and x86_64.
                // Resets the list of ABIs that Gradle should create APKs for to none.
                reset()

                if (!buildingAppBundle) {
                    // Specifies a list of ABIs that Gradle should create APKs for.
                    include("armeabi-v7a", "x86", "arm64-v8a", "x86_64")
                    // Generate a universal APK that includes all ABIs, so user who installs from CI tool can use this one by default.
                    isUniversalApk = true
                }
            }
        }

        androidResources {
            // SecureChat ships in English only (users are in Australia). Add locales back here
            // (e.g. `setOf("en", "en-rUS", "vi")`) if other languages are ever needed.
            localeFilters += setOf("en", "en-rUS")
        }
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = file("./signature/debug.keystore")
            storePassword = "android"
        }
        // Ký bản phát hành của SecureChat. Đọc từ biến môi trường nên khoá KHÔNG bao giờ
        // nằm trong repo. CI nạp khoá từ GitHub Secrets; máy cá nhân không có biến này thì
        // cấu hình bên dưới không được tạo và bản release lùi về khoá debug (có cảnh báo).
        val scKeystore = System.getenv("SECURECHAT_KEYSTORE_FILE")
        if (!scKeystore.isNullOrBlank() && file(scKeystore).exists()) {
            register("securechat") {
                storeFile = file(scKeystore)
                storePassword = System.getenv("SECURECHAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SECURECHAT_KEY_ALIAS") ?: "securechat"
                keyPassword = System.getenv("SECURECHAT_KEY_PASSWORD")
            }
        }

        register("nightly") {
            keyAlias = System.getenv("SECURECHAT_NIGHTLY_KEY_ID")
                ?: project.property("signing.securechat.nightly.keyId") as? String?
            keyPassword = System.getenv("SECURECHAT_NIGHTLY_KEY_PASSWORD")
                ?: project.property("signing.securechat.nightly.keyPassword") as? String?
            storeFile = file("./signature/nightly.keystore")
            storePassword = System.getenv("SECURECHAT_NIGHTLY_STORE_PASSWORD")
                ?: project.property("signing.securechat.nightly.storePassword") as? String?
        }
    }

    val baseAppName = BuildTimeConfig.APPLICATION_NAME
    val buildType = if (isEnterpriseBuild) "Enterprise" else "FOSS"
    logger.warnInBox("Building ${defaultConfig.applicationId} ($baseAppName) [$buildType]")

    buildTypes {
        val oAuthRedirectSchemeBase = BuildTimeConfig.METADATA_HOST_REVERSED ?: "com.securechat"
        getByName("debug") {
            resValue("string", "app_name", "$baseAppName dbg")
            resValue(
                "string",
                "login_redirect_scheme",
                "$oAuthRedirectSchemeBase.debug",
            )
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }

        getByName("release") {
            resValue("string", "app_name", baseAppName)
            resValue(
                "string",
                "login_redirect_scheme",
                oAuthRedirectSchemeBase,
            )
            // Upstream ký bản release bằng khoá DEBUG — khoá đó nằm công khai trong repo,
            // nghĩa là ai cũng ký được bản cập nhật giả mạo. Chỉ chấp nhận được khi build thử.
            signingConfig = signingConfigs.findByName("securechat")
                ?: signingConfigs.getByName("debug").also {
                    logger.warnInBox(
                        "CẢNH BÁO: bản release đang ký bằng khoá DEBUG.\n" +
                            "Khoá debug nằm công khai trong repo — KHÔNG phát hành bản này cho người dùng.\n" +
                            "Đặt SECURECHAT_KEYSTORE_FILE/_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD để ký thật."
                    )
                }

            optimization {
                enable = true
                keepRules {
                    // Equivalent of adding `getDefaultProguardFile("proguard-android-optimize.txt")` (this is the default value).
                    includeDefault = true
                }
                // Our custom keep rules are registered as `keepRules` source folders in the `androidComponents` block below,
                // as the former `keepRules.files` DSL is deprecated since AGP 9.
            }
        }

        register("nightly") {
            val release = getByName("release")
            initWith(release)
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-nightly"
            resValue("string", "app_name", "$baseAppName nightly")
            resValue(
                "string",
                "login_redirect_scheme",
                "$oAuthRedirectSchemeBase.nightly",
            )
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("nightly")
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }
    flavorDimensions += "store"
    productFlavors {
        create("gplay") {
            dimension = "store"
            isDefault = true
            buildConfigFieldStr("SHORT_FLAVOR_DESCRIPTION", "G")
            buildConfigFieldStr("FLAVOR_DESCRIPTION", "GooglePlay")
        }
        create("fdroid") {
            dimension = "store"
            buildConfigFieldStr("SHORT_FLAVOR_DESCRIPTION", "F")
            buildConfigFieldStr("FLAVOR_DESCRIPTION", "FDroid")
        }
    }

    packaging {
        resources.pickFirsts += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )

        jniLibs {
            useLegacyPackaging = project.findProperty("useLegacyPackaging")?.toString()?.toBoolean()
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    // map for the version codes last digit
    // x86 must have greater values than arm
    // 64 bits have greater value than 32 bits
    val abiVersionCodes = mapOf(
        "armeabi-v7a" to 1,
        "arm64-v8a" to 2,
        "x86" to 3,
        "x86_64" to 4,
    )

    onVariants { variant ->
        // Register the R8 keep rules source folders for optimized build types (release, nightly).
        // Replaces the deprecated `optimization.keepRules.files` DSL (AGP 9+).
        if (variant.buildType != "debug") {
            variant.sources.keepRules?.let { keepRules ->
                // Common rules, always applied.
                keepRules.addStaticSourceDirectory("proguard/common")

                // Depending on whether the app flavor is enterprise or not we want to use different proguard rules.
                val flavorProguardDir = if (isEnterpriseBuild) {
                    // Custom rules for enterprise builds
                    "../enterprise/proguard"
                } else {
                    // Custom fules for FOSS builds
                    "proguard/foss"
                }

                if (File(projectDir, flavorProguardDir).exists()) {
                    keepRules.addStaticSourceDirectory(flavorProguardDir)
                } else {
                    logger.warn("Proguard folder ${File(projectDir, flavorProguardDir).absolutePath} does not exist")
                }
            }
        }

        // Assigns a different version code for each output APK
        // other than the universal APK.
        variant.outputs.forEach { output ->
            val name = output.filters.find { it.filterType == ABI }?.identifier

            // Stores the value of abiCodes that is associated with the ABI for this variant.
            val abiCode = abiVersionCodes[name] ?: 0
            // Assigns the new version code to output.versionCode, which changes the version code
            // for only the output APK, not for the variant itself.
            output.versionCode.set((output.versionCode.orNull ?: 0) * 10 + abiCode)
        }
    }

    val reportingExtension: ReportingExtension = project.extensions.getByType(ReportingExtension::class.java)
    configureLicensesTasks(reportingExtension)
}

// Configure the SonarQube plugin to wait for the resource generation tasks to complete before running the analysis.
tasks.withType<SonarResolverTask>().configureEach {
    dependsOn("generateGplayDebugResValues", "generateGplayDebugAndroidTestResValues")
}

setupDependencyInjection()

dependencies {
    allLibrariesImpl()
    allServicesImpl()
    if (isEnterpriseBuild) {
        allEnterpriseImpl(project)
        implementation(projects.appicon.enterprise)
    } else {
        implementation(projects.features.enterprise.implFoss)
        implementation(projects.appicon.element)
    }
    allFeaturesImpl(project)
    implementation(projects.features.migration.api)
    implementation(projects.appnav)
    implementation(projects.appconfig)
    implementation(projects.libraries.uiStrings)
    implementation(projects.services.analytics.compose)

    if (ModulesConfig.pushProvidersConfig.includeFirebase) {
        "gplayImplementation"(projects.libraries.pushproviders.firebase)
    }
    if (ModulesConfig.pushProvidersConfig.includeUnifiedPush) {
        implementation(projects.libraries.pushproviders.unifiedpush)
    }

    implementation(libs.appyx.core)
    implementation(libs.androidx.splash)
    implementation(libs.androidx.core)
    implementation(libs.androidx.corektx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.preference)
    implementation(libs.coil)

    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp.logging)
    implementation(libs.serialization.json)

    implementation(libs.matrix.emojibase.bindings)

    testCommonDependencies(libs)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.services.toolbox.test)
}

tasks.withType<GenerateBuildConfig>().configureEach {
    outputs.upToDateWhen { false }
    val gitRevision = providers.of(GitRevisionValueSource::class.java) {}.get()
    val gitBranchName = providers.of(GitBranchNameValueSource::class.java) {}.get()
    android.defaultConfig.buildConfigFieldStr("GIT_REVISION", gitRevision)
    android.defaultConfig.buildConfigFieldStr("GIT_BRANCH_NAME", gitBranchName)
}

licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allowUrl("https://opensource.org/license/bsd-3-clause")
    allowUrl("https://www.zetetic.net/sqlcipher/license/")
    allowUrl("https://jsoup.org/license")
    allowUrl("https://asm.ow2.io/license.html")
    allowUrl("https://github.com/mhssn95/compose-color-picker/blob/main/LICENSE")
    ignoreDependencies("com.github.matrix-org", "matrix-analytics-events")
    // Ignore dependency that are not third-party licenses to us.
    ignoreDependencies(groupId = "io.element.android")
}

fun Project.configureLicensesTasks(reportingExtension: ReportingExtension) {
    androidComponents {
        onVariants { variant ->
            val capitalizedVariantName = variant.name.replaceFirstChar {
                if (it.isLowerCase()) {
                    it.titlecase(Locale.getDefault())
                } else {
                    it.toString()
                }
            }
            val artifactsFile = reportingExtension.baseDirectory.file("licensee/android$capitalizedVariantName/artifacts.json")

            val copyArtifactsTask =
                project.tasks.register<AssetCopyTask>("copy${capitalizedVariantName}LicenseeReportToAssets") {
                    inputFile.set(artifactsFile)
                    targetFileName.set("licensee-artifacts.json")
                }
            variant.sources.assets?.addGeneratedSourceDirectory(
                copyArtifactsTask,
                AssetCopyTask::outputDirectory,
            )
            copyArtifactsTask.dependsOn("licenseeAndroid$capitalizedVariantName")
        }
    }
}

configurations.all {
    resolutionStrategy {
        dependencySubstitution {
            val tink = libs.google.tink.get()
            substitute(module("com.google.crypto.tink:tink")).using(module("${tink.group}:${tink.name}:${tink.version}"))
        }
    }
}
