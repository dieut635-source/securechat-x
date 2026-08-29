# SecureChat Android deep links

SecureChat accepts three link families:

- Internal session links: `securechat://open/{userId}/{roomId}/{threadId}/{eventId}`. Only the
  user ID is required; every segment must be URI-encoded when it contains reserved characters.
- Matrix protocol links such as `matrix:r/securechat:chat.securechat.com.au`.
- SecureChat login/configuration links at
  `https://chat.securechat.com.au/securechat/?account_provider=<provider>`.

The HTTPS association is not considered verified until
`https://chat.securechat.com.au/.well-known/assetlinks.json` contains the production application ID
and signing-certificate digest. Do not reuse a debug-certificate digest for production.

The OAuth callback is separate from navigation links: release builds use `com.securechat:/` and
debug builds use `com.securechat.debug:/`.

Developer examples are maintained in `tools/adb/deeplink.sh`, `tools/adb/deeplink_matrix.sh`, and
`tools/adb/deeplink_matrixto.sh`. To inspect Android link state for a debug installation:

```bash
adb shell pm get-app-links com.securechat.app.debug
```
