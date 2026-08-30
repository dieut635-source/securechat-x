"""Database-only core for the read-only SecureChat login guard.

This module intentionally has no Synapse or third-party imports.  Keeping the
state transition here makes it possible to test the security-sensitive logic
with Python's standard library and small transaction fakes.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol


TABLE_NAME = "securechat_single_device_bindings_v1"
ERROR_FIELD = "com.securechat.single_device"


class Transaction(Protocol):
    """Small DB-API subset also provided by Synapse LoggingTransaction."""

    def execute(self, sql: str, parameters: tuple[Any, ...] = ()) -> Any:
        ...

    def fetchone(self) -> tuple[Any, ...] | None:
        ...


class Mode(str, Enum):
    AUDIT = "audit"
    ENFORCE = "enforce"


class DecisionCode(str, Enum):
    ENROLLMENT_REQUIRED = "SC_ENROLLMENT_REQUIRED"
    AUDIT_ENROLLMENT_REQUIRED = "SC_AUDIT_ENROLLMENT_REQUIRED"
    ALREADY_BOUND = "SC_DEVICE_ALREADY_BOUND"
    AUDIT_ALREADY_BOUND = "SC_AUDIT_DEVICE_ALREADY_BOUND"
    GUARD_NOT_READY = "SC_GUARD_NOT_READY"
    DATABASE_ERROR = "SC_GUARD_DATABASE_ERROR"


@dataclass(frozen=True)
class Decision:
    """Result of one login decision.

    ``allowed`` describes what the active mode does, not merely what enforce
    mode would do.  Audit decisions therefore allow the request but retain a
    precise ``SC_AUDIT_*`` code for logs.
    """

    allowed: bool
    code: DecisionCode
    admin_action_required: bool = False


CREATE_TABLE_SQL = f"""
CREATE TABLE IF NOT EXISTS {TABLE_NAME} (
    user_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL CHECK (length(device_id) > 0),
    bound_ts BIGINT NOT NULL,
    -- Random operation identifier written only by the reviewed migration CLI.
    -- The login callback never inserts or updates this table.
    claim_id TEXT NOT NULL
)
"""


def ensure_schema(txn: Transaction) -> None:
    """Create schema for explicit provisioning/tests, never a login callback."""

    txn.execute(CREATE_TABLE_SQL)


def verify_schema(txn: Transaction) -> None:
    """Read-only readiness probe for the explicitly provisioned schema."""

    txn.execute(
        f"SELECT user_id, device_id, bound_ts, claim_id FROM {TABLE_NAME} WHERE 1 = 0"
    )


def inspect_login_state(
    txn: Transaction,
    *,
    mode: Mode,
    user_id: str,
) -> Decision:
    """Read binding state and make a login decision without writing anything.

    The callback is intentionally *not* an enrollment mechanism.  A binding is
    created only by the migration CLI after Synapse has already committed and
    audited the selected device, access token and E2EE public device key.  This
    avoids the callback-before-``register_device`` atomicity gap in Synapse.

    Enforce mode rejects every password login: an unbound account needs an
    administrator-controlled enrollment/migration, while a bound account must
    continue using its existing session.  Audit mode records the same state but
    always allows the request and never writes a binding.
    """

    txn.execute(
        f"SELECT 1 FROM {TABLE_NAME} WHERE user_id = ?",
        (user_id,),
    )
    if txn.fetchone() is not None:
        if mode is Mode.AUDIT:
            return Decision(
                True,
                DecisionCode.AUDIT_ALREADY_BOUND,
                admin_action_required=True,
            )
        return Decision(
            False,
            DecisionCode.ALREADY_BOUND,
            admin_action_required=True,
        )

    if mode is Mode.AUDIT:
        return Decision(
            True,
            DecisionCode.AUDIT_ENROLLMENT_REQUIRED,
            admin_action_required=True,
        )
    return Decision(
        False,
        DecisionCode.ENROLLMENT_REQUIRED,
        admin_action_required=True,
    )


def error_payload(decision: Decision) -> dict[str, object]:
    """Build stable, machine-readable additional fields for a Matrix error."""

    retryable = decision.code in {
        DecisionCode.GUARD_NOT_READY,
        DecisionCode.DATABASE_ERROR,
    }
    return {
        ERROR_FIELD: {
            "version": 1,
            "code": decision.code.value,
            "retryable": retryable,
            "admin_action_required": decision.admin_action_required,
        }
    }
