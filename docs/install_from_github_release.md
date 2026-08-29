# Installing a signed SecureChat build

SecureChat does not currently attach APKs to GitHub Releases automatically. The trusted distribution
path is the `SecureChat Release APK` workflow in `.github/workflows/securechat-release.yml`.

1. Open the workflow run for the intended tag or approved manual release.
2. Confirm that the `Xác minh chữ ký` step succeeded.
3. Download the `securechat-release-apk` artifact and extract it.
4. Install the APK from Android's file manager, or with:

   ```bash
   adb install -r path/to/app-fdroid-arm64-v8a-release.apk
   ```

Android only accepts an in-place update when the application ID, signing key, and version ordering
match the installed application. A signature mismatch requires uninstalling the existing app, which
removes its local data. Verify the certificate digest shown in the workflow summary before wider
distribution and ensure it matches previous SecureChat releases.

Never distribute outputs from a local release build, the generic build workflow, or the repository's
public debug/nightly keys.
