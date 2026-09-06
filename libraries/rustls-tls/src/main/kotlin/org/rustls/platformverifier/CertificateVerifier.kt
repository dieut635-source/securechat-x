@file:SuppressLint("LogNotTimber", "ObsoleteSdkInt")
@file:Suppress("KotlinConstantConditions")

// IMPORTANT: this file comes from rustls-platform-verifier and should not be modified locally.

/*
 * Copyright (c) 2022 1Password
 *
 * SPDX-License-Identifier: MIT
 */

package org.rustls.platformverifier

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.X509TrustManagerExtensions
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Enumeration
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

private const val SYSTEM_CERTIFICATE_ALIAS_PREFIX = "system:"

internal fun selectSystemCertificateAliases(aliases: Enumeration<String>): List<String> = buildList {
    while (aliases.hasMoreElements()) {
        val alias = aliases.nextElement()
        if (alias.startsWith(SYSTEM_CERTIFICATE_ALIAS_PREFIX)) {
            add(alias)
        }
    }
}

// If this is updated, update the Rust definition too.
// Marked private as this is not meant to be used in Android code.
private enum class StatusCode(val value: Int) {
    Ok(0),
    Unavailable(1),
    Expired(2),
    UnknownCert(3),
    Revoked(4),
    InvalidEncoding(5),
    InvalidExtension(6),
}

// Marked private as this is not meant to be used in Android code.
private class VerificationResult(
    status: StatusCode,
    @Suppress("unused") val message: String? = null
) {
    @Suppress("unused")
    private val code: Int = status.value
}

// NOTE: All TrustManager and certificate validation methods are not thread safe. These
// are all guarded by Kotlin's `Synchronized` accessors to prevent undefined behavior.

// Only JNI and test code calls this, so unused code warnings are suppressed.
// Internal for test code - no other Kotlin code should use this object directly.
@Suppress("unused")
// We want to show a difference between Kotlin-side logs and those in Rust code
@SuppressLint("LongLogTag")
internal object CertificateVerifier {
    private const val TAG = "rustls-platform-verifier-android"

    private fun createTrustManager(keystore: KeyStore): X509TrustManagerExtensions? {
        val factory = try {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keystore)
            }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "failed to initialize a TrustManager", e)
            return null
        } catch (e: RuntimeException) {
            Log.e(TAG, "unexpected failure initializing a TrustManager", e)
            return null
        }

        val availableTrustManagers = try {
            factory.trustManagers
        } catch (e: RuntimeException) {
            Log.w(TAG, "exception thrown creating a TrustManager: $e")
            return null
        }

        for (manager in availableTrustManagers) {
            if (manager is X509TrustManager) {
                // Kotlin ensures this can't throw at runtime since it knows that
                // it must be the correct type by now.
                return X509TrustManagerExtensions(manager)
            }
        }

        Log.e(TAG, "failed to find a usable trust manager")
        return null
    }

    private fun makeLazyTrustManager(keystore: KeyStore?): Lazy<X509TrustManagerExtensions?> {
        return lazy { keystore?.let(::createTrustManager) }
    }

    private fun createEmptyKeystore(): KeyStore {
        return KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
    }

    private fun loadPlatformKeystore(): KeyStore? {
        return try {
            KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "failed to load Android CA store", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "failed to read Android CA store", e)
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "unexpected failure loading Android CA store", e)
            null
        }
    }

    /**
     * Copies only active Android system trust anchors into a separate keystore.
     *
     * AndroidCAStore also exposes aliases prefixed with `user:`. Initializing a trust manager
     * directly from that store would therefore bypass the release network-security-config for
     * Matrix traffic verified through Rust. Returning null on any error is intentional: release
     * verification must fail closed rather than silently fall back to the platform default store.
     */
    private fun createSystemOnlyKeystore(platformKeystore: KeyStore?): KeyStore? {
        if (platformKeystore == null) return null

        return try {
            val systemOnlyKeystore = createEmptyKeystore()
            selectSystemCertificateAliases(platformKeystore.aliases()).forEach { alias ->
                val certificate = platformKeystore.getCertificate(alias)
                if (certificate is X509Certificate) {
                    systemOnlyKeystore.setCertificateEntry(alias, certificate)
                }
            }
            systemOnlyKeystore.takeIf { it.size() > 0 }.also {
                if (it == null) {
                    Log.e(TAG, "Android system CA store did not contain any usable certificates")
                }
            }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "failed to create system-only CA store", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "failed to initialize system-only CA store", e)
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "unexpected failure creating system-only CA store", e)
            null
        }
    }

    // -- Test only --
    // Ideally, all of this will be optimized out at compile time due to not being accessed
    // in release builds.

    @get:Synchronized
    private val mockKeystore: KeyStore = createEmptyKeystore()

    @get:Synchronized
    private var mockTrustManager: Lazy<X509TrustManagerExtensions?> =
        makeLazyTrustManager(mockKeystore)

    @JvmStatic
    private fun addMockRoot(root: ByteArray) {
        if (!BuildConfig.TEST) {
            throw Exception("attempted to add a mock root outside a test!")
        }

        val alias = "root_${mockKeystore.size()}"
        // Throwing here is fine since test roots should always be well-formed
        val cert = certFactory.generateCertificate(ByteArrayInputStream(root))
        mockKeystore.setCertificateEntry(alias, cert)

        reloadMockData()
    }

    @JvmStatic
    private fun clearMockRoots() {
        // Reload to get a completely fresh internal state
        mockKeystore.load(null)
        reloadMockData()
    }

    @JvmStatic
    private fun reloadMockData() {
        if (mockTrustManager.isInitialized()) {
            mockTrustManager = makeLazyTrustManager(mockKeystore)
        }
    }

    // Get a list of the system's root CAs.
    // Function is public for testing only.
    @JvmStatic
    fun getSystemRootCAs(): List<X509Certificate> {
        val rootCAs = mutableListOf<X509Certificate>()
        val loadedSystemKeystore = systemOnlyKeystore ?: return rootCAs

        val factory = try {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(loadedSystemKeystore)
            }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "failed to initialize the system TrustManager", e)
            return rootCAs
        } catch (e: RuntimeException) {
            Log.e(TAG, "unexpected failure initializing the system TrustManager", e)
            return rootCAs
        }

        val availableTrustManagers = try {
            factory.trustManagers
        } catch (e: RuntimeException) {
            Log.w(TAG, "exception thrown creating a TrustManager: $e")
            return rootCAs
        }

        availableTrustManagers.forEach { trustManager ->
            if (trustManager is X509TrustManager) {
                rootCAs.addAll(trustManager.acceptedIssuers)
            }
        }

        return rootCAs
    }

    // -- End testing requirements --

    private val certFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

    private val platformKeystore: KeyStore? = loadPlatformKeystore()
    private val systemOnlyKeystore: KeyStore? = createSystemOnlyKeystore(platformKeystore)

    private val systemTrustAnchors: Set<Pair<X500Principal, PublicKey>> by lazy {
        val keystore = systemOnlyKeystore ?: return@lazy emptySet()
        selectSystemCertificateAliases(keystore.aliases()).mapNotNullTo(mutableSetOf()) { alias ->
            (keystore.getCertificate(alias) as? X509Certificate)?.let { certificate ->
                Pair(certificate.subjectX500Principal, certificate.publicKey)
            }
        }
    }

    @get:Synchronized
    private val systemTrustManager: Lazy<X509TrustManagerExtensions?> =
        makeLazyTrustManager(systemOnlyKeystore)

    @get:Synchronized
    private val platformTrustManager: Lazy<X509TrustManagerExtensions?> =
        makeLazyTrustManager(platformKeystore)

    @JvmStatic
    private fun verifyCertificateChain(
        @Suppress("UNUSED_PARAMETER") context: Context,
        serverName: String,
        authMethod: String,
        allowedEkus: Array<String>,
        ocspResponse: ByteArray?,
        time: Long,
        certChain: Array<ByteArray>
    ): VerificationResult {
        // Convert the array of (supposedly) DER bytes into certificates.
        val certificateChain = mutableListOf<X509Certificate>()
        certChain.forEach { certBytes ->
            val certificate = try {
                certFactory.generateCertificate(ByteArrayInputStream(certBytes))
            } catch (e: CertificateException) {
                return VerificationResult(StatusCode.InvalidEncoding)
            }
            certificateChain.add(certificate as X509Certificate)
        }

        if (certificateChain.isEmpty()) {
            return VerificationResult(StatusCode.InvalidEncoding)
        }
        val endEntity = certificateChain[0]

        // Check that the certificate is valid at the point of time provided by `rustls`.
        try {
            endEntity.checkValidity(Date(time))
        } catch (e: CertificateExpiredException) {
            return VerificationResult(StatusCode.Expired)
        } catch (e: CertificateNotYetValidException) {
            return VerificationResult(StatusCode.Expired)
        }

        // Check that this certificate can be used in a TLS server.
        if (!verifyCertUsage(endEntity, allowedEkus)) {
            return VerificationResult(StatusCode.InvalidExtension)
        }

        // Select the trust manager to use.
        //
        // We select them as follows:
        // - If built for release/nightly, only use the system-only trust manager.
        // - If built for debug, use AndroidCAStore so local TLS interception remains possible.
        // - If built for tests:
        //      - If the mock CA store has any values, use the mock trust manager.
        //      - Otherwise, follow the build-type policy.
        val trustManager = when {
            BuildConfig.TEST && mockKeystore.size() != 0 ->
                mockTrustManager.value ?: return VerificationResult(StatusCode.Unavailable)
            BuildConfig.DEBUG ->
                platformTrustManager.value ?: return VerificationResult(StatusCode.Unavailable)
            else ->
                systemTrustManager.value ?: return VerificationResult(StatusCode.Unavailable)
        }

        // Verify that the certificate chain is valid and correct, and nothing more.
        //
        // NOTE: This does not validate `serverName` is valid for the end-entity certificate.
        // That is handled in Rust as Android/Java do not currently provide a RFC 6125 compliant
        // hostname verifier. Additionally, even the RFC 2818 verifier is not available until API 24.
        //
        // `serverName` is only used for pinning/CT requirements.
        //
        // Returns the "the properly ordered chain used for verification as a list of X509Certificates.",
        // meaning a list from end-entity certificate to trust-anchor.
        val validChain = try {
            trustManager.checkServerTrusted(certificateChain.toTypedArray(), authMethod, serverName)
        } catch (e: CertificateException) {
            // In test configurations we may see `checkServerTrusted` fail once vendored test
            // certificates pass their expiry date. We try to avoid that by using a fixed
            // verification time when calling `endEntity.checkValidity` above, however we can't
            // fix the time for the `checkServerTrusted` call.
            //
            // To make diagnosing CI test failures easier we try to find the root cause of
            // checkServerTrusted failing, returning a different `StatusCode` as appropriate.
            if (BuildConfig.TEST) {
                var rootCause: Throwable? = e
                while (rootCause?.cause != null && rootCause.cause != rootCause) {
                    rootCause = rootCause.cause
                }
                return when (rootCause) {
                    is CertificateExpiredException, is CertificateNotYetValidException -> VerificationResult(
                        StatusCode.Expired,
                        rootCause.toString()
                    )

                    else -> VerificationResult(StatusCode.UnknownCert, rootCause.toString())
                }
            }
            // In non-test configurations we should have caught expiry errors earlier and
            // can simply return an unknown cert error without digging through the exception
            // cause chain.
            return VerificationResult(StatusCode.UnknownCert, e.toString())
        } catch (e: RuntimeException) {
            Log.w(TAG, "unexpected failure validating a server certificate", e)
            return VerificationResult(StatusCode.Unavailable)
        }

        // Defense in depth for non-debug artifacts: even if the platform trust manager behavior
        // changes, never accept a chain whose final trust anchor is not in the filtered system set.
        if (!BuildConfig.DEBUG && !BuildConfig.TEST) {
            val root = validChain.lastOrNull()
                ?: return VerificationResult(StatusCode.UnknownCert)
            if (!isKnownRoot(root)) {
                return VerificationResult(StatusCode.UnknownCert)
            }
        }

        // TEST ONLY: Mock test suite cannot attempt to check revocation status if no OSCP data has been stapled,
        // because Android requires certificates to an specify OCSP responder for network fetch in this case.
        // If in testing w/o OCSP stapled, short-circuit here - only prior checks apply.
        if (BuildConfig.TEST && (mockKeystore.size() != 0) && (ocspResponse == null)) {
            return VerificationResult(StatusCode.Ok)
        }

        // =================================================================================================
        // IMPORTANT! DIFF WITH THE ORIGINAL SOURCES HERE:
        //
        // We removed the cert revocation checks because Android won't do them by default
        // and supporting them would be quite difficult given CRLs won't work out of the box.
        //
        // See https://github.com/rustls/rustls-platform-verifier/issues/221 for more info.
        // =================================================================================================

        return VerificationResult(StatusCode.Ok)
    }

    private fun verifyCertUsage(certificate: X509Certificate, allowedEkus: Array<String>): Boolean {
        val ekus = try {
            certificate.extendedKeyUsage
        }
        // This should be unreachable, but could happen.
        catch (_: CertificateParsingException) {
            return false
        } catch (_: NullPointerException) {
            // According to Chromium's implementation, this can crash when the EKU data is malformed.
            Log.w(TAG, "exception handling certificate EKU")
            return false
        } ?: return true // If the list is empty, we have nothing to do.

        return ekus.any { allowedEkus.contains(it) }
    }

    // Check whether a root is an active Android system CA. User/enterprise-installed CAs are
    // deliberately excluded from this set for SecureChat release builds.
    fun isKnownRoot(root: X509Certificate): Boolean {
        return Pair(root.subjectX500Principal, root.publicKey) in systemTrustAnchors
    }
}
