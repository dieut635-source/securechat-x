#!/usr/bin/env bash
# Read-only probes from a separately administered SSH vantage host.
set -Eeuo pipefail
umask 077

PHASE="${1:-}"
SERVER_NAME="${2:-}"
SSH_TARGET="${EXTERNAL_PROBE_SSH_TARGET:-}"
KNOWN_HOSTS="${EXTERNAL_PROBE_KNOWN_HOSTS:-}"
EXPECTED_HOSTNAME="${EXTERNAL_PROBE_EXPECTED_HOSTNAME:-}"

die() { printf '[LỖI] External-vantage: %s\n' "$*" >&2; exit 2; }

[[ "$PHASE" == "web-neutral" || "$PHASE" == "auth-closed" ]] \
    || die "phase phải là web-neutral hoặc auth-closed."
[[ "$SERVER_NAME" =~ ^[A-Za-z0-9.-]+$ && "$SERVER_NAME" == *.* ]] \
    || die "SERVER_NAME không hợp lệ."
[[ "$SSH_TARGET" =~ ^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$ ]] \
    || die "cần EXTERNAL_PROBE_SSH_TARGET=user@host cố định."
[[ "$EXPECTED_HOSTNAME" =~ ^[A-Za-z0-9._-]+$ ]] \
    || die "cần EXTERNAL_PROBE_EXPECTED_HOSTNAME cố định."
[[ -f "$KNOWN_HOSTS" && ! -L "$KNOWN_HOSTS" && -s "$KNOWN_HOSTS" ]] \
    || die "EXTERNAL_PROBE_KNOWN_HOSTS phải là file thật, không rỗng."
command -v ssh >/dev/null 2>&1 || die "không có ssh client."

local_hostname="$(hostname -f 2>/dev/null || hostname)"
[[ "$EXPECTED_HOSTNAME" != "$local_hostname" ]] \
    || die "vantage hostname trùng server; cần máy/mạng độc lập."

ssh \
    -o BatchMode=yes \
    -o ConnectTimeout=10 \
    -o ConnectionAttempts=1 \
    -o StrictHostKeyChecking=yes \
    -o "UserKnownHostsFile=$KNOWN_HOSTS" \
    -- "$SSH_TARGET" sh -s -- "$PHASE" "$SERVER_NAME" "$EXPECTED_HOSTNAME" <<'REMOTE'
set -eu
phase="$1"
server_name="$2"
expected_hostname="$3"

command -v curl >/dev/null 2>&1 || exit 20
command -v python3 >/dev/null 2>&1 || exit 21
actual_hostname="$(hostname -f 2>/dev/null || hostname)"
[ "$actual_hostname" = "$expected_hostname" ] || exit 22

# Follow this host's own DNS/CDN/LB path; never use --resolve. Split-horizon,
# loopback, RFC1918 and link-local results are rejected.
python3 - "$server_name" <<'PY'
import ipaddress
import socket
import sys

addresses = {
    item[4][0]
    for item in socket.getaddrinfo(sys.argv[1], 443, type=socket.SOCK_STREAM)
}
if not addresses or any(not ipaddress.ip_address(value).is_global for value in addresses):
    raise SystemExit(23)
PY

curl_code() {
    method="$1"
    path="$2"
    if [ "$method" = POST ]; then
        curl --silent --show-error --noproxy '*' --connect-timeout 8 --max-time 25 \
            --request POST --header 'Content-Type: application/json' --data '{}' \
            --output /dev/null --write-out '%{http_code}' \
            "https://$server_name$path" || true
    else
        curl --silent --show-error --noproxy '*' --connect-timeout 8 --max-time 25 \
            --output /dev/null --write-out '%{http_code}' \
            "https://$server_name$path" || true
    fi
}

versions="$(curl --fail --silent --show-error --noproxy '*' \
    --connect-timeout 8 --max-time 25 \
    "https://$server_name/_matrix/client/versions")"
printf '%s' "$versions" | python3 -c \
    'import json,sys; v=json.load(sys.stdin); assert isinstance(v.get("versions"),list) and v["versions"]'

well_known="$(curl --fail --silent --show-error --noproxy '*' \
    --connect-timeout 8 --max-time 25 \
    "https://$server_name/.well-known/matrix/client")"
printf '%s' "$well_known" | python3 -c \
    'import json,sys; v=json.load(sys.stdin); s=sys.argv[1]; assert v == {"m.homeserver":{"base_url":"https://"+s},"org.matrix.msc4143.rtc_foci":[{"type":"livekit","livekit_service_url":"https://"+s+"/livekit/jwt"}]}' \
    "$server_name"

[ "$(curl_code GET '/_synapse/admin/v1/server_version?external=1')" = 404 ] || exit 24
[ "$(curl_code POST '/_synapse/admin/v1/server_version?external=1')" = 404 ] || exit 25

root="$(curl --fail --silent --show-error --noproxy '*' \
    --connect-timeout 8 --max-time 25 "https://$server_name/")"
printf '%s' "$root" | grep -Fq 'id="securechat-app-only"' || exit 26

if [ "$phase" = auth-closed ]; then
    for version in v3 r0 unstable api/v1; do
        for endpoint in login register; do
            base="/_matrix/client/$version/$endpoint"
            [ "$(curl_code GET "$base?external=1")" = 404 ] || exit 27
            [ "$(curl_code POST "$base?external=1")" = 404 ] || exit 28
            [ "$(curl_code GET "$base/securechat-deny-probe?external=1")" = 404 ] \
                || exit 29
            [ "$(curl_code POST "$base/securechat-deny-probe?external=1")" = 404 ] \
                || exit 30
        done
    done
    [ "$(curl_code GET '/_matrix/client/v3/sync?external=1')" = 401 ] || exit 31
    media_code="$(curl_code GET '/_matrix/media/v3/config?external=1')"
    [ "$media_code" = 200 ] || [ "$media_code" = 401 ] || exit 32
fi

sfu_code="$(curl_code GET '/livekit/sfu/')"
case "$sfu_code" in
    000|502|503|504|'') exit 33 ;;
esac
printf 'EXTERNAL_VANTAGE_OK phase=%s host=%s\n' "$phase" "$actual_hostname"
REMOTE
