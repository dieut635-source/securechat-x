# OAuth configuration

SecureChat delegates Matrix OAuth/OIDC discovery and authentication to the Matrix Rust SDK. Client
metadata is built from `plugins/src/main/kotlin/config/BuildTimeConfig.kt`:

- client name: `SecureChat`
- client URI, terms, and privacy URI: `https://chat.securechat.com.au`
- logo URI: `https://chat.securechat.com.au/securechat/favicon.svg`
- release redirect URI: `com.securechat://oauth/callback`
- debug redirect URI: `com.securechat.debug://oauth/callback`

The redirect schemes are declared through `login_redirect_scheme` in `app/build.gradle.kts` and
consumed by `app/src/main/AndroidManifest.xml`. Any OAuth client registration on the homeserver must
use the exact scheme for the installed build type.

The callback parser requires the exact scheme, host and path, a single non-empty `state`, and either
a single non-empty authorization `code` or the exact cancellation error. The Matrix SDK retains the
OAuth transaction and performs state/PKCE validation before committing a session.

The current callback contract is therefore:

- endpoint: exactly `com.securechat://oauth/callback` for release or
  `com.securechat.debug://oauth/callback` for debug;
- success query: exactly one non-empty `state` and exactly one non-empty `code`, with no `error`;
- cancellation query: exactly one non-empty `state` and exactly one `error=access_denied`, with no
  `code`;
- fragments, duplicate security parameters, other hosts/paths, and non-`VIEW` intents are rejected.

The parser checks that `state` is present and unambiguous. The SDK remains responsible for comparing
it with the transaction's unpredictable stored value and for validating PKCE; presence checking is
not a substitute for that comparison.

Residual requirement: Android custom schemes cannot cryptographically prove application ownership,
so another installed application can claim the same scheme and cause callback interception (PKCE
prevents it from redeeming the stolen code, but interception can still deny login). Eliminating this
risk requires a SecureChat-owned HTTPS callback, matching OAuth client metadata, and a valid
`assetlinks.json` containing the production signing certificate. Do not switch to such a callback or
invent a server path until all three server-side pieces have been deployed and tested together.

The separate HTTPS login/configuration App Link and its resolver are disabled while the server
association is invalid. Before restoring them, the server statement must remove every obsolete
application target, name Android package `com.securechat.app`, and contain the SHA-256 digest of the
certificate that signs the manually distributed production APK. Manifest `autoVerify=true` only
requests verification; it does not prove that this server-side contract exists. Debug and any
separately signed internal builds need their own package/certificate statements if they are also
expected to verify.

The unsupported-server dialog deliberately has no external documentation target. Add one only after
an audited HTTPS page exists on a SecureChat-owned route.

Do not add real authorization codes, state values, test-account credentials, or production tokens to
documentation or fixtures. Protocol-level SDK references belong to the Matrix Rust SDK and are not
SecureChat product branding.
