#!/usr/bin/env bash
# Gỡ bề mặt đăng nhập Web và Admin API công khai khỏi
# chat.securechat.com.au mà không đụng dữ liệu Synapse.
#
# Mặc định: chỉ báo cáo, không ghi file và không reload/recreate container.
# Áp dụng:  APPLY=1 bash disable-web-login.sh
# Không có rollback về Web cũ: thao tác đó có thể xóa policy auth fail-closed.
#
# Script cố ý fail-closed: chỉ sửa đúng topology nginx/Docker Compose đã biết. Nếu
# cấu hình thật khác, nó dừng trước thay đổi thay vì đoán cấu trúc server block.
set -Eeuo pipefail
umask 077

SERVER_NAME="${SERVER_NAME:-chat.securechat.com.au}"
MATRIX_DIR="${MATRIX_DIR:-/opt/matrix}"
NGINX_CONF="${NGINX_CONF:-$MATRIX_DIR/nginx/nginx.conf}"
COMPOSE_FILE="${COMPOSE_FILE:-$MATRIX_DIR/docker-compose.yml}"
PUBLIC_HOST_ROOT="${PUBLIC_HOST_ROOT:-$MATRIX_DIR/securechat-public}"
PUBLIC_CONTAINER_ROOT="${PUBLIC_CONTAINER_ROOT:-/var/www/securechat-public}"
LEGACY_CONTAINER_ROOT="${LEGACY_CONTAINER_ROOT:-/var/www/element-web}"
NGINX_CONTAINER="${NGINX_CONTAINER:-nginx}"
NGINX_SERVICE="${NGINX_SERVICE:-nginx}"
NGINX_CONFIG_IN_CONTAINER="${NGINX_CONFIG_IN_CONTAINER:-/etc/nginx/nginx.conf}"
SYNAPSE_CONTAINER="${SYNAPSE_CONTAINER:-synapse}"
SYNAPSE_SERVICE="${SYNAPSE_SERVICE:-synapse}"
SYNAPSE_CONFIG_IN_CONTAINER="${SYNAPSE_CONFIG_IN_CONTAINER:-/data/homeserver.yaml}"
SYNAPSE_LOCAL_PORT="${SYNAPSE_LOCAL_PORT:-8008}"
BACKUP_BASE="${BACKUP_BASE:-$MATRIX_DIR/backups/disable-web-login}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)-$$"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ASSET_DIR="${ASSET_DIR:-$SCRIPT_DIR/../assets/app-only-public}"
NGINX_TOPOLOGY_AUDITOR="${NGINX_TOPOLOGY_AUDITOR:-$SCRIPT_DIR/verify-nginx-topology.py}"
SYNAPSE_RUNTIME_AUDITOR="${SYNAPSE_RUNTIME_AUDITOR:-$SCRIPT_DIR/verify-synapse-runtime.py}"
EXTERNAL_VANTAGE_AUDITOR="${EXTERNAL_VANTAGE_AUDITOR:-$SCRIPT_DIR/verify-external-vantage.sh}"
EXTERNAL_PROBE_SSH_TARGET="${EXTERNAL_PROBE_SSH_TARGET:-}"
EXTERNAL_PROBE_KNOWN_HOSTS="${EXTERNAL_PROBE_KNOWN_HOSTS:-}"
EXTERNAL_PROBE_EXPECTED_HOSTNAME="${EXTERNAL_PROBE_EXPECTED_HOSTNAME:-}"

MODE="report"
TRANSACTION_KIND=""
TRANSACTION_BACKUP=""
CONTAINER_CANDIDATE=""

log()  { printf '[+] %s\n' "$*"; }
ok()   { printf '[OK] %s\n' "$*"; }
warn() { printf '[!] %s\n' "$*" >&2; }
die()  { printf '[LỖI] %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Cách dùng:
  bash disable-web-login.sh                         # mặc định chỉ báo cáo
  REPORT=1 bash disable-web-login.sh                # chỉ báo cáo
  APPLY=1 bash disable-web-login.sh                 # backup, kiểm thử, áp dụng

ROLLBACK về Web/nginx cũ bị vô hiệu hóa theo policy app-only. Backup chỉ phục vụ
forensics/manual recovery bằng một candidate vẫn giữ static neutral và toàn bộ
Admin/login/register deny blocks.

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
validate_switch REPORT "$REPORT_VALUE"
validate_switch APPLY "$APPLY_VALUE"
validate_switch ROLLBACK "$ROLLBACK_VALUE"

SELECTED=$((REPORT_VALUE + APPLY_VALUE + ROLLBACK_VALUE))
(( SELECTED <= 1 )) || die "Chỉ được chọn một chế độ REPORT, APPLY hoặc ROLLBACK."
if (( APPLY_VALUE == 1 )); then
    MODE="apply"
elif (( ROLLBACK_VALUE == 1 )); then
    die "ROLLBACK về Web/nginx cũ bị khóa fail-closed; không được mở lại public auth/Web."
else
    MODE="report"
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

count_fixed() {
    local pattern="$1"
    local file="$2"
    local result
    result="$(grep -Fc -- "$pattern" "$file" || true)"
    printf '%s' "${result:-0}"
}

verify_effective_nginx_topology() {
    local allow_missing_admin="${1:-0}" snapshot
    [[ -f "$NGINX_TOPOLOGY_AUDITOR" && ! -L "$NGINX_TOPOLOGY_AUDITOR" ]] \
        || die "Thiếu nginx topology auditor đã review: $NGINX_TOPOLOGY_AUDITOR"
    snapshot="$(mktemp /tmp/securechat-nginx-T.XXXXXX)"
    if ! docker exec "$NGINX_CONTAINER" nginx -T >"$snapshot" 2>&1; then
        sed -n '1,80p' "$snapshot" >&2
        rm -f -- "$snapshot"
        die "Không lấy được effective nginx -T."
    fi
    if [[ "$allow_missing_admin" == "1" ]]; then
        python3 "$NGINX_TOPOLOGY_AUDITOR" --config "$snapshot" \
            --server-name "$SERVER_NAME" --allow-missing-admin
    else
        python3 "$NGINX_TOPOLOGY_AUDITOR" --config "$snapshot" \
            --server-name "$SERVER_NAME"
    fi
    rm -f -- "$snapshot"
}

verify_origin_is_loopback_only() {
    docker ps --format '{{.Names}}' | grep -qx "$SYNAPSE_CONTAINER" \
        || die "Container $SYNAPSE_CONTAINER không chạy."
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

report_state() {
    local old_root new_root old_mount new_mount well_known old_alias new_alias
    local matrix_refs synapse_proxy livekit_refs admin_block admin_refs
    old_root="$(count_re "^[[:space:]]*root[[:space:]]+$LEGACY_CONTAINER_ROOT[[:space:]]*;" "$NGINX_CONF")"
    new_root="$(count_re "^[[:space:]]*root[[:space:]]+$PUBLIC_CONTAINER_ROOT[[:space:]]*;" "$NGINX_CONF")"
    old_mount="$(count_fixed "./element-web:$LEGACY_CONTAINER_ROOT" "$COMPOSE_FILE")"
    new_mount="$(count_fixed "./securechat-public:$PUBLIC_CONTAINER_ROOT:ro" "$COMPOSE_FILE")"
    well_known="$(count_re 'location[[:space:]]*=[[:space:]]*/\.well-known/matrix/client' "$NGINX_CONF")"
    old_alias="$(count_fixed "$LEGACY_CONTAINER_ROOT/.well-known/matrix/client" "$NGINX_CONF")"
    new_alias="$(count_fixed "$PUBLIC_CONTAINER_ROOT/.well-known/matrix/client" "$NGINX_CONF")"
    matrix_refs="$(count_fixed '/_matrix' "$NGINX_CONF")"
    synapse_proxy="$(count_re 'proxy_pass[[:space:]]+http://synapse:8008' "$NGINX_CONF")"
    livekit_refs="$(count_re 'livekit|LiveKit|:7880|:8080' "$NGINX_CONF")"
    admin_block="$(count_re 'location[[:space:]]+\^~[[:space:]]+/_synapse/admin/[[:space:]]*\{' "$NGINX_CONF")"
    admin_refs="$(count_fixed '/_synapse/admin/' "$NGINX_CONF")"

    printf '\n=== Báo cáo bề mặt Web (không sửa hệ thống) ===\n'
    printf 'nginx.conf:                 %s\n' "$NGINX_CONF"
    printf 'Docker Compose:             %s\n' "$COMPOSE_FILE"
    printf 'root Web cũ / root neutral: %s / %s\n' "$old_root" "$new_root"
    printf 'mount Web cũ / neutral:     %s / %s\n' "$old_mount" "$new_mount"
    printf 'location .well-known:       %s\n' "$well_known"
    printf 'alias .well-known cũ/mới:   %s / %s\n' "$old_alias" "$new_alias"
    printf 'tham chiếu Matrix/proxy:    %s / %s\n' "$matrix_refs" "$synapse_proxy"
    printf 'tham chiếu LiveKit:         %s\n' "$livekit_refs"
    printf 'chặn Admin API/ref khác:    %s / %s\n' "$admin_block" "$((admin_refs - admin_block))"

    if docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" config -q >/dev/null 2>&1; then
        ok "Docker Compose hiện tại hợp lệ"
        if docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" config --services \
            | grep -qx "$NGINX_SERVICE"; then
            ok "Nhận diện được service Compose '$NGINX_SERVICE'"
        else
            warn "Không thấy service Compose '$NGINX_SERVICE'. Không được APPLY."
        fi
    else
        warn "Docker Compose hiện tại không qua được 'config -q'. Không được APPLY."
    fi
    if docker ps --format '{{.Names}}' | grep -qx "$NGINX_CONTAINER"; then
        if docker exec "$NGINX_CONTAINER" nginx -t >/dev/null 2>&1; then
            ok "nginx đang chạy và cấu hình hiện tại hợp lệ"
        else
            warn "nginx -t hiện tại thất bại. Không được APPLY."
        fi
    else
        warn "Container $NGINX_CONTAINER không chạy. Không được APPLY."
    fi

    if (( old_root == 1 && new_root == 0 )); then
        warn "Root / vẫn trỏ tới mã Web cũ; giao diện đăng nhập Web chưa được gỡ."
    elif (( old_root == 0 && new_root == 1 )); then
        ok "Root / đã trỏ tới static root trung tính"
    else
        warn "Không nhận diện duy nhất root Web; script sẽ fail-closed khi APPLY."
    fi

    if (( matrix_refs > 0 && synapse_proxy > 0 )); then
        ok "Có proxy Matrix cần được giữ nguyên"
    else
        warn "Không nhận diện được proxy Matrix chuẩn; script sẽ không APPLY."
    fi
    if (( well_known != 1 || (old_alias + new_alias) != 1 )); then
        warn "Không nhận diện duy nhất location/alias .well-known; cần snapshot nginx thật để rà soát."
    fi
    if (( admin_block == 1 && admin_refs == 1 )); then
        ok "Có một location dành riêng để chặn Admin API công khai"
    elif (( admin_block == 0 && admin_refs == 0 )); then
        warn "Admin API chưa có location chặn; APPLY sẽ chỉ thêm nếu xác định duy nhất server block public."
    else
        warn "Có topology Admin API không kỳ vọng; script sẽ fail-closed khi APPLY."
    fi
    if (verify_origin_is_loopback_only) >/dev/null 2>&1; then
        ok "Origin Synapse 8008 chỉ publish trên loopback"
    else
        warn "Không chứng minh được origin 8008 chỉ loopback; APPLY sẽ từ chối."
    fi

    printf '\n'
    warn "Phạm vi: bước này gỡ UI Web và chặn Admin API qua public nginx. Endpoint Matrix login vẫn phải được khóa bằng module xác thực app-only ở bước kế tiếp."
}

validate_assets() {
    [[ -d "$ASSET_DIR" && ! -L "$ASSET_DIR" ]] \
        || die "Không thấy asset bundle trung tính: $ASSET_DIR"
    [[ -f "$ASSET_DIR/index.html" && ! -L "$ASSET_DIR/index.html" ]] \
        || die "Thiếu index.html thật (không chấp nhận symlink)."
    [[ -f "$ASSET_DIR/.well-known/matrix/client" \
        && ! -L "$ASSET_DIR/.well-known/matrix/client" ]] \
        || die "Thiếu .well-known/matrix/client thật (không chấp nhận symlink)."
    if find "$ASSET_DIR" -type l -print -quit | grep -q .; then
        die "Asset bundle có symlink; từ chối để tránh copy ngoài phạm vi."
    fi
    local file_count
    file_count="$(find "$ASSET_DIR" -type f | wc -l | tr -d '[:space:]')"
    [[ "$file_count" == "2" ]] \
        || die "Asset bundle phải có đúng 2 file; hiện có $file_count."
    if grep -RniE 'element|vector|riot|matrix\.org|vector\.im' "$ASSET_DIR" >/dev/null; then
        die "Asset neutral còn chứa thương hiệu/domain bị cấm."
    fi
    grep -Fq 'id="securechat-app-only"' "$ASSET_DIR/index.html" \
        || die "index.html thiếu marker securechat-app-only."
    if grep -Eqi '<(form|input|script|style|link)([[:space:]>])|javascript:|on[a-z]+[[:space:]]*=' \
        "$ASSET_DIR/index.html"; then
        die "Trang neutral không được chứa form, input, script, style, link hoặc event handler."
    fi
    python3 - "$ASSET_DIR/.well-known/matrix/client" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    value = json.load(handle)
expected = {
    "m.homeserver": {"base_url": "https://chat.securechat.com.au"},
    "org.matrix.msc4143.rtc_foci": [
        {
            "type": "livekit",
            "livekit_service_url": "https://chat.securechat.com.au/livekit/jwt",
        }
    ],
}
if value != expected:
    raise SystemExit(".well-known/matrix/client không đúng giá trị SecureChat cố định")
PY
    ok "Asset neutral hợp lệ, không có form đăng nhập hoặc branding cũ"
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
    CONTAINER_CANDIDATE="/tmp/securechat-nginx-$STAMP.conf"
    docker cp "$candidate" "$NGINX_CONTAINER:$CONTAINER_CANDIDATE" >/dev/null
    if ! docker exec "$NGINX_CONTAINER" nginx -t -c "$CONTAINER_CANDIDATE" >/dev/null 2>&1; then
        docker exec "$NGINX_CONTAINER" nginx -t -c "$CONTAINER_CANDIDATE" || true
        die "Candidate nginx không hợp lệ trong đúng container đang chạy."
    fi
    docker exec "$NGINX_CONTAINER" rm -f -- "$CONTAINER_CANDIDATE"
    CONTAINER_CANDIDATE=""
    ok "Candidate nginx qua nginx -t trong container hiện tại"
}

restore_apply_failure() {
    local backup="$1"
    warn "Áp dụng lỗi; không khôi phục Web/nginx cũ vì có thể mở lại public surface."
    if [[ -d "$backup/candidate/public" ]]; then
        if [[ -e "$PUBLIC_HOST_ROOT" ]]; then
            mv -- "$PUBLIC_HOST_ROOT" "$backup/failed-current-public-$STAMP"
        fi
        mv -- "$backup/candidate/public" "$PUBLIC_HOST_ROOT"
    fi
    atomic_install_file "$backup/candidate/nginx.conf" "$NGINX_CONF"
    atomic_install_file "$backup/candidate/docker-compose.yml" "$COMPOSE_FILE"
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" \
        up -d --no-deps --force-recreate "$NGINX_SERVICE" >/dev/null 2>&1 || true
    warn "Đã thử giữ candidate neutral/Admin-closed. Bắt buộc mở incident và kiểm tra nginx -t, Admin, Matrix/LiveKit."
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
    fi
    exit "$status"
}
trap on_exit EXIT

build_backup_and_candidates() {
    local backup="$1"
    install -d -m 0700 "$backup/original" "$backup/candidate/public"
    cp -a -- "$NGINX_CONF" "$backup/original/nginx.conf"
    cp -a -- "$COMPOSE_FILE" "$backup/original/docker-compose.yml"
    if [[ -e "$PUBLIC_HOST_ROOT" ]]; then
        [[ -d "$PUBLIC_HOST_ROOT" && ! -L "$PUBLIC_HOST_ROOT" ]] \
            || die "Static root hiện tại không phải thư mục thật: $PUBLIC_HOST_ROOT"
        cp -a -- "$PUBLIC_HOST_ROOT" "$backup/original/securechat-public"
        : > "$backup/original/HAD_PUBLIC_ROOT"
    fi
    cp -a -- "$ASSET_DIR/." "$backup/candidate/public/"
    find "$backup/candidate/public" -type d -exec chmod 0755 {} +
    find "$backup/candidate/public" -type f -exec chmod 0644 {} +

    python3 - "$NGINX_CONF" "$backup/candidate/nginx.conf" \
        "$LEGACY_CONTAINER_ROOT" "$PUBLIC_CONTAINER_ROOT" "$SERVER_NAME" <<'PY'
import re
import sys

source, target, legacy_root, public_root, server_name = sys.argv[1:]
with open(source, encoding="utf-8") as handle:
    original = handle.read()


def structural_text(value: str) -> str:
    """Hide comments/quoted text while preserving byte positions for brace scans."""

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
        raise SystemExit("Fail-closed: nginx.conf có chuỗi quoted chưa đóng")
    return "".join(result)


def server_blocks(value: str) -> list[tuple[int, int]]:
    structure = structural_text(value)
    blocks: list[tuple[int, int]] = []
    for match in re.finditer(r"(?m)^[ \t]*server[ \t]*\{", structure):
        opening = structure.find("{", match.start(), match.end())
        depth = 0
        for position in range(opening, len(structure)):
            if structure[position] == "{":
                depth += 1
            elif structure[position] == "}":
                depth -= 1
                if depth == 0:
                    blocks.append((match.start(), position + 1))
                    break
        else:
            raise SystemExit("Fail-closed: server block nginx chưa đóng")
    return blocks


def public_server_block(value: str, root_position: int) -> tuple[int, int]:
    matches = [
        bounds
        for bounds in server_blocks(value)
        if bounds[0] <= root_position < bounds[1]
    ]
    if len(matches) != 1:
        raise SystemExit(
            "Fail-closed: root Web không nằm trong duy nhất một server block"
        )
    start, end = matches[0]
    structure = structural_text(value[start:end])
    names = re.findall(r"(?m)^[ \t]*server_name[ \t]+([^;]+);", structure)
    if len(names) != 1 or server_name not in names[0].split():
        raise SystemExit(
            f"Fail-closed: server block của root không có duy nhất server_name {server_name}"
        )
    if not re.search(r"(?m)^[ \t]*listen[ \t]+(?:\[[^]]+\]:)?443(?:[ \t;])", structure):
        raise SystemExit(
            "Fail-closed: server block của root không nhận diện được listener HTTPS 443"
        )
    return start, end

old_root_re = re.compile(
    rf"^(?P<indent>[ \t]*)root[ \t]+{re.escape(legacy_root)}[ \t]*;",
    re.MULTILINE,
)
new_root_re = re.compile(
    rf"^[ \t]*root[ \t]+{re.escape(public_root)}[ \t]*;",
    re.MULTILINE,
)
old_roots = len(old_root_re.findall(original))
new_roots = len(new_root_re.findall(original))
if (old_roots, new_roots) == (1, 0):
    candidate = old_root_re.sub(
        lambda match: f"{match.group('indent')}root  {public_root};",
        original,
        count=1,
    )
elif (old_roots, new_roots) == (0, 1):
    candidate = original
else:
    raise SystemExit(
        f"Fail-closed: cần đúng 1 root cũ hoặc 1 root neutral; thấy {old_roots}/{new_roots}"
    )

well_known_locations = len(re.findall(
    r"location[ \t]*=[ \t]*/\.well-known/matrix/client", candidate
))
old_alias = f"{legacy_root}/.well-known/matrix/client"
new_alias = f"{public_root}/.well-known/matrix/client"
old_aliases = candidate.count(old_alias)
new_aliases = candidate.count(new_alias)
if well_known_locations != 1:
    raise SystemExit(
        "Fail-closed: cần đúng 1 location = /.well-known/matrix/client; "
        f"thấy {well_known_locations}"
    )
if (old_aliases, new_aliases) == (1, 0):
    candidate = candidate.replace(old_alias, new_alias, 1)
elif (old_aliases, new_aliases) != (0, 1):
    raise SystemExit(
        "Fail-closed: location .well-known không dùng alias cũ/mới duy nhất; "
        "cần rà soát nginx.conf thật"
    )

# Chỉ chặn Admin API ở reverse proxy public. Port Synapse localhost:8008 không
# bị sửa, để quản trị nội bộ tiếp tục dùng được. Anchor là root duy nhất của
# đúng server_name HTTPS; nếu topology khác, dừng thay vì đoán server block.
root_match = new_root_re.search(candidate)
if root_match is None:
    raise SystemExit("Candidate không tìm thấy root neutral để đặt Admin API block")
public_start, public_end = public_server_block(candidate, root_match.start())
structure = structural_text(candidate)
includes = re.findall(r"(?m)^[ \t]*include[ \t]+([^;]+);", structure)
unexpected_includes = [item.strip() for item in includes if item.strip() != "/etc/nginx/mime.types"]
if unexpected_includes:
    raise SystemExit(
        "Fail-closed: nginx có include ngoài mime.types; cần review nginx -T thật"
    )
if re.search(r"(?m)^[ \t]*upstream[ \t]+[^\n{]+\{", structure):
    raise SystemExit("Fail-closed: upstream alias chưa được review")
for start, end in server_blocks(candidate):
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
admin_path_refs = candidate.count("/_synapse/admin/")
admin_block_re = re.compile(
    r"(?m)^(?P<indent>[ \t]*)location[ \t]+\^~[ \t]+/_synapse/admin/[ \t]*\{"
    r"[ \t]*(?:\n[ \t]*)?return[ \t]+404;[ \t]*(?:\n[ \t]*)?\}"
)
admin_matches = list(admin_block_re.finditer(candidate))
if admin_path_refs == 0 and not admin_matches:
    indent = root_match.group(0)[: len(root_match.group(0)) - len(root_match.group(0).lstrip())]
    admin_block = (
        f"{indent}# --- SecureChat: block public Synapse Admin API ---\n"
        f"{indent}location ^~ /_synapse/admin/ {{\n"
        f"{indent}    return 404;\n"
        f"{indent}}}\n"
        f"{indent}# --- end SecureChat public Admin API block ---\n\n"
    )
    candidate = candidate[:root_match.start()] + admin_block + candidate[root_match.start():]
elif admin_path_refs == 1 and len(admin_matches) == 1:
    if not (public_start <= admin_matches[0].start() < public_end):
        raise SystemExit(
            "Fail-closed: Admin API block hiện có không nằm trong server block public"
        )
else:
    raise SystemExit(
        "Fail-closed: có location/tham chiếu /_synapse/admin/ không đúng exact block return 404"
    )

root_match = new_root_re.search(candidate)
if root_match is None:
    raise SystemExit("Candidate mất root neutral sau khi thêm Admin API block")
public_start, public_end = public_server_block(candidate, root_match.start())
admin_matches = list(admin_block_re.finditer(candidate))
if (
    candidate.count("/_synapse/admin/") != 1
    or len(admin_matches) != 1
    or not (public_start <= admin_matches[0].start() < public_end)
):
    raise SystemExit("Candidate không có duy nhất exact Admin API block trong server public")

if legacy_root in candidate:
    raise SystemExit(
        f"Fail-closed: nginx candidate vẫn tham chiếu {legacy_root}; không được làm lộ Web cũ"
    )
if len(new_root_re.findall(candidate)) != 1 or candidate.count(new_alias) != 1:
    raise SystemExit("Candidate không có duy nhất root/alias neutral")
if "/_matrix" not in original or "proxy_pass http://synapse:8008" not in original:
    raise SystemExit("Fail-closed: không nhận diện proxy Matrix/Synapse chuẩn")
if original.count("proxy_pass") != candidate.count("proxy_pass"):
    raise SystemExit("Số proxy_pass đã đổi ngoài dự kiến")
if original.lower().count("livekit") != candidate.lower().count("livekit"):
    raise SystemExit("Tham chiếu LiveKit đã đổi ngoài dự kiến")

with open(target, "w", encoding="utf-8") as handle:
    handle.write(candidate)
PY

    python3 - "$COMPOSE_FILE" "$backup/candidate/docker-compose.yml" \
        "$LEGACY_CONTAINER_ROOT" "$PUBLIC_CONTAINER_ROOT" <<'PY'
import re
import sys

source, target, legacy_root, public_root = sys.argv[1:]
with open(source, encoding="utf-8") as handle:
    original = handle.read()

new_mount = f"./securechat-public:{public_root}:ro"
new_count = original.count(new_mount)
if new_count == 1:
    candidate = original
elif new_count == 0:
    pattern = re.compile(
        rf"^(?P<indent>[ \t]*)-[ \t]+\./element-web:{re.escape(legacy_root)}(?P<mode>:[^ \t#]+)?[ \t]*$",
        re.MULTILINE,
    )
    matches = list(pattern.finditer(original))
    if len(matches) != 1:
        raise SystemExit(
            "Fail-closed: không thấy duy nhất bind-mount './element-web:/var/www/element-web'; "
            "không đoán Docker Compose thật"
        )
    match = matches[0]
    addition = f"{match.group('indent')}- {new_mount}"
    candidate = original[:match.end()] + "\n" + addition + original[match.end():]
else:
    raise SystemExit(f"Fail-closed: mount neutral xuất hiện {new_count} lần")

if candidate.count(new_mount) != 1:
    raise SystemExit("Candidate không có duy nhất bind-mount static neutral")
if original.lower().count("livekit") != candidate.lower().count("livekit"):
    raise SystemExit("Dịch vụ LiveKit trong Compose đã đổi ngoài dự kiến")

with open(target, "w", encoding="utf-8") as handle:
    handle.write(candidate)
PY

    printf 'backup_id=%s\nserver_name=%s\n' "$(basename -- "$backup")" "$SERVER_NAME" \
        > "$backup/MANIFEST"
    ok "Đã tạo backup và candidate trong $backup"
}

validate_candidates() {
    local backup="$1"
    docker compose --project-directory "$MATRIX_DIR" \
        -f "$backup/candidate/docker-compose.yml" config -q >/dev/null
    ok "Candidate Docker Compose qua config -q"
    test_nginx_candidate "$backup/candidate/nginx.conf"
    if grep -RniE 'element|vector|riot|matrix\.org|vector\.im' \
        "$backup/candidate/public" >/dev/null; then
        die "Candidate public còn branding/domain bị cấm."
    fi
}

curl_local() {
    curl --silent --show-error --fail \
        --connect-timeout 5 --max-time 20 \
        --noproxy '*' \
        --resolve "$SERVER_NAME:443:127.0.0.1" "$@"
}

verify_after_apply() {
    local backup="$1"
    curl_local "https://$SERVER_NAME/_matrix/client/versions" \
        -o "$backup/verify-matrix-versions.json"
    python3 - "$backup/verify-matrix-versions.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    value = json.load(handle)
if not isinstance(value.get("versions"), list) or not value["versions"]:
    raise SystemExit("/_matrix/client/versions không trả danh sách versions")
PY
    ok "Matrix client API vẫn hoạt động"

    curl_local "https://$SERVER_NAME/.well-known/matrix/client" \
        -o "$backup/verify-well-known.json"
    python3 - "$backup/verify-well-known.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    value = json.load(handle)
expected = {
    "m.homeserver": {"base_url": "https://chat.securechat.com.au"},
    "org.matrix.msc4143.rtc_foci": [
        {
            "type": "livekit",
            "livekit_service_url": "https://chat.securechat.com.au/livekit/jwt",
        }
    ],
}

verify_external_vantage() {
    [[ -f "$EXTERNAL_VANTAGE_AUDITOR" && ! -L "$EXTERNAL_VANTAGE_AUDITOR" ]] \
        || die "Thiếu external-vantage auditor: $EXTERNAL_VANTAGE_AUDITOR"
    EXTERNAL_PROBE_SSH_TARGET="$EXTERNAL_PROBE_SSH_TARGET" \
    EXTERNAL_PROBE_KNOWN_HOSTS="$EXTERNAL_PROBE_KNOWN_HOSTS" \
    EXTERNAL_PROBE_EXPECTED_HOSTNAME="$EXTERNAL_PROBE_EXPECTED_HOSTNAME" \
        bash "$EXTERNAL_VANTAGE_AUDITOR" web-neutral "$SERVER_NAME"
    ok "Vantage host độc lập xác nhận DNS/CDN/LB public đã gỡ Web/Admin"
}
if value != expected:
    raise SystemExit(".well-known public không đúng giá trị kỳ vọng")
PY
    ok ".well-known trỏ đúng SecureChat homeserver và giữ LiveKit RTC focus"

    local public_admin_code local_admin_code
    public_admin_code="$(curl --silent --show-error \
        --connect-timeout 5 --max-time 20 \
        --noproxy '*' \
        --resolve "$SERVER_NAME:443:127.0.0.1" \
        -o "$backup/verify-public-admin-response.txt" -w '%{http_code}' \
        "https://$SERVER_NAME/_synapse/admin/v1/server_version" || true)"
    [[ "$public_admin_code" == "404" ]] \
        || die "Admin API public chưa bị chặn chính xác (HTTP ${public_admin_code:-000})."
    ok "Admin API qua public nginx trả 404"

    local_admin_code="$(curl --silent --show-error \
        --connect-timeout 5 --max-time 20 \
        --noproxy '*' \
        -o "$backup/verify-local-admin-server-version.json" -w '%{http_code}' \
        "http://127.0.0.1:$SYNAPSE_LOCAL_PORT/_synapse/admin/v1/server_version" || true)"
    [[ "$local_admin_code" == "200" ]] \
        || die "Admin API nội bộ localhost:8008 không còn phản hồi 200 (HTTP ${local_admin_code:-000})."
    ok "Admin API nội bộ localhost:8008 vẫn hoạt động"

    curl_local "https://$SERVER_NAME/" -o "$backup/verify-root.html"
    curl_local "https://$SERVER_NAME/login" -o "$backup/verify-login-path.html"
    grep -Fq 'id="securechat-app-only"' "$backup/verify-root.html" \
        || die "Root / không trả trang app-only neutral."
    grep -Fq 'id="securechat-app-only"' "$backup/verify-login-path.html" \
        || die "/login không trả trang app-only neutral."
    if grep -Eqi 'element|vector|riot|<(form|input|script|style|link)([[:space:]>])|javascript:|on[a-z]+[[:space:]]*=' \
        "$backup/verify-root.html" "$backup/verify-login-path.html"; then
        die "Trang public còn branding cũ hoặc thành phần active/style không được phép."
    fi
    ok "Root / và /login không còn giao diện đăng nhập Web"

    if grep -Fq '/livekit/sfu/' "$backup/original/nginx.conf"; then
        local sfu_code
        sfu_code="$(curl --silent --show-error --connect-timeout 5 --max-time 20 \
            --noproxy '*' \
            --resolve "$SERVER_NAME:443:127.0.0.1" -o /dev/null -w '%{http_code}' \
            "https://$SERVER_NAME/livekit/sfu/" || true)"
        case "$sfu_code" in
            000|502|503|504|'') die "Proxy LiveKit SFU lỗi sau rollout (HTTP ${sfu_code:-000})." ;;
            *) ok "Proxy LiveKit SFU còn phản hồi (HTTP $sfu_code)" ;;
        esac
    fi
    if grep -Fq '/livekit/jwt/healthz' "$backup/original/nginx.conf"; then
        local jwt_code
        jwt_code="$(curl --silent --show-error --connect-timeout 5 --max-time 20 \
            --noproxy '*' \
            --resolve "$SERVER_NAME:443:127.0.0.1" -o /dev/null -w '%{http_code}' \
            "https://$SERVER_NAME/livekit/jwt/healthz" || true)"
        [[ "$jwt_code" == "200" ]] \
            || die "LiveKit JWT healthz không còn 200 (HTTP ${jwt_code:-000})."
        ok "LiveKit JWT healthz còn hoạt động"
    fi
}

apply_change() {
    validate_assets
    verify_origin_is_loopback_only
    docker ps --format '{{.Names}}' | grep -qx "$NGINX_CONTAINER" \
        || die "Container $NGINX_CONTAINER không chạy."
    docker exec "$NGINX_CONTAINER" nginx -t >/dev/null
    verify_effective_nginx_topology 1
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" config -q >/dev/null
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" config --services \
        | grep -qx "$NGINX_SERVICE" \
        || die "Không thấy service Compose '$NGINX_SERVICE'; dừng trước thay đổi."

    install -d -m 0700 "$BACKUP_BASE"
    local backup="$BACKUP_BASE/$STAMP"
    [[ ! -e "$backup" ]] || die "Backup ID đã tồn tại: $backup"
    build_backup_and_candidates "$backup"
    validate_candidates "$backup"

    log "Cài static root neutral trước khi đổi nginx..."
    TRANSACTION_KIND="apply"
    TRANSACTION_BACKUP="$backup"
    if [[ -e "$PUBLIC_HOST_ROOT" ]]; then
        mv -- "$PUBLIC_HOST_ROOT" "$backup/replaced-static-root"
    fi
    mv -- "$backup/candidate/public" "$PUBLIC_HOST_ROOT"

    atomic_install_file "$backup/candidate/docker-compose.yml" "$COMPOSE_FILE"
    atomic_install_file "$backup/candidate/nginx.conf" "$NGINX_CONF"
    docker compose --project-directory "$MATRIX_DIR" -f "$COMPOSE_FILE" \
        up -d --no-deps --force-recreate "$NGINX_SERVICE" >/dev/null
    docker exec "$NGINX_CONTAINER" nginx -t >/dev/null
    verify_after_apply "$backup"
    verify_effective_nginx_topology 0
    verify_external_vantage

    : > "$backup/APPLIED"
    TRANSACTION_KIND=""
    ok "Đã gỡ giao diện Web. Backup ID: $(basename -- "$backup")"
    warn "Chưa phải enforcement app-only: chưa chặn Matrix login API trước khi module xác thực mới, migration và APK giữ session sẵn sàng."
}

case "$MODE" in
    report) report_state ;;
    apply) apply_change ;;
    *) die "Mode nội bộ không hợp lệ: $MODE" ;;
esac
