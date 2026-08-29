[![SecureChat build](https://github.com/dieut635-source/securechat-x/actions/workflows/securechat-build.yml/badge.svg)](https://github.com/dieut635-source/securechat-x/actions/workflows/securechat-build.yml)

# SecureChat for Android

SecureChat is an independent Matrix client for Android, preconfigured for the SecureChat service at [chat.securechat.com.au](https://chat.securechat.com.au). It supports encrypted messaging, rooms, file sharing, voice messages, and voice/video calls in a modern Jetpack Compose interface.

The release application ID is `com.securechat.app`; the launcher and all user-facing application names use **SecureChat**. Debug and nightly builds add a visible suffix so they cannot be mistaken for a production build.

## Secure defaults

- Default homeserver: `https://chat.securechat.com.au`
- Account registration: disabled unless an administrator explicitly enables it
- File sending: enabled by default and enforceable through managed configuration
- Automatic logout: disabled by default and configurable in minutes
- Analytics, crash reporting, and remote bug-report upload: disabled until SecureChat-owned endpoints are explicitly configured
- Firebase push: excluded until a SecureChat-owned Firebase project and push gateway are configured; F-Droid builds retain UnifiedPush support
- Legal, policy, OAuth metadata, and help links: hosted on `chat.securechat.com.au`

See [SECURECHAT.md](SECURECHAT.md) for managed-configuration keys, signing requirements, and fork-maintenance notes.

## Build

Requirements:

- Android SDK
- JDK 21
- Git LFS for upstream binary fixtures and screenshots when running the complete test suite

Build the F-Droid debug APK without Firebase:

```bash
./gradlew :app:assembleFdroidDebug --no-configuration-cache
```

Run the SecureChat configuration audit and focused tests:

```bash
bash tools/check/check_securechat_configuration.sh
./gradlew \
  :app:testFdroidDebugUnitTest \
  :libraries:deeplink:impl:testDebugUnitTest \
  :features:login:impl:testDebugUnitTest \
  :features:enterprise:impl-foss:testDebugUnitTest \
  :features:forward:impl:testDebugUnitTest \
  :libraries:mdm:impl:testDebugUnitTest \
  :features:messages:impl:testDebugUnitTest \
  :features:share:impl:testDebugUnitTest \
  --no-configuration-cache
```

Run Android lint:

```bash
./gradlew :app:lintFdroidDebug --no-configuration-cache
```

## Continuous integration and releases

[`securechat-build.yml`](.github/workflows/securechat-build.yml) checks branding/configuration invariants, builds F-Droid debug APKs, runs focused unit tests, and runs lint on pushes and pull requests targeting `main` or `develop`.

[`securechat-release.yml`](.github/workflows/securechat-release.yml) produces signed release APKs from a tag or a manual run. It requires the four `SECURECHAT_KEYSTORE_*` repository secrets plus `SECURECHAT_RELEASE_CERT_SHA256`, and rejects an APK unless its signer matches that pinned production certificate.

The repository also retains generic build, test, lint/quality, screenshot, LFS, dependency-analysis,
Maestro, and optional Sonar workflows. Parent-company release, enterprise-submodule, triage, and
localization automations have been removed; the retained workflows do not require a private submodule.

Do not publish a locally built release that falls back to the repository debug key. Losing the production keystore prevents in-place updates for installed users.

## Managed Android deployments

SecureChat exposes four Android Enterprise restrictions:

| Key | Type | Default | Behaviour |
|---|---|---:|---|
| `homeserver_url` | string | `https://chat.securechat.com.au` | Enforces the configured homeserver |
| `allow_registration` | boolean | `false` | Controls account-creation entry points |
| `allow_file_send` | boolean | `true` | Controls attachments, media sharing, voice messages, and inbound file shares |
| `auto_logout_minutes` | integer | `0` | Logs out after the configured background interval; `0` disables automatic logout |

The key names are a deployment contract. Renaming them silently resets already-managed devices to defaults.

## Project architecture

The client uses the Matrix Rust SDK through an FFI boundary, Appyx for navigation, Metro for dependency injection, and Jetpack Compose for UI. Production source packages still use the inherited `io.element.android.*` namespace to avoid a high-risk, repository-wide migration. This namespace is an internal technical identifier; the installable package remains `com.securechat.app`.

## Provenance and licence

This repository is a modified fork of the open-source Element X Android project. Existing source-file copyright notices for Element Creations Ltd., New Vector Ltd., and other contributors are legal attribution and are intentionally retained; they are not SecureChat product branding.

The licence text is in [LICENSE](LICENSE). Additional upstream attribution is retained in [AUTHORS.md](AUTHORS.md), source headers, dependency notices, and version history.
