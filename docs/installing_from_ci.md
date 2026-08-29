# Installing a debug build from CI

The `SecureChat APK Build` workflow uploads two F-Droid debug artifacts:

- `app-debug.apk` for arm64 phones and Apple Silicon emulators.
- `app-debug-x86_64.apk` for x86_64 emulators.

Open the relevant workflow run in GitHub Actions, download the matching artifact, extract it, and run:

```bash
adb install -r app-debug.apk
```

These APKs use the public Android debug key and are for testing only. They cannot update a production
SecureChat installation signed with the release key. Use the signed release workflow described in
`docs/install_from_github_release.md` for distributable builds.

The scripts in `tools/github/` can download artifacts when automation needs the GitHub API. Use a
short-lived, least-privilege token; public browser downloads do not require storing a token in this
repository.
