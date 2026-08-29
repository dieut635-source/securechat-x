import extension.buildConfigFieldStr
import extension.readLocalProperty
import extension.setupDependencyInjection
import extension.testCommonDependencies
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-compose-library")
    id("kotlin-parcelize")
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Extracts the embedded call web application from its upstream AAR and applies the small,
 * reviewed SecureChat presentation overlay. The upstream AAR contains no classes or Android
 * resources, so consuming these generated assets instead of merging the AAR preserves the call
 * implementation while preventing the upstream wordmark and user-facing links from shipping.
 */
abstract class PrepareSecureChatCallAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceAar: ConfigurableFileCollection

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val aar = sourceAar.singleFile
        val actualSha256 = sha256(aar)
        check(actualSha256 == expectedSha256.get()) {
            "Unexpected embedded call AAR checksum. " +
                "Expected ${expectedSha256.get()}, got $actualSha256. " +
                "Review and update the SecureChat call branding overlay before accepting a new artifact."
        }

        val outputRoot = outputDirectory.get().asFile
        check(outputRoot.deleteRecursively() || !outputRoot.exists()) {
            "Unable to clean generated SecureChat call assets at $outputRoot"
        }
        check(outputRoot.mkdirs()) { "Unable to create $outputRoot" }

        var extractedFileCount = 0
        ZipFile(aar).use { archive ->
            archive.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.name.startsWith(UPSTREAM_ASSET_PREFIX) }
                .filterNot { it.name.endsWith(".map") }
                .forEach { entry ->
                    val upstreamRelativePath = entry.name.removePrefix(UPSTREAM_ASSET_PREFIX)
                    val relativePath = "$SECURECHAT_CALL_ASSET_DIRECTORY/$upstreamRelativePath"
                    val destination = outputRoot.resolve(relativePath).normalize()
                    check(destination.toPath().startsWith(outputRoot.toPath())) {
                        "Unsafe path in embedded call AAR: ${entry.name}"
                    }
                    destination.parentFile.mkdirs()

                    archive.getInputStream(entry).use { input ->
                        if (relativePath.isPatchableTextFile()) {
                            val original = input.readBytes().toString(StandardCharsets.UTF_8)
                            destination.writeText(
                                original.applySecureChatBranding(relativePath),
                                StandardCharsets.UTF_8,
                            )
                        } else {
                            Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    extractedFileCount++
                }
        }
        check(extractedFileCount >= MINIMUM_EXPECTED_FILE_COUNT) {
            "Embedded call artifact layout changed: extracted only $extractedFileCount files"
        }
    }

    private fun String.isPatchableTextFile(): Boolean =
        endsWith(".html") || endsWith(".json") || endsWith(".js") || endsWith(".css")

    private fun String.applySecureChatBranding(relativePath: String): String {
        var result = this
            .replace("Element Call", "SecureChat Call")
            .replace("https://element.io/privacy", SECURECHAT_PUBLIC_URL)
            .replace("https://element.io/cookie-policy", SECURECHAT_PUBLIC_URL)
            .replace(
                "https://docs.element.io/latest/element-server-suite-pro/configuring-components/" +
                    "configuring-matrix-rtc/#sfu-connectivity-troubleshooting",
                SECURECHAT_PUBLIC_URL,
            )
            .replace(
                "https://static.element.io/legal/" +
                    "element-software-and-services-license-agreement-uk-1.pdf",
                SECURECHAT_PUBLIC_URL,
            )
            // The embedded app carries an inherited public STUN fallback. SecureChat must not
            // silently contact that service; deployments should supply their own RTC/TURN config.
            .replace(UPSTREAM_STUN_URL, SECURECHAT_DISABLED_STUN_URL)
            // These are private browser-storage names, but they are still shipped in the APK.
            // Keep the embedded app's on-device state independently branded as well.
            .replace("logs-element-call", "logs-securechat-call")
            .replace("element-call-sync", "securechat-call-sync")

        // Locale bundles contain the standalone upstream product name in translated sentences.
        // This replacement is intentionally limited to locale JSON so DOM APIs such as Element,
        // HTMLElement, and protocol identifiers in JavaScript remain byte-for-byte intact.
        if (relativePath.substringAfterLast('/').contains("-app-") && relativePath.endsWith(".json")) {
            result = result
                .replace(STANDALONE_ELEMENT_NAME, "SecureChat")
                // Finnish inflects the product name and therefore does not match a word boundary.
                .replace("Elementiin", "SecureChatiin", ignoreCase = true)
        }

        if (relativePath == "$SECURECHAT_CALL_ASSET_DIRECTORY/index.html") {
            check(result.contains("</head>")) { "Embedded call index no longer has a closing head tag" }
            result = result.replace("</head>", "$SECURECHAT_LOGO_OVERLAY</head>")
        }
        return result
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val SECURECHAT_CALL_ASSET_DIRECTORY = "securechat-call"
        const val UPSTREAM_ASSET_PREFIX = "assets/element-call/"
        const val MINIMUM_EXPECTED_FILE_COUNT = 100
        const val SECURECHAT_PUBLIC_URL = "https://chat.securechat.com.au"
        const val UPSTREAM_STUN_URL = "stun:turn.matrix.org"
        const val SECURECHAT_DISABLED_STUN_URL = "stun:disabled.securechat.invalid"
        val STANDALONE_ELEMENT_NAME = Regex(
            """(?<![A-Za-z0-9_.-])Element(?![A-Za-z0-9_.-])""",
            RegexOption.IGNORE_CASE,
        )

        // These four view boxes are unique to the upstream Element wordmarks in the pinned AAR.
        // Hiding them avoids modifying minified JavaScript or Matrix widget protocol behaviour.
        val SECURECHAT_LOGO_OVERLAY = """
            <style id="securechat-call-branding">
            svg[viewBox="0 0 300 66"],svg[viewBox="0 0 48 48"],svg[viewBox="0 0 160 22"],svg[viewBox="0 0 260 30"]{display:none!important}
            a:has(>svg[viewBox="0 0 260 30"])::after{content:"SecureChat";font:600 1rem/1.2 sans-serif;color:inherit}
            </style>
        """.trimIndent()
    }
}

abstract class VerifySecureChatCallAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedAssets: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val assetRoot = generatedAssets.singleFile.resolve(SECURECHAT_CALL_ASSET_DIRECTORY)
        check(assetRoot.resolve("index.html").isFile) { "Generated embedded call index is missing" }
        check(assetRoot.resolve("config.json").isFile) { "Generated embedded call config is missing" }

        val files = assetRoot.walkTopDown().filter { it.isFile }.toList()
        check(files.none { it.extension == "map" }) { "Source maps must not be packaged in embedded call assets" }

        val textFiles = files.filter { file ->
            file.extension in setOf("html", "json", "js", "css")
        }
        val forbiddenEndpoint = Regex(
            """(?:https?://[^`"'\s]*(?:element\.(?:io|dev)|vector\.im|riot\.im)|(?:stun|turns?):[^`"'\s]*matrix\.org)""",
            RegexOption.IGNORE_CASE,
        )
        textFiles.forEach { file ->
            val text = file.readText(StandardCharsets.UTF_8)
            check(!text.contains("Element Call", ignoreCase = true)) {
                "User-visible Element Call branding remains in ${file.relativeTo(assetRoot)}"
            }
            check(!text.contains("element-call", ignoreCase = true)) {
                "Inherited embedded-call storage identifier remains in ${file.relativeTo(assetRoot)}"
            }
            check(!forbiddenEndpoint.containsMatchIn(text)) {
                "Upstream Element/Vector/Riot endpoint remains in ${file.relativeTo(assetRoot)}"
            }
            if (file.name.contains("-app-") && file.extension == "json") {
                check(!INHERITED_LOCALE_BRAND.containsMatchIn(text)) {
                    "Upstream product name remains in locale ${file.name}"
                }
            }
        }

        val index = assetRoot.resolve("index.html").readText(StandardCharsets.UTF_8)
        check(index.contains("<title>SecureChat Call</title>")) { "SecureChat call title is missing" }
        check(index.contains("id=\"securechat-call-branding\"")) {
            "SecureChat embedded-call logo overlay is missing"
        }
        REQUIRED_LOGO_VIEW_BOXES.forEach { viewBox ->
            check(index.contains("svg[viewBox=\"$viewBox\"]")) {
                "Element logo suppression is missing for viewBox $viewBox"
            }
        }

        val javascript = textFiles
            .filter { it.extension == "js" }
            .joinToString(separator = "\n") { it.readText(StandardCharsets.UTF_8) }
        REQUIRED_PROTOCOL_IDENTIFIERS.forEach { identifier ->
            check(javascript.contains(identifier)) {
                "Required Matrix/widget protocol identifier was changed or removed: $identifier"
            }
        }
    }

    private companion object {
        const val SECURECHAT_CALL_ASSET_DIRECTORY = "securechat-call"
        val INHERITED_LOCALE_BRAND = Regex("element|vector|riot", RegexOption.IGNORE_CASE)
        val REQUIRED_LOGO_VIEW_BOXES = listOf(
            "0 0 300 66",
            "0 0 48 48",
            "0 0 160 22",
            "0 0 260 30",
        )
        val REQUIRED_PROTOCOL_IDENTIFIERS = listOf(
            "io.element.join",
            "im.vector.hangup",
            "io.element.close",
            "io.element.device_mute",
            "im.vector.analytics",
        )
    }
}

val secureChatCallEmbeddedAar = configurations.create("secureChatCallEmbeddedAar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

android {
    namespace = "io.element.android.features.call.impl"

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    defaultConfig {
        buildConfigFieldStr(
            name = "SENTRY_DSN",
            value = System.getenv("SECURECHAT_CALL_SENTRY_DSN")
                ?: readLocalProperty("features.call.sentry.dsn")
                ?: ""
        )
        buildConfigFieldStr(
            name = "POSTHOG_USER_ID",
            value = System.getenv("SECURECHAT_CALL_POSTHOG_USER_ID")
                ?: readLocalProperty("features.call.posthog.userid")
                ?: ""
        )
        buildConfigFieldStr(
            name = "POSTHOG_API_HOST",
            value = System.getenv("SECURECHAT_CALL_POSTHOG_API_HOST")
                ?: readLocalProperty("features.call.posthog.api.host")
                ?: ""
        )
        buildConfigFieldStr(
            name = "POSTHOG_API_KEY",
            value = System.getenv("SECURECHAT_CALL_POSTHOG_API_KEY")
                ?: readLocalProperty("features.call.posthog.api.key")
                ?: ""
        )
        buildConfigFieldStr(
            name = "RAGESHAKE_URL",
            value = System.getenv("SECURECHAT_CALL_RAGESHAKE_URL")
                ?: readLocalProperty("features.call.regeshake.url")
                ?: ""
        )
    }
}

setupDependencyInjection()

dependencies {
    implementation(projects.appconfig)
    implementation(projects.features.enterprise.api)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.androidutils)
    implementation(projects.libraries.audio.api)
    implementation(projects.libraries.core)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.featureflag.api)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.matrixmedia.api)
    implementation(projects.libraries.network)
    implementation(projects.libraries.preferences.api)
    implementation(projects.libraries.push.api)
    implementation(projects.libraries.uiStrings)
    implementation(projects.services.analytics.api)
    implementation(projects.services.appnavstate.api)
    implementation(projects.services.toolbox.api)
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.serialization.json)
    add(secureChatCallEmbeddedAar.name, libs.element.call.embedded)
    api(projects.features.call.api)

    testCommonDependencies(libs, true)
    testImplementation(projects.features.call.test)
    testImplementation(projects.libraries.featureflag.test)
    testImplementation(projects.libraries.preferences.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.libraries.matrixmedia.test)
    testImplementation(projects.libraries.push.test)
    testImplementation(projects.services.analytics.test)
    testImplementation(projects.services.appnavstate.impl)
    testImplementation(projects.services.appnavstate.test)
    testImplementation(projects.services.toolbox.test)
}

val prepareSecureChatCallAssets = tasks.register<PrepareSecureChatCallAssets>("prepareSecureChatCallAssets") {
    sourceAar.from(secureChatCallEmbeddedAar)
    expectedSha256.set("f2e6d530499ecd43e864899dd0f307000582251a5e73e0b6ef6033160b8a3038")
    outputDirectory.set(layout.buildDirectory.dir("generated/secureChatCallAssets"))
}

val verifySecureChatCallAssets = tasks.register<VerifySecureChatCallAssets>("verifySecureChatCallAssets") {
    dependsOn(prepareSecureChatCallAssets)
    generatedAssets.from(prepareSecureChatCallAssets.flatMap { it.outputDirectory })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareSecureChatCallAssets,
            PrepareSecureChatCallAssets::outputDirectory,
        )
    }
}

// Every Android build packages only assets that have passed the branding and protocol audit.
tasks.named("preBuild") {
    dependsOn(verifySecureChatCallAssets)
}
