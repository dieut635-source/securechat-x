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
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.Properties

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
        // SecureChat production releases are signed only on the isolated release workstation.
        // The configuration is always present so a release can never fall back to the public
        // debug key. Missing credentials are rejected by the validation task below.
        val scKeystore = System.getenv("SECURECHAT_KEYSTORE_FILE")
        register("securechat") {
            if (!scKeystore.isNullOrBlank()) {
                storeFile = file(scKeystore)
            }
            storePassword = System.getenv("SECURECHAT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SECURECHAT_KEY_ALIAS")
            keyPassword = System.getenv("SECURECHAT_KEY_PASSWORD")

            // minSdk is API 24, where APK Signature Scheme v2 is supported. Do not emit the
            // legacy v1/JAR signature. V2 and v3 protect the whole APK and support modern
            // Android signing/key-rotation semantics. V4 is an optional adb sidecar and is not
            // part of the manually distributed release package.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
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
            // Fail closed: there is deliberately no debug-key fallback for a production build.
            signingConfig = signingConfigs.getByName("securechat")

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

val verifySecureChatReleaseSigningConfiguration = tasks.register("verifySecureChatReleaseSigningConfiguration") {
    group = "verification"
    description = "Fails unless SecureChat offline release-signing credentials are complete and stored outside the repository."

    doLast {
        val requiredEnvironment = listOf(
            "SECURECHAT_KEYSTORE_FILE",
            "SECURECHAT_KEYSTORE_PASSWORD",
            "SECURECHAT_KEY_ALIAS",
            "SECURECHAT_KEY_PASSWORD",
            "SECURECHAT_RELEASE_CERT_SHA256",
            "SECURECHAT_OFFLINE_RELEASE_MARKER_FILE",
        )
        val missingEnvironment = requiredEnvironment.filter { System.getenv(it).isNullOrBlank() }
        if (missingEnvironment.isNotEmpty()) {
            throw GradleException(
                "SecureChat release signing is fail-closed. Missing: ${missingEnvironment.joinToString()}. " +
                    "Run tools/release/build_securechat_offline.sh on the isolated release workstation."
            )
        }

        val configuredKeystore = file(System.getenv("SECURECHAT_KEYSTORE_FILE")).canonicalFile
        if (!configuredKeystore.isFile) {
            throw GradleException("SecureChat release keystore is not a regular file: $configuredKeystore")
        }

        val repositoryRoot = rootProject.projectDir.canonicalFile.toPath()
        if (configuredKeystore.toPath().startsWith(repositoryRoot)) {
            throw GradleException("SecureChat production keystore must be stored outside the source repository.")
        }

        val publicDebugKeystore = file("./signature/debug.keystore").canonicalFile
        val publicNightlyKeystore = file("./signature/nightly.keystore").canonicalFile
        if (configuredKeystore == publicDebugKeystore || configuredKeystore == publicNightlyKeystore) {
            throw GradleException("Public debug/nightly keystores cannot sign a SecureChat production release.")
        }

        val expectedCertificateSha256 = System.getenv("SECURECHAT_RELEASE_CERT_SHA256")
            .replace(Regex("[\\s:]"), "")
            .lowercase(Locale.ROOT)
        if (!expectedCertificateSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            throw GradleException("SECURECHAT_RELEASE_CERT_SHA256 must contain exactly one SHA-256 certificate fingerprint.")
        }

        val keyStore = try {
            KeyStore.getInstance(
                configuredKeystore,
                System.getenv("SECURECHAT_KEYSTORE_PASSWORD").toCharArray(),
            )
        } catch (failure: Exception) {
            throw GradleException("Unable to open the SecureChat release keystore or verify its certificate.", failure)
        }
        val signingCertificate = keyStore.getCertificate(System.getenv("SECURECHAT_KEY_ALIAS"))
            ?: throw GradleException("The configured SecureChat release alias does not contain a certificate.")
        val x509Certificate = signingCertificate as? X509Certificate
            ?: throw GradleException("The SecureChat release certificate must be X.509.")
        val rsaPublicKey = x509Certificate.publicKey as? RSAPublicKey
            ?: throw GradleException("The SecureChat release certificate must use RSA.")
        if (rsaPublicKey.modulus.bitLength() < 3072) {
            throw GradleException("The SecureChat release RSA key must be at least 3072 bits.")
        }
        if (x509Certificate.sigAlgName.contains("MD5", ignoreCase = true) ||
            x509Certificate.sigAlgName.contains("SHA1", ignoreCase = true)
        ) {
            throw GradleException("MD5/SHA-1 release certificate signatures are forbidden.")
        }
        if (x509Certificate.subjectX500Principal.name.contains("CN=Android Debug", ignoreCase = true)) {
            throw GradleException("The Android debug certificate is forbidden for SecureChat production.")
        }
        try {
            x509Certificate.checkValidity()
            x509Certificate.checkValidity(Date.from(Instant.now().plus(365, ChronoUnit.DAYS)))
        } catch (failure: Exception) {
            throw GradleException("The SecureChat release certificate must remain valid for at least one year.", failure)
        }
        val actualCertificateSha256 = MessageDigest.getInstance("SHA-256")
            .digest(signingCertificate.encoded)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
        if (actualCertificateSha256 != expectedCertificateSha256) {
            throw GradleException("The release keystore certificate does not match the independently pinned SecureChat certificate.")
        }

        val markerInput = file(System.getenv("SECURECHAT_OFFLINE_RELEASE_MARKER_FILE"))
        if (Files.isSymbolicLink(markerInput.toPath())) {
            throw GradleException("The SecureChat offline release marker must not be a symbolic link.")
        }
        val markerFile = markerInput.canonicalFile
        if (!markerFile.isFile || markerFile.toPath().startsWith(repositoryRoot)) {
            throw GradleException("The SecureChat offline release marker must be a regular file outside the source repository.")
        }
        val gitRevision = providers.of(GitRevisionValueSource::class.java) {}.get()
        val expectedMarker = buildString {
            appendLine("securechat-offline-release-v1")
            appendLine("gitRevision=$gitRevision")
            appendLine("certificateSha256=$expectedCertificateSha256")
        }
        if (markerFile.readText() != expectedMarker) {
            throw GradleException("The SecureChat offline release marker does not match this source revision and certificate pin.")
        }

        val forbiddenReleaseEnvironment = listOf(
            "SECURECHAT_MAPTILER_API_KEY",
            "SECURECHAT_MAPTILER_LIGHT_MAP_ID",
            "SECURECHAT_MAPTILER_DARK_MAP_ID",
            "SECURECHAT_CALL_SENTRY_DSN",
            "SECURECHAT_CALL_POSTHOG_USER_ID",
            "SECURECHAT_CALL_POSTHOG_API_HOST",
            "SECURECHAT_CALL_POSTHOG_API_KEY",
            "SECURECHAT_CALL_RAGESHAKE_URL",
        ).filter { !System.getenv(it).isNullOrBlank() }
        if (forbiddenReleaseEnvironment.isNotEmpty()) {
            throw GradleException(
                "Third-party service configuration is forbidden in SecureChat production: " +
                    forbiddenReleaseEnvironment.joinToString()
            )
        }

        val forbiddenLocalProperties = setOf(
            "services.maptiler.apikey",
            "services.maptiler.lightMapId",
            "services.maptiler.darkMapId",
            "features.call.sentry.dsn",
            "features.call.posthog.userid",
            "features.call.posthog.api.host",
            "features.call.posthog.api.key",
            "features.call.regeshake.url",
        )
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            val localProperties = Properties().apply {
                localPropertiesFile.inputStream().use(::load)
            }
            val configuredForbiddenProperties = forbiddenLocalProperties.filter(localProperties::containsKey)
            if (configuredForbiddenProperties.isNotEmpty()) {
                throw GradleException(
                    "Third-party local.properties entries are forbidden in SecureChat production: " +
                        configuredForbiddenProperties.joinToString()
                )
            }
        }
    }
}

// Only tasks that create/install a release artifact require the production key. Release lint and
// source compilation intentionally remain available to untrusted CI without signing credentials.
tasks.configureEach {
    val releaseArtifactTask = name.matches(Regex("^(assemble|bundle|install)(Fdroid|Gplay)?Release$")) ||
        name.matches(Regex("^package(Fdroid|Gplay)Release(UniversalApk|Bundle)?$")) ||
        name.matches(Regex("^sign(Fdroid|Gplay)ReleaseBundle$")) ||
        name.matches(Regex("^(extractApksFor|zipApksFor)(Fdroid|Gplay)Release$"))
    if (releaseArtifactTask) {
        dependsOn(verifySecureChatReleaseSigningConfiguration)
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
    // Xoá dữ liệu từ xa: cần đọc/xoá bản ghi phiên và các annotation tiêm phụ thuộc.
    implementation(projects.libraries.core)
    implementation(projects.libraries.di)
    implementation(projects.libraries.sessionStorage.api)
    // The app supplies the three managed-configuration keys that libraries/mdm reads back.
    implementation(projects.libraries.mdm.api)
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
    testImplementation(libs.network.mockwebserver)
    testImplementation(projects.libraries.sessionStorage.test)
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
