#!/usr/bin/env bash

set -euo pipefail

failure=0

require_literal() {
    local file="$1"
    local literal="$2"
    if ! grep -Fq -- "$literal" "$file"; then
        printf 'Missing required SecureChat configuration in %s: %s\n' "$file" "$literal" >&2
        failure=1
    fi
}

runtime_config_files=(
    app/src/main/AndroidManifest.xml
    appconfig/build.gradle.kts
    appconfig/src/main/kotlin/io/element/android/appconfig/ApplicationConfig.kt
    appconfig/src/main/kotlin/io/element/android/appconfig/LearnMoreConfig.kt
    features/preferences/impl/build.gradle.kts
    features/call/impl/src/main/AndroidManifest.xml
    features/location/api/build.gradle.kts
    features/login/impl/build.gradle.kts
    features/login/impl/src/main/AndroidManifest.xml
    features/login/impl/src/main/kotlin/io/element/android/features/login/impl/DefaultLoginIntentResolver.kt
    features/login/impl/src/main/kotlin/io/element/android/features/login/impl/accesscontrol/DefaultAccountProviderAccessControl.kt
    features/login/impl/src/main/kotlin/io/element/android/features/login/impl/classic/ElementClassicConnection.kt
    libraries/matrix/api/build.gradle.kts
    libraries/push/impl/build.gradle.kts
    libraries/pushproviders/firebase/src/main/kotlin/io/element/android/libraries/pushproviders/firebase/FirebaseConfig.kt
    libraries/pushproviders/firebase/src/main/res/values/firebase.xml
    plugins/src/main/kotlin/ModulesConfig.kt
    plugins/src/main/kotlin/config/BuildTimeConfig.kt
    services/analyticsproviders/posthog/build.gradle.kts
    services/analyticsproviders/posthog/src/main/kotlin/io/element/android/services/analyticsproviders/posthog/PosthogEndpointConfigProvider.kt
    services/analyticsproviders/sentry/build.gradle.kts
)

shopt -s nullglob
workflow_config_files=(.github/workflows/*.yml)
operational_branding_files=(
    "${workflow_config_files[@]}"
    .github/*.yml
    .github/*.yaml
    .github/ISSUE_TEMPLATE/*
    .github/workflows/scripts/*.sh
    .github/workflows/scripts/maestro/*.sh
    tools/adb/*.sh
    tools/compound/*.sh
    tools/danger/*.js
    tools/github/*.py
    tools/localazy/README.md
    tools/manifest/gplay/release/aaptDump.txt
)
shopt -u nullglob
if [[ -d .maestro ]]; then
    while IFS= read -r file; do
        operational_branding_files+=("$file")
    done < <(find .maestro -type f \( -name '*.md' -o -name '*.yaml' -o -name '*.yml' \) -print)
fi

forbidden_runtime_pattern='(https?://[^[:space:]"<]*(element\.(io|dev)|vector\.im)|posthog\.element\.(io|dev)|rageshakes\.element\.io|vector-alpha|/vector-icons/|android:scheme="element(x)?"|android:taskAffinity="io\.element|System\.getenv\("ELEMENT_|im\.vector|io\.element\.enterprise)'
if grep -Eni -- "$forbidden_runtime_pattern" "${runtime_config_files[@]}"; then
    printf 'Inherited branding or endpoint found in runtime configuration.\n' >&2
    failure=1
fi

# The embedded-call build deliberately contains upstream URL literals as transformation inputs.
# Audit its generated output in Gradle, and statically ensure the raw branded AAR cannot be merged.
call_build_file=features/call/impl/build.gradle.kts
if grep -En -- 'implementation\(libs\.element\.call\.embedded\)' "$call_build_file"; then
    printf 'Raw branded element-call-embedded AAR is still merged into the application.\n' >&2
    failure=1
fi
if grep -En -- 'System\.getenv\("ELEMENT_CALL_' "$call_build_file"; then
    printf 'Inherited Element Call environment variable remains in the call build.\n' >&2
    failure=1
fi

if (( ${#workflow_config_files[@]} > 0 )) && \
    grep -En -- '(ELEMENT_[A-Z0-9_]+|DANGER_GITHUB_API_TOKEN)' "${workflow_config_files[@]}"; then
    printf 'Inherited environment variable found in build workflows.\n' >&2
    failure=1
fi

operational_branding_pattern='element-hq|https?://[^[:space:]"<]*element\.(io|dev)|element[[:space:]_-]*x|element[[:space:]_-]+pro|element[[:space:]_-]*(bot|enterprise|android|meta)|vector\.im|riot\.im|PR-Element-Pro'
if (( ${#operational_branding_files[@]} > 0 )) && \
    grep -Eni -- "$operational_branding_pattern" "${operational_branding_files[@]}"; then
    printf 'Inherited branding or parent-company integration found in operational files.\n' >&2
    failure=1
fi

# Preview providers are compiled from production sources and feed screenshot baselines, so their
# sample identities must not make the old public homeserver look like a SecureChat default.
preview_source_files=()
while IFS= read -r file; do
    if [[ "$file" == *Preview* ]] || grep -Eq -- '@(Preview|Previews)' "$file"; then
        preview_source_files+=("$file")
    fi
done < <(find app appicon features libraries services -type f -path '*/src/main/*' -name '*.kt' -print)
if (( ${#preview_source_files[@]} > 0 )) && \
    grep -Eni -- 'matrix\.org' "${preview_source_files[@]}"; then
    printf 'Inherited public homeserver found in a production UI preview.\n' >&2
    failure=1
fi

# Diagnostic output can be attached to reports or observed by administrators. Keep product names
# neutral even though class and package identifiers remain source-compatible with upstream.
diagnostic_source_files=()
while IFS= read -r file; do
    diagnostic_source_files+=("$file")
done < <(find app appicon features libraries services -type f -path '*/src/main/*' -name '*.kt' -print)
if (( ${#diagnostic_source_files[@]} > 0 )) && \
    grep -Eni -- '(Timber\.[[:alnum:]_.()]+|loggerTag\.value).*Element[[:space:]]+(X|Pro|Classic|Call|Android)' \
        "${diagnostic_source_files[@]}"; then
    printf 'Inherited product name found in diagnostic output.\n' >&2
    failure=1
fi

# Scan every production source tree rather than relying only on the hand-maintained runtime list
# above. String-literal scanning avoids package names and preserved copyright notices, while still
# catching hard-coded endpoints and Compose/UI text introduced in a new module. The matrix test
# fixture module is not packaged into the application; UtdTracker's matrix.org comparison is a
# protocol analytics dimension rather than a configured endpoint.
python3 - <<'PY' || failure=1
import re
import sys
from pathlib import Path

roots = [Path(name) for name in ("app", "appconfig", "appicon", "appnav", "features", "libraries", "services")]
source_suffixes = {".kt", ".java", ".xml", ".json", ".html", ".js", ".properties"}
string_literal = re.compile(r'"(?:\\.|[^"\\])*"', re.DOTALL)
brand_word = re.compile(r'(?<![A-Za-z0-9_])(?:Element|Vector|Riot)(?![A-Za-z0-9_])')
old_endpoint = re.compile(
    r'https?://[^\s"<]*(?:element\.(?:io|dev)|vector\.im|riot\.im)|'
    r'(?<![A-Za-z0-9_.])(?:vector\.im|riot\.im|matrix\.org)(?![A-Za-z0-9_.])',
    re.IGNORECASE,
)
allowed_endpoint_literals = {
    (Path("libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/analytics/UtdTracker.kt"), '"matrix.org"'),
}
errors = []
for root in roots:
    for path in root.glob("**/src/main/**/*"):
        if not path.is_file() or path.suffix not in source_suffixes or path.name == "localazy.xml":
            continue
        if Path("libraries/matrix/test") in path.parents:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for literal_match in string_literal.finditer(text):
            literal = literal_match.group(0)
            line = text.count("\n", 0, literal_match.start()) + 1
            if old_endpoint.search(literal) and (path, literal) not in allowed_endpoint_literals:
                errors.append(f"{path}:{line}: inherited endpoint in production string literal: {literal}")
            if path.suffix in {".kt", ".java"} and brand_word.search(literal):
                errors.append(f"{path}:{line}: inherited product name in production string literal: {literal}")

if errors:
    print("Production string-literal branding audit failed:", file=sys.stderr)
    print(*errors, sep="\n", file=sys.stderr)
    raise SystemExit(1)
PY

maestro_config_files=()
if [[ -d .maestro ]]; then
    while IFS= read -r file; do
        maestro_config_files+=("$file")
    done < <(find .maestro -type f \( -name '*.md' -o -name '*.yaml' -o -name '*.yml' \) -print)
fi
if (( ${#maestro_config_files[@]} > 0 )) && \
    grep -Eni -- 'matrix\.org|io\.element\.android\.x|maestroelement' "${maestro_config_files[@]}"; then
    printf 'Inherited account, server, or application ID found in Maestro configuration.\n' >&2
    failure=1
fi

if ! bash tools/manifest/check_securechat_aapt_dump.sh tools/manifest/gplay/release/aaptDump.txt; then
    failure=1
fi

obsolete_operational_files=(
    .github/workflows/build_enterprise.yml
    .github/workflows/danger.yml
    .github/workflows/fork-pr-notice.yml
    .github/workflows/generate_github_pages.yml
    .github/workflows/post-release.yml
    .github/workflows/pull_request.yml
    .github/workflows/release.yml
    .github/workflows/sync-localazy.yml
    .github/workflows/triage-incoming.yml
    .github/workflows/triage-labelled.yml
    CODEOWNERS
    screenshots/html/data.js
    screenshots/html/script.js
)
for obsolete_file in "${obsolete_operational_files[@]}"; do
    if [[ -e "$obsolete_file" ]]; then
        printf 'Obsolete inherited operational file is present: %s\n' "$obsolete_file" >&2
        failure=1
    fi
done

# The disabled public screenshot gallery must not be regenerated by maintenance tooling.
if grep -En -- 'screenshots/html/(data|script)\.js|generateJavascriptFile' \
    tools/test/generateAllScreenshots.py; then
    printf 'Screenshot tooling can recreate the disabled public gallery.\n' >&2
    failure=1
fi

operational_docs=(
    docs/analytics.md
    docs/continuous_integration.md
    docs/deeplink.md
    docs/install_from_github_release.md
    docs/installing_from_ci.md
    docs/nightly_build.md
    docs/oauth.md
    docs/pull_request.md
    docs/screenshot_testing.md
    screenshots/README.md
)
forbidden_operational_doc_pattern='element-hq|https?://[^[:space:]>)]*element\.(io|dev)|element-x/PLAN\.md|~/SecureChat/server/scripts|server/scripts/make-release-keystore\.sh|ELEMENT_(ANDROID|SDK|CALL)|DANGER_GITHUB_API_TOKEN|io\.element\.android\.x|vector-(Fdroid|Gplay)'
if grep -Eni -- "$forbidden_operational_doc_pattern" "${operational_docs[@]}"; then
    printf 'Inherited or invalid instruction found in current operational documentation.\n' >&2
    failure=1
fi
if grep -En -- 'element-x/PLAN\.md|~/SecureChat/server/scripts|server/scripts/make-release-keystore\.sh' \
    SECURECHAT.md "${workflow_config_files[@]}"; then
    printf 'Broken workspace-only signing instruction found in SecureChat documentation or CI.\n' >&2
    failure=1
fi

store_metadata_files=(
    fastlane/metadata/android/en-US/title.txt
    fastlane/metadata/android/en-US/short_description.txt
    fastlane/metadata/android/en-US/full_description.txt
    fastlane/metadata/android/en-US/changelogs/*.txt
    tools/release/ReleaseNotesNightly.md
    tools/release/release.sh
    tools/release/releaseV2.sh
)
if ! python3 - "${store_metadata_files[@]}" <<'PY'
import re
import sys
from pathlib import Path

brand = re.compile(
    r"(?<![A-Za-z0-9_])(?:element|vector|riot)(?![A-Za-z0-9_])|"
    r"element-hq|element\.io|vector\.im",
    re.IGNORECASE,
)
legal_prefixes = (
    "# Copyright ",
    "# SPDX-License-Identifier:",
    "# Please see LICENSE ",
)
errors = []
for name in sys.argv[1:]:
    path = Path(name)
    for number, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        if line.lstrip().startswith(legal_prefixes):
            continue
        if brand.search(line):
            errors.append(f"{path}:{number}:{line}")

if errors:
    print(*errors, sep="\n")
    raise SystemExit(1)
PY
then
    printf 'Inherited branding found in store metadata or release tooling.\n' >&2
    failure=1
fi

# Guard the known inherited store graphics as well as text. The public screenshot set is intentionally
# limited to the two independently generated SecureChat graphics; inherited promotional art must not
# be reintroduced.
forbidden_store_asset_hashes=(
    8711263881562bd8d54827e526fbb6616bcf616f468bb5d85cc552d1bc07c29a
    d1035562e1a2998397b3c11cf536e199431584376b9f4fb9bff98d9ac8736adf
    9766ada948a92b763b71bd98cbc438a7a28bd36f4b06e3fd227474c886a472d3
)
for asset in \
    fastlane/metadata/android/en-US/images/featureGraphic.png \
    fastlane/metadata/android/en-US/images/icon.png \
    fastlane/metadata/android/en-US/images/phoneScreenshots/1.png \
    fastlane/metadata/android/en-US/images/phoneScreenshots/2.png; do
    asset_hash="$(shasum -a 256 "$asset" | awk '{print $1}')"
    for forbidden_hash in "${forbidden_store_asset_hashes[@]}"; do
        if [[ "$asset_hash" == "$forbidden_hash" ]]; then
            printf 'Inherited store graphic is still present: %s\n' "$asset" >&2
            failure=1
        fi
    done
done

# The screenshot index must remain an internal SecureChat notice. Rendering the inherited snapshot
# tree would expose stale upstream visual baselines that have not yet been re-recorded.
gallery_files=(
    screenshots/index.html
    screenshots/html/screenshots.css
)
forbidden_gallery_pattern='Element X Android Gallery|element-hq|https?://(www\.)?element\.io|localazy\.com/p/element|html/script\.js|screenshots_container|element-logo|fullstop--green|#0DBD8B'
if grep -Eni -- "$forbidden_gallery_pattern" "${gallery_files[@]}"; then
    printf 'Inherited branding or dynamic snapshot renderer found in the screenshot gallery.\n' >&2
    failure=1
fi

# Guard two inherited visual assets that are easy to reintroduce without creating a text match:
# the notification glyph and the public screenshot-gallery page.
forbidden_branding_asset_hashes=(
    f2a543dbd376189b8b1834e13ba62001fdd1aa2ccf7eb1a2217641d835b994ef
    61c9ea5023513506425070aabfbfa44c77e4a4c2dd3372c85202b0c71fa3162d
)
for asset in \
    libraries/designsystem/src/main/res/drawable/ic_notification.xml \
    screenshots/index.html; do
    asset_hash="$(shasum -a 256 "$asset" | awk '{print $1}')"
    for forbidden_hash in "${forbidden_branding_asset_hashes[@]}"; do
        if [[ "$asset_hash" == "$forbidden_hash" ]]; then
            printf 'Inherited branding asset is still present: %s\n' "$asset" >&2
            failure=1
        fi
    done
done

# Detect deleted upstream logos even if they are restored under a different path or filename.
# LFS pointer OIDs and materialized files are both supported so this runs identically locally and
# in CI after checkout.
media_hash() {
    local asset="$1"
    local oid
    oid="$(LC_ALL=C sed -n 's/^oid sha256://p' "$asset" | head -n 1)"
    if [[ -n "$oid" ]]; then
        printf '%s' "$oid"
    else
        shasum -a 256 "$asset" | awk '{print $1}'
    fi
}

forbidden_inherited_media_hashes=(
    5e54e124926d2e9f3f71a3823dcf219db9724799cd04c6f691c24c9a8c48d8d2
    29ce5ed4cffdb722a1e271165e5874353d852f3aa966db87617dd20e36c133b0
    395546bc85dfdd1e3f72b159a9fdebeb8601b0b08300c2a8a735c746c08f9fd5
    b0ac695779bf6f577f207ac395a81bc00d07624a595df198243d987e8a1bcf4d
    fe18ed4915080ff97f7484345c3dc9e19611d27160cf42dc3235186680a7bc56
)
while IFS= read -r asset; do
    asset_digest="$(media_hash "$asset")"
    for forbidden_hash in "${forbidden_inherited_media_hashes[@]}"; do
        if [[ "$asset_digest" == "$forbidden_hash" ]]; then
            printf 'Inherited logo/media asset is present: %s\n' "$asset" >&2
            failure=1
        fi
    done
    asset_name="$(basename "$asset")"
    if [[ "$asset_name" =~ [Ee]lement|[Vv]ector|[Rr]iot ]]; then
        printf 'Inherited branding remains in a media filename: %s\n' "$asset" >&2
        failure=1
    fi
done < <(
    find app appicon features libraries services -type f -path '*/src/main/*' \
        \( -iname '*.png' -o -iname '*.webp' -o -iname '*.jpg' -o -iname '*.jpeg' \
        -o -iname '*.svg' -o -iname '*.gif' -o -iname '*.mp3' -o -iname '*.ogg' \) -print
    find fastlane -type f \
        \( -iname '*.png' -o -iname '*.webp' -o -iname '*.jpg' -o -iname '*.jpeg' \
        -o -iname '*.svg' -o -iname '*.gif' \) -print
)

require_literal screenshots/index.html '<title>SecureChat Android Visual Baselines</title>'
require_literal screenshots/index.html '<h1 id="notice-title">Visual baselines are internal</h1>'
require_literal libraries/designsystem/src/main/res/drawable/ic_notification.xml \
    'android:fillType="evenOdd"'

# Audit the resource names that inherited hard-coded product/server branding in every locale.
# The check is intentionally scoped by resource name: words such as Romanian "element" can be
# ordinary translated nouns and are not evidence of product branding by themselves.
python3 - <<'PY' || failure=1
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

audited_names = {
    "element_call",
    "screen_incoming_call_subtitle_android",
    "call_invalid_audio_device_bluetooth_devices_disabled",
    "screen_advanced_settings_element_call_base_url",
    "screen_advanced_settings_element_call_base_url_description",
    "screen_notification_settings_sound_element_default",
    "screen_notification_settings_sound_element_fade",
    "screen_room_timeline_legacy_call",
    "screen_change_server_error_element_pro_required_title",
    "screen_change_server_error_element_pro_required_message",
    "screen_missing_key_backup_open_element_classic",
    "screen_missing_key_backup_step_1",
    "screen_onboarding_welcome_title",
    "screen_server_confirmation_message_login_element_dot_io",
    "screen_change_account_provider_matrix_org_subtitle",
    "screen_change_server_textfield_footer_register",
    "screen_start_chat_join_room_by_address_supporting_text",
}
securechat_names = {
    "element_call",
    "screen_notification_settings_sound_element_default",
    "screen_notification_settings_sound_element_fade",
    "screen_room_timeline_legacy_call",
    "screen_change_server_error_element_pro_required_message",
    "screen_onboarding_welcome_title",
}
securechat_homeserver_names = {
    "screen_change_account_provider_matrix_org_subtitle",
    "screen_change_server_textfield_footer_register",
    "screen_start_chat_join_room_by_address_supporting_text",
}
definitive_upstream_brand = re.compile(
    r"\belement\s+(?:x|pro|classic|call|android|default|fade)\b|"
    r"element\.(?:io|dev)|vector\.im|riot\.im|matrix\.org",
    re.IGNORECASE,
)

# Qualified source values catch regressions even on a clean checkout. Generated localazy.xml is
# deliberately excluded; the app and locale-qualified translation files override those values.
resource_files = {
    Path("app/src/main/res/values/securechat_strings.xml"),
    Path("tests/uitests/src/main/res/values/securechat_strings.xml"),
    *(path for path in Path(".").glob("**/src/main/res/values*/*.xml") if path.name != "localazy.xml"),
}
# When a resource merge has run, inspect every merged locale as the final authority too.
resource_files.update(
    Path("app/build/intermediates/incremental").glob(
        "*/merge*Resources/merged.dir/values*/values*.xml"
    )
)
resource_files.update(
    Path("tests/uitests/build/intermediates/incremental").glob(
        "*/merge*Resources/merged.dir/values*/values*.xml"
    )
)

def string_map(path):
    root = ET.parse(path).getroot()
    return {
        item.attrib["name"]: "".join(item.itertext())
        for item in root.findall("string")
        if item.attrib.get("name") in audited_names
    }

app_overrides = string_map(Path("app/src/main/res/values/securechat_strings.xml"))
ui_test_overrides = string_map(Path("tests/uitests/src/main/res/values/securechat_strings.xml"))
missing_app = audited_names - app_overrides.keys()
missing_ui_test = audited_names - ui_test_overrides.keys()
if missing_app:
    errors = [f"App branding overrides are missing: {sorted(missing_app)!r}"]
else:
    errors = []
if missing_ui_test:
    errors.append(f"UI-test branding overrides are missing: {sorted(missing_ui_test)!r}")
if app_overrides != ui_test_overrides:
    errors.append("App and UI-test branding override values are not identical")

# Treat generated English resources as discovery inputs without editing them. Any newly introduced
# upstream product/server phrase must be explicitly neutralized in both final resource graphs.
discovery_pattern = re.compile(
    r"\b(?:Element|Vector|Riot)\b|element\.(?:io|dev)|vector\.im|riot\.im|matrix\.org",
    re.IGNORECASE,
)
default_value_brand = re.compile(r"\b(?:Element|Vector|Riot)\b")
discovered_names = set()
for path in Path(".").glob("**/src/main/res/values/localazy.xml"):
    root = ET.parse(path).getroot()
    for item in root.findall("string"):
        if discovery_pattern.search("".join(item.itertext())):
            discovered_names.add(item.attrib.get("name"))
uncovered_names = discovered_names - app_overrides.keys()
if uncovered_names:
    errors.append(f"New inherited English strings need SecureChat overrides: {sorted(uncovered_names)!r}")

for path in sorted(resource_files):
    root = ET.parse(path).getroot()
    is_default_values = path.parent.name == "values"
    for item in root:
        if item.tag not in {"string", "plurals", "string-array"}:
            continue
        value = "".join(item.itertext())
        name = item.attrib.get("name", "<unnamed>")
        if definitive_upstream_brand.search(value) or (is_default_values and default_value_brand.search(value)):
            errors.append(f"{path}: {name} contains inherited branding: {value!r}")
    for item in root.findall("string"):
        name = item.attrib.get("name")
        if name not in audited_names:
            continue
        value = "".join(item.itertext())
        if definitive_upstream_brand.search(value):
            errors.append(f"{path}: {name} contains inherited branding: {value!r}")
        if name in securechat_names and "SecureChat" not in value:
            errors.append(f"{path}: {name} does not identify SecureChat: {value!r}")
        if name in securechat_homeserver_names and "chat.securechat.com.au" not in value:
            errors.append(f"{path}: {name} does not use the SecureChat homeserver: {value!r}")

if errors:
    print("Localized SecureChat branding audit failed:", file=sys.stderr)
    print(*errors, sep="\n", file=sys.stderr)
    raise SystemExit(1)
PY

# Snapshot files are stored with Git LFS. Compare either the pointer OID or a materialized file hash
# so the same guard works locally and in CI.
snapshot_hash() {
    local asset="$1"
    local oid
    oid="$(LC_ALL=C sed -n 's/^oid sha256://p' "$asset" | head -n 1)"
    if [[ -n "$oid" ]]; then
        printf '%s' "$oid"
    else
        shasum -a 256 "$asset" | awk '{print $1}'
    fi
}

forbidden_snapshot_hashes=(
    0db3553ee5ed730fda10fa78bb19f3714c7ecdf2b2b82b0946335aaede2c3c61
    901b31047da757e8f0cd391dd7bbbf9d7c20e66bb4c21497acbe1616648926ba
    d588b54e770dd21a63208bdd596f3e7f3c4074b8cbfa33affcc8750c4bf89c2d
)
snapshot_dir=tests/uitests/src/test/snapshots/images
while IFS= read -r snapshot; do
    snapshot_digest="$(snapshot_hash "$snapshot")"
    for forbidden_hash in "${forbidden_snapshot_hashes[@]}"; do
        if [[ "$snapshot_digest" == "$forbidden_hash" ]]; then
            printf 'Stale inherited visual baseline is still present: %s\n' "$snapshot" >&2
            failure=1
        fi
    done
done < <(find "$snapshot_dir" -maxdepth 1 -type f -name '*.png' -print)

if find "$snapshot_dir" -maxdepth 1 -type f -name '*_ElementLogoAtom*.png' -print -quit | grep -q .; then
    printf 'Inherited Element logo snapshot baselines are still present.\n' >&2
    failure=1
fi
securechat_logo_snapshot_count="$(find "$snapshot_dir" -maxdepth 1 -type f -name '*_SecureChatLogoAtom*.png' | wc -l | tr -d ' ')"
if [[ "$securechat_logo_snapshot_count" != 8 ]]; then
    printf 'Expected 8 SecureChat logo snapshots, found %s. Re-record screenshots.\n' \
        "$securechat_logo_snapshot_count" >&2
    failure=1
fi

require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'const val APPLICATION_ID = "com.securechat.app"'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'const val APPLICATION_NAME = "SecureChat"'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'val METADATA_HOST_REVERSED: String? = "com.securechat"'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'val OAUTH_CLIENT_URL_PATH: String? = null'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'val URL_LOGO: String? = "https://chat.securechat.com.au/securechat/favicon.svg"'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'val SERVICES_POSTHOG_HOST: String? = null'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'val SERVICES_POSTHOG_APIKEY: String? = null'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'val SERVICES_SENTRY_DSN: String? = null'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'val SERVICES_SENTRY_DSN_RUST: String? = null'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'val BUG_REPORT_URL: String? = null'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt 'const val PUSH_CONFIG_INCLUDE_FIREBASE: Boolean = false'
require_literal libraries/pushproviders/firebase/src/main/res/values/firebase.xml \
    '<string name="google_app_id" translatable="false" tools:ignore="UnusedResources">UNCONFIGURED</string>'
require_literal libraries/pushproviders/firebase/src/main/kotlin/io/element/android/libraries/pushproviders/firebase/FirebaseConfig.kt \
    'https://firebase-disabled.securechat.invalid/_matrix/push/v1/notify'
require_literal plugins/src/main/kotlin/config/BuildTimeConfig.kt \
    'val PUSHER_APP_ID_RELEASE: String? = "com.securechat.app.android"'
require_literal appconfig/src/main/kotlin/io/element/android/appconfig/AuthenticationConfig.kt \
    'const val DEFAULT_HOMESERVER_URL = "https://chat.securechat.com.au"'
require_literal libraries/mdm/api/src/main/kotlin/io/element/android/libraries/mdm/api/MdmConfig.kt \
    'const val DEFAULT_HOMESERVER_URL = "https://chat.securechat.com.au"'
require_literal appconfig/src/main/kotlin/io/element/android/appconfig/ApplicationConfig.kt \
    'const val PRODUCTION_APPLICATION_NAME: String = "SecureChat"'
require_literal appconfig/src/main/kotlin/io/element/android/appconfig/ApplicationConfig.kt \
    'const val DESKTOP_APPLICATION_NAME: String = "SecureChat"'
require_literal libraries/deeplink/impl/src/main/kotlin/io/element/android/libraries/deeplink/impl/Constants.kt \
    'internal const val SCHEME = "securechat"'
require_literal app/src/main/AndroidManifest.xml 'android:host="chat.securechat.com.au"'
require_literal app/src/main/AndroidManifest.xml 'android:path="/securechat/"'
require_literal features/login/impl/src/main/kotlin/io/element/android/features/login/impl/DefaultLoginIntentResolver.kt \
    'const val SECURECHAT_HOST = "chat.securechat.com.au"'
require_literal settings.gradle.kts 'rootProject.name = "SecureChat"'
require_literal "$call_build_file" \
    'add(secureChatCallEmbeddedAar.name, libs.element.call.embedded)'
require_literal "$call_build_file" \
    'expectedSha256.set("f2e6d530499ecd43e864899dd0f307000582251a5e73e0b6ef6033160b8a3038")'
require_literal "$call_build_file" 'dependsOn(verifySecureChatCallAssets)'
require_literal "$call_build_file" 'id="securechat-call-branding"'
require_literal "$call_build_file" 'SECURECHAT_CALL_ASSET_DIRECTORY = "securechat-call"'
require_literal "$call_build_file" 'const val SECURECHAT_DISABLED_STUN_URL = "stun:disabled.securechat.invalid"'
require_literal features/call/impl/src/main/kotlin/io/element/android/features/call/impl/utils/DefaultCallWidgetProvider.kt \
    'https://appassets.androidplatform.net/securechat-call/index.html'
require_literal .github/workflows/securechat-build.yml ':features:call:impl:verifySecureChatCallAssets'
require_literal .github/workflows/securechat-release.yml ':features:call:impl:verifySecureChatCallAssets'
require_literal features/call/impl/src/main/AndroidManifest.xml \
    'android:taskAffinity="${applicationId}.call"'
require_literal libraries/matrix/api/src/main/kotlin/io/element/android/libraries/matrix/api/auth/OAuthConfig.kt \
    'val STATIC_REGISTRATIONS: Map<String, String> = emptyMap()'
require_literal libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/analytics/RustAnalyticsSdkSpan.kt \
    'target = "securechat"'

python3 - <<'PY' || failure=1
import sys
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse("app/src/main/res/xml/app_restrictions.xml").getroot()
actual = {
    item.attrib[android + "key"]: (
        item.attrib[android + "restrictionType"],
        item.attrib[android + "defaultValue"],
    )
    for item in root.findall("restriction")
}
expected = {
    "homeserver_url": ("string", "https://chat.securechat.com.au"),
    "allow_registration": ("bool", "false"),
    "allow_file_send": ("bool", "true"),
    "auto_logout_minutes": ("integer", "0"),
}
if actual != expected:
    print(f"Managed configuration mismatch. Expected {expected!r}, got {actual!r}", file=sys.stderr)
    raise SystemExit(1)
PY

if (( failure != 0 )); then
    exit 1
fi

printf 'SecureChat runtime configuration audit passed.\n'
