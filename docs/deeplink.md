# SecureChat Android deep links

SecureChat accepts two link families:

- Internal session links: `securechat://open/{userId}/{roomId}/{threadId}/{eventId}`. Only the
  user ID is required; every segment must be URI-encoded when it contains reserved characters.
- Matrix protocol links such as `matrix:r/securechat:chat.securechat.com.au`.

Public HTTPS login/configuration links are disabled: the application manifest has no HTTPS intent
filter and the login-intent resolver rejects them. The domain-association statement currently does
not authorize the SecureChat production package and signing certificate, so accepting such links
would not provide a verified ownership boundary.

Do not restore this link family until all of the following are true:

- the intended HTTPS route and its parameter contract exist and have been audited on a
  SecureChat-owned server;
- `https://chat.securechat.com.au/.well-known/assetlinks.json` removes every obsolete application
  target and authorizes only the intended SecureChat build, including package `com.securechat.app`
  and the SHA-256 digest of the certificate signing the manually distributed production APK;
- the manifest uses an exact host/path filter with `android:autoVerify="true"`, the resolver checks
  the same endpoint, and a production-signed installation reports the domain as verified through
  `adb shell pm get-app-links com.securechat.app`.

`autoVerify=true` only requests verification. Without the matching server statement it is not a
security boundary; Android version and user settings can still affect dispatch. Do not reuse a
debug-certificate digest for production.

The OAuth callback is separate from navigation links: release builds use
`com.securechat://oauth/callback` and debug builds use
`com.securechat.debug://oauth/callback`. The exact host and path are enforced by both the manifest
and callback parser.

SecureChat generates `matrix:` protocol links for users and room aliases. It can parse a valid
`https://matrix.to/` link locally for interoperability, but it does not generate new public
matrix.to links or rewrite links from arbitrary web origins into in-app navigation.

Developer examples are maintained in `tools/adb/deeplink.sh`, `tools/adb/deeplink_matrix.sh`, and
`tools/adb/deeplink_matrixto.sh`. After the server-side blocker has been resolved and the HTTPS link
family deliberately restored, inspect Android link state for a production installation with:

```bash
adb shell pm get-app-links com.securechat.app
```
