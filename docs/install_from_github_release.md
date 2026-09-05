# Offline SecureChat release and computer-only installation

SecureChat production APKs are never built or signed in GitHub Actions. The production key must stay
offline. The workflow in `.github/workflows/securechat-release.yml` is only a source gate: it runs
audits, tests, lint, and release-source compilation, and explicitly fails if CI creates an APK/AAB.

> **Current status (2026-08-30): NO-GO.** No approved production APK has been created. The commands
> below define the required ceremony; they are not evidence that it has run. Old workspace APKs that
> used an RSA-1024 debug signer were quarantined and must never be installed or distributed.

## Release ceremony

Use a dedicated, encrypted workstation with networking physically disabled. The workstation must
have JDK 21, the Android SDK, Python 3, Git LFS objects, a fully populated offline Gradle cache, and
reviewed `gradle/verification-metadata.xml` SHA-256 pins for that cache, and the trusted release-tag
public key. Keep the independently approved OpenPGP primary/signing-key fingerprint in a separate
file outside the checkout; a locally imported key or a merely valid signature is not sufficient.
Install the exact Android Build Tools version declared in
`plugins/src/main/kotlin/Versions.kt`; the release script rejects a different/missing version. Review
the source-gate result and the exact commit before starting.

The audited workstation hydrated all 3,170 tracked UI snapshots and matched each file SHA-256 to its
Git LFS object ID in `HEAD`; no pointer/missing/mismatched object remained. This does **not** make the
current source releasable: full Paparazzi still has 2,841 real mismatches out of 3,170 tests. Review
and close that gate on the final commit. Do not record all baselines merely to turn the gate green.

1. Verify that the approved commit is clean, then create a signed annotated tag matching
   `Versions.VERSION_NAME`, for example:

   ```bash
   git tag -s v26.08.3 -m "SecureChat 26.08.3"
   git verify-tag --raw v26.08.3
   ```

   Independently compare the release-tag public key with the approved key-ceremony record, then put
   exactly its 40- or 64-hex primary fingerprint (or the exact signing-subkey fingerprint) in an
   offline file such as `/secure/offline/securechat-release-tag-signer.fingerprint`. Do not derive
   this pin from the tag, checkout, release output, or the workstation's existing GnuPG keyring.

2. Mount/read the production keystore, certificate pin, and release-tag signer fingerprint file.
   All three must be outside the repository. Run:

   ```bash
   tools/release/build_securechat_offline.sh \
     --keystore /secure/offline/securechat-release.keystore \
     --alias securechat \
     --cert-pin-file /secure/offline/securechat-release-cert.sha256 \
     --tag-signer-fingerprint-file /secure/offline/securechat-release-tag-signer.fingerprint
   ```

   Passwords are requested using hidden terminal input. The script does not accept password flags,
   does not log them, runs Gradle offline without a persistent daemon, and clears its temporary files.

3. The script refuses dirty/untagged source, unsigned tags, signatures whose machine-readable GnuPG
   `VALIDSIG` primary/signing fingerprint does not match the independent pin, missing reviewed
   dependency-verification metadata, keys stored in the repository, weak or
   expiring certificates, debug/nightly signers, non-v2/v3 APK signatures, bad zip alignment,
   package/version mismatches, failing tests/lint, and output-directory overwrite. It also rejects
   MapTiler/Sentry/PostHog/Rageshake values inherited from the shell or `local.properties`. Gradle
   release packaging requires the certificate pin and a revision-bound marker created by this script;
   an APK found directly under `app/build/` is never an approved production artifact.

   Before Gradle starts, the script runs `git lfs fsck`, checks out tracked LFS objects from the
   offline cache, and rejects any tracked file that still contains an LFS pointer header. The source
   gate must also include reviewed SBOM/advisory results for native Rust/crates, embedded AARs and the
   call web bundle; an OWASP JVM dependency report alone is insufficient.

   Distribution files are built in a hidden staging directory under the selected output root. The
   script publishes that directory with one same-filesystem rename only after every APK, signed
   metadata file, checksum, provenance record, and final source-tree check succeeds. On success,
   failure, or interruption it removes staging and every raw signed APK under `app/build/`; only the
   atomically published final directory is eligible for distribution.

4. Move the resulting `release-out/SecureChat-<version>-<commit>/` directory to approved offline
   distribution media. It contains ABI-specific APKs, `SHA256SUMS`, `release-provenance.json`, and a
   metadata JAR signed with the same production key.

## Independent verification on the installation computer

Do not trust the loose checksum or provenance file until the signed metadata container has been
verified. Copy the expected production certificate SHA-256 fingerprint from a separate offline/paper
record, not from the release directory.

```bash
mkdir verified-metadata
keytool -printcert -jarfile SecureChat-26.08.3-release-metadata.jar -rfc \
  | sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' \
  | sed -n '1,/-----END CERTIFICATE-----/p' \
  > verified-metadata/release-certificate.pem
openssl x509 -in verified-metadata/release-certificate.pem -noout -sha256 -fingerprint
keytool -importcert -noprompt \
  -alias securechat-release \
  -file verified-metadata/release-certificate.pem \
  -keystore verified-metadata/trusted-release.p12 \
  -storetype PKCS12 \
  -storepass changeit
jarsigner -verify -strict \
  -keystore verified-metadata/trusted-release.p12 \
  -storepass changeit \
  SecureChat-26.08.3-release-metadata.jar
(cd verified-metadata && jar -xf ../SecureChat-26.08.3-release-metadata.jar)
shasum -a 256 -c verified-metadata/SHA256SUMS
```

`changeit` above protects only a temporary trust store containing the public certificate; it is not a
production secret. Confirm that the certificate SHA-256 printed by OpenSSL matches the independent
pin **before** trusting the strict JAR result. Inspect `verified-metadata/release-provenance.json` and
compare its version, tag, pinned/actual OpenPGP signer and primary fingerprints, full commit and
source tree with the separately approved release record; a validly signed old release must not be
accepted for a newly provisioned device.

Then verify the selected APK directly with the Android SDK before installation:

```bash
apksigner verify --verbose --print-certs SecureChat-26.08.3-arm64-v8a.apk
aapt dump badging SecureChat-26.08.3-arm64-v8a.apk | head -n 3
adb install --no-streaming -r SecureChat-26.08.3-arm64-v8a.apk
```

The APK must have application ID `com.securechat.app`, exactly one signer, v1 signing disabled,
v2/v3 signing enabled, and the same pinned certificate. Never use `adb install -d`: allowing a
downgrade weakens rollback protection. Android will reject an update signed with a different key.
Do not work around that error by uninstalling—the uninstall removes local encrypted state and may
hide a tampered package.

Before connecting a phone, confirm that it is enrolled in the approved fleet: supported OEM/Android
security patch, locked bootloader, trusted Verified Boot state, no root, and Android System WebView
at or above the version approved by the security owner. The app currently supports API 24, but
installability on Android 7 is not proof that its vendor firmware or WebView is still safe.

## Apply managed configuration after sideloading

`adb install` installs the package only. It does not set Android managed configurations. Provision a
DPC/EMM as Device Owner or Profile Owner (or an approved OEM platform such as Knox), then use its
application-restrictions API for `com.securechat.app`:

| Key | APK default | Production requirement |
| --- | --- | --- |
| `homeserver_url` | `https://chat.securechat.com.au` | Must remain the canonical SecureChat URL. |
| `allow_registration` | `false` | Must remain disabled. |
| `allow_file_send` | `true` | Set explicitly according to the approved DLP policy. |
| `auto_logout_minutes` | `0` | Use an approved positive value for a managed high-security fleet. |

Verify what the app actually receives at startup and resume. Test a live policy change, process
death and device reboot. If the DPC uses `KEY_RESTRICTIONS_PENDING`, policy-sensitive functionality
must remain fail-closed until the final bundle is available; do not treat permissive APK defaults as
proof that an administrator policy has been applied.

After installation, disconnect USB, revoke the computer's debugging authorization on the device,
and disable Developer options/USB debugging unless operational policy requires it. Do not transfer
production APKs through chat, personal email, public file sharing, QR-code installers, or Diawi.

Record the device serial, owner/profile, policy version, app version, full commit, production
certificate fingerprint, APK checksum, installer and timestamp in the fleet inventory. Repeat the
managed-policy and smoke checks after reboot before handover.

Firebase and public UnifiedPush are intentionally disabled. Consequently, messages and calls may
not notify the user until SecureChat is opened or Android permits background sync. Obtain explicit
product/security risk acceptance for this limitation, or deploy a separately threat-reviewed and
tested SecureChat-controlled push gateway before promising real-time notifications.

ADB installation remains available under Android's developer-verification rollout. Even though the
app is never published on Google Play, register `com.securechat.app` and its production signing key
through the Android Developer Console for apps distributed exclusively outside Play before the
global enforcement planned from 2027. Registration is not store publication. See
https://developer.android.com/developer-verification/guides for the current rollout and account
requirements.

Keep the previous verified APK for emergency rollback planning, but do not install it over a newer
version. Any rollback must use an explicitly designed data-migration/recovery procedure.

Because there is no app store rollout or recall mechanism, maintain a patch SLA for Critical/High
findings, a compliance view showing every installed version, a secure emergency-notification
channel, and a server-side minimum supported client version (or equivalent block). Exercise forced
update, key-compromise response, device recall and rollback/data-migration procedures before public
production use.
