# Continuous integration

SecureChat uses GitHub Actions with JDK 21. The primary workflows are:

- `securechat-build.yml`: branding/configuration audit, F-Droid debug APKs, focused unit tests, and
  Android lint.
- `securechat-release.yml`: intentional tag/manual F-Droid release, mandatory private signing
  secrets, lint/tests, and post-build certificate verification.
- `tests.yml` and `quality.yml`: broader unit, screenshot, lint, Detekt, Ktlint, Konsist, shell, and
  workflow checks.
- `recordScreenshots.yml` and `validate-lfs.yml`: visual-baseline recording and LFS validation.
- `maestro-local.yml`: manual emulator smoke tests using SecureChat-owned test accounts.
- `nightlyReports.yml` and `sonar.yml`: scheduled reports and optional Sonar upload when configured.

Generic build jobs may compile release sources, but they do not publish distributable release
artifacts. Only `securechat-release.yml` is authorized to upload a release APK, and it fails when
signing secrets are missing or the public Android debug certificate is detected.

Parent-company enterprise, release, translation, PR-policy, Danger, and triage automations were
removed because they depended on private repositories, accounts, or secrets not owned by SecureChat.
