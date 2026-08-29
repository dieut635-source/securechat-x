#! /bin/bash

# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

set -euo pipefail

# Assemble Gplay Release
./gradlew assembleGplayRelease

# Dump resolved identity plus the complete manifest component tree using the newest installed
# Android Build Tools version. Compile SDK and Build Tools versions do not always share a number.
BUILD_TOOLS_VERSION="$(ls "$ANDROID_HOME"/build-tools/ | sort -V | tail -1)"
AAPT="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt"
APK=./app/build/outputs/apk/gplay/release/app-gplay-universal-release.apk
DUMP=./tools/manifest/gplay/release/aaptDump.txt
"$AAPT" dump badging "$APK" > "$DUMP"
"$AAPT" dump xmltree "$APK" AndroidManifest.xml >> "$DUMP"

bash ./tools/manifest/check_securechat_aapt_dump.sh ./tools/manifest/gplay/release/aaptDump.txt
