# Continuous integration

SecureChat uses GitHub Actions with JDK 21. The primary workflows are:

- `securechat-build.yml`: branding/configuration audit, F-Droid debug APKs, focused unit tests, and
  Android lint.
- `securechat-release.yml`: production source gate for exact tag/manual revisions. It validates LFS,
  full tests/screenshots, Detekt/Ktlint, dependency vulnerabilities, release lint, and unsigned
  release-source compilation. Dependency-Check fails on every known scored vulnerability (CVSS
  threshold `0.0`) and uploads its reports for review. The workflow has no signing secrets and creates
  no APK/AAB.
- `tests.yml` and `quality.yml`: broader unit, screenshot, lint, Detekt, Ktlint, Konsist, shell, and
  workflow checks.
- `recordScreenshots.yml` and `validate-lfs.yml`: visual-baseline recording and LFS validation.
- `maestro-local.yml`: manual emulator smoke tests using SecureChat-owned test accounts.
- `nightlyReports.yml` and `sonar.yml`: scheduled reports and optional Sonar upload when configured.

All cloud jobs may compile release sources, but they must not package or upload a production release.
The production key must never be stored in GitHub Secrets or exposed to a hosted runner. Signed APKs
are created only on the isolated workstation with `tools/release/build_securechat_offline.sh`; Gradle
fails closed if someone requests release packaging without the complete offline signing environment.
When Gradle runs with `--offline`, Dependency-Check disables its own data updates and remote analyzers;
the release operator must therefore import the fresh, reviewed vulnerability database produced for
the same source-gate run before disconnecting the workstation.

Always invoke the multi-project scan as the root task `:dependencyCheckAggregate` with
`--no-parallel --no-configure-on-demand`. The leading colon prevents Gradle from selecting the task
of the same name in every subproject; serial configuration prevents Gradle 9 from resolving the
aggregate cross-project graph concurrently. CI also disables the configuration cache for this task.

Parent-company enterprise, release, translation, PR-policy, Danger, and triage automations were
removed because they depended on private repositories, accounts, or secrets not owned by SecureChat.
