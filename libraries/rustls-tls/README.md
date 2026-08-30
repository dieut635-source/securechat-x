This module is a wrapper for the Android code distributed in the rustls-platform-verifier-android crate.

To avoid the distribution mess that this library has (download a Rust crate, then search for it using Gradle and use it as local maven repo),
we previously just manually updated the AAR file instead using a script. This won't work for F-Droid because the AAR library is a black box with
no sources attached to it, so we can't use it like that.

Instead, for the time being, we're adding the single `CertificateVerifier.kt` class this AAR had in it as part of our sources.

When this file is updated, the [UPDATED.md](./UPDATED.md) file should be updated too with the commit SHA of the new version.

## SecureChat hardening

SecureChat applies a local hardening patch on top of the vendored implementation. Release and
nightly builds copy only active `system:` aliases out of Android's `AndroidCAStore` and validate
against that filtered keystore. They fail closed if the system-only store cannot be created. This
prevents a user- or enterprise-installed CA from becoming a trust anchor for Matrix traffic that
is verified through Rust and therefore does not use Android's Network Security Config directly.

Debug builds retain the complete Android CA store to support local development and TLS inspection.
The release chain is additionally checked to ensure its final anchor belongs to the filtered system
set. Keep this policy and its tests when importing a newer upstream verifier.
