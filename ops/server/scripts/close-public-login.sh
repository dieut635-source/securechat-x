#!/usr/bin/env bash
# Đóng mọi route login mà Synapse 1.159.0 đăng ký công khai sau khi
# migration/bind hoàn tất.
#
# Mặc định chỉ báo cáo. APPLY và ROLLBACK đều fail-closed, có snapshot và không
# sửa dữ liệu Synapse. Rollback chỉ được khôi phục một cấu hình vẫn đóng public
# login/registration; script tuyệt đối không tạo cửa sổ enrollment công khai.
set -Eeuo pipefail
umask 077

SERVER_NAME="${SERVER_NAME:-chat.securechat.com.au}"
MATRIX_DIR="${MATRIX_DIR:-/opt/matrix}"
NGINX_CONF="${NGINX_CONF:-$MATRIX_DIR/nginx/nginx.conf}"
COMPOSE_FILE="${COMPOSE_FILE:-$MATRIX_DIR/docker-compose.yml}"
NGINX_CONTAINER="${NGINX_CONTAINER:-nginx}"
NGINX_SERVICE="${NGINX_SERVICE:-nginx}"
NGINX_CONFIG_IN_CONTAINER="${NGINX_CONFIG_IN_CONTAINER:-/etc/nginx/nginx.conf}"
SYNAPSE_CONTAINER="${SYNAPSE_CONTAINER:-synapse}"
SYNAPSE_SERVICE="${SYNAPSE_SERVICE:-synapse}"
SYNAPSE_CONFIG_IN_CONTAINER="${SYNAPSE_CONFIG_IN_CONTAINER:-/data/homeserver.yaml}"
SYNAPSE_LOCAL_PORT="${SYNAPSE_LOCAL_PORT:-8008}"
EXPECTED_SYNAPSE_VERSION="${EXPECTED_SYNAPSE_VERSION:-1.159.0}"
BACKUP_BASE="${BACKUP_BASE:-$MATRIX_DIR/backups/close-public-login}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)-$$"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
GUARD_SOURCE_DIR="${GUARD_SOURCE_DIR:-$SCRIPT_DIR/../single-device/securechat_single_device}"
NGINX_TOPOLOGY_AUDITOR="${NGINX_TOPOLOGY_AUDITOR:-$SCRIPT_DIR/verify-nginx-topology.py}"
SYNAPSE_RUNTIME_AUDITOR="${SYNAPSE_RUNTIME_AUDITOR:-$SCRIPT_DIR/verify-synapse-runtime.py}"
EXTERNAL_VANTAGE_AUDITOR="${EXTERNAL_VANTAGE_AUDITOR:-$SCRIPT_DIR/verify-external-vantage.sh}"
EXTERNAL_PROBE_SSH_TARGET="${EXTERNAL_PROBE_SSH_TARGET:-}"
EXTERNAL_PROBE_KNOWN_HOSTS="${EXTERNAL_PROBE_KNOWN_HOSTS:-}"
EXTERNAL_PROBE_EXPECTED_HOSTNAME="${EXTERNAL_PROBE_EXPECTED_HOSTNAME:-}"

MODE="report"
TRANSACTION_KIND=""
TRANSACTION_BACKUP=""
ROLLBACK_SNAPSHOT=""
CONTAINER_CANDIDATE=""

log()  { printf '[+] %s\n' "$*"; }
ok()   { printf '[OK] %s\n' "$*"; }
warn() { printf '[!] %s\n' "$*" >&2; }
die()  { printf '[LỖI] %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Cách dùng:
  bash close-public-login.sh
  REPORT=1 bash close-public-login.sh
  APPLY=1 CONFIRM_GUARD_ENFORCE=1 CONFIRM_BINDINGS_COMPLETE=1 \
    CONFIRM_ACTIVE_SESSIONS_VERIFIED=1 \
    EXTERNAL_PROBE_SSH_TARGET=<user@host> \
    EXTERNAL_PROBE_KNOWN_HOSTS=<path> \
    EXTERNAL_PROBE_EXPECTED_HOSTNAME=<hostname> bash close-public-login.sh
  ROLLBACK=1 CONFIRM_FAIL_CLOSED_ROLLBACK=1 \
    CONFIRM_GUARD_ENFORCE=1 EXTERNAL_PROBE_SSH_TARGET=<user@host> \
    EXTERNAL_PROBE_KNOWN_HOSTS=<path> \
    EXTERNAL_PROBE_EXPECTED_HOSTNAME=<hostname> bash close-public-login.sh
  ROLLBACK=1 BACKUP_ID=<id> CONFIRM_FAIL_CLOSED_ROLLBACK=1 \
    CONFIRM_GUARD_ENFORCE=1 EXTERNAL_PROBE_SSH_TARGET=<user@host> \
    EXTERNAL_PROBE_KNOWN_HOSTS=<path> \
    EXTERNAL_PROBE_EXPECTED_HOSTNAME=<hostname> bash close-public-login.sh

Không đặt đồng thời REPORT=1, APPLY=1 và ROLLBACK=1.
EOF
}

validate_switch() {
    local name="$1"
    local value="$2"
    [[ "$value" == "0" || "$value" == "1" ]] \
        || die "$name chỉ nhận 0 hoặc 1 (đang là: $value)."
}

REPORT_VALUE="${REPORT:-0}"
APPLY_VALUE="${APPLY:-0}"
ROLLBACK_VALUE="${ROLLBACK:-0}"
CONFIRM_GUARD_ENFORCE_VALUE="${CONFIRM_GUARD_ENFORCE:-0}"
CONFIRM_BINDINGS_COMPLETE_VALUE="${CONFIRM_BINDINGS_COMPLETE:-0}"
CONFIRM_ACTIVE_SESSIONS_VERIFIED_VALUE="${CONFIRM_ACTIVE_SESSIONS_VERIFIED:-0}"
CONFIRM_FAIL_CLOSED_ROLLBACK_VALUE="${CONFIRM_FAIL_CLOSED_ROLLBACK:-0}"
for switch_name in REPORT APPLY ROLLBACK CONFIRM_GUARD_ENFORCE \
    CONFIRM_BINDINGS_COMPLETE CONFIRM_ACTIVE_SESSIONS_VERIFIED \
    CONFIRM_FAIL_CLOSED_ROLLBACK; do
    case "$switch_name" in
        REPORT) switch_value="$REPORT_VALUE" ;;
        APPLY) switch_value="$APPLY_VALUE" ;;
        ROLLBACK) switch_value="$ROLLBACK_VALUE" ;;
        CONFIRM_GUARD_ENFORCE) switch_value="$CONFIRM_GUARD_ENFORCE_VALUE" ;;
        CONFIRM_BINDINGS_COMPLETE) switch_value="$CONFIRM_BINDINGS_COMPLETE_VALUE" ;;
        CONFIRM_ACTIVE_SESSIONS_VERIFIED) switch_value="$CONFIRM_ACTIVE_SESSIONS_VERIFIED_VALUE" ;;
        CONFIRM_FAIL_CLOSED_ROLLBACK) switch_value="$CONFIRM_FAIL_CLOSED_ROLLBACK_VALUE" ;;
    esac
    validate_switch "$switch_name" "$switch_value"
done

SELECTED=$((REPORT_VALUE + APPLY_VALUE + ROLLBACK_VALUE))
(( SELECTED <= 1 )) || die "Chỉ được chọn một chế độ REPORT, APPLY hoặc ROLLBACK."
if (( APPLY_VALUE == 1 )); then
    MODE="apply"
elif (( ROLLBACK_VALUE == 1 )); then
    MODE="rollback"
fi

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi
[[ $# -eq 0 ]] || die "Không nhận đối số: $*. Dùng --help để xem cách chạy."

[[ $EUID -eq 0 ]] || die "Hãy chạy bằng root trên máy chủ."
command -v docker >/dev/null 2>&1 || die "Không tìm thấy docker."
command -v python3 >/dev/null 2>&1 || die "Không tìm thấy python3."
command -v curl >/dev/null 2>&1 || die "Không tìm thấy curl."
docker compose version >/dev/null 2>&1 || die "Không có docker compose plugin."
[[ -f "$NGINX_CONF" && ! -L "$NGINX_CONF" ]] \
    || die "nginx.conf không tồn tại hoặc là symlink: $NGINX_CONF"
[[ -f "$COMPOSE_FILE" && ! -L "$COMPOSE_FILE" ]] \
    || die "docker-compose.yml không tồn tại hoặc là symlink: $COMPOSE_FILE"

count_re() {
    local pattern="$1"
    local file="$2"
    local result
    result="$(grep -Ec -- "$pattern" "$file" || true)"
    printf '%s' "${result:-0}"
}

verify_effective_nginx_topology() {
    local require_closed="${1:-0}" snapshot
    [[ -f "$NGINX_TOPOLOGY_AUDITOR" && ! -L "$NGINX_TOPOLOGY_AUDITOR" ]] \
        || die "Thiếu nginx topology auditor đã review: $NGINX_TOPOLOGY_AUDITOR"
    snapshot="$(mktemp /tmp/securechat-nginx-T.XXXXXX)"
    if ! docker exec "$NGINX_CONTAINER" nginx -T >"$snapshot" 2>&1; then
        sed -n '1,80p' "$snapshot" >&2
        rm -f -- "$snapshot"
        die "Không lấy được effective nginx -T."
    fi
    if [[ "$require_closed" == "1" ]]; then
        python3 "$NGINX_TOPOLOGY_AUDITOR" --config "$snapshot" \
            --server-name "$SERVER_NAME" --require-closed-auth
    else
        python3 "$NGINX_TOPOLOGY_AUDITOR" --config "$snapshot" \
            --server-name "$SERVER_NAME"
    fi
    rm -f -- "$snapshot"
}

http_code_public() {
    local path="$1"
    curl --silent --show-error --connect-timeout 5 --max-time 20 \
        --noproxy '*' \
        --resolve "$SERVER_NAME:443:127.0.0.1" \
        -o /dev/null -w '%{http_code}' "https://$SERVER_NAME$path" || true
}

http_code_public_method() {
    local method="$1"
    local path="$2"
    if [[ "$method" == "POST" ]]; then
        curl --silent --show-error --connect-timeout 5 --max-time 20 \
            --noproxy '*' \
            --resolve "$SERVER_NAME:443:127.0.0.1" \
            --request POST --header 'Content-Type: application/json' --data '{}' \
            -o /dev/null -w '%{http_code}' "https://$SERVER_NAME$path" || true
    else
        http_code_public "$path"
    fi
}

http_code_local() {
    local path="$1"
    curl --silent --show-error --connect-timeout 5 --max-time 20 \
        --noproxy '*' \
        -o /dev/null -w '%{http_code}' \
        "http://127.0.0.1:$SYNAPSE_LOCAL_PORT$path" || true
}

report_state() {
    local v3_count r0_count unstable_count api_v1_count register_count admin_count
    local v3_public r0_public unstable_public api_v1_public register_public admin_public
    v3_count="$(count_re 'location[[:space:]]*=[[:space:]]*/_matrix/client/v3/login[[:space:]]*\{' "$NGINX_CONF")"
    r0_count="$(count_re 'location[[:space:]]*=[[:space:]]*/_matrix/client/r0/login[[:space:]]*\{' "$NGINX_CONF")"
    unstable_count="$(count_re 'location[[:space:]]*=[[:space:]]*/_matrix/client/unstable/login[[:space:]]*\{' "$NGINX_CONF")"
    api_v1_count="$(count_re 'location[[:space:]]*=[[:space:]]*/_matrix/client/api/v1/login[[:space:]]*\{' "$NGINX_CONF")"
    register_count="$(count_re 'location[[:space:]]*=[[:space:]]*/_matrix/client/(v3|r0|unstable|api/v1)/register[[:space:]]*\{' "$NGINX_CONF")"
    admin_count="$(count_re 'location[[:space:]]+\^~[[:space:]]+/_synapse/admin/[[:space:]]*\{' "$NGINX_CONF")"
    v3_public="$(http_code_public '/_matrix/client/v3/login')"
    r0_public="$(http_code_public '/_matrix/client/r0/login')"
    unstable_public="$(http_code_public '/_matrix/client/unstable/login')"
    api_v1_public="$(http_code_public '/_matrix/client/api/v1/login')"
    register_public="$(http_code_public '/_matrix/client/v3/register')"
    admin_public="$(http_code_public '/_synapse/admin/v1/server_version')"

    printf '\n=== Báo cáo ingress steady-state (không sửa hệ thống) ===\n'
    printf 'nginx.conf:                  %s\n' "$NGINX_CONF"
    printf 'exact location v3/r0:        %s / %s\n' "$v3_count" "$r0_count"
    printf 'exact location unstable/v1:  %s / %s\n' "$unstable_count" "$api_v1_count"
    printf 'exact registration (cần 4):  %s\n' "$register_count"
    printf 'Admin API block:             %s\n' "$admin_count"
    printf 'HTTP public v3/r0/unst/v1:   %s / %s / %s / %s\n' \
        "${v3_public:-000}" "${r0_public:-000}" \
        "${unstable_public:-000}" "${api_v1_public:-000}"
    printf 'HTTP public Admin API:       %s\n' "${admin_public:-000}"
    printf 'HTTP public v3 register:     %s\n' "${register_public:-000}"
    printf 'HTTP local v3/r0/admin:      %s / %s / %s\n' \
        "$(http_code_local '/_matrix/client/v3/login')" \
        "$(http_code_local '/_matrix/client/r0/login')" \
        "$(http_code_local '/_synapse/admin/v1/server_version')"

    if [[ "$v3_public" == "404" && "$r0_public" == "404" \
        && "$unstable_public" == "404" && "$api_v1_public" == "404" ]]; then
        if [[ "$register_public" == "404" && "$register_count" == "4" ]]; then
            ok "Public login và registration đang đóng"
        else
            warn "Public login đã đóng nhưng registration chưa được chứng minh 404."
        fi
    else
        warn "Login public chưa ở steady-state 404. Không APPLY trước migration/bind và kiểm chứng session."
    fi
    if [[ "$admin_public" == "404" && "$admin_count" == "1" ]]; then
        ok "Admin API public đang bị chặn"
    else
        warn "Admin API public chưa được chặn đúng; chạy phase disable-web-login trước."
    fi
    if (verify_guard_enforce) >/dev/null 2>&1; then
        ok "Guard enforce, auth local-only và origin loopback được nhận diện"
    else
        warn "Chưa chứng minh được guard đang chạy enforce; APPLY/ROLLBACK sẽ từ chối."
    fi
    printf '\n'
}

verify_origin_is_loopback_only() {
    local network_mode ports_json networks_json client_ports_json
    [[ -f "$SYNAPSE_RUNTIME_AUDITOR" && ! -L "$SYNAPSE_RUNTIME_AUDITOR" ]] \
        || die "Thiếu Synapse runtime auditor đã review: $SYNAPSE_RUNTIME_AUDITOR"
    python3 "$SYNAPSE_RUNTIME_AUDITOR" \
        --matrix-dir "$MATRIX_DIR" \
        --compose-file "$COMPOSE_FILE" \
        --synapse-container "$SYNAPSE_CONTAINER" \
        --synapse-service "$SYNAPSE_SERVICE" \
        --nginx-container "$NGINX_CONTAINER" \
        --nginx-service "$NGINX_SERVICE" \
        --config-path "$SYNAPSE_CONFIG_IN_CONTAINER" \
        --nginx-config-path "$NGINX_CONFIG_IN_CONTAINER"
    network_mode="$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$SYNAPSE_CONTAINER")"
    [[ "$network_mode" =~ ^[A-Za-z0-9_.-]+$ ]] \
        || die "Docker network mode Synapse chứa ký tự/topology chưa review: $network_mode"
    case "$network_mode" in
        host|none) die "Docker network mode Synapse không được hỗ trợ fail-closed: $network_mode" ;;
        *) ;;
    esac
    ports_json="$(docker inspect --format '{{json .NetworkSettings.Ports}}' "$SYNAPSE_CONTAINER")"
    networks_json="$(docker inspect --format '{{json .NetworkSettings.Networks}}' "$SYNAPSE_CONTAINER")"
    client_ports_json="$(docker exec -i "$SYNAPSE_CONTAINER" python - "$SYNAPSE_CONFIG_IN_CONTAINER" <<'PY'
import json
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as handle:
    config = yaml.safe_load(handle)
if not isinstance(config, dict) or not isinstance(config.get("listeners"), list):
    raise SystemExit("Synapse listeners không parse được")
ports = set()
for listener in config["listeners"]:
    if not isinstance(listener, dict):
        raise SystemExit("Synapse listener không phải mapping")
    if listener.get("type") != "http":
        continue
    resources = listener.get("resources", [])
    if not isinstance(resources, list):
        raise SystemExit("Synapse listener resources không phải list")
    serves_client = False
    for resource in resources:
        if not isinstance(resource, dict) or not isinstance(resource.get("names"), list):
            raise SystemExit("Synapse HTTP resource không đúng schema")
        names = resource["names"]
        if any(not isinstance(name, str) for name in names):
            raise SystemExit("Synapse resource name không phải string")
        serves_client = serves_client or bool({"client", "openid"}.intersection(names))
    if serves_client:
        if listener.get("additional_resources"):
            raise SystemExit("Client listener có additional_resources chưa review")
        port = listener.get("port")
        if type(port) is not int or not (1 <= port <= 65535):
            raise SystemExit("Client listener port không hợp lệ")
        ports.add(port)
print(json.dumps(sorted(ports)))
PY
)"
    python3 - "$ports_json" "$networks_json" "$client_ports_json" "$SYNAPSE_LOCAL_PORT" <<'PY'
import json
import sys

try:
    published = json.loads(sys.argv[1])
    networks = json.loads(sys.argv[2])
    client_ports = json.loads(sys.argv[3])
    expected_port = int(sys.argv[4])
except Exception:
    raise SystemExit("Không parse được topology Docker/Synapse")
if client_ports != [8008] or expected_port != 8008:
    raise SystemExit("Phải có đúng một client listener Synapse tại container port 8008")
if not isinstance(networks, dict) or len(networks) != 1:
    raise SystemExit("Synapse phải nằm trên đúng một Docker network đã review")
for detail in networks.values():
    if not isinstance(detail, dict) or not isinstance(detail.get("Aliases"), list):
        raise SystemExit("Docker network aliases không parse được")
if not isinstance(published, dict):
    raise SystemExit("Docker published ports không phải mapping")
for container_port, bindings in published.items():
    if bindings is not None and container_port != "8008/tcp":
        raise SystemExit("Synapse có thêm host-published port chưa review")
bindings = published.get("8008/tcp")
if not isinstance(bindings, list) or not bindings:
    raise SystemExit("Synapse 8008 không có publish mapping loopback đã kiểm chứng")
for binding in bindings:
    if not isinstance(binding, dict):
        raise SystemExit("Docker port mapping không đúng object")
    if binding.get("HostIp") not in ("127.0.0.1", "::1"):
        raise SystemExit("Synapse 8008 có HostIp không phải loopback")
    try:
        host_port = int(binding.get("HostPort", ""))
    except (TypeError, ValueError):
        raise SystemExit("Docker HostPort không hợp lệ")
    if host_port != expected_port:
        raise SystemExit("Docker HostPort khác SYNAPSE_LOCAL_PORT đã kiểm tra")
PY
}

verify_guard_source_provenance() {
    [[ -d "$GUARD_SOURCE_DIR" && ! -L "$GUARD_SOURCE_DIR" ]] \
        || die "Không thấy source guard đã review: $GUARD_SOURCE_DIR"
    local expected_digest runtime_digest
    expected_digest="$(python3 - "$GUARD_SOURCE_DIR" <<'PY'
import hashlib
from pathlib import Path
import sys

root = Path(sys.argv[1])
digest = hashlib.sha256()
for name in ("__init__.py", "core.py", "module.py"):
    path = root / name
    if not path.is_file() or path.is_symlink():
        raise SystemExit(f"missing/non-regular reviewed guard source: {name}")
    digest.update(name.encode("utf-8") + b"\0" + path.read_bytes())
print(digest.hexdigest())
PY
)"
    runtime_digest="$(docker exec -i "$SYNAPSE_CONTAINER" python - <<'PY'
import hashlib
from pathlib import Path

import securechat_single_device
import securechat_single_device.core
import securechat_single_device.module

modules = {
    "__init__.py": securechat_single_device,
    "core.py": securechat_single_device.core,
    "module.py": securechat_single_device.module,
}
digest = hashlib.sha256()
for name in ("__init__.py", "core.py", "module.py"):
    raw_path = getattr(modules[name], "__file__", None)
    if not raw_path:
        raise SystemExit(f"runtime module has no source path: {name}")
    path = Path(raw_path)
    if path.suffix == ".pyc":
        path = Path(str(path)[:-1])
    if not path.is_file():
        raise SystemExit(f"runtime guard source unavailable: {name}")
    digest.update(name.encode("utf-8") + b"\0" + path.read_bytes())
print(digest.hexdigest())
PY
)"
    [[ "$expected_digest" =~ ^[0-9a-f]{64}$ \
        && "$runtime_digest" == "$expected_digest" ]] \
        || die "Source guard runtime không khớp bundle đã review; từ chối rollout."
}

verify_guard_enforce() {
    docker ps --format '{{.Names}}' | grep -qx "$SYNAPSE_CONTAINER" \
        || die "Container $SYNAPSE_CONTAINER không chạy."
    local services worker_count started_at config_mtime
    services="$(docker compose --project-directory "$MATRIX_DIR" \
        -f "$COMPOSE_FILE" config --services)"
    printf '%s\n' "$services" | grep -qx "$SYNAPSE_SERVICE" \
        || die "Không thấy service Synapse '$SYNAPSE_SERVICE'."
    worker_count="$(printf '%s\n' "$services" | grep -Eci 'worker' || true)"
    (( worker_count == 0 )) \
        || die "Nhận diện service worker; script single-process không được phép đoán routing /login."
    docker exec "$SYNAPSE_CONTAINER" python -c \
        'import synapse,sys; sys.exit(0 if synapse.__version__ == sys.argv[1] else 2)' \
        "$EXPECTED_SYNAPSE_VERSION" \
        || die "Synapse runtime không đúng baseline $EXPECTED_SYNAPSE_VERSION."

    verify_origin_is_loopback_only
    verify_guard_source_provenance

    docker exec -i "$SYNAPSE_CONTAINER" python - \
        "$SYNAPSE_CONFIG_IN_CONTAINER" <<'PY'
import sys
import yaml

path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as handle:
        config = yaml.safe_load(handle)
except Exception:
    raise SystemExit("homeserver config could not be parsed safely")
if not isinstance(config, dict):
    raise SystemExit("homeserver config is not a mapping")


def require_mapping(name):
    value = config.get(name)
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise SystemExit(f"{name} is not a mapping")
    return value


def require_bool(container, key, default, name):
    if key not in container:
        return default
    value = container[key]
    if type(value) is not bool:
        raise SystemExit(f"{name} is not a strict boolean")
    return value


def synapse_strtobool(value, name):
    normalized = str(value).lower()
    if normalized in ("y", "yes", "t", "true", "on", "1"):
        return True
    if normalized in ("n", "no", "f", "false", "off", "0"):
        return False
    raise SystemExit(f"{name} is not a recognized boolean")


mas = require_mapping("matrix_authentication_service")
if require_bool(mas, "enabled", False, "matrix_authentication_service.enabled"):
    raise SystemExit("MAS/OAuth delegation must be disabled")

login_existing = require_mapping("login_via_existing_session")
if require_bool(login_existing, "enabled", False, "login_via_existing_session.enabled"):
    raise SystemExit("login_via_existing_session must be disabled")

jwt = require_mapping("jwt_config")
if require_bool(jwt, "enabled", False, "jwt_config.enabled"):
    raise SystemExit("JWT login must be disabled")

legacy_oidc = require_mapping("oidc_config")
if require_bool(legacy_oidc, "enabled", False, "oidc_config.enabled"):
    raise SystemExit("legacy OIDC login must be disabled")
providers = config.get("oidc_providers", [])
if providers is None:
    providers = []
if not isinstance(providers, list) or providers:
    raise SystemExit("new-style OIDC providers must be an empty list")

ldap = require_mapping("ldap_config")
if require_bool(ldap, "enabled", False, "ldap_config.enabled"):
    raise SystemExit("legacy LDAP password provider must be disabled")

saml = require_mapping("saml2_config")
saml_enabled = bool(saml) and require_bool(
    saml, "enabled", True, "saml2_config.enabled"
) and bool(saml.get("sp_config") or saml.get("config_path"))
if saml_enabled:
    raise SystemExit("SAML login must be disabled")

cas = require_mapping("cas_config")
cas_enabled = bool(cas) and require_bool(cas, "enabled", True, "cas_config.enabled")
if cas_enabled:
    raise SystemExit("CAS login must be disabled")

if "disable_registration" in config:
    registration_enabled = not synapse_strtobool(
        config["disable_registration"], "disable_registration"
    )
else:
    registration_enabled = synapse_strtobool(
        config.get("enable_registration", False), "enable_registration"
    )
if registration_enabled:
    raise SystemExit("public registration must be disabled")
if require_bool(config, "allow_guest_access", False, "allow_guest_access"):
    raise SystemExit("guest registration/access must be disabled")
if require_bool(
    config,
    "enable_registration_without_verification",
    False,
    "enable_registration_without_verification",
):
    raise SystemExit("registration without verification must be disabled")

appservices = config.get("app_service_config_files", [])
if appservices is None:
    appservices = []
if not isinstance(appservices, list) or appservices:
    raise SystemExit("application-service configs must be absent for this baseline")
password_providers = config.get("password_providers", [])
if password_providers is None:
    password_providers = []
if not isinstance(password_providers, list) or password_providers:
    raise SystemExit("password-provider modules must be absent")

password = require_mapping("password_config")
password_enabled = password.get("enabled", True)
if password_enabled is not True:
    raise SystemExit("password_config.enabled must be true, not only_for_reauth")
if not require_bool(password, "localdb_enabled", True, "password_config.localdb_enabled"):
    raise SystemExit("password local database auth must be enabled")

for lifetime in (
    "session_lifetime",
    "refreshable_access_token_lifetime",
    "nonrefreshable_access_token_lifetime",
):
    if config.get(lifetime) is not None:
        raise SystemExit(f"{lifetime} must be unset for retained steady-state sessions")

for shared_secret_key in (
    "registration_shared_secret",
    "registration_shared_secret_path",
):
    if shared_secret_key in config:
        raise SystemExit(
            f"{shared_secret_key} must be absent in app-only steady state"
        )

modules = config.get("modules", [])
expected_module = {
    "module": "securechat_single_device.module.SecureChatSingleDeviceModule",
    "config": {"mode": "enforce"},
}
if modules != [expected_module]:
    raise SystemExit(
        "modules must contain exactly the enforce single-device guard and nothing else"
    )
PY

    started_at="$(docker inspect --format '{{.State.StartedAt}}' "$SYNAPSE_CONTAINER")"
    config_mtime="$(docker exec "$SYNAPSE_CONTAINER" python -c \
        'import os,sys; print(os.stat(sys.argv[1]).st_mtime)' \
        "$SYNAPSE_CONFIG_IN_CONTAINER")"
    python3 - "$started_at" "$config_mtime" <<'PY'
from datetime import datetime
import re
import sys

started = re.sub(r"\.(\d{6})\d*(Z|[+-])", r".\1\2", sys.argv[1])
if started.endswith("Z"):
    started = started[:-1] + "+00:00"
if datetime.fromisoformat(started).timestamp() < float(sys.argv[2]):
    raise SystemExit("Synapse container predates enforce config; restart it first")
PY

    docker logs "$SYNAPSE_CONTAINER" 2>&1 \
        | grep -F 'SecureChat single-device guard ready mode=enforce' >/dev/null \
        || die "Không thấy marker runtime guard ready mode=enforce trong log Synapse."
}

atomic_install_file() {
    local source="$1"
    local target="$2"
    local temporary="${target}.securechat-new-$STAMP"
    cp -a -- "$source" "$temporary"
    mv -- "$temporary" "$target"
}

test_nginx_candidate() {
    local candidate="$1"
    docker ps --format '{{.Names}}' | grep -qx "$NGINX_CONTAINER" \
        || die "Container $NGINX_CONTAINER không chạy."
    CONTAINER_CANDIDATE="/tmp/securechat-login-nginx-$STAMP.conf"
    docker cp "$candidate" "$NGINX_CONTAINER:$CONTAINER_CANDIDATE" >/dev/null
    if ! docker exec "$NGINX_CONTAINER" nginx -t -c "$CONTAINER_CANDIDATE" >/dev/null 2>&1; then
        docker exec "$NGINX_CONTAINER" nginx -t -c "$CONTAINER_CANDIDATE" || true
        die "Candidate nginx không hợp lệ trong container đang chạy."
    fi
    docker exec "$NGINX_CONTAINER" rm -f -- "$CONTAINER_CANDIDATE"
    CONTAINER_CANDIDATE=""
    ok "Candidate nginx qua nginx -t trong container hiện tại"
}

restore_apply_failure() {
    local backup="$1"
    local closed_check="$backup/failure-original-closed-check.conf"
    warn "APPLY lỗi; đang kiểm tra bản gốc có giữ fail-closed hay không."
    if build_candidate "$backup/original/nginx.conf" "$closed_check" \
        && cmp -s -- "$backup/original/nginx.conf" "$closed_check"; then
        atomic_install_file "$backup/original/nginx.conf" "$NGINX_CONF"
        warn "Đã khôi phục bản gốc vì bản đó vẫn đóng đầy đủ public auth."
    else
        warn "Không khôi phục bản gốc vì nó sẽ mở public auth; giữ candidate fail-closed trên đĩa."
    fi
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" \
        up -d --no-deps --force-recreate "$NGINX_SERVICE" >/dev/null 2>&1 || true
    warn "Bắt buộc kiểm tra nginx -t, Matrix sync và trạng thái login/register/Admin."
}

restore_rollback_failure() {
    local snapshot="$1"
    warn "ROLLBACK lỗi; đang phục hồi trạng thái ngay trước rollback."
    atomic_install_file "$snapshot/nginx.conf" "$NGINX_CONF"
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" \
        up -d --no-deps --force-recreate "$NGINX_SERVICE" >/dev/null 2>&1 || true
    warn "Đã thử phục hồi. Bắt buộc kiểm tra thủ công."
}

on_exit() {
    local status=$?
    trap - EXIT
    set +e
    if [[ -n "$CONTAINER_CANDIDATE" ]] \
        && docker ps --format '{{.Names}}' | grep -qx "$NGINX_CONTAINER"; then
        docker exec "$NGINX_CONTAINER" rm -f -- "$CONTAINER_CANDIDATE" >/dev/null 2>&1
    fi
    if [[ "$TRANSACTION_KIND" == "apply" ]]; then
        restore_apply_failure "$TRANSACTION_BACKUP"
    elif [[ "$TRANSACTION_KIND" == "rollback" ]]; then
        restore_rollback_failure "$ROLLBACK_SNAPSHOT"
    fi
    exit "$status"
}
trap on_exit EXIT

build_candidate() {
    local source="$1"
    local target="$2"
    python3 - "$source" "$target" "$SERVER_NAME" <<'PY'
import re
import sys

source, target, server_name = sys.argv[1:]
with open(source, encoding="utf-8") as handle:
    original = handle.read()


def structural_text(value):
    result = list(value)
    quote = None
    escaped = False
    in_comment = False
    for index, char in enumerate(value):
        if in_comment:
            if char == "\n":
                in_comment = False
            else:
                result[index] = " "
            continue
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            if char != "\n":
                result[index] = " "
            continue
        if char == "#":
            in_comment = True
            result[index] = " "
        elif char in ("'", '"'):
            quote = char
            result[index] = " "
    if quote is not None:
        raise SystemExit("Fail-closed: nginx.conf có quoted string chưa đóng")
    return "".join(result)


def matching_end(structure, opening):
    depth = 0
    for position in range(opening, len(structure)):
        if structure[position] == "{":
            depth += 1
        elif structure[position] == "}":
            depth -= 1
            if depth == 0:
                return position + 1
    raise SystemExit("Fail-closed: nginx block chưa đóng")


def all_server_blocks(value):
    structure = structural_text(value)
    result = []
    for match in re.finditer(r"(?m)^[ \t]*server[ \t]*\{", structure):
        opening = structure.find("{", match.start(), match.end())
        result.append((match.start(), matching_end(structure, opening)))
    return result


def select_public_server(value):
    structure = structural_text(value)
    matches = []
    for start, end in all_server_blocks(value):
        block = structure[start:end]
        names = re.findall(r"(?m)^[ \t]*server_name[ \t]+([^;]+);", block)
        has_name = len(names) == 1 and server_name in names[0].split()
        has_https = re.search(
            r"(?m)^[ \t]*listen[ \t]+(?:\[[^]]+\]:)?443(?:[ \t;])", block
        )
        if has_name and has_https:
            matches.append((start, end))
    if len(matches) != 1:
        raise SystemExit(
            f"Fail-closed: cần đúng 1 HTTPS server block cho {server_name}; thấy {len(matches)}"
        )
    return matches[0]


def exact_location_blocks(value, path):
    structure = structural_text(value)
    pattern = re.compile(
        rf"(?m)^(?P<indent>[ \t]*)location[ \t]*=[ \t]*{re.escape(path)}[ \t]*\{{"
    )
    result = []
    for match in pattern.finditer(structure):
        opening = structure.find("{", match.start(), match.end())
        result.append((match.start(), matching_end(structure, opening), match.group("indent")))
    return result


public_start, public_end = select_public_server(original)
public_block = original[public_start:public_end]
structure = structural_text(original)
includes = re.findall(r"(?m)^[ \t]*include[ \t]+([^;]+);", structure)
unexpected_includes = [item.strip() for item in includes if item.strip() != "/etc/nginx/mime.types"]
if unexpected_includes:
    raise SystemExit(
        "Fail-closed: nginx có include ngoài mime.types; cần review nginx -T thật"
    )
if re.search(r"(?m)^[ \t]*upstream[ \t]+[^\n{]+\{", structure):
    raise SystemExit("Fail-closed: upstream alias chưa được review")
for start, end in all_server_blocks(original):
    if (start, end) == (public_start, public_end):
        continue
    other = structure[start:end]
    if "/_matrix" in other or "/_synapse" in other or re.search(
        r"(?m)^[ \t]*(?:proxy_pass|grpc_pass|fastcgi_pass|uwsgi_pass|scgi_pass)[ \t]+",
        other,
    ):
        raise SystemExit(
            "Fail-closed: server block/vhost khác còn proxy route chưa review"
        )
admin_re = re.compile(
    r"(?m)^[ \t]*location[ \t]+\^~[ \t]+/_synapse/admin/[ \t]*\{"
    r"[ \t]*(?:\n[ \t]*)?return[ \t]+404;[ \t]*(?:\n[ \t]*)?\}"
)
admin_matches = list(admin_re.finditer(public_block))
if public_block.count("/_synapse/admin/") != 1 or len(admin_matches) != 1:
    raise SystemExit(
        "Fail-closed: phase trước chưa đặt duy nhất exact Admin API return 404 trong server public"
    )

versions = ("v3", "r0", "unstable", "api/v1")
login_paths = tuple(f"/_matrix/client/{version}/login" for version in versions)
register_paths = tuple(f"/_matrix/client/{version}/register" for version in versions)
paths = login_paths + register_paths
exact_path_re = re.compile(
    r"(?m)^[ \t]*location[ \t]*=[ \t]*(?P<path>/[^ \t{;]+)[ \t]*\{"
)
for match in exact_path_re.finditer(structural_text(public_block)):
    configured_path = match.group("path")
    if any(configured_path.startswith(path + "/") for path in paths):
        raise SystemExit(
            "Fail-closed: exact nginx auth subroute có thể thắng prefix ^~; cần review riêng"
        )
candidate = original
for path in paths:
    locations = exact_location_blocks(candidate, path)
    current_start, current_end = select_public_server(candidate)
    in_public = [item for item in locations if current_start <= item[0] < current_end]
    if len(locations) != len(in_public) or len(in_public) > 1:
        raise SystemExit(f"Fail-closed: topology exact location không duy nhất cho {path}")
    if in_public:
        start, end, indent = in_public[0]
        existing = structural_text(candidate[start:end])
        exact_deny = re.fullmatch(
            rf"[ \t]*location[ \t]*=[ \t]*{re.escape(path)}[ \t]*\{{"
            r"[ \t\n]*return[ \t]+404;[ \t\n]*\}",
            existing,
        )
        if exact_deny is not None:
            continue
        while end < len(candidate) and candidate[end] in " \t":
            end += 1
        if end < len(candidate) and candidate[end] == "\n":
            end += 1
    else:
        start, end = current_end - 1, current_end - 1
        server_indent = re.match(r"[ \t]*", candidate[current_start:]).group(0)
        indent = server_indent + "    "
    replacement = (
        f"{indent}# SecureChat steady-state: no public login or registration\n"
        f"{indent}location = {path} {{\n"
        f"{indent}    return 404;\n"
        f"{indent}}}\n"
    )
    candidate = candidate[:start] + replacement + candidate[end:]


def prefix_location_blocks(value, path):
    structure = structural_text(value)
    pattern = re.compile(
        rf"(?m)^(?P<indent>[ \t]*)location[ \t]+\^~[ \t]+{re.escape(path)}[ \t]*\{{"
    )
    result = []
    for match in pattern.finditer(structure):
        opening = structure.find("{", match.start(), match.end())
        result.append((match.start(), matching_end(structure, opening), match.group("indent")))
    return result


# Chặn cả subroute SSO/CAS dưới /login/ và registration stages dưới /register/.
# Dùng ^~ với dấu slash cuối để thắng generic regex proxy Matrix.
prefix_paths = tuple(path + "/" for path in paths)
for path in prefix_paths:
    locations = prefix_location_blocks(candidate, path)
    current_start, current_end = select_public_server(candidate)
    in_public = [item for item in locations if current_start <= item[0] < current_end]
    if len(locations) != len(in_public) or len(in_public) > 1:
        raise SystemExit(f"Fail-closed: topology prefix location không duy nhất cho {path}")
    if in_public:
        start, end, indent = in_public[0]
        existing = structural_text(candidate[start:end])
        prefix_deny = re.fullmatch(
            rf"[ \t]*location[ \t]+\^~[ \t]+{re.escape(path)}[ \t]*\{{"
            r"[ \t\n]*return[ \t]+404;[ \t\n]*\}",
            existing,
        )
        if prefix_deny is not None:
            continue
        while end < len(candidate) and candidate[end] in " \t":
            end += 1
        if end < len(candidate) and candidate[end] == "\n":
            end += 1
    else:
        start, end = current_end - 1, current_end - 1
        server_indent = re.match(r"[ \t]*", candidate[current_start:]).group(0)
        indent = server_indent + "    "
    replacement = (
        f"{indent}# SecureChat steady-state: block auth subroutes\n"
        f"{indent}location ^~ {path} {{\n"
        f"{indent}    return 404;\n"
        f"{indent}}}\n"
    )
    candidate = candidate[:start] + replacement + candidate[end:]

public_start, public_end = select_public_server(candidate)
for path in paths:
    locations = exact_location_blocks(candidate, path)
    if len(locations) != 1 or not (public_start <= locations[0][0] < public_end):
        raise SystemExit(f"Candidate thiếu exact public location cho {path}")
    body = structural_text(candidate[locations[0][0]:locations[0][1]])
    if len(re.findall(r"(?m)^[ \t]*return[ \t]+404;", body)) != 1:
        raise SystemExit(f"Candidate không return 404 duy nhất cho {path}")
    if "proxy_pass" in body:
        raise SystemExit(f"Candidate vẫn proxy login cho {path}")
for path in prefix_paths:
    locations = prefix_location_blocks(candidate, path)
    if len(locations) != 1 or not (public_start <= locations[0][0] < public_end):
        raise SystemExit(f"Candidate thiếu prefix public location cho {path}")
    body = structural_text(candidate[locations[0][0]:locations[0][1]])
    if len(re.findall(r"(?m)^[ \t]*return[ \t]+404;", body)) != 1:
        raise SystemExit(f"Candidate không return 404 duy nhất cho prefix {path}")
    if "proxy_pass" in body:
        raise SystemExit(f"Candidate vẫn proxy login prefix {path}")

if original.lower().count("livekit") != candidate.lower().count("livekit"):
    raise SystemExit("Tham chiếu LiveKit đổi ngoài các auth block cho phép")
if "/_matrix" not in candidate or "proxy_pass http://synapse:8008" not in candidate:
    raise SystemExit("Candidate làm mất proxy Matrix/Synapse tổng quát")

with open(target, "w", encoding="utf-8") as handle:
    handle.write(candidate)
PY
}

verify_matrix_versions() {
    local output="$1"
    curl --silent --show-error --fail --connect-timeout 5 --max-time 20 \
        --noproxy '*' \
        --resolve "$SERVER_NAME:443:127.0.0.1" \
        "https://$SERVER_NAME/_matrix/client/versions" -o "$output"
    python3 - "$output" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    value = json.load(handle)
if not isinstance(value.get("versions"), list) or not value["versions"]:
    raise SystemExit("Matrix versions response invalid")
PY
}

verify_closed() {
    local backup="$1" path subpath method public_code local_code sync_code media_code
    verify_matrix_versions "$backup/verify-matrix-versions.json"
    ok "Matrix client API vẫn hoạt động"
    for path in \
        '/_matrix/client/v3/login' \
        '/_matrix/client/r0/login' \
        '/_matrix/client/unstable/login' \
        '/_matrix/client/api/v1/login'; do
        for method in GET POST; do
            public_code="$(http_code_public_method "$method" "$path")"
            [[ "$public_code" == "404" ]] \
                || die "$method $path public chưa trả 404 (HTTP ${public_code:-000})."
        done
        local_code="$(http_code_local "$path")"
        [[ "$local_code" == "200" ]] \
            || die "$path nội bộ localhost:8008 không còn trả 200 (HTTP ${local_code:-000})."
        for subpath in \
            '/sso/redirect' \
            '/sso/redirect/test-idp' \
            '/cas/redirect' \
            '/cas/ticket'; do
            for method in GET POST; do
                public_code="$(http_code_public_method "$method" "$path$subpath")"
                [[ "$public_code" == "404" ]] \
                    || die "$method $path/ public prefix chưa trả 404 (HTTP ${public_code:-000})."
            done
        done
    done
    for path in \
        '/_matrix/client/v3/register' \
        '/_matrix/client/r0/register' \
        '/_matrix/client/unstable/register' \
        '/_matrix/client/api/v1/register'; do
        for method in GET POST; do
            public_code="$(http_code_public_method "$method" "$path")"
            [[ "$public_code" == "404" ]] \
                || die "$method $path public chưa trả 404 (HTTP ${public_code:-000})."
        done
        for subpath in \
            '/available' \
            '/email/requestToken' \
            '/msisdn/requestToken'; do
            for method in GET POST; do
                public_code="$(http_code_public_method "$method" "$path$subpath")"
                [[ "$public_code" == "404" ]] \
                    || die "$method $path/ public prefix chưa trả 404 (HTTP ${public_code:-000})."
            done
        done
    done
    [[ "$(http_code_public '/_synapse/admin/v1/server_version')" == "404" ]] \
        || die "Admin API public không còn trả 404."
    sync_code="$(http_code_public '/_matrix/client/v3/sync')"
    [[ "$sync_code" == "401" ]] \
        || die "Sync probe không còn tới Synapse như kỳ vọng (HTTP ${sync_code:-000}, cần 401 khi không token)."
    media_code="$(http_code_public '/_matrix/media/v3/config')"
    case "$media_code" in
        200|401) ;;
        *) die "Media probe không còn tới Synapse (HTTP ${media_code:-000}, cần 200/401)." ;;
    esac
    ok "Bốn version login/register và mọi prefix tương ứng trả 404 cho GET/POST"
    ok "Probe không token xác nhận sync và media vẫn đi tới Synapse"

    if grep -Fq '/livekit/sfu/' "$backup/original/nginx.conf"; then
        local sfu_code
        sfu_code="$(http_code_public '/livekit/sfu/')"
        case "$sfu_code" in
            000|502|503|504|'') die "Proxy LiveKit SFU lỗi sau khi đóng login (HTTP ${sfu_code:-000})." ;;
            *) ok "Proxy LiveKit SFU còn phản hồi (HTTP $sfu_code)" ;;
        esac
    fi
    if grep -Fq '/livekit/jwt/healthz' "$backup/original/nginx.conf"; then
        [[ "$(http_code_public '/livekit/jwt/healthz')" == "200" ]] \
            || die "LiveKit JWT healthz không còn 200."
        ok "LiveKit JWT healthz còn hoạt động"
    fi
}

verify_external_vantage() {
    [[ -f "$EXTERNAL_VANTAGE_AUDITOR" && ! -L "$EXTERNAL_VANTAGE_AUDITOR" ]] \
        || die "Thiếu external-vantage auditor: $EXTERNAL_VANTAGE_AUDITOR"
    EXTERNAL_PROBE_SSH_TARGET="$EXTERNAL_PROBE_SSH_TARGET" \
    EXTERNAL_PROBE_KNOWN_HOSTS="$EXTERNAL_PROBE_KNOWN_HOSTS" \
    EXTERNAL_PROBE_EXPECTED_HOSTNAME="$EXTERNAL_PROBE_EXPECTED_HOSTNAME" \
        bash "$EXTERNAL_VANTAGE_AUDITOR" auth-closed "$SERVER_NAME"
    ok "Vantage host độc lập xác nhận DNS/CDN/LB public đã đóng auth"
}

apply_change() {
    (( CONFIRM_GUARD_ENFORCE_VALUE == 1 )) \
        || die "APPLY cần CONFIRM_GUARD_ENFORCE=1."
    (( CONFIRM_BINDINGS_COMPLETE_VALUE == 1 )) \
        || die "APPLY cần CONFIRM_BINDINGS_COMPLETE=1 sau plan/bind."
    (( CONFIRM_ACTIVE_SESSIONS_VERIFIED_VALUE == 1 )) \
        || die "APPLY cần CONFIRM_ACTIVE_SESSIONS_VERIFIED=1 sau smoke test session đang giữ."
    verify_guard_enforce
    docker ps --format '{{.Names}}' | grep -qx "$NGINX_CONTAINER" \
        || die "Container $NGINX_CONTAINER không chạy."
    docker exec "$NGINX_CONTAINER" nginx -t >/dev/null
    verify_effective_nginx_topology 0
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" config -q >/dev/null
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" config --services \
        | grep -qx "$NGINX_SERVICE" \
        || die "Không thấy service nginx '$NGINX_SERVICE'."

    install -d -m 0700 "$BACKUP_BASE"
    local backup="$BACKUP_BASE/$STAMP"
    install -d -m 0700 "$backup/original" "$backup/candidate"
    cp -a -- "$NGINX_CONF" "$backup/original/nginx.conf"
    build_candidate "$NGINX_CONF" "$backup/candidate/nginx.conf"
    test_nginx_candidate "$backup/candidate/nginx.conf"
    printf 'backup_id=%s\nserver_name=%s\n' "$(basename -- "$backup")" "$SERVER_NAME" \
        > "$backup/MANIFEST"

    TRANSACTION_KIND="apply"
    TRANSACTION_BACKUP="$backup"
    atomic_install_file "$backup/candidate/nginx.conf" "$NGINX_CONF"
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" \
        up -d --no-deps --force-recreate "$NGINX_SERVICE" >/dev/null
    docker exec "$NGINX_CONTAINER" nginx -t >/dev/null
    verify_effective_nginx_topology 1
    verify_closed "$backup"
    verify_external_vantage
    : > "$backup/APPLIED"
    TRANSACTION_KIND=""
    ok "Đã đóng public login steady-state. Backup ID: $(basename -- "$backup")"
    warn "APK đang giữ token tiếp tục sync; fresh install không thể login ở baseline này. Enrollment cần phase nội bộ/restricted riêng đã review."
}

select_rollback_backup() {
    local requested="${BACKUP_ID:-}" candidate
    if [[ -n "$requested" ]]; then
        [[ "$requested" != */* && "$requested" != "." && "$requested" != ".." ]] \
            || die "BACKUP_ID không hợp lệ."
        candidate="$BACKUP_BASE/$requested"
        [[ -d "$candidate" && -f "$candidate/APPLIED" && ! -f "$candidate/ROLLED_BACK" ]] \
            || die "Backup không hợp lệ, chưa APPLY hoặc đã rollback: $requested"
        printf '%s' "$candidate"
        return
    fi
    while IFS= read -r candidate; do
        if [[ -f "$candidate/APPLIED" && ! -f "$candidate/ROLLED_BACK" ]]; then
            printf '%s' "$candidate"
            return
        fi
    done < <(find "$BACKUP_BASE" -mindepth 1 -maxdepth 1 -type d -print 2>/dev/null | sort -r)
    die "Không có backup đã APPLY và chưa rollback."
}

rollback_change() {
    (( CONFIRM_FAIL_CLOSED_ROLLBACK_VALUE == 1 )) \
        || die "ROLLBACK cần CONFIRM_FAIL_CLOSED_ROLLBACK=1 và không được mở public auth."
    (( CONFIRM_GUARD_ENFORCE_VALUE == 1 )) \
        || die "ROLLBACK cần CONFIRM_GUARD_ENFORCE=1."
    verify_guard_enforce
    [[ -d "$BACKUP_BASE" ]] || die "Chưa có thư mục backup: $BACKUP_BASE"
    local backup
    backup="$(select_rollback_backup)"
    [[ -f "$backup/original/nginx.conf" ]] \
        || die "Backup thiếu nginx.conf gốc: $backup"
    test_nginx_candidate "$backup/original/nginx.conf"

    # build_candidate phải idempotent trên một cấu hình đã đóng. Nếu nó còn
    # phải thêm/thay bất kỳ block nào thì bản backup sẽ mở public login và bị
    # từ chối tuyệt đối, không dùng làm cửa sổ enrollment.
    build_candidate "$backup/original/nginx.conf" "$backup/rollback-closed-check-$STAMP.conf"
    cmp -s -- "$backup/original/nginx.conf" "$backup/rollback-closed-check-$STAMP.conf" \
        || die "Backup sẽ nới public auth; rollback fail-closed bị từ chối."
    verify_effective_nginx_topology 1

    ROLLBACK_SNAPSHOT="$backup/rollback-current-$STAMP"
    install -d -m 0700 "$ROLLBACK_SNAPSHOT"
    cp -a -- "$NGINX_CONF" "$ROLLBACK_SNAPSHOT/nginx.conf"
    TRANSACTION_KIND="rollback"
    atomic_install_file "$backup/original/nginx.conf" "$NGINX_CONF"
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" \
        up -d --no-deps --force-recreate "$NGINX_SERVICE" >/dev/null
    docker exec "$NGINX_CONTAINER" nginx -t >/dev/null
    verify_effective_nginx_topology 1
    verify_closed "$backup"
    verify_external_vantage
    : > "$backup/ROLLED_BACK"
    TRANSACTION_KIND=""
    ok "Đã khôi phục backup $(basename -- "$backup") và policy public auth vẫn đóng."
    warn "Rollback này không mở enrollment; fresh login vẫn bị chặn theo baseline."
}

case "$MODE" in
    report) report_state ;;
    apply) apply_change ;;
    rollback) rollback_change ;;
    *) die "Mode nội bộ không hợp lệ: $MODE" ;;
esac
