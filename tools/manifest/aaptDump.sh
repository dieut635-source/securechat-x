#!/usr/bin/env bash

# Copyright (c) 2026 SecureChat
#
# SPDX-License-Identifier: AGPL-3.0-only
# Please see LICENSE files in the repository root for full details.

set -euo pipefail

apk=${1:?Usage: tools/manifest/aaptDump.sh <production-apk> [output-file]}
output_file=${2:-}
[[ -f "$apk" ]] || {
    printf 'APK does not exist: %s\n' "$apk" >&2
    exit 1
}

sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
[[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]] || {
    printf 'Set ANDROID_SDK_ROOT or ANDROID_HOME to a valid Android SDK.\n' >&2
    exit 1
}

# Use the Build Tools version pinned by the source rather than whichever version happens to be
# newest on the workstation. This helper validates an already-built APK and never builds or signs.
build_tools_version=$(sed -nE \
    's/^[[:space:]]*private const val BUILD_TOOLS_VERSION = "([^"]+)"$/\1/p' \
    plugins/src/main/kotlin/Versions.kt)
[[ -n "$build_tools_version" ]] || {
    printf 'Unable to read BUILD_TOOLS_VERSION from Versions.kt.\n' >&2
    exit 1
}
aapt="$sdk_root/build-tools/$build_tools_version/aapt"
[[ -x "$aapt" ]] || {
    printf 'Required aapt is not executable: %s\n' "$aapt" >&2
    exit 1
}

temporary_directory=$(mktemp -d "${TMPDIR:-/tmp}/securechat-aapt.XXXXXX")
temporary_dump="$temporary_directory/aapt-dump.txt"
temporary_resources="$temporary_directory/resources.txt"
temporary_xml_resources="$temporary_directory/xml-resources.txt"
temporary_archive="$temporary_directory/archive.txt"
temporary_packaged_xml="$temporary_directory/packaged-xml.txt"
trap 'rm -rf -- "$temporary_directory"' EXIT HUP INT TERM
"$aapt" dump badging "$apk" > "$temporary_dump"
printf '\nSECURECHAT_AAPT_RESOURCE_BEGIN %s\n' AndroidManifest.xml >> "$temporary_dump"
"$aapt" dump xmltree "$apk" AndroidManifest.xml >> "$temporary_dump"
printf 'SECURECHAT_AAPT_RESOURCE_END %s\n' AndroidManifest.xml >> "$temporary_dump"

# Keep the compiled XML resource-ID bindings in the evidence file. `--values` is required here:
# the ordinary resource dump exposes symbolic names but not the backing file paths, and XML aliases
# or configuration-specific values would otherwise let the audit inspect an unrelated safe file.
"$aapt" dump --values resources "$apk" > "$temporary_resources"
LC_ALL=C awk '
    /^[[:space:]]*spec resource 0x[0-9a-fA-F]+ [^:[:space:]]+:xml\// {
        print
        next
    }
    /^[[:space:]]*resource 0x[0-9a-fA-F]+ [^:[:space:]]+:xml\// {
        print
        if ($0 ~ / t=0x03 /) {
            if (getline <= 0) {
                exit 2
            }
            print
        }
    }
' "$temporary_resources" > "$temporary_xml_resources"
[[ -s "$temporary_xml_resources" ]] || {
    printf 'Compiled XML resource table is empty in APK: %s\n' "$apk" >&2
    exit 1
}
printf '\nSECURECHAT_AAPT_XML_RESOURCE_TABLE_BEGIN\n' >> "$temporary_dump"
sed -n 'p' "$temporary_xml_resources" >> "$temporary_dump"
printf 'SECURECHAT_AAPT_XML_RESOURCE_TABLE_END\n' >> "$temporary_dump"

# Dump every file backing a compiled `xml` resource, including aliases' targets and qualified
# variants. Release resource optimization may shorten a path (for example, to `res/a.xml`), so the
# resource table's exact file value is authoritative rather than its symbolic resource name.
"$aapt" list "$apk" > "$temporary_archive"
LC_ALL=C sed -nE 's#^[[:space:]]*\(string(8|16)\) "([^\"]+)"$#\2#p' "$temporary_xml_resources" | \
    LC_ALL=C sort -u > "$temporary_packaged_xml"
[[ -s "$temporary_packaged_xml" ]] || {
    printf 'APK contains no packaged XML resources: %s\n' "$apk" >&2
    exit 1
}
while IFS= read -r packaged_xml; do
    archive_match_count=$(LC_ALL=C awk -v path="$packaged_xml" \
        '$0 == path { count++ } END { print count + 0 }' "$temporary_archive")
    [[ "$archive_match_count" == 1 ]] || {
        printf 'Compiled XML path must occur exactly once in APK (found %s): %s\n' \
            "$archive_match_count" "$packaged_xml" >&2
        exit 1
    }
done < "$temporary_packaged_xml"
printf '\nSECURECHAT_AAPT_PACKAGED_XML_BEGIN\n' >> "$temporary_dump"
sed -n 'p' "$temporary_packaged_xml" >> "$temporary_dump"
printf 'SECURECHAT_AAPT_PACKAGED_XML_END\n' >> "$temporary_dump"

while IFS= read -r packaged_xml; do
    printf '\nSECURECHAT_AAPT_RESOURCE_BEGIN %s\n' "$packaged_xml" >> "$temporary_dump"
    "$aapt" dump xmltree "$apk" "$packaged_xml" >> "$temporary_dump"
    printf 'SECURECHAT_AAPT_RESOURCE_END %s\n' "$packaged_xml" >> "$temporary_dump"
done < "$temporary_packaged_xml"
bash tools/manifest/check_securechat_aapt_dump.sh "$temporary_dump"

if [[ -n "$output_file" ]]; then
    install -m 0644 "$temporary_dump" "$output_file"
    printf 'Validated manifest dump written to %s\n' "$output_file"
else
    printf 'SecureChat production APK manifest validation passed.\n'
fi
