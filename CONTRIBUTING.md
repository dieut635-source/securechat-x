# Contributing to SecureChat Android

Thank you for helping improve SecureChat. This repository is an Android Matrix client configured for `https://chat.securechat.com.au`.

## Before opening a change

- Search this repository's issues and pull requests for related work.
- Use the `develop` branch as the pull-request base.
- Keep changes focused and explain user-facing behavior in the pull-request description.
- Preserve source copyright and SPDX notices. They are required legal attribution.
- Do not add product-facing references, analytics endpoints, Firebase projects, or legal links owned by the upstream project.

## Development setup

Use JDK 21 and a current Android SDK. Build the privacy-preserving F-Droid variant with:

```bash
./gradlew :app:assembleFdroidDebug --no-configuration-cache
```

Run the configuration audit before submitting:

```bash
bash tools/check/check_securechat_configuration.sh
```

Run focused tests and lint for the area you changed. The SecureChat CI workflow runs the app branding and managed-configuration tests, affected feature-module tests, Android lint, and an F-Droid debug build.

## Strings and translations

English product strings should live in an application-owned resource file such as `app/src/main/res/values/securechat_strings.xml`. Do not directly edit generated `localazy.xml` files. Add or update tests whenever an application overlay is used to neutralize an inherited value.

SecureChat currently packages English resources. If another locale is enabled, its full product-facing resource set must be reviewed and translated consistently before release.

## UI and artwork

- Give new Composables a preview where practical.
- Test light and dark themes, accessibility labels, and large font sizes.
- Do not reuse inherited product logos or store imagery.
- UI changes should include current SecureChat screenshots. The inherited screenshot gallery is intentionally not published until its baselines are regenerated.

## Managed configuration

The following keys are a stable deployment contract and must remain backward compatible:

- `homeserver_url`
- `allow_registration`
- `allow_file_send`
- `auto_logout_minutes`

Policy checks belong at both the UI entry point and the final action boundary. Tests should cover live policy changes and stale callbacks.

## Pull requests

Use a short sentence-style title that describes the user-visible outcome. Include:

- motivation and scope;
- tests and build commands run;
- screenshots for UI or branding changes;
- migration, privacy, or deployment implications;
- any known limitation that remains.

Choose exactly one `PR-*` release-note label. Use `Record-Screenshots` when UI baselines need to be regenerated.
