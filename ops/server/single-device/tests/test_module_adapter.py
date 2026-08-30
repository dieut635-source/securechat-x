from __future__ import annotations

import asyncio
import sqlite3
import sys
import types
import unittest


# The production adapter imports Synapse's stable module API.  These tiny
# modules let us exercise the adapter without installing Synapse locally.
class FakeConfigError(Exception):
    pass


class FakeCodes:
    FORBIDDEN = "M_FORBIDDEN"


fake_synapse = types.ModuleType("synapse")
fake_synapse.__path__ = []
fake_config = types.ModuleType("synapse.config")
fake_config.ConfigError = FakeConfigError
fake_module_api = types.ModuleType("synapse.module_api")
fake_module_api.ModuleApi = object
fake_module_api.NOT_SPAM = "NOT_SPAM"
fake_module_api.errors = types.SimpleNamespace(Codes=FakeCodes)
sys.modules["synapse"] = fake_synapse
sys.modules["synapse.config"] = fake_config
sys.modules["synapse.module_api"] = fake_module_api


from securechat_single_device.core import (  # noqa: E402
    ERROR_FIELD,
    TABLE_NAME,
    Mode,
    ensure_schema,
)
from securechat_single_device.module import (  # noqa: E402
    GuardConfig,
    SecureChatSingleDeviceModule,
)


class FakeModuleApi:
    def __init__(self) -> None:
        self.db = sqlite3.connect(":memory:")
        self.db.execute(
            """CREATE TABLE devices (
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                hidden BOOLEAN DEFAULT FALSE
            )"""
        )
        self.callback = None
        self.background = None

    def close(self) -> None:
        self.db.close()

    def register_spam_checker_callbacks(self, *, check_login_for_spam):
        self.callback = check_login_for_spam

    def run_as_background_process(self, description, function):
        self.background = (description, function)

    async def run_db_interaction(self, description, function, *args, **kwargs):
        del description
        try:
            result = function(self.db.cursor(), *args, **kwargs)
            self.db.commit()
            return result
        except Exception:
            self.db.rollback()
            raise


class ModuleAdapterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.api = FakeModuleApi()

    def tearDown(self) -> None:
        self.api.close()

    def make_module(self, mode: Mode) -> SecureChatSingleDeviceModule:
        return SecureChatSingleDeviceModule(GuardConfig(mode), self.api)

    def provision_schema(self) -> None:
        ensure_schema(self.api.db.cursor())
        self.api.db.commit()

    def login(self, module, device_id="DEVICE_A"):
        return asyncio.run(
            module.check_login_for_spam(
                "@alice:securechat.test",
                device_id,
                "SecureChat",
                [("spoofed-user-agent", "203.0.113.7")],
                None,
            )
        )

    def test_callback_is_registered_before_schema_setup(self) -> None:
        module = self.make_module(Mode.ENFORCE)

        self.assertEqual(self.api.callback, module.check_login_for_spam)
        self.assertEqual(
            self.api.background[0],
            "securechat_single_device_schema_check",
        )

    def test_runtime_schema_check_is_read_only_and_missing_schema_stays_not_ready(self) -> None:
        module = self.make_module(Mode.AUDIT)

        with self.assertLogs("securechat_single_device.module", level="ERROR") as logs:
            asyncio.run(module._setup_schema())

        self.assertFalse(module._schema_ready)
        self.assertIn("schema check failed", "\n".join(logs.output))

    def test_enforce_rejects_with_machine_error_while_not_ready(self) -> None:
        module = self.make_module(Mode.ENFORCE)

        result = self.login(module)

        self.assertEqual(result[0], "M_FORBIDDEN")
        self.assertEqual(
            result[1][ERROR_FIELD]["code"],
            "SC_GUARD_NOT_READY",
        )
        self.assertTrue(result[1][ERROR_FIELD]["retryable"])

    def test_missing_device_id_does_not_create_an_enrollment_path(self) -> None:
        module = self.make_module(Mode.ENFORCE)
        self.provision_schema()
        asyncio.run(module._setup_schema())

        result = self.login(module, None)

        self.assertEqual(result[0], "M_FORBIDDEN")
        self.assertEqual(
            result[1][ERROR_FIELD]["code"],
            "SC_ENROLLMENT_REQUIRED",
        )

    def test_ready_enforce_module_never_creates_binding_or_allows_login(self) -> None:
        module = self.make_module(Mode.ENFORCE)
        self.provision_schema()
        asyncio.run(module._setup_schema())

        result = self.login(module)

        self.assertEqual(result[0], "M_FORBIDDEN")
        self.assertEqual(
            result[1][ERROR_FIELD]["code"],
            "SC_ENROLLMENT_REQUIRED",
        )
        count = self.api.db.execute(f"SELECT count(*) FROM {TABLE_NAME}").fetchone()[0]
        self.assertEqual(count, 0)

    def test_bound_account_is_still_denied_and_binding_is_unchanged(self) -> None:
        module = self.make_module(Mode.ENFORCE)
        self.provision_schema()
        asyncio.run(module._setup_schema())
        self.api.db.execute(
            f"INSERT INTO {TABLE_NAME} VALUES (?, ?, ?, ?)",
            ("@alice:securechat.test", "DEVICE_A", 1234, "migration-a"),
        )
        self.api.db.commit()

        result = self.login(module, "ANY_CLIENT_CHOSEN_ID")

        self.assertEqual(result[0], "M_FORBIDDEN")
        self.assertEqual(
            result[1][ERROR_FIELD]["code"],
            "SC_DEVICE_ALREADY_BOUND",
        )
        rows = self.api.db.execute(
            f"SELECT device_id, claim_id FROM {TABLE_NAME}"
        ).fetchall()
        self.assertEqual(rows, [("DEVICE_A", "migration-a")])

    def test_audit_allows_but_never_writes_binding(self) -> None:
        module = self.make_module(Mode.AUDIT)
        self.provision_schema()
        asyncio.run(module._setup_schema())

        result = self.login(module)

        self.assertEqual(result, "NOT_SPAM")
        count = self.api.db.execute(f"SELECT count(*) FROM {TABLE_NAME}").fetchone()[0]
        self.assertEqual(count, 0)

    def test_config_requires_explicit_known_mode(self) -> None:
        self.assertEqual(
            SecureChatSingleDeviceModule.parse_config({"mode": "audit"}),
            GuardConfig(Mode.AUDIT),
        )
        with self.assertRaises(FakeConfigError):
            SecureChatSingleDeviceModule.parse_config({})
        with self.assertRaises(FakeConfigError):
            SecureChatSingleDeviceModule.parse_config(
                {"mode": "enforce", "fail_open": True}
            )

    def test_database_failure_log_matches_mode_and_suppresses_exception(self) -> None:
        async def fail_interaction(*args, **kwargs):
            del args, kwargs
            raise RuntimeError("private-driver-detail")

        self.api.run_db_interaction = fail_interaction

        enforce = self.make_module(Mode.ENFORCE)
        enforce._schema_ready = True
        with self.assertLogs("securechat_single_device.module", level="ERROR") as logs:
            result = self.login(enforce)
        self.assertEqual(result[1][ERROR_FIELD]["code"], "SC_GUARD_DATABASE_ERROR")
        self.assertTrue(result[1][ERROR_FIELD]["retryable"])
        joined = "\n".join(logs.output)
        self.assertIn("mode=enforce allowed=False", joined)
        self.assertNotIn("private-driver-detail", joined)
        self.assertNotIn("login denied", joined)

        audit = self.make_module(Mode.AUDIT)
        audit._schema_ready = True
        with self.assertLogs("securechat_single_device.module", level="ERROR") as logs:
            result = self.login(audit)
        self.assertEqual(result, "NOT_SPAM")
        joined = "\n".join(logs.output)
        self.assertIn("mode=audit allowed=True", joined)
        self.assertNotIn("private-driver-detail", joined)
        self.assertNotIn("login denied", joined)


if __name__ == "__main__":
    unittest.main()
