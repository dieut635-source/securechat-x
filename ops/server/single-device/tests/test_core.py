from __future__ import annotations

import sqlite3
import unittest

from securechat_single_device.core import (
    ERROR_FIELD,
    TABLE_NAME,
    Decision,
    DecisionCode,
    Mode,
    ensure_schema,
    error_payload,
    inspect_login_state,
)


class CoreDatabaseTests(unittest.TestCase):
    def setUp(self) -> None:
        self.db = sqlite3.connect(":memory:")
        self.db.execute(
            """CREATE TABLE devices (
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                hidden BOOLEAN DEFAULT FALSE
            )"""
        )
        ensure_schema(self.db.cursor())
        self.db.commit()

    def tearDown(self) -> None:
        self.db.close()

    def inspect(
        self,
        *,
        mode: Mode = Mode.ENFORCE,
        user_id: str = "@alice:securechat.test",
    ):
        result = inspect_login_state(
            self.db.cursor(),
            mode=mode,
            user_id=user_id,
        )
        self.db.commit()
        return result

    def test_schema_setup_is_idempotent(self) -> None:
        ensure_schema(self.db.cursor())
        ensure_schema(self.db.cursor())

    def test_unbound_enforce_login_is_denied_without_writing(self) -> None:
        decision = self.inspect()

        self.assertFalse(decision.allowed)
        self.assertEqual(decision.code, DecisionCode.ENROLLMENT_REQUIRED)
        self.assertTrue(decision.admin_action_required)
        count = self.db.execute(f"SELECT count(*) FROM {TABLE_NAME}").fetchone()[0]
        self.assertEqual(count, 0)

    def test_bound_enforce_login_is_always_denied(self) -> None:
        self.db.execute(
            f"INSERT INTO {TABLE_NAME} VALUES (?, ?, ?, ?)",
            ("@alice:securechat.test", "DEVICE_A", 1234, "migration-a"),
        )
        self.db.commit()

        decision = self.inspect()

        self.assertFalse(decision.allowed)
        self.assertEqual(decision.code, DecisionCode.ALREADY_BOUND)
        self.assertTrue(decision.admin_action_required)

    def test_existing_synapse_device_is_not_adopted_by_callback(self) -> None:
        self.db.execute(
            "INSERT INTO devices (user_id, device_id) VALUES (?, ?)",
            ("@alice:securechat.test", "LEGACY"),
        )
        self.db.commit()

        decision = self.inspect()

        self.assertFalse(decision.allowed)
        self.assertEqual(decision.code, DecisionCode.ENROLLMENT_REQUIRED)
        count = self.db.execute(f"SELECT count(*) FROM {TABLE_NAME}").fetchone()[0]
        self.assertEqual(count, 0)

    def test_unbound_audit_login_is_observational_without_writing(self) -> None:
        decision = self.inspect(mode=Mode.AUDIT)

        self.assertTrue(decision.allowed)
        self.assertEqual(decision.code, DecisionCode.AUDIT_ENROLLMENT_REQUIRED)
        count = self.db.execute(f"SELECT count(*) FROM {TABLE_NAME}").fetchone()[0]
        self.assertEqual(count, 0)

    def test_bound_audit_login_is_observational_without_writing(self) -> None:
        self.db.execute(
            f"INSERT INTO {TABLE_NAME} VALUES (?, ?, ?, ?)",
            ("@alice:securechat.test", "DEVICE_A", 1234, "migration-a"),
        )
        self.db.commit()

        decision = self.inspect(mode=Mode.AUDIT)

        self.assertTrue(decision.allowed)
        self.assertEqual(decision.code, DecisionCode.AUDIT_ALREADY_BOUND)
        count = self.db.execute(f"SELECT count(*) FROM {TABLE_NAME}").fetchone()[0]
        self.assertEqual(count, 1)


class ReadOnlyTransaction:
    """Reject any callback SQL other than the one binding SELECT."""

    def execute(self, sql: str, parameters=()):
        normalized = " ".join(sql.split())
        if not normalized.startswith(f"SELECT 1 FROM {TABLE_NAME}"):
            raise AssertionError(f"Unexpected SQL: {normalized}")
        self.parameters = parameters

    def fetchone(self):
        return None


class CoreDecisionTests(unittest.TestCase):
    def test_login_decision_executes_only_a_binding_select(self) -> None:
        txn = ReadOnlyTransaction()
        decision = inspect_login_state(
            txn,
            mode=Mode.ENFORCE,
            user_id="@alice:securechat.test",
        )

        self.assertFalse(decision.allowed)
        self.assertEqual(decision.code, DecisionCode.ENROLLMENT_REQUIRED)
        self.assertEqual(txn.parameters, ("@alice:securechat.test",))

    def test_error_payload_is_stable_and_contains_no_identity(self) -> None:
        decision = Decision(
            False,
            DecisionCode.ENROLLMENT_REQUIRED,
            admin_action_required=True,
        )

        payload = error_payload(decision)

        self.assertEqual(
            payload,
            {
                ERROR_FIELD: {
                    "version": 1,
                    "code": "SC_ENROLLMENT_REQUIRED",
                    "retryable": False,
                    "admin_action_required": True,
                }
            },
        )
        self.assertNotIn("user_id", repr(payload))
        self.assertNotIn("device_id", repr(payload))

    def test_transient_guard_errors_are_marked_retryable(self) -> None:
        for code in (DecisionCode.GUARD_NOT_READY, DecisionCode.DATABASE_ERROR):
            payload = error_payload(Decision(False, code))
            self.assertTrue(payload[ERROR_FIELD]["retryable"])


if __name__ == "__main__":
    unittest.main()
