"""Safe PostgreSQL planning and binding for existing Synapse devices.

The functions in this file only read Synapse tables.  The sole write operation
is an insert into the module-owned binding table.  In particular, this module
never deletes devices, access tokens, refresh tokens, or E2EE keys.
"""

from __future__ import annotations

import secrets
import time
from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol

from .core import TABLE_NAME


class Cursor(Protocol):
    def execute(self, sql: str, parameters: tuple[Any, ...] = ()) -> Any:
        ...

    def fetchone(self) -> tuple[Any, ...] | None:
        ...

    def fetchall(self) -> list[tuple[Any, ...]]:
        ...


class MigrationCode(str, Enum):
    GUARD_TABLE_MISSING = "SC_MIGRATION_GUARD_TABLE_MISSING"
    USER_NOT_FOUND = "SC_MIGRATION_USER_NOT_FOUND"
    DEVICE_NOT_FOUND = "SC_MIGRATION_DEVICE_NOT_FOUND"
    PRECONDITION_FAILED = "SC_MIGRATION_PRECONDITION_FAILED"
    BINDING_CONFLICT = "SC_MIGRATION_BINDING_CONFLICT"


class MigrationError(RuntimeError):
    """Expected, client-safe migration failure."""

    def __init__(
        self,
        code: MigrationCode,
        message: str,
        *,
        blockers: tuple[str, ...] = (),
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.blockers = blockers


@dataclass(frozen=True)
class DeviceSummary:
    device_id: str
    last_seen_ms: int | None
    has_e2e_device_key: bool
    active_access_token_count: int
    refresh_token_row_count: int

    def as_dict(self) -> dict[str, object]:
        return {
            "device_id": self.device_id,
            "last_seen_ms": self.last_seen_ms,
            "has_e2e_device_key": self.has_e2e_device_key,
            "active_access_token_count": self.active_access_token_count,
            "refresh_token_row_count": self.refresh_token_row_count,
        }


@dataclass(frozen=True)
class CredentialSummary:
    device_id: str
    active_access_token_count: int
    refresh_token_row_count: int

    def as_dict(self) -> dict[str, object]:
        return {
            "device_id": self.device_id,
            "active_access_token_count": self.active_access_token_count,
            "refresh_token_row_count": self.refresh_token_row_count,
        }


@dataclass(frozen=True)
class UserState:
    deactivated: bool
    is_guest: bool
    appservice_id: str | None
    user_type: str | None
    locked: bool
    suspended: bool


@dataclass(frozen=True)
class MigrationPlan:
    user_id: str
    selected_device: DeviceSummary
    other_devices: tuple[DeviceSummary, ...]
    binding_device_id: str | None
    active_non_device_access_token_count: int
    active_puppet_access_token_count: int
    orphan_credentials: tuple[CredentialSummary, ...]
    expected_cross_signing_hidden_device_count: int
    unexpected_hidden_device_ids: tuple[str, ...]
    null_hidden_device_ids: tuple[str, ...]
    blockers: tuple[str, ...]
    warnings: tuple[str, ...]

    @property
    def can_bind(self) -> bool:
        return not self.blockers

    @property
    def status(self) -> str:
        if self.binding_device_id == self.selected_device.device_id:
            return "already_bound"
        if self.binding_device_id is not None:
            return "binding_conflict"
        if self.blockers:
            return "cleanup_required"
        return "ready_to_bind"

    def as_dict(self, *, action: str = "plan") -> dict[str, object]:
        binding: dict[str, object] | None = None
        if self.binding_device_id is not None:
            binding = {"device_id": self.binding_device_id}

        return {
            "schema_version": 1,
            "action": action,
            "status": self.status,
            "user_id": self.user_id,
            "selected_device": self.selected_device.as_dict(),
            "other_devices_to_revoke": [
                device.as_dict() for device in self.other_devices
            ],
            "binding": binding,
            "active_non_device_access_token_count": (
                self.active_non_device_access_token_count
            ),
            "active_puppet_access_token_count": (
                self.active_puppet_access_token_count
            ),
            "orphan_credentials": [
                credential.as_dict() for credential in self.orphan_credentials
            ],
            "expected_cross_signing_hidden_device_count": (
                self.expected_cross_signing_hidden_device_count
            ),
            "unexpected_hidden_device_ids": list(self.unexpected_hidden_device_ids),
            "null_hidden_device_ids": list(self.null_hidden_device_ids),
            "blockers": list(self.blockers),
            "warnings": list(self.warnings),
            "can_bind": self.can_bind,
        }


def _fetch_count_map(cursor: Cursor) -> dict[str | None, int]:
    return {row[0]: int(row[1]) for row in cursor.fetchall()}


def read_plan(
    cursor: Cursor,
    *,
    user_id: str,
    selected_device_id: str,
    lock_user: bool = False,
    now_ms: int | None = None,
) -> MigrationPlan:
    """Read and validate a migration plan without modifying any row."""

    if now_ms is None:
        now_ms = int(time.time() * 1000)

    cursor.execute("SELECT to_regclass(%s)", (TABLE_NAME,))
    table_row = cursor.fetchone()
    if table_row is None or table_row[0] is None:
        raise MigrationError(
            MigrationCode.GUARD_TABLE_MISSING,
            "Single-device guard table is not installed",
        )

    user_sql = """
        SELECT
            deactivated,
            is_guest,
            appservice_id,
            user_type,
            COALESCE(locked, FALSE),
            COALESCE(suspended, FALSE)
        FROM users
        WHERE name = %s
    """
    if lock_user:
        user_sql += " FOR UPDATE"
    cursor.execute(user_sql, (user_id,))
    user_row = cursor.fetchone()
    if user_row is None:
        raise MigrationError(
            MigrationCode.USER_NOT_FOUND,
            "The requested local Synapse user does not exist",
        )
    user = UserState(
        deactivated=bool(user_row[0]),
        is_guest=bool(user_row[1]),
        appservice_id=user_row[2],
        user_type=user_row[3],
        locked=bool(user_row[4]),
        suspended=bool(user_row[5]),
    )

    cursor.execute(
        """
        SELECT device_id
        FROM devices
        WHERE user_id = %s AND hidden IS NULL
        ORDER BY device_id
        """,
        (user_id,),
    )
    null_hidden_device_ids = tuple(str(row[0]) for row in cursor.fetchall())

    cursor.execute(
        """
        SELECT device_id, last_seen
        FROM devices
        WHERE user_id = %s AND COALESCE(hidden, FALSE) = FALSE
        ORDER BY device_id
        """,
        (user_id,),
    )
    device_rows = cursor.fetchall()

    # Synapse reserves hidden device rows for cross-signing public keys.  Those
    # are not sessions and the Admin Device API intentionally omits them.  We
    # identify the expected rows from the public cross-signing key JSON and
    # fail closed on any other hidden row.
    cursor.execute(
        """
        SELECT
            d.device_id,
            EXISTS (
                SELECT 1
                FROM e2e_cross_signing_keys x
                CROSS JOIN LATERAL jsonb_each_text(
                    (x.keydata::jsonb)->'keys'
                ) AS key_entry(key_name, key_value)
                WHERE x.user_id = d.user_id
                  AND key_entry.key_value = d.device_id
            ) AS is_cross_signing_key
        FROM devices d
        WHERE d.user_id = %s AND d.hidden = TRUE
        ORDER BY d.device_id
        """,
        (user_id,),
    )
    hidden_device_rows = cursor.fetchall()

    # Only retrieve identifiers, never the public-key JSON itself.  Presence is
    # a useful E2EE readiness precondition, but is not hardware/app attestation.
    cursor.execute(
        """
        SELECT device_id
        FROM e2e_device_keys_json
        WHERE user_id = %s
        ORDER BY device_id
        """,
        (user_id,),
    )
    e2e_key_device_ids = {str(row[0]) for row in cursor.fetchall()}

    cursor.execute(
        """
        SELECT device_id, COUNT(*)
        FROM access_tokens
        WHERE user_id = %s
          AND puppets_user_id IS NULL
          AND (valid_until_ms IS NULL OR valid_until_ms >= %s)
        GROUP BY device_id
        ORDER BY device_id NULLS FIRST
        """,
        (user_id, now_ms),
    )
    access_counts = _fetch_count_map(cursor)

    # Synapse admin impersonation tokens are independent bearer credentials.
    # Count every live puppet token associated with the target on either side:
    # a token owned by the target which puppets another user, or a token owned
    # elsewhere which puppets the target. Neither may be adopted as normal.
    cursor.execute(
        """
        SELECT COUNT(*)
        FROM access_tokens
        WHERE (user_id = %s OR puppets_user_id = %s)
          AND puppets_user_id IS NOT NULL
          AND (valid_until_ms IS NULL OR valid_until_ms >= %s)
        """,
        (user_id, user_id, now_ms),
    )
    puppet_access_row = cursor.fetchone()
    active_puppet_access_count = (
        int(puppet_access_row[0]) if puppet_access_row is not None else 0
    )

    cursor.execute(
        """
        SELECT device_id, COUNT(*)
        FROM refresh_tokens
        WHERE user_id = %s
        GROUP BY device_id
        ORDER BY device_id
        """,
        (user_id,),
    )
    refresh_counts = _fetch_count_map(cursor)

    devices = tuple(
        DeviceSummary(
            device_id=str(row[0]),
            last_seen_ms=int(row[1]) if row[1] is not None else None,
            has_e2e_device_key=str(row[0]) in e2e_key_device_ids,
            active_access_token_count=access_counts.get(str(row[0]), 0),
            refresh_token_row_count=refresh_counts.get(str(row[0]), 0),
        )
        for row in device_rows
    )
    by_id = {device.device_id: device for device in devices}
    selected = by_id.get(selected_device_id)
    if selected is None:
        raise MigrationError(
            MigrationCode.DEVICE_NOT_FOUND,
            "The selected visible device does not exist for this user",
        )

    cursor.execute(
        f"SELECT device_id FROM {TABLE_NAME} WHERE user_id = %s",
        (user_id,),
    )
    binding_row = cursor.fetchone()
    binding_device_id = str(binding_row[0]) if binding_row is not None else None

    visible_device_ids = set(by_id)
    credential_device_ids = {
        device_id
        for device_id in set(access_counts) | set(refresh_counts)
        if device_id is not None
    }
    orphan_credentials = tuple(
        CredentialSummary(
            device_id=str(device_id),
            active_access_token_count=access_counts.get(device_id, 0),
            refresh_token_row_count=refresh_counts.get(device_id, 0),
        )
        for device_id in sorted(credential_device_ids - visible_device_ids)
    )
    other_devices = tuple(
        device for device in devices if device.device_id != selected_device_id
    )
    expected_cross_signing_hidden_count = sum(
        1 for _, is_cross_signing in hidden_device_rows if bool(is_cross_signing)
    )
    unexpected_hidden_device_ids = tuple(
        str(device_id)
        for device_id, is_cross_signing in hidden_device_rows
        if not bool(is_cross_signing)
    )

    blockers: list[str] = []
    if user.deactivated:
        blockers.append("SC_MIGRATION_USER_DEACTIVATED")
    if user.locked:
        blockers.append("SC_MIGRATION_USER_LOCKED")
    if user.suspended:
        blockers.append("SC_MIGRATION_USER_SUSPENDED")
    if user.is_guest:
        blockers.append("SC_MIGRATION_GUEST_USER_UNSUPPORTED")
    if user.appservice_id is not None:
        blockers.append("SC_MIGRATION_APPSERVICE_USER_UNSUPPORTED")
    if user.user_type is not None:
        blockers.append("SC_MIGRATION_SPECIAL_USER_UNSUPPORTED")
    if binding_device_id is not None:
        if binding_device_id == selected_device_id:
            blockers.append("SC_MIGRATION_ALREADY_BOUND")
        else:
            blockers.append("SC_MIGRATION_BINDING_CONFLICT")
    if other_devices:
        blockers.append("SC_MIGRATION_OTHER_DEVICES_REQUIRE_ADMIN_REVOKE")

    non_device_access_count = access_counts.get(None, 0)
    if non_device_access_count:
        blockers.append("SC_MIGRATION_NON_DEVICE_ACCESS_TOKEN_PRESENT")
    if active_puppet_access_count:
        blockers.append("SC_MIGRATION_PUPPET_ACCESS_TOKEN_PRESENT")
    if orphan_credentials:
        blockers.append("SC_MIGRATION_ORPHAN_CREDENTIAL_PRESENT")
    if unexpected_hidden_device_ids:
        blockers.append("SC_MIGRATION_UNEXPECTED_HIDDEN_DEVICE_PRESENT")
    if null_hidden_device_ids:
        blockers.append("SC_MIGRATION_NULL_HIDDEN_DEVICE_PRESENT")

    warnings: list[str] = []
    if expected_cross_signing_hidden_count:
        warnings.append("SC_MIGRATION_CROSS_SIGNING_HIDDEN_DEVICES_PRESENT")
    # A client-chosen device_id is not a session identity: repeated logins can
    # attach several independent bearer-token lineages to the same device row.
    # This strict migration baseline deliberately supports only the known
    # SecureChat shape (one live access token and refresh tokens disabled).
    # Anything else must go through fresh administrator-controlled enrollment;
    # guessing which credential to preserve would silently adopt an attacker.
    if selected.active_access_token_count != 1:
        blockers.append(
            "SC_MIGRATION_SELECTED_DEVICE_ACCESS_TOKEN_COUNT_INVALID"
        )
    if selected.refresh_token_row_count != 0:
        blockers.append("SC_MIGRATION_SELECTED_DEVICE_REFRESH_TOKEN_PRESENT")
    if not selected.has_e2e_device_key:
        blockers.append("SC_MIGRATION_SELECTED_DEVICE_E2EE_KEY_MISSING")

    return MigrationPlan(
        user_id=user_id,
        selected_device=selected,
        other_devices=other_devices,
        binding_device_id=binding_device_id,
        active_non_device_access_token_count=non_device_access_count,
        active_puppet_access_token_count=active_puppet_access_count,
        orphan_credentials=orphan_credentials,
        expected_cross_signing_hidden_device_count=(
            expected_cross_signing_hidden_count
        ),
        unexpected_hidden_device_ids=unexpected_hidden_device_ids,
        null_hidden_device_ids=null_hidden_device_ids,
        blockers=tuple(blockers),
        warnings=tuple(warnings),
    )


def bind_existing_device(
    cursor: Cursor,
    *,
    user_id: str,
    selected_device_id: str,
    now_ms: int | None = None,
    claim_id: str | None = None,
) -> MigrationPlan:
    """Bind one existing device, refusing all ambiguous or unsafe states.

    A short PostgreSQL table lock prevents a concurrent device insert/delete
    while the selected mapping is checked and written.  This function never
    deletes or updates Synapse-owned rows.
    """

    if now_ms is None:
        now_ms = int(time.time() * 1000)
    if claim_id is None:
        claim_id = secrets.token_hex(32)

    # SERIALIZABLE does not make non-serializable Synapse writers participate in
    # SSI.  These short table locks therefore cover user state, device and E2EE
    # key changes, admin impersonation token creation, refresh rotation, and
    # token revocation for the duration of the snapshot + binding insert.  A
    # five-second lock timeout is set by the CLI, so contention fails closed.
    cursor.execute(
        """
        LOCK TABLE
            users,
            devices,
            access_tokens,
            refresh_tokens,
            e2e_device_keys_json,
            e2e_cross_signing_keys
        IN SHARE ROW EXCLUSIVE MODE
        """
    )
    plan = read_plan(
        cursor,
        user_id=user_id,
        selected_device_id=selected_device_id,
        lock_user=True,
        now_ms=now_ms,
    )
    if not plan.can_bind:
        raise MigrationError(
            MigrationCode.PRECONDITION_FAILED,
            "Migration plan has blockers; no binding was written",
            blockers=plan.blockers,
        )

    cursor.execute(
        f"""
        INSERT INTO {TABLE_NAME} (user_id, device_id, bound_ts, claim_id)
        VALUES (%s, %s, %s, %s)
        ON CONFLICT(user_id) DO NOTHING
        RETURNING user_id
        """,
        (user_id, selected_device_id, now_ms, claim_id),
    )
    if cursor.fetchone() is None:
        raise MigrationError(
            MigrationCode.BINDING_CONFLICT,
            "A binding appeared concurrently; no migration change was committed",
        )

    return plan
