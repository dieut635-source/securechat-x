#!/usr/bin/env bash
# Rà soát CHỈ-ĐỌC trước khi bật chính sách "một tài khoản - một thiết bị".
# Không in mật khẩu, access token, private key, địa chỉ IP hay secret cấu hình.
set -euo pipefail

SYNAPSE_CONTAINER="${SYNAPSE_CONTAINER:-synapse}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-postgres}"
HOMESERVER_CONFIG="${HOMESERVER_CONFIG:-/data/homeserver.yaml}"
EXPECTED_SYNAPSE_VERSION="${EXPECTED_SYNAPSE_VERSION:-1.159.0}"

fail() {
    printf '[LỖI] %s\n' "$*" >&2
    exit 1
}

section() {
    printf '\n=== %s ===\n' "$*"
}

command -v docker >/dev/null 2>&1 || fail "Không tìm thấy docker."
docker ps --format '{{.Names}}' | grep -qx "$SYNAPSE_CONTAINER" \
    || fail "Container $SYNAPSE_CONTAINER không chạy."

section "Phiên bản và cấu hình xác thực (đã lọc bí mật)"
docker exec "$SYNAPSE_CONTAINER" python -c \
    'import synapse,sys; print("Synapse:", synapse.__version__); sys.exit(0 if synapse.__version__ == sys.argv[1] else 2)' \
    "$EXPECTED_SYNAPSE_VERSION" \
    || fail "Phiên bản Synapse không đúng baseline $EXPECTED_SYNAPSE_VERSION; không áp dụng parser này."
AUTH_AUDIT_STATUS=0
docker exec -i "$SYNAPSE_CONTAINER" python - "$HOMESERVER_CONFIG" <<'PY' \
    || AUTH_AUDIT_STATUS=$?
import sys
import yaml

path = sys.argv[1]
unknown = []
policy_blockers = []


def mark_unknown(name):
    if name not in unknown:
        unknown.append(name)
    return None


def mapping(value, name, *, none_is_empty=True):
    if value is None and none_is_empty:
        return {}
    if not isinstance(value, dict):
        mark_unknown(name)
        return {}
    return value


def strict_bool(container, key, default, name):
    if key not in container:
        return default
    value = container[key]
    if type(value) is bool:
        return value
    return mark_unknown(name)


def synapse_strtobool(value, name):
    normalized = str(value).lower()
    if normalized in ("y", "yes", "t", "true", "on", "1"):
        return True
    if normalized in ("n", "no", "f", "false", "off", "0"):
        return False
    return mark_unknown(name)


def state(value):
    if value is None:
        return "KHÔNG RÕ (fail-closed)"
    return "BẬT" if value else "TẮT"


try:
    with open(path, encoding="utf-8") as handle:
        loaded = yaml.safe_load(handle)
except Exception:
    print("AUDIT_STATUS: KHÔNG RÕ — không parse được YAML (không in chi tiết để tránh lộ secret)")
    raise SystemExit(2)
if loaded is None:
    config = {}
elif isinstance(loaded, dict):
    config = loaded
else:
    print("AUDIT_STATUS: KHÔNG RÕ — homeserver config không phải mapping")
    raise SystemExit(2)

database = mapping(config.get("database"), "database")
password = mapping(config.get("password_config"), "password_config")
experimental = mapping(config.get("experimental_features"), "experimental_features")
login_existing = mapping(
    config.get("login_via_existing_session"), "login_via_existing_session"
)
jwt = mapping(config.get("jwt_config"), "jwt_config")
mas = mapping(config.get("matrix_authentication_service"), "matrix_authentication_service")
saml = mapping(config.get("saml2_config"), "saml2_config")
cas = mapping(config.get("cas_config"), "cas_config")
legacy_oidc = mapping(config.get("oidc_config"), "oidc_config")
ldap = mapping(config.get("ldap_config"), "ldap_config")

mas_enabled = strict_bool(mas, "enabled", False, "matrix_authentication_service.enabled")
login_existing_enabled = strict_bool(
    login_existing, "enabled", False, "login_via_existing_session.enabled"
)
jwt_enabled = strict_bool(jwt, "enabled", False, "jwt_config.enabled")
legacy_oidc_enabled = strict_bool(
    legacy_oidc, "enabled", False, "oidc_config.enabled"
)
ldap_enabled = strict_bool(ldap, "enabled", False, "ldap_config.enabled")
cas_enabled_flag = strict_bool(cas, "enabled", True, "cas_config.enabled")
cas_enabled = False if not cas else cas_enabled_flag
saml_enabled_flag = strict_bool(saml, "enabled", True, "saml2_config.enabled")
if not saml:
    saml_enabled = False
elif saml_enabled_flag is None:
    saml_enabled = None
elif not saml_enabled_flag:
    saml_enabled = False
else:
    # Synapse 1.159.0 only enables SAML when provider configuration exists.
    saml_enabled = bool(saml.get("sp_config") or saml.get("config_path"))

if "disable_registration" in config:
    disabled = synapse_strtobool(
        config["disable_registration"], "disable_registration"
    )
    public_registration = None if disabled is None else not disabled
else:
    public_registration = synapse_strtobool(
        config.get("enable_registration", False), "enable_registration"
    )

guest_access = strict_bool(config, "allow_guest_access", False, "allow_guest_access")
registration_without_verification = strict_bool(
    config,
    "enable_registration_without_verification",
    False,
    "enable_registration_without_verification",
)

password_raw = password.get(
    "enabled", False if mas_enabled is True else True if mas_enabled is False else None
)
if password_raw == "only_for_reauth":
    password_login_enabled = False
    password_reauth_enabled = True
elif type(password_raw) is bool:
    password_login_enabled = password_raw
    password_reauth_enabled = password_raw
else:
    password_login_enabled = mark_unknown("password_config.enabled")
    password_reauth_enabled = None
password_localdb = strict_bool(
    password, "localdb_enabled", True, "password_config.localdb_enabled"
)

oidc_providers_raw = config.get("oidc_providers", [])
if oidc_providers_raw is None:
    oidc_providers_raw = []
if not isinstance(oidc_providers_raw, list) or not all(
    isinstance(item, dict) for item in oidc_providers_raw
):
    mark_unknown("oidc_providers")
    oidc_provider_count = None
else:
    # New-style providers are active by presence; they have no top-level
    # enabled switch in Synapse 1.159.0.
    oidc_provider_count = len(oidc_providers_raw)

appservice_files = config.get("app_service_config_files", [])
if appservice_files is None:
    appservice_files = []
if not isinstance(appservice_files, list):
    mark_unknown("app_service_config_files")
    appservice_count = None
else:
    appservice_count = len(appservice_files)

password_providers = config.get("password_providers", [])
if password_providers is None:
    password_providers = []
if not isinstance(password_providers, list):
    mark_unknown("password_providers")
    password_provider_count = None
else:
    password_provider_count = len(password_providers)

modules = config.get("modules", [])
if modules is None:
    modules = []
if not isinstance(modules, list):
    mark_unknown("modules")
    modules = []

expected_module_name = (
    "securechat_single_device.module.SecureChatSingleDeviceModule"
)
module_shapes_known = all(
    isinstance(item, dict) and isinstance(item.get("module"), str)
    for item in modules
)
if not module_shapes_known:
    mark_unknown("modules entries")
guard_config_valid = modules in (
    [{"module": expected_module_name, "config": {"mode": "audit"}}],
    [{"module": expected_module_name, "config": {"mode": "enforce"}}],
)
if len(modules) != 1 or not guard_config_valid:
    policy_blockers.append("SC_AUDIT_MODULE_ALLOWLIST_MISMATCH")

msc3861 = experimental.get("msc3861", {})
if msc3861 is None:
    msc3861 = {}
if not isinstance(msc3861, dict):
    mark_unknown("experimental_features.msc3861")
    msc3861_state = "KHÔNG RÕ (fail-closed)"
elif msc3861:
    # Synapse 1.159.0 rejects every non-empty legacy MSC3861 mapping.
    msc3861_state = "KHÔNG HỢP LỆ trên Synapse 1.159.0"
    policy_blockers.append("SC_AUDIT_LEGACY_MSC3861_CONFIG_PRESENT")
else:
    msc3861_state = "TẮT/không cấu hình"

database_name = database.get("name", "<không rõ>")
if not isinstance(database_name, str):
    mark_unknown("database.name")
    database_name = "<không rõ>"

print("Database:", database_name)
print("Đăng ký công khai:", state(public_registration))
print("Guest registration/access:", state(guest_access))
print("Registration without verification:", state(registration_without_verification))
print("Login qua session có sẵn:", state(login_existing_enabled))
print("Password login:", state(password_login_enabled))
print("Password re-auth:", state(password_reauth_enabled))
print("Password localdb_enabled:", state(password_localdb))
print("Password-provider modules:", password_provider_count if password_provider_count is not None else "KHÔNG RÕ")
print("JWT login:", state(jwt_enabled))
print("OIDC providers (new style):", oidc_provider_count if oidc_provider_count is not None else "KHÔNG RÕ")
print("OIDC legacy enabled:", state(legacy_oidc_enabled))
print("LDAP legacy enabled:", state(ldap_enabled))
print("SAML enabled:", state(saml_enabled))
print("CAS enabled:", state(cas_enabled))
print("Application-service configs:", appservice_count if appservice_count is not None else "KHÔNG RÕ")
print("Shared-secret registration configured:", "CÓ" if config.get("registration_shared_secret") or config.get("registration_shared_secret_path") else "KHÔNG")
print("MAS/OAuth delegation:", state(mas_enabled))
print("Legacy MSC3861:", msc3861_state)
print(
    "Session/token lifetimes configured:",
    "CÓ"
    if any(
        config.get(name) is not None
        for name in (
            "session_lifetime",
            "refreshable_access_token_lifetime",
            "nonrefreshable_access_token_lifetime",
        )
    )
    else "KHÔNG",
)
print("Modules:")
if modules:
    for item in modules:
        if isinstance(item, dict):
            print("  -", item.get("module", "<không rõ>"))
        else:
            print("  - <định dạng không rõ>")
else:
    print("  - <không có>")

for condition, blocker in (
    (public_registration is True, "SC_AUDIT_PUBLIC_REGISTRATION_ENABLED"),
    (guest_access is True, "SC_AUDIT_GUEST_ACCESS_ENABLED"),
    (
        registration_without_verification is True,
        "SC_AUDIT_REGISTRATION_WITHOUT_VERIFICATION_ENABLED",
    ),
    (login_existing_enabled is True, "SC_AUDIT_LOGIN_VIA_SESSION_ENABLED"),
    (password_login_enabled is False, "SC_AUDIT_LOCAL_PASSWORD_LOGIN_DISABLED"),
    (password_localdb is False, "SC_AUDIT_LOCAL_PASSWORD_DB_DISABLED"),
    (bool(password_provider_count), "SC_AUDIT_PASSWORD_PROVIDER_PRESENT"),
    (ldap_enabled is True, "SC_AUDIT_LDAP_ENABLED"),
    (jwt_enabled is True, "SC_AUDIT_JWT_ENABLED"),
    (bool(oidc_provider_count), "SC_AUDIT_OIDC_PROVIDER_PRESENT"),
    (legacy_oidc_enabled is True, "SC_AUDIT_LEGACY_OIDC_ENABLED"),
    (saml_enabled is True, "SC_AUDIT_SAML_ENABLED"),
    (cas_enabled is True, "SC_AUDIT_CAS_ENABLED"),
    (bool(appservice_count), "SC_AUDIT_APPSERVICE_PRESENT"),
    (mas_enabled is True, "SC_AUDIT_MAS_ENABLED"),
    (
        any(
            config.get(name) is not None
            for name in (
                "session_lifetime",
                "refreshable_access_token_lifetime",
                "nonrefreshable_access_token_lifetime",
            )
        ),
        "SC_AUDIT_SESSION_OR_TOKEN_LIFETIME_PRESENT",
    ),
):
    if condition:
        policy_blockers.append(blocker)
if unknown:
    print("AUDIT_STATUS: KHÔNG RÕ — từ chối coi cấu hình là an toàn")
    print("Trường không xác định:", ", ".join(sorted(unknown)))
    raise SystemExit(2)
if policy_blockers:
    print("AUDIT_STATUS: BLOCKED — auth surface không đúng baseline local-password-only")
    print("Blockers:", ", ".join(sorted(set(policy_blockers))))
    raise SystemExit(3)
print("AUDIT_STATUS: ĐÃ PARSE theo semantics Synapse 1.159.0")
PY

section "Thiết bị Matrix hiện có"
ENGINE="$(docker exec -i "$SYNAPSE_CONTAINER" python - "$HOMESERVER_CONFIG" <<'PY'
import sys
import yaml
with open(sys.argv[1], encoding="utf-8") as handle:
    print(((yaml.safe_load(handle) or {}).get("database") or {}).get("name", ""))
PY
)"

DEVICE_SQL="
SELECT d.user_id,
       d.device_id,
       COALESCE(d.display_name, '') AS display_name,
       COUNT(a.id) AS stored_token_rows
FROM devices AS d
LEFT JOIN access_tokens AS a
  ON a.user_id = d.user_id AND a.device_id = d.device_id
WHERE COALESCE(d.hidden, FALSE) = FALSE
GROUP BY d.user_id, d.device_id, d.display_name
ORDER BY d.user_id, d.device_id;
"

COUNT_SQL="
SELECT user_id, COUNT(*) AS visible_devices
FROM devices
WHERE COALESCE(hidden, FALSE) = FALSE
GROUP BY user_id
ORDER BY user_id;
"

NULL_HIDDEN_SQL="SELECT COUNT(*) FROM devices WHERE hidden IS NULL;"
NULL_HIDDEN_COUNT=""

if [[ "$ENGINE" == "psycopg2" || "$ENGINE" == "psycopg" ]]; then
    docker ps --format '{{.Names}}' | grep -qx "$POSTGRES_CONTAINER" \
        || fail "Cấu hình dùng PostgreSQL nhưng container $POSTGRES_CONTAINER không chạy."
    docker exec "$POSTGRES_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U synapse -d synapse \
        -P pager=off -c "$COUNT_SQL"
    docker exec "$POSTGRES_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U synapse -d synapse \
        -P pager=off -c "$DEVICE_SQL"
    NULL_HIDDEN_COUNT="$(docker exec "$POSTGRES_CONTAINER" \
        psql -X -v ON_ERROR_STOP=1 -U synapse -d synapse \
        -A -t -c "$NULL_HIDDEN_SQL" | tr -d '[:space:]')"
elif [[ "$ENGINE" == "sqlite3" ]]; then
    NULL_HIDDEN_COUNT="$(docker exec -i "$SYNAPSE_CONTAINER" python - <<'PY'
import sqlite3
db = sqlite3.connect("/data/homeserver.db")
print(db.execute("SELECT COUNT(*) FROM devices WHERE hidden IS NULL").fetchone()[0])
PY
)"
    docker exec -i "$SYNAPSE_CONTAINER" python - <<'PY'
import sqlite3

db = sqlite3.connect("/data/homeserver.db")
for row in db.execute(
    "SELECT user_id, COUNT(*) FROM devices WHERE COALESCE(hidden, 0) = 0 "
    "GROUP BY user_id ORDER BY user_id"
):
    print("COUNT", *row, sep=" | ")
for row in db.execute(
    "SELECT d.user_id, d.device_id, COALESCE(d.display_name, ''), COUNT(a.id) "
    "FROM devices d LEFT JOIN access_tokens a "
    "ON a.user_id=d.user_id AND a.device_id=d.device_id "
    "WHERE COALESCE(d.hidden, 0)=0 "
    "GROUP BY d.user_id,d.device_id,d.display_name "
    "ORDER BY d.user_id,d.device_id"
):
    print("DEVICE", *row, sep=" | ")
PY
else
    fail "Database engine không được hỗ trợ hoặc không xác định: ${ENGINE:-<rỗng>}"
fi
[[ "$NULL_HIDDEN_COUNT" =~ ^[0-9]+$ ]] \
    || fail "Không đọc được số row devices.hidden IS NULL; audit fail-closed."
printf 'NULL_HIDDEN_DEVICE_ROWS | %s\n' "$NULL_HIDDEN_COUNT"

section "Bề mặt Web/nginx"
docker inspect "$SYNAPSE_CONTAINER" --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
if docker ps --format '{{.Names}}' | grep -qx nginx; then
    docker exec nginx nginx -T 2>/dev/null \
        | grep -E '^[[:space:]]*(server_name|root|location|proxy_pass|ssl_verify_client|ssl_client_certificate)' \
        | sed -E 's/[[:space:]]+/ /g' \
        | sed -n '1,160p'
else
    printf 'Container nginx không chạy.\n'
fi

section "Kết luận tự động"
printf '%s\n' \
    '- Guard enforce không tạo binding và từ chối mọi login; binding chỉ qua CLI sau khi session/device/E2EE key đã tồn tại.' \
    '- stored_token_rows không chứng minh token còn hiệu lực; không dùng riêng số này để chọn/xóa device.' \
    '- Device không có token row vẫn có thể còn khóa E2EE; không xóa mù.' \
    '- devices.hidden IS NULL là dữ liệu không hợp lệ và phải được xử lý trước bind.' \
    '- Phải đóng cả v3, r0, unstable, api/v1 /login, /register và mọi prefix tương ứng ở public ingress.' \
    '- Script này không chứng minh app-only; mTLS/policy gateway vẫn là lớp bắt buộc.'

case "$AUTH_AUDIT_STATUS" in
    0) ;;
    2) fail "Có trường xác thực không parse được an toàn; không được coi audit là đạt." ;;
    3) fail "Bề mặt xác thực không đúng baseline local-password-only; xem blockers phía trên." ;;
    *) fail "Kiểm tra cấu hình xác thực lỗi ngoài dự kiến (status $AUTH_AUDIT_STATUS)." ;;
esac
if (( NULL_HIDDEN_COUNT != 0 )); then
    fail "Phát hiện devices.hidden IS NULL; guard/migration phải fail closed cho tới khi điều tra xong."
fi
