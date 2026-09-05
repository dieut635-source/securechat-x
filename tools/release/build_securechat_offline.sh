#!/usr/bin/env bash

# SPDX-License-Identifier: AGPL-3.0-only

# Build, sign, and verify the production SecureChat APKs on an isolated workstation.
# Passwords are always read from a terminal and are never accepted as command-line arguments.

set -euo pipefail
set +x
umask 077
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
cd "$REPOSITORY_ROOT"

usage() {
    cat <<'EOF'
Usage:
  tools/release/build_securechat_offline.sh \
    --keystore /absolute/offline/path/securechat-release.keystore \
    --alias securechat \
    --cert-pin-file /absolute/offline/path/securechat-release-cert.sha256 \
    --tag-signer-fingerprint-file /absolute/offline/path/release-tag-signer.fingerprint \
    [--output-dir /absolute/path]

The current commit must be clean and carry a valid signed tag matching the app version
(for example v26.08.3). The certificate pin must contain one SHA-256 fingerprint; the
tag-signer pin must contain one OpenPGP primary/signing-key fingerprint. Both files must
be independently stored outside the repository (colons and whitespace are allowed).
EOF
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

sha256_file() {
    openssl dgst -sha256 "$1" | sed -nE 's/^.*= ([0-9a-fA-F]{64})$/\1/p' | tr '[:upper:]' '[:lower:]'
}

canonical_regular_file() {
    local input_path=$1
    local input_dir
    local canonical_dir
    local base_name

    [[ -n "$input_path" ]] || fail "An empty file path was supplied."
    [[ -f "$input_path" ]] || fail "Not a regular file: $input_path"
    [[ ! -L "$input_path" ]] || fail "Symlinks are not accepted for security-sensitive files: $input_path"
    input_dir=$(dirname -- "$input_path")
    base_name=$(basename -- "$input_path")
    canonical_dir=$(CDPATH= cd -- "$input_dir" && pwd -P)
    printf '%s/%s\n' "$canonical_dir" "$base_name"
}

path_is_inside_repository() {
    case "$1" in
        "$REPOSITORY_ROOT" | "$REPOSITORY_ROOT"/*) return 0 ;;
        *) return 1 ;;
    esac
}

keystore_input=''
key_alias='securechat'
certificate_pin_input=''
tag_signer_fingerprint_input=''
output_root_input="$REPOSITORY_ROOT/release-out"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --keystore)
            [[ $# -ge 2 ]] || fail "--keystore requires a path."
            keystore_input=$2
            shift 2
            ;;
        --alias)
            [[ $# -ge 2 ]] || fail "--alias requires a value."
            key_alias=$2
            shift 2
            ;;
        --cert-pin-file)
            [[ $# -ge 2 ]] || fail "--cert-pin-file requires a path."
            certificate_pin_input=$2
            shift 2
            ;;
        --tag-signer-fingerprint-file)
            [[ $# -ge 2 ]] || fail "--tag-signer-fingerprint-file requires a path."
            tag_signer_fingerprint_input=$2
            shift 2
            ;;
        --output-dir)
            [[ $# -ge 2 ]] || fail "--output-dir requires a path."
            output_root_input=$2
            shift 2
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            fail "Unknown argument: $1"
            ;;
    esac
done

[[ -n "$keystore_input" ]] || { usage >&2; fail "--keystore is required."; }
[[ -n "$certificate_pin_input" ]] || { usage >&2; fail "--cert-pin-file is required."; }
[[ -n "$tag_signer_fingerprint_input" ]] || { usage >&2; fail "--tag-signer-fingerprint-file is required."; }
[[ "$key_alias" =~ ^[A-Za-z0-9._-]+$ ]] || fail "The signing alias contains unsupported characters."
[[ -t 0 && -t 1 ]] || fail "Production signing must run interactively on the isolated release workstation."

# Do not inherit signing state from a shell, IDE, CI runner, or password-bearing environment.
unset \
    SECURECHAT_KEYSTORE_FILE \
    SECURECHAT_KEYSTORE_PASSWORD \
    SECURECHAT_KEY_ALIAS \
    SECURECHAT_KEY_PASSWORD \
    SECURECHAT_RELEASE_CERT_SHA256 \
    SECURECHAT_RELEASE_TAG_SIGNER_FINGERPRINT \
    SECURECHAT_OFFLINE_RELEASE_MARKER_FILE

# These values enable third-party map/analytics/crash-upload services in otherwise clean source.
# A production ceremony must fail instead of silently inheriting them from the operator's shell.
forbidden_release_environment=(
    SECURECHAT_MAPTILER_API_KEY
    SECURECHAT_MAPTILER_LIGHT_MAP_ID
    SECURECHAT_MAPTILER_DARK_MAP_ID
    SECURECHAT_CALL_SENTRY_DSN
    SECURECHAT_CALL_POSTHOG_USER_ID
    SECURECHAT_CALL_POSTHOG_API_HOST
    SECURECHAT_CALL_POSTHOG_API_KEY
    SECURECHAT_CALL_RAGESHAKE_URL
)
configured_forbidden_environment=()
for variable_name in "${forbidden_release_environment[@]}"; do
    if [[ -n "${!variable_name:-}" ]]; then
        configured_forbidden_environment+=("$variable_name")
    fi
done
(( ${#configured_forbidden_environment[@]} == 0 )) ||
    fail "Third-party service environment is forbidden in production: ${configured_forbidden_environment[*]}"

require_command git
require_command openssl
require_command python3
require_command sed

# Offline mode prevents downloads but does not prove that artifacts already present in the Gradle
# cache are the reviewed bytes. A production ceremony therefore remains deliberately blocked until
# checksum metadata has been bootstrapped in a clean environment, independently reviewed, and
# committed with the signed release source.
dependency_verification_metadata=gradle/verification-metadata.xml
[[ -f "$dependency_verification_metadata" ]] ||
    fail "Missing reviewed Gradle dependency verification metadata: $dependency_verification_metadata"
grep -Fq '<sha256 value=' "$dependency_verification_metadata" ||
    fail "Gradle dependency verification metadata does not contain SHA-256 checksums."

forbidden_local_property_pattern='^[[:space:]]*(services\.maptiler\.(apikey|lightMapId|darkMapId)|features\.call\.(sentry\.dsn|posthog\.(userid|api\.host|api\.key)|regeshake\.url))([[:space:]]|[:=])'
if [[ -f local.properties ]] && grep -Eq "$forbidden_local_property_pattern" local.properties; then
    fail "Third-party map/call properties are forbidden in local.properties during a production release."
fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA="$JAVA_HOME/bin/java"
    JAR="$JAVA_HOME/bin/jar"
    JARSIGNER="$JAVA_HOME/bin/jarsigner"
    KEYTOOL="$JAVA_HOME/bin/keytool"
else
    require_command java
    require_command jar
    require_command jarsigner
    require_command keytool
    JAVA=$(command -v java)
    JAR=$(command -v jar)
    JARSIGNER=$(command -v jarsigner)
    KEYTOOL=$(command -v keytool)
fi

for java_tool in "$JAVA" "$JAR" "$JARSIGNER" "$KEYTOOL"; do
    [[ -x "$java_tool" ]] || fail "JDK tool is not executable: $java_tool"
done

java_major=$($JAVA -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')
[[ "$java_major" = '21' ]] || fail "JDK 21 is required; detected major version '${java_major:-unknown}'."

require_command git-lfs
git lfs fsck

# Materialize tracked LFS objects from the local object store only. `git lfs fsck` proves that an
# object is available and valid, but a worktree file can still be the small text pointer. Building
# that pointer as an image/test fixture causes misleading test failures or, worse, a malformed APK.
# `checkout` never downloads; a missing local object therefore remains a pointer and is rejected by
# the explicit scan below.
git lfs checkout
while IFS= read -r -d '' lfs_file; do
    [[ -f "$lfs_file" ]] || fail "Tracked Git LFS file is missing from the worktree: $lfs_file"
    if grep -Fqx 'version https://git-lfs.github.com/spec/v1' "$lfs_file"; then
        fail "Git LFS pointer was not materialized before the release build: $lfs_file"
    fi
done < <(git ls-files -z ':(attr:filter=lfs)')

working_tree_status=$(git status --porcelain=v1 --untracked-files=all)
[[ -z "$working_tree_status" ]] || fail "The production source tree is not clean. Commit/review every change before signing."

version_file=plugins/src/main/kotlin/Versions.kt
version_year=$(sed -nE 's/^private const val versionYear = ([0-9]+)$/\1/p' "$version_file")
version_month=$(sed -nE 's/^private const val versionMonth = ([0-9]+)$/\1/p' "$version_file")
version_release=$(sed -nE 's/^private const val versionReleaseNumber = ([0-9]+)$/\1/p' "$version_file")
build_tools_version=$(sed -nE 's/^[[:space:]]*private const val BUILD_TOOLS_VERSION = "([^"]+)"$/\1/p' "$version_file")
[[ -n "$version_year" && -n "$version_month" && -n "$version_release" && -n "$build_tools_version" ]] ||
    fail "Unable to read the application version and required Android Build Tools version."
printf -v padded_month '%02d' "$version_month"
version_name="$version_year.$padded_month.$version_release"
release_tag="v$version_name"

git rev-parse --verify "refs/tags/$release_tag^{tag}" >/dev/null 2>&1 ||
    fail "A signed annotated tag named $release_tag is required at HEAD."
head_commit=$(git rev-parse HEAD)
tag_commit=$(git rev-list -n 1 "$release_tag")
[[ "$head_commit" = "$tag_commit" ]] || fail "Tag $release_tag does not point to HEAD ($head_commit)."

keystore_path=$(canonical_regular_file "$keystore_input")
certificate_pin_path=$(canonical_regular_file "$certificate_pin_input")
tag_signer_fingerprint_path=$(canonical_regular_file "$tag_signer_fingerprint_input")
path_is_inside_repository "$keystore_path" && fail "The production keystore must be outside the source repository."
path_is_inside_repository "$certificate_pin_path" && fail "The trusted certificate pin must be outside the source repository."
path_is_inside_repository "$tag_signer_fingerprint_path" &&
    fail "The trusted release-tag signer fingerprint must be outside the source repository."

expected_tag_signer_fingerprint=$(tr -d '[:space:]:' < "$tag_signer_fingerprint_path" | tr '[:upper:]' '[:lower:]')
[[ "$expected_tag_signer_fingerprint" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] ||
    fail "Release-tag signer pin must contain exactly one 40- or 64-hex OpenPGP fingerprint."

# `git verify-tag` succeeding proves signature integrity, but not that the key is the independently
# approved release identity. Parse the machine-readable GnuPG status and pin either the exact signing
# key or its primary key. Multiple/malformed VALIDSIG records are rejected rather than guessed.
if ! tag_verification_raw=$(git verify-tag --raw "$release_tag" 2>&1); then
    fail "Tag $release_tag does not have a valid OpenPGP signature."
fi
validsig_records=()
while IFS= read -r status_line; do
    if [[ "$status_line" == "[GNUPG:] VALIDSIG "* ]]; then
        validsig_records+=("$status_line")
    fi
done <<< "$tag_verification_raw"
tag_verification_raw=''
[[ "${#validsig_records[@]}" -eq 1 ]] ||
    fail "Tag $release_tag must produce exactly one GnuPG VALIDSIG record; found ${#validsig_records[@]}."

IFS=' ' read -r -a validsig_fields <<< "${validsig_records[0]}"
validsig_field_count=${#validsig_fields[@]}
(( validsig_field_count == 11 || validsig_field_count == 12 )) ||
    fail "Tag $release_tag produced a malformed GnuPG VALIDSIG record."
tag_signature_fingerprint=$(printf '%s' "${validsig_fields[2]}" | tr '[:upper:]' '[:lower:]')
[[ "$tag_signature_fingerprint" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] ||
    fail "Tag $release_tag produced an invalid OpenPGP signing-key fingerprint."
tag_primary_fingerprint=$tag_signature_fingerprint
if (( validsig_field_count == 12 )); then
    tag_primary_fingerprint=$(printf '%s' "${validsig_fields[11]}" | tr '[:upper:]' '[:lower:]')
    [[ "$tag_primary_fingerprint" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] ||
        fail "Tag $release_tag produced an invalid OpenPGP primary-key fingerprint."
fi
if [[ "$expected_tag_signer_fingerprint" != "$tag_signature_fingerprint" &&
    "$expected_tag_signer_fingerprint" != "$tag_primary_fingerprint" ]]; then
    fail "Tag $release_tag signature does not match the independently pinned OpenPGP fingerprint."
fi
validsig_records=()
validsig_fields=()

if mode=$(stat -f '%Lp' "$keystore_path" 2>/dev/null); then
    :
elif mode=$(stat -c '%a' "$keystore_path" 2>/dev/null); then
    :
else
    fail "Unable to inspect keystore permissions."
fi
mode_value=$((8#$mode))
(( (mode_value & 077) == 0 )) || fail "Keystore permissions must deny all group/other access (recommended: chmod 600)."

expected_certificate_sha256=$(tr -d '[:space:]:' < "$certificate_pin_path" | tr '[:upper:]' '[:lower:]')
[[ "$expected_certificate_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "Certificate pin must contain exactly one SHA-256 fingerprint."

sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$sdk_root" && -f local.properties ]]; then
    sdk_root=$(sed -n 's/^sdk.dir=//p' local.properties | tail -n 1)
fi
[[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]] || fail "Set ANDROID_SDK_ROOT or ANDROID_HOME to a valid Android SDK."
build_tools_dir="$sdk_root/build-tools/$build_tools_version"
[[ -d "$build_tools_dir" ]] || fail "Android Build Tools $build_tools_version is required by Versions.kt."
AAPT="$build_tools_dir/aapt"
APKSIGNER="$build_tools_dir/apksigner"
ZIPALIGN="$build_tools_dir/zipalign"
for android_tool in "$AAPT" "$APKSIGNER" "$ZIPALIGN"; do
    [[ -x "$android_tool" ]] || fail "Android build tool is not executable: $android_tool"
done

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/securechat-release.XXXXXX")
staging_dir=''
output_root=''
apk_build_dir="$REPOSITORY_ROOT/app/build/outputs/apk/fdroid/release"
cleanup_apk_build_outputs=false
store_password=''
key_password=''
release_marker="$temp_dir/offline-release.marker"
git_revision=$(git rev-parse --short=8 HEAD)
{
    printf 'securechat-offline-release-v1\n'
    printf 'gitRevision=%s\n' "$git_revision"
    printf 'certificateSha256=%s\n' "$expected_certificate_sha256"
} > "$release_marker"

cleanup_raw_apks() {
    if [[ -d "$apk_build_dir" ]]; then
        find "$apk_build_dir" -maxdepth 1 -type f -name '*.apk' -delete
    fi
}

cleanup() {
    local exit_status=$?
    trap - EXIT HUP INT TERM
    set +e
    store_password=''
    key_password=''
    tag_verification_raw=''
    if [[ "$cleanup_apk_build_outputs" = true ]]; then
        cleanup_raw_apks >/dev/null 2>&1 || true
    fi
    if [[ -n "${staging_dir:-}" && -n "${output_root:-}" && -d "$staging_dir" ]]; then
        case "$staging_dir" in
            "$output_root"/.securechat-release-staging.*) rm -rf -- "$staging_dir" ;;
        esac
    fi
    if [[ -n "${temp_dir:-}" && -d "$temp_dir" ]]; then
        rm -rf -- "$temp_dir"
    fi
    exit "$exit_status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# Bộ nhớ cho bản phát hành, tách khỏi bộ nhớ dùng hằng ngày.
#
# gradle.properties để -Xmx8g, đủ cho bản debug. Bản release thì KHÔNG: R8 và
# lintVital chạy ngay trong tiến trình Gradle và cùng chết vì
# `OutOfMemoryError: Java heap space`. Đo ngày 05/09/2026 trên Mac 16 GB:
#
#     -Xmx8g   -> minifyFdroidReleaseWithR8 HỎNG (OOM), lintVitalAnalyzeRelease HỎNG (OOM)
#     -Xmx11g  -> cả hai QUA, chạy tới bước ký
#
# Không nâng trong gradle.properties vì con số đó áp cho mọi lần build thường
# ngày; 11 GB trên máy 16 GB sẽ đẩy máy vào swap. Ở đây thì an toàn: script này
# chạy --no-daemon --no-parallel, tức chỉ một tiến trình JVM duy nhất.
#
# Đây là chỗ đáng để lộ ra ngoài: một máy build khác có thể cần con số khác.
release_heap="${SECURECHAT_RELEASE_HEAP:-11g}"
release_jvmargs="-Xmx${release_heap} -Dfile.encoding=UTF-8 -XX:+UseG1GC"

# Từ chối sớm nếu máy không đủ RAM, thay vì để người dùng chờ tám phút rồi mới
# thấy OOM ở gần cuối. Chỉ kiểm trên macOS và Linux; nơi khác thì bỏ qua.
total_ram_gb=""
if command -v sysctl >/dev/null 2>&1 && sysctl -n hw.memsize >/dev/null 2>&1; then
    total_ram_gb=$(( $(sysctl -n hw.memsize) / 1073741824 ))
elif [[ -r /proc/meminfo ]]; then
    total_ram_gb=$(( $(awk '/MemTotal/{print $2}' /proc/meminfo) / 1048576 ))
fi
if [[ -n "$total_ram_gb" ]]; then
    heap_gb="${release_heap%g}"
    if [[ "$heap_gb" =~ ^[0-9]+$ ]] && (( total_ram_gb < heap_gb + 3 )); then
        fail "Máy chỉ có ${total_ram_gb} GB RAM, không đủ cho heap ${release_heap} cộng phần cho hệ điều hành.
Đặt SECURECHAT_RELEASE_HEAP thấp hơn rồi chạy lại — nhưng biết trước là dưới 11g thì R8 đã từng OOM."
    fi
fi

printf 'Running offline production gates for SecureChat %s (%s)...\n' "$version_name" "$head_commit"
bash tools/check/check_securechat_configuration.sh
./gradlew \
    -Dorg.gradle.jvmargs="$release_jvmargs" \
    test \
    verifyPaparazziDebug \
    detekt \
    ktlintCheck \
    :app:lintFdroidRelease \
    :features:call:impl:verifySecureChatCallAssets \
    --offline \
    --no-daemon \
    --no-configuration-cache \
    -PallWarningsAsErrors=true

# Dependency-Check aggregates configurations from every project. Gradle 9 must
# configure and resolve that cross-project graph serially.
./gradlew \
    -Dorg.gradle.jvmargs="$release_jvmargs" \
    :dependencyCheckAggregate \
    --offline \
    --no-daemon \
    --no-configuration-cache \
    --no-parallel \
    --no-configure-on-demand

printf 'Keystore password: ' >&2
IFS= read -r -s store_password
printf '\nPrivate-key password: ' >&2
IFS= read -r -s key_password
printf '\n' >&2
[[ -n "$store_password" && -n "$key_password" ]] || fail "Signing passwords cannot be empty."

keystore_details="$temp_dir/keystore-details.txt"
SECURECHAT_KEYSTORE_PASSWORD="$store_password" \
    "$KEYTOOL" -list -v \
    -keystore "$keystore_path" \
    -alias "$key_alias" \
    -storepass:env SECURECHAT_KEYSTORE_PASSWORD > "$keystore_details"
grep -Fq 'Entry type: PrivateKeyEntry' "$keystore_details" || fail "The selected alias is not a private-key entry."

certificate_der="$temp_dir/release-certificate.der"
SECURECHAT_KEYSTORE_PASSWORD="$store_password" \
    "$KEYTOOL" -exportcert \
    -keystore "$keystore_path" \
    -alias "$key_alias" \
    -storepass:env SECURECHAT_KEYSTORE_PASSWORD \
    -file "$certificate_der" >/dev/null

actual_certificate_sha256=$(openssl dgst -sha256 "$certificate_der" | sed -nE 's/^.*= ([0-9a-fA-F]{64})$/\1/p' | tr '[:upper:]' '[:lower:]')
[[ "$actual_certificate_sha256" = "$expected_certificate_sha256" ]] || fail "Keystore certificate does not match the independently stored certificate pin."

certificate_subject=$(openssl x509 -inform DER -in "$certificate_der" -noout -subject)
printf '%s' "$certificate_subject" | grep -qi 'CN[[:space:]]*=[[:space:]]*Android Debug' && fail "The Android debug certificate is forbidden."
openssl x509 -inform DER -in "$certificate_der" -checkend 31536000 -noout >/dev/null ||
    fail "The release certificate expires in less than one year. Rotate it before release."

certificate_text="$temp_dir/release-certificate.txt"
openssl x509 -inform DER -in "$certificate_der" -noout -text > "$certificate_text"
grep -Fq 'Public Key Algorithm: rsaEncryption' "$certificate_text" || fail "Production signing currently requires an RSA certificate."
rsa_bits=$(sed -nE 's/.*Public-Key: \(([0-9]+) bit\).*/\1/p' "$certificate_text" | head -n 1)
[[ -n "$rsa_bits" && "$rsa_bits" -ge 3072 ]] || fail "Production RSA key must be at least 3072 bits."
grep -Eqi 'Signature Algorithm: (md5|sha1)' "$certificate_text" && fail "Weak MD5/SHA-1 certificate signature is forbidden."

cleanup_apk_build_outputs=true
cleanup_raw_apks

printf 'Building the signed F-Droid release without a persistent Gradle daemon...\n'
SECURECHAT_KEYSTORE_FILE="$keystore_path" \
SECURECHAT_KEYSTORE_PASSWORD="$store_password" \
SECURECHAT_KEY_ALIAS="$key_alias" \
SECURECHAT_KEY_PASSWORD="$key_password" \
SECURECHAT_RELEASE_CERT_SHA256="$expected_certificate_sha256" \
SECURECHAT_OFFLINE_RELEASE_MARKER_FILE="$release_marker" \
    ./gradlew :app:assembleFdroidRelease \
    -Dorg.gradle.jvmargs="$release_jvmargs" \
    --offline \
    --no-daemon \
    --no-configuration-cache \
    -PallWarningsAsErrors=true

apk_list="$temp_dir/apks.txt"
find "$apk_build_dir" -maxdepth 1 -type f -name '*.apk' -print | LC_ALL=C sort > "$apk_list"
apk_count=$(wc -l < "$apk_list" | tr -d '[:space:]')
[[ "$apk_count" -gt 0 ]] || fail "Gradle completed without producing a release APK."

short_commit=$(printf '%s' "$head_commit" | cut -c1-12)
if [[ "$output_root_input" = /* ]]; then
    output_root=$output_root_input
else
    output_root="$REPOSITORY_ROOT/$output_root_input"
fi
mkdir -p -- "$output_root"
output_root=$(CDPATH= cd -- "$output_root" && pwd -P)
output_dir="$output_root/SecureChat-$version_name-$short_commit"
[[ ! -e "$output_dir" ]] || fail "Output directory already exists; refusing to overwrite: $output_dir"
staging_dir=$(mktemp -d "$output_root/.securechat-release-staging.XXXXXX")
chmod 0700 "$staging_dir"

artifact_tsv="$temp_dir/artifacts.tsv"
: > "$artifact_tsv"

while IFS= read -r apk; do
    [[ -f "$apk" ]] || fail "Expected APK disappeared: $apk"
    apk_base=$(basename -- "$apk")
    variant_name=${apk_base#app-fdroid-}
    variant_name=${variant_name%-release.apk}
    distribution_name="SecureChat-$version_name-$variant_name.apk"
    distribution_apk="$staging_dir/$distribution_name"

    signature_report="$temp_dir/$apk_base.apksigner.txt"
    "$APKSIGNER" verify --verbose --print-certs "$apk" > "$signature_report"
    grep -Fq 'Verified using v1 scheme (JAR signing): false' "$signature_report" || fail "$apk_base unexpectedly contains a legacy v1 signature."
    grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' "$signature_report" || fail "$apk_base is missing APK Signature Scheme v2."
    grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' "$signature_report" || fail "$apk_base is missing APK Signature Scheme v3."
    grep -qi 'CN=Android Debug' "$signature_report" && fail "$apk_base is signed with the Android debug key."

    signer_count=$(grep -c '^Signer #[0-9][0-9]* certificate SHA-256 digest: ' "$signature_report" || true)
    [[ "$signer_count" -eq 1 ]] || fail "$apk_base must have exactly one current signer; found $signer_count."
    apk_certificate_sha256=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$signature_report" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')
    [[ "$apk_certificate_sha256" = "$expected_certificate_sha256" ]] || fail "$apk_base signer does not match the trusted certificate pin."

    "$ZIPALIGN" -c -P 16 -v 4 "$apk" >/dev/null

    aapt_dump="$temp_dir/$apk_base.aapt.txt"
    ANDROID_HOME="$sdk_root" bash tools/manifest/aaptDump.sh "$apk" "$aapt_dump"

    apk_version_name=$(sed -nE "s/^package: .*versionName='([^']+)'.*/\\1/p" "$aapt_dump" | head -n 1)
    apk_version_code=$(sed -nE "s/^package: .*versionCode='([^']+)'.*/\\1/p" "$aapt_dump" | head -n 1)
    [[ "$apk_version_name" = "$version_name" ]] || fail "$apk_base versionName is $apk_version_name, expected $version_name."

    install -m 0644 "$apk" "$distribution_apk"
    apk_sha256=$(sha256_file "$distribution_apk")
    apk_size=$(wc -c < "$distribution_apk" | tr -d '[:space:]')
    printf '%s\t%s\t%s\t%s\t%s\n' "$distribution_name" "$apk_sha256" "$apk_size" "$apk_version_code" "$variant_name" >> "$artifact_tsv"
done < "$apk_list"

while IFS= read -r artifact_line; do
    artifact_name=$(printf '%s' "$artifact_line" | cut -f 1)
    artifact_sha256=$(printf '%s' "$artifact_line" | cut -f 2)
    printf '%s  %s\n' "$artifact_sha256" "$artifact_name"
done < "$artifact_tsv" > "$staging_dir/SHA256SUMS"

tree_hash=$(git rev-parse 'HEAD^{tree}')
wrapper_sha256=$(sha256_file gradle/wrapper/gradle-wrapper.jar)
build_timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
provenance_path="$staging_dir/release-provenance.json"

SC_PROVENANCE_OUTPUT="$provenance_path" \
SC_ARTIFACT_TSV="$artifact_tsv" \
SC_VERSION_NAME="$version_name" \
SC_RELEASE_TAG="$release_tag" \
SC_TAG_PINNED_FINGERPRINT="$expected_tag_signer_fingerprint" \
SC_TAG_SIGNATURE_FINGERPRINT="$tag_signature_fingerprint" \
SC_TAG_PRIMARY_FINGERPRINT="$tag_primary_fingerprint" \
SC_SOURCE_COMMIT="$head_commit" \
SC_SOURCE_TREE="$tree_hash" \
SC_BUILD_TIMESTAMP="$build_timestamp" \
SC_CERTIFICATE_SHA256="$expected_certificate_sha256" \
SC_GRADLE_WRAPPER_SHA256="$wrapper_sha256" \
SC_ANDROID_BUILD_TOOLS_VERSION="$build_tools_version" \
SC_BUILD_OS="$(uname -s)" \
SC_BUILD_ARCH="$(uname -m)" \
    python3 - <<'PY'
import csv
import json
import os
from pathlib import Path

artifacts = []
with Path(os.environ["SC_ARTIFACT_TSV"]).open(encoding="utf-8", newline="") as stream:
    for file_name, sha256, size_bytes, version_code, abi_variant in csv.reader(stream, delimiter="\t"):
        artifacts.append(
            {
                "file": file_name,
                "sha256": sha256,
                "sizeBytes": int(size_bytes),
                "versionCode": int(version_code),
                "abiVariant": abi_variant,
            }
        )

provenance = {
    "schemaVersion": 1,
    "product": "SecureChat",
    "applicationId": "com.securechat.app",
    "versionName": os.environ["SC_VERSION_NAME"],
    "releaseTag": os.environ["SC_RELEASE_TAG"],
    "source": {
        "repository": "https://github.com/dieut635-source/securechat-x",
        "commit": os.environ["SC_SOURCE_COMMIT"],
        "tree": os.environ["SC_SOURCE_TREE"],
        "workingTreeClean": True,
        "signedTagVerified": True,
        "tagSignature": {
            "pinnedFingerprint": os.environ["SC_TAG_PINNED_FINGERPRINT"],
            "signerFingerprint": os.environ["SC_TAG_SIGNATURE_FINGERPRINT"],
            "primaryFingerprint": os.environ["SC_TAG_PRIMARY_FINGERPRINT"],
        },
    },
    "build": {
        "timestampUtc": os.environ["SC_BUILD_TIMESTAMP"],
        "environment": "isolated-offline-workstation",
        "operatingSystem": os.environ["SC_BUILD_OS"],
        "architecture": os.environ["SC_BUILD_ARCH"],
        "jdkMajor": 21,
        "androidBuildToolsVersion": os.environ["SC_ANDROID_BUILD_TOOLS_VERSION"],
        "gradleOffline": True,
        "gradleWrapperSha256": os.environ["SC_GRADLE_WRAPPER_SHA256"],
    },
    "signing": {
        "certificateSha256": os.environ["SC_CERTIFICATE_SHA256"],
        "v1Enabled": False,
        "v2Enabled": True,
        "v3Enabled": True,
    },
    "artifacts": artifacts,
}

Path(os.environ["SC_PROVENANCE_OUTPUT"]).write_text(
    json.dumps(provenance, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY

chmod 0644 "$staging_dir/SHA256SUMS" "$provenance_path"
metadata_jar_name="SecureChat-$version_name-release-metadata.jar"
metadata_jar="$staging_dir/$metadata_jar_name"
(CDPATH= cd -- "$staging_dir" && "$JAR" --create --file "$metadata_jar_name" SHA256SUMS release-provenance.json)

SECURECHAT_KEYSTORE_PASSWORD="$store_password" \
SECURECHAT_KEY_PASSWORD="$key_password" \
    "$JARSIGNER" \
    -keystore "$keystore_path" \
    -storepass:env SECURECHAT_KEYSTORE_PASSWORD \
    -keypass:env SECURECHAT_KEY_PASSWORD \
    -digestalg SHA-256 \
    -sigalg SHA384withRSA \
    "$metadata_jar" "$key_alias" >/dev/null
SECURECHAT_KEYSTORE_PASSWORD="$store_password" \
    "$JARSIGNER" -verify -strict \
    -keystore "$keystore_path" \
    -storepass:env SECURECHAT_KEYSTORE_PASSWORD \
    "$metadata_jar" >/dev/null
"$JAR" --list --file "$metadata_jar" | grep -Eq '^META-INF/[^/]+\.(RSA|EC|DSA)$' || fail "Metadata JAR does not contain a signing block."

metadata_certificates="$temp_dir/metadata-certificates.pem"
metadata_certificate="$temp_dir/metadata-signer.pem"
"$KEYTOOL" -printcert -jarfile "$metadata_jar" -rfc > "$metadata_certificates"
sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' "$metadata_certificates" | sed -n '1,/-----END CERTIFICATE-----/p' > "$metadata_certificate"
metadata_certificate_sha256=$(openssl x509 -in "$metadata_certificate" -outform DER | openssl dgst -sha256 | sed -nE 's/^.*= ([0-9a-fA-F]{64})$/\1/p' | tr '[:upper:]' '[:lower:]')
[[ "$metadata_certificate_sha256" = "$expected_certificate_sha256" ]] || fail "Signed metadata JAR does not use the trusted release certificate."

metadata_jar_sha256=$(sha256_file "$metadata_jar")
printf '%s  %s\n' "$metadata_jar_sha256" "$metadata_jar_name" > "$staging_dir/METADATA-JAR-SHA256"
chmod 0644 "$metadata_jar" "$staging_dir/METADATA-JAR-SHA256"

# Re-read the complete staged tree after signing. Publication is forbidden unless it contains only
# the expected regular files and every loose checksum/provenance identity still matches its bytes.
SC_STAGE_DIR="$staging_dir" \
SC_ARTIFACT_TSV="$artifact_tsv" \
SC_METADATA_JAR_NAME="$metadata_jar_name" \
SC_TAG_PINNED_FINGERPRINT="$expected_tag_signer_fingerprint" \
SC_TAG_SIGNATURE_FINGERPRINT="$tag_signature_fingerprint" \
SC_TAG_PRIMARY_FINGERPRINT="$tag_primary_fingerprint" \
    python3 - <<'PY'
import csv
import hashlib
import json
import os
from pathlib import Path

stage = Path(os.environ["SC_STAGE_DIR"])
metadata_jar_name = os.environ["SC_METADATA_JAR_NAME"]
with Path(os.environ["SC_ARTIFACT_TSV"]).open(encoding="utf-8", newline="") as stream:
    artifacts = list(csv.reader(stream, delimiter="\t"))

expected_names = [row[0] for row in artifacts] + [
    "SHA256SUMS",
    "release-provenance.json",
    metadata_jar_name,
    "METADATA-JAR-SHA256",
]
entries = list(stage.iterdir())
if any(entry.is_symlink() or not entry.is_file() for entry in entries):
    raise SystemExit("Staged release contains a symlink, directory, or non-regular file.")
actual_names = [entry.name for entry in entries]
if len(actual_names) != len(expected_names) or sorted(actual_names) != sorted(expected_names):
    raise SystemExit(
        f"Staged release file set mismatch: expected {sorted(expected_names)!r}, got {sorted(actual_names)!r}"
    )

checksum_lines = []
for file_name, expected_sha256, size_bytes, _version_code, _abi_variant in artifacts:
    artifact = stage / file_name
    actual_sha256 = hashlib.sha256(artifact.read_bytes()).hexdigest()
    if actual_sha256 != expected_sha256 or artifact.stat().st_size != int(size_bytes):
        raise SystemExit(f"Staged APK changed after verification: {file_name}")
    checksum_lines.append(f"{expected_sha256}  {file_name}\n")
if (stage / "SHA256SUMS").read_text(encoding="utf-8") != "".join(checksum_lines):
    raise SystemExit("Staged SHA256SUMS does not exactly describe the verified APKs.")

metadata_sha256 = hashlib.sha256((stage / metadata_jar_name).read_bytes()).hexdigest()
expected_metadata_line = f"{metadata_sha256}  {metadata_jar_name}\n"
if (stage / "METADATA-JAR-SHA256").read_text(encoding="utf-8") != expected_metadata_line:
    raise SystemExit("Staged metadata JAR checksum does not match the signed JAR.")

provenance = json.loads((stage / "release-provenance.json").read_text(encoding="utf-8"))
expected_tag_signature = {
    "pinnedFingerprint": os.environ["SC_TAG_PINNED_FINGERPRINT"],
    "signerFingerprint": os.environ["SC_TAG_SIGNATURE_FINGERPRINT"],
    "primaryFingerprint": os.environ["SC_TAG_PRIMARY_FINGERPRINT"],
}
if provenance.get("source", {}).get("tagSignature") != expected_tag_signature:
    raise SystemExit("Staged provenance does not contain the verified release-tag signer identity.")
PY

final_tree_status=$(git status --porcelain=v1 --untracked-files=all)
[[ -z "$final_tree_status" ]] || fail "The build changed the source tree; release output is not trusted."

# Raw Gradle APK outputs are not approved distribution artifacts. Remove and prove them absent before
# making the fully verified staging directory visible under its final name; the EXIT trap repeats
# this cleanup on every success, failure, or handled signal after release assembly begins.
cleanup_raw_apks
remaining_raw_apk=$(find "$apk_build_dir" -maxdepth 1 -type f -name '*.apk' -print -quit 2>/dev/null || true)
[[ -z "$remaining_raw_apk" ]] || fail "Could not remove raw signed APK build output: $remaining_raw_apk"

store_password=''
key_password=''

[[ ! -e "$output_dir" && ! -L "$output_dir" ]] || fail "Output path appeared during verification; refusing to overwrite: $output_dir"
mv -- "$staging_dir" "$output_dir"
staging_dir=''

cat <<EOF

SecureChat offline release completed successfully.
Output: $output_dir
Version: $version_name
Commit: $head_commit
Release-tag OpenPGP fingerprint: $expected_tag_signer_fingerprint
Certificate SHA-256: $expected_certificate_sha256
APK count: $apk_count

Before installation, independently verify the signed metadata bundle and APK certificate
as documented in docs/install_from_github_release.md.
EOF
