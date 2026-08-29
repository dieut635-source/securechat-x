#!/usr/bin/env bash

set -euo pipefail

dump_file=${1:?Usage: check_securechat_aapt_dump.sh <aapt-badging-dump>}

grep -Eq "^package: name='com\\.securechat\\.app'([[:space:]]|$)" "$dump_file" || {
    printf 'Unexpected release application ID in %s.\n' "$dump_file" >&2
    exit 1
}

grep -Eq "^application-label:'SecureChat'$" "$dump_file" || {
    printf 'SecureChat application label is missing from %s.\n' "$dump_file" >&2
    exit 1
}

if grep -E '^application-label(-[^:]*)?:' "$dump_file" | \
    grep -Ev "^application-label(-[^:]*)?:'SecureChat'$"; then
    printf 'Unexpected localized application label found in %s.\n' "$dump_file" >&2
    exit 1
fi

grep -Eq "^application: label='SecureChat'([[:space:]]|$)" "$dump_file" || {
    printf 'SecureChat resolved application label is missing from %s.\n' "$dump_file" >&2
    exit 1
}

if grep -E '^application:' "$dump_file" | \
    grep -Ev "^application: label='SecureChat'([[:space:]]|$)"; then
    printf 'Unexpected resolved application label found in %s.\n' "$dump_file" >&2
    exit 1
fi

# Launcher labels and literal component labels are externally visible even when the application
# label itself is correct. Resource-reference labels are covered by the localized app-label checks;
# direct literals are checked in the appended manifest tree.
if grep -E '^(launchable-activity|activity-alias):' "$dump_file" | \
    grep -Ei "label='[^']*(Element|Vector|Riot)[^']*'"; then
    printf 'Inherited branding found in a launchable component label in %s.\n' "$dump_file" >&2
    exit 1
fi
if grep -Ei '^[[:space:]]*A: android:label.*="[^"]*(Element|Vector|Riot)[^"]*"' "$dump_file"; then
    printf 'Inherited branding found in a literal component label in %s.\n' "$dump_file" >&2
    exit 1
fi
if grep -Ei '^[[:space:]]*A: android:taskAffinity.*="io\.element[^"]*"' "$dump_file"; then
    printf 'Inherited runtime task affinity found in %s.\n' "$dump_file" >&2
    exit 1
fi

# Firebase is deliberately excluded from SecureChat builds. Scan both `aapt dump badging` and the
# appended AndroidManifest.xml tree so a transitive permission, provider, service, or intent action
# cannot silently re-enable inherited push infrastructure.
if grep -Eni -- \
    'com\.google\.android\.c2dm|com\.google\.firebase|firebase_messaging_|MESSAGING_EVENT|FirebaseMessagingService|io\.element\.android\.libraries\.pushproviders\.firebase' \
    "$dump_file"; then
    printf 'Firebase/FCM manifest residue found in %s.\n' "$dump_file" >&2
    exit 1
fi
