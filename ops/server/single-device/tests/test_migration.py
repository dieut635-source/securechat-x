from __future__ import annotations

import io
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

from securechat_single_device.migrate_cli import (
    DSN_ENV,
    DSN_FILE_ENV,
    ConfigurationError,
    build_parser,
    load_dsn,
    main,
)
from securechat_single_device.migration import (
    MigrationCode,
    MigrationError,
    bind_existing_device,
    read_plan,
)


class FakeMigrationCursor:
    def __init__(
        self,
        *,
        user_row=(False, False, None, None, False, False),
        devices=(("DEVICE_A", 1000),),
        access_counts=(("DEVICE_A", 1),),
        puppet_access_token_count=0,
        refresh_counts=(),
        hidden_devices=(),
        null_hidden_devices=(),
        e2e_key_devices=(("DEVICE_A",),),
        binding=None,
        table_exists=True,
        insert_succeeds=True,
    ) -> None:
        self.user_row = user_row
        self.devices = list(devices)
        self.access_counts = list(access_counts)
        self.puppet_access_token_count = puppet_access_token_count
        self.refresh_counts = list(refresh_counts)
        self.hidden_devices = list(hidden_devices)
        self.null_hidden_devices = list(null_hidden_devices)
        self.e2e_key_devices = list(e2e_key_devices)
        self.binding = binding
        self.table_exists = table_exists
        self.insert_succeeds = insert_succeeds
        self.executed = []
        self._one = None
        self._all = []

    def execute(self, sql, parameters=()):
        normalized = " ".join(sql.split())
        self.executed.append((normalized, parameters))
        self._one = None
        self._all = []

        if normalized.startswith("SELECT to_regclass"):
            self._one = (
                "securechat_single_device_bindings_v1" if self.table_exists else None,
            )
        elif "FROM users" in normalized:
            self._one = self.user_row
        elif "FROM devices" in normalized and "hidden IS NULL" in normalized:
            self._all = [(device_id,) for device_id in self.null_hidden_devices]
        elif "FROM devices d" in normalized and "d.hidden = TRUE" in normalized:
            self._all = list(self.hidden_devices)
        elif "FROM e2e_device_keys_json" in normalized:
            self._all = list(self.e2e_key_devices)
        elif "FROM devices" in normalized:
            self._all = list(self.devices)
        elif (
            "FROM access_tokens" in normalized
            and "(user_id = %s OR puppets_user_id = %s)" in normalized
        ):
            self._one = (self.puppet_access_token_count,)
        elif "FROM access_tokens" in normalized:
            self._all = list(self.access_counts)
        elif "FROM refresh_tokens" in normalized:
            self._all = list(self.refresh_counts)
        elif normalized.startswith("SELECT device_id FROM securechat_single_device"):
            self._one = (self.binding,) if self.binding is not None else None
        elif normalized.startswith("LOCK TABLE users, devices, access_tokens"):
            pass
        elif normalized.startswith("INSERT INTO securechat_single_device"):
            self._one = (parameters[0],) if self.insert_succeeds else None
        else:
            raise AssertionError(f"Unexpected SQL: {normalized}")

    def fetchone(self):
        value = self._one
        self._one = None
        return value

    def fetchall(self):
        values = self._all
        self._all = []
        return values


class MigrationPlanTests(unittest.TestCase):
    def test_plan_lists_other_devices_and_refuses_bind(self) -> None:
        cursor = FakeMigrationCursor(
            devices=(("DEVICE_A", 1000), ("DEVICE_B", 900)),
            access_counts=(("DEVICE_A", 1), ("DEVICE_B", 2)),
        )

        plan = read_plan(
            cursor,
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertFalse(plan.can_bind)
        self.assertEqual(
            [device.device_id for device in plan.other_devices],
            ["DEVICE_B"],
        )
        self.assertIn(
            "SC_MIGRATION_OTHER_DEVICES_REQUIRE_ADMIN_REVOKE",
            plan.blockers,
        )
        self.assertEqual(plan.other_devices[0].active_access_token_count, 2)

    def test_plan_is_ready_for_one_active_selected_device(self) -> None:
        plan = read_plan(
            FakeMigrationCursor(),
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertTrue(plan.can_bind)
        self.assertEqual(plan.status, "ready_to_bind")

    def test_non_device_and_orphan_credentials_are_blockers(self) -> None:
        cursor = FakeMigrationCursor(
            access_counts=(("DEVICE_A", 1), (None, 1), ("ORPHAN", 2)),
            refresh_counts=(("ORPHAN_REFRESH", 1),),
        )

        plan = read_plan(
            cursor,
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertFalse(plan.can_bind)
        self.assertEqual(plan.active_non_device_access_token_count, 1)
        self.assertEqual(
            [credential.device_id for credential in plan.orphan_credentials],
            ["ORPHAN", "ORPHAN_REFRESH"],
        )

    def test_selected_device_must_exist(self) -> None:
        with self.assertRaises(MigrationError) as raised:
            read_plan(
                FakeMigrationCursor(),
                user_id="@alice:securechat.test",
                selected_device_id="MISSING",
                now_ms=2000,
            )

        self.assertEqual(raised.exception.code, MigrationCode.DEVICE_NOT_FOUND)

    def test_schema_query_excludes_hidden_devices(self) -> None:
        cursor = FakeMigrationCursor()
        read_plan(
            cursor,
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        device_queries = [
            sql
            for sql, _ in cursor.executed
            if "FROM devices" in sql and "COALESCE(hidden, FALSE) = FALSE" in sql
        ]
        self.assertEqual(len(device_queries), 1)
        self.assertIn("COALESCE(hidden, FALSE) = FALSE", device_queries[0])

    def test_null_hidden_device_is_reported_and_blocks_binding(self) -> None:
        plan = read_plan(
            FakeMigrationCursor(null_hidden_devices=("CORRUPT_NULL",)),
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertFalse(plan.can_bind)
        self.assertEqual(plan.null_hidden_device_ids, ("CORRUPT_NULL",))
        self.assertIn("SC_MIGRATION_NULL_HIDDEN_DEVICE_PRESENT", plan.blockers)

    def test_expected_cross_signing_hidden_rows_are_reported_not_blocked(self) -> None:
        plan = read_plan(
            FakeMigrationCursor(hidden_devices=(("PUBLIC_CROSS_SIGNING_KEY", True),)),
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertTrue(plan.can_bind)
        self.assertEqual(plan.expected_cross_signing_hidden_device_count, 1)
        self.assertIn(
            "SC_MIGRATION_CROSS_SIGNING_HIDDEN_DEVICES_PRESENT",
            plan.warnings,
        )

    def test_unexpected_hidden_row_is_a_blocker(self) -> None:
        plan = read_plan(
            FakeMigrationCursor(hidden_devices=(("UNKNOWN_HIDDEN", False),)),
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertFalse(plan.can_bind)
        self.assertEqual(plan.unexpected_hidden_device_ids, ("UNKNOWN_HIDDEN",))
        self.assertIn(
            "SC_MIGRATION_UNEXPECTED_HIDDEN_DEVICE_PRESENT",
            plan.blockers,
        )

    def test_selected_device_with_multiple_access_tokens_is_a_blocker(self) -> None:
        plan = read_plan(
            FakeMigrationCursor(access_counts=(("DEVICE_A", 2),)),
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertFalse(plan.can_bind)
        self.assertIn(
            "SC_MIGRATION_SELECTED_DEVICE_ACCESS_TOKEN_COUNT_INVALID",
            plan.blockers,
        )

    def test_selected_device_with_refresh_token_is_a_blocker(self) -> None:
        plan = read_plan(
            FakeMigrationCursor(refresh_counts=(("DEVICE_A", 1),)),
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertFalse(plan.can_bind)
        self.assertIn(
            "SC_MIGRATION_SELECTED_DEVICE_REFRESH_TOKEN_PRESENT",
            plan.blockers,
        )

    def test_refresh_query_rejects_every_predecessor_and_expired_row(self) -> None:
        cursor = FakeMigrationCursor(refresh_counts=(("DEVICE_A", 3),))
        plan = read_plan(
            cursor,
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        refresh_query, refresh_parameters = next(
            (sql, parameters)
            for sql, parameters in cursor.executed
            if "FROM refresh_tokens" in sql
        )
        self.assertNotIn("next_token_id", refresh_query)
        self.assertNotIn("expiry_ts", refresh_query)
        self.assertEqual(refresh_parameters, ("@alice:securechat.test",))
        self.assertEqual(plan.selected_device.refresh_token_row_count, 3)
        self.assertIn(
            "SC_MIGRATION_SELECTED_DEVICE_REFRESH_TOKEN_PRESENT",
            plan.blockers,
        )

    def test_selected_device_without_e2ee_key_is_a_blocker(self) -> None:
        plan = read_plan(
            FakeMigrationCursor(e2e_key_devices=()),
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertFalse(plan.can_bind)
        self.assertFalse(plan.selected_device.has_e2e_device_key)
        self.assertIn(
            "SC_MIGRATION_SELECTED_DEVICE_E2EE_KEY_MISSING",
            plan.blockers,
        )

    def test_access_token_queries_split_regular_and_puppet_credentials(self) -> None:
        cursor = FakeMigrationCursor()
        read_plan(
            cursor,
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        access_queries = [
            sql for sql, _ in cursor.executed if "FROM access_tokens" in sql
        ]
        self.assertEqual(len(access_queries), 2)
        regular_query = next(
            sql for sql in access_queries if "GROUP BY device_id" in sql
        )
        puppet_query = next(
            sql for sql in access_queries if "SELECT COUNT(*)" in sql
        )
        self.assertIn("user_id = %s", regular_query)
        self.assertIn("puppets_user_id IS NULL", regular_query)
        self.assertIn("(user_id = %s OR puppets_user_id = %s)", puppet_query)
        self.assertIn("puppets_user_id IS NOT NULL", puppet_query)
        puppet_params = next(
            params
            for sql, params in cursor.executed
            if "(user_id = %s OR puppets_user_id = %s)" in sql
        )
        self.assertEqual(
            puppet_params,
            ("@alice:securechat.test", "@alice:securechat.test", 2000),
        )

    def test_active_puppet_access_token_is_a_blocker(self) -> None:
        plan = read_plan(
            FakeMigrationCursor(puppet_access_token_count=1),
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
        )

        self.assertFalse(plan.can_bind)
        self.assertEqual(plan.active_puppet_access_token_count, 1)
        self.assertIn(
            "SC_MIGRATION_PUPPET_ACCESS_TOKEN_PRESENT",
            plan.blockers,
        )


class SchemaPrivilegeTests(unittest.TestCase):
    def test_schema_requires_explicit_owner_and_least_privilege_roles(self) -> None:
        schema = (
            Path(__file__).resolve().parents[1]
            / "schema"
            / "001_bindings_v1.sql"
        ).read_text(encoding="utf-8")

        self.assertIn('OWNER TO :"securechat_schema_owner"', schema)
        self.assertIn("REVOKE ALL ON TABLE", schema)
        self.assertIn("FROM PUBLIC", schema)
        self.assertIn('TO :"securechat_runtime_role"', schema)
        self.assertIn('TO :"securechat_migration_role"', schema)
        runtime_grant = next(
            line
            for line in schema.splitlines()
            if line.startswith("GRANT SELECT ON TABLE")
        )
        self.assertNotIn("INSERT", runtime_grant)
        self.assertNotIn("UPDATE", schema)
        self.assertNotIn("DELETE", schema)


class MigrationBindTests(unittest.TestCase):
    def test_bind_only_inserts_module_binding(self) -> None:
        cursor = FakeMigrationCursor()

        plan = bind_existing_device(
            cursor,
            user_id="@alice:securechat.test",
            selected_device_id="DEVICE_A",
            now_ms=2000,
            claim_id="test-claim",
        )

        self.assertTrue(plan.can_bind)
        statements = [sql for sql, _ in cursor.executed]
        self.assertEqual(
            sum(sql.startswith("INSERT INTO securechat_single_device") for sql in statements),
            1,
        )
        self.assertFalse(any(sql.startswith("DELETE") for sql in statements))
        self.assertFalse(any(sql.startswith("UPDATE") for sql in statements))
        lock_statements = [sql for sql in statements if sql.startswith("LOCK TABLE")]
        self.assertEqual(len(lock_statements), 1)
        for table in (
            "users",
            "devices",
            "access_tokens",
            "refresh_tokens",
            "e2e_device_keys_json",
            "e2e_cross_signing_keys",
        ):
            self.assertIn(table, lock_statements[0])
        self.assertIn("IN SHARE ROW EXCLUSIVE MODE", lock_statements[0])

    def test_bind_refuses_when_other_device_still_exists(self) -> None:
        cursor = FakeMigrationCursor(
            devices=(("DEVICE_A", 1000), ("DEVICE_B", 900)),
            access_counts=(("DEVICE_A", 1), ("DEVICE_B", 1)),
        )

        with self.assertRaises(MigrationError) as raised:
            bind_existing_device(
                cursor,
                user_id="@alice:securechat.test",
                selected_device_id="DEVICE_A",
                now_ms=2000,
                claim_id="test-claim",
            )

        self.assertEqual(raised.exception.code, MigrationCode.PRECONDITION_FAILED)
        self.assertFalse(
            any(
                sql.startswith("INSERT INTO securechat_single_device")
                for sql, _ in cursor.executed
            )
        )

    def test_concurrent_binding_conflict_is_refused(self) -> None:
        cursor = FakeMigrationCursor(insert_succeeds=False)

        with self.assertRaises(MigrationError) as raised:
            bind_existing_device(
                cursor,
                user_id="@alice:securechat.test",
                selected_device_id="DEVICE_A",
                now_ms=2000,
                claim_id="test-claim",
            )

        self.assertEqual(raised.exception.code, MigrationCode.BINDING_CONFLICT)


class MigrationCliSafetyTests(unittest.TestCase):
    def test_default_action_is_read_only_plan(self) -> None:
        args = build_parser().parse_args(
            [
                "--user-id",
                "@alice:securechat.test",
                "--device-id",
                "DEVICE_A",
            ]
        )

        self.assertEqual(args.action, "plan")

    def test_dsn_is_loaded_from_environment_without_echo(self) -> None:
        dsn = "dbname=synapse user=securechat password=do-not-print"

        self.assertEqual(load_dsn({DSN_ENV: dsn}), dsn)

    def test_dsn_file_requires_private_permissions(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as handle:
            handle.write("service=securechat")
            handle.flush()
            os.chmod(handle.name, 0o600)
            self.assertEqual(
                load_dsn({DSN_FILE_ENV: handle.name}),
                "service=securechat",
            )

    def test_dsn_file_rejects_group_or_other_permissions(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as handle:
            handle.write("service=securechat")
            handle.flush()
            os.chmod(handle.name, 0o640)

            with self.assertRaises(ConfigurationError):
                load_dsn({DSN_FILE_ENV: handle.name})

    def test_dsn_file_rejects_symbolic_link(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = os.path.join(directory, "dsn")
            link = os.path.join(directory, "dsn-link")
            with open(target, "w", encoding="utf-8") as handle:
                handle.write("service=securechat")
            os.chmod(target, 0o600)
            os.symlink(target, link)

            with self.assertRaises(ConfigurationError):
                load_dsn({DSN_FILE_ENV: link})

    def test_dsn_file_requires_effective_user_ownership(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as handle:
            handle.write("service=securechat")
            handle.flush()
            os.chmod(handle.name, 0o600)
            owner = os.stat(handle.name).st_uid

            with mock.patch(
                "securechat_single_device.migrate_cli.os.geteuid",
                return_value=owner + 1,
            ):
                with self.assertRaises(ConfigurationError):
                    load_dsn({DSN_FILE_ENV: handle.name})

    def test_missing_dsn_fails_before_loading_database_driver(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()

        exit_code = main(
            [
                "--user-id",
                "@alice:securechat.test",
                "--device-id",
                "DEVICE_A",
            ],
            environment={},
            stdout=stdout,
            stderr=stderr,
        )

        self.assertEqual(exit_code, 2)
        self.assertEqual(stdout.getvalue(), "")
        self.assertNotIn("password", stderr.getvalue().lower())

    def test_bind_requires_exact_duplicate_confirmation(self) -> None:
        stderr = io.StringIO()

        exit_code = main(
            [
                "--action",
                "bind",
                "--user-id",
                "@alice:securechat.test",
                "--device-id",
                "DEVICE_A",
                "--confirm-user-id",
                "@alice:securechat.test",
                "--confirm-device-id",
                "WRONG_DEVICE",
                "--confirm-logins-enforced",
            ],
            environment={DSN_ENV: "password=do-not-print"},
            stdout=io.StringIO(),
            stderr=stderr,
        )

        self.assertEqual(exit_code, 2)
        self.assertNotIn("do-not-print", stderr.getvalue())

    def test_bind_requires_confirmation_that_login_paths_are_safe(self) -> None:
        stderr = io.StringIO()

        exit_code = main(
            [
                "--action",
                "bind",
                "--user-id",
                "@alice:securechat.test",
                "--device-id",
                "DEVICE_A",
                "--confirm-user-id",
                "@alice:securechat.test",
                "--confirm-device-id",
                "DEVICE_A",
            ],
            environment={DSN_ENV: "password=do-not-print"},
            stdout=io.StringIO(),
            stderr=stderr,
        )

        self.assertEqual(exit_code, 2)
        self.assertNotIn("do-not-print", stderr.getvalue())

    def test_driver_rollback_and_close_errors_never_escape_or_leak(self) -> None:
        secret = "private-driver-detail"

        class FailingCursor:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_value, traceback):
                return False

            def execute(self, sql, parameters=()):
                raise RuntimeError(secret)

        class FailingConnection:
            def set_session(self, **kwargs):
                pass

            def cursor(self):
                return FailingCursor()

            def rollback(self):
                raise RuntimeError(secret)

            def close(self):
                raise RuntimeError(secret)

        fake_driver = types.SimpleNamespace(
            connect=lambda *args, **kwargs: FailingConnection(),
            extensions=types.SimpleNamespace(
                ISOLATION_LEVEL_SERIALIZABLE=3,
                ISOLATION_LEVEL_READ_COMMITTED=1,
            ),
        )
        stdout = io.StringIO()
        stderr = io.StringIO()

        with mock.patch.dict(sys.modules, {"psycopg2": fake_driver}):
            exit_code = main(
                [
                    "--user-id",
                    "@alice:securechat.test",
                    "--device-id",
                    "DEVICE_A",
                ],
                environment={DSN_ENV: "service=securechat"},
                stdout=stdout,
                stderr=stderr,
            )

        self.assertEqual(exit_code, 5)
        self.assertEqual(stdout.getvalue(), "")
        self.assertNotIn(secret, stderr.getvalue())
        self.assertIn("SC_MIGRATION_DATABASE_ERROR", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
