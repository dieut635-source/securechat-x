# OAuth configuration

SecureChat delegates Matrix OAuth/OIDC discovery and authentication to the Matrix Rust SDK. Client
metadata is built from `plugins/src/main/kotlin/config/BuildTimeConfig.kt`:

- client name: `SecureChat`
- client URI, terms, and privacy URI: `https://chat.securechat.com.au`
- logo URI: `https://chat.securechat.com.au/securechat/favicon.svg`
- release redirect URI: `com.securechat:/`
- debug redirect URI: `com.securechat.debug:/`

The redirect schemes are declared through `login_redirect_scheme` in `app/build.gradle.kts` and
consumed by `app/src/main/AndroidManifest.xml`. Any OAuth client registration on the homeserver must
use the exact scheme for the installed build type.

Do not add real authorization codes, state values, test-account credentials, or production tokens to
documentation or fixtures. Protocol-level SDK references belong to the Matrix Rust SDK and are not
SecureChat product branding.
