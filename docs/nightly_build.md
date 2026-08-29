# Nightly builds

The `nightly` build type is available for local testing and has application ID
`com.securechat.app.nightly` and a visible nightly name suffix. CI only compiles nightly sources; it
does not publish or distribute nightly APKs.

The repository contains a public test keystore and dummy nightly passwords so developers can verify
the variant locally. Anything signed with that key is untrusted and must never be distributed as a
SecureChat release.

Build locally with JDK 21:

```bash
./gradlew :app:assembleGplayNightly --no-configuration-cache
```

For an internal deployment, provide a private `app/signature/nightly.keystore` and set
`SECURECHAT_NIGHTLY_STORE_PASSWORD`, `SECURECHAT_NIGHTLY_KEY_ID`, and
`SECURECHAT_NIGHTLY_KEY_PASSWORD`. Keep those values outside the repository. Production releases
must always use `.github/workflows/securechat-release.yml` and its separate release key.
