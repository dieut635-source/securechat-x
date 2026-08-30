"""Command-line entry point for safe existing-device migration."""

from __future__ import annotations

import argparse
import json
import os
import stat
import sys
from pathlib import Path
from typing import Mapping, Sequence, TextIO

from .migration import MigrationError, bind_existing_device, read_plan


DSN_ENV = "SECURECHAT_DATABASE_DSN"
DSN_FILE_ENV = "SECURECHAT_DATABASE_DSN_FILE"


class ConfigurationError(RuntimeError):
    pass


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Plan or bind one existing Synapse device. The default action is "
            "read-only plan. Database credentials are accepted only via environment."
        )
    )
    parser.add_argument("--action", choices=("plan", "bind"), default="plan")
    parser.add_argument("--user-id", required=True)
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--confirm-user-id")
    parser.add_argument("--confirm-device-id")
    parser.add_argument(
        "--confirm-logins-enforced",
        action="store_true",
        help=(
            "Required for bind: confirm every /login path is already enforced "
            "or quiesced and will stay closed until enforce mode is active"
        ),
    )
    return parser


def validate_identifiers(user_id: str, device_id: str) -> None:
    if (
        not user_id.startswith("@")
        or ":" not in user_id[1:]
        or any(ord(char) < 32 for char in user_id)
    ):
        raise ConfigurationError("--user-id must be a full, printable Matrix ID")
    if not device_id or len(device_id) > 512 or any(
        ord(char) < 32 for char in device_id
    ):
        raise ConfigurationError(
            "--device-id must be printable, non-empty, and at most 512 characters"
        )


def load_dsn(environment: Mapping[str, str]) -> str:
    direct = environment.get(DSN_ENV)
    filename = environment.get(DSN_FILE_ENV)
    if direct and filename:
        raise ConfigurationError(
            f"Set only one of {DSN_ENV} or {DSN_FILE_ENV}"
        )
    if direct:
        if len(direct) > 65536:
            raise ConfigurationError(f"{DSN_ENV} is unexpectedly large")
        return direct
    if not filename:
        raise ConfigurationError(
            f"Set {DSN_ENV} or {DSN_FILE_ENV}; DSN is not accepted on the command line"
        )

    path = Path(filename)
    path_stat = path.lstat()
    if stat.S_ISLNK(path_stat.st_mode):
        raise ConfigurationError(f"{DSN_FILE_ENV} must not be a symbolic link")

    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags)
    try:
        file_stat = os.fstat(descriptor)
        if not stat.S_ISREG(file_stat.st_mode):
            raise ConfigurationError(f"{DSN_FILE_ENV} must point to a regular file")
        if (file_stat.st_dev, file_stat.st_ino) != (
            path_stat.st_dev,
            path_stat.st_ino,
        ):
            raise ConfigurationError(f"{DSN_FILE_ENV} changed while being opened")
        if file_stat.st_uid != os.geteuid():
            raise ConfigurationError(
                f"{DSN_FILE_ENV} must be owned by the effective process user"
            )
        if stat.S_IMODE(file_stat.st_mode) & 0o077:
            raise ConfigurationError(
                f"{DSN_FILE_ENV} must not be readable or writable by group/others"
            )
        if file_stat.st_size > 65536:
            raise ConfigurationError(f"{DSN_FILE_ENV} is unexpectedly large")
        with os.fdopen(descriptor, mode="r", encoding="utf-8") as handle:
            descriptor = -1
            dsn = handle.read(65537).strip()
    finally:
        if descriptor >= 0:
            os.close(descriptor)

    if len(dsn) > 65536:
        raise ConfigurationError(f"{DSN_FILE_ENV} is unexpectedly large")
    if not dsn:
        raise ConfigurationError(f"{DSN_FILE_ENV} is empty")
    return dsn


def safe_error(
    *,
    action: str,
    code: str,
    message: str,
    blockers: Sequence[str] = (),
) -> dict[str, object]:
    return {
        "schema_version": 1,
        "action": action,
        "status": "error",
        "code": code,
        "message": message,
        "blockers": list(blockers),
    }


def _write_json(stream: TextIO, value: dict[str, object]) -> None:
    json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")


def _rollback_without_disclosure(connection: object) -> None:
    """Best-effort rollback without allowing a driver error to escape."""

    try:
        connection.rollback()  # type: ignore[attr-defined]
    except Exception:
        # libpq exceptions can contain host/user/database details.  The caller
        # already emits a fixed machine-readable error and must not leak these.
        pass


def _close_without_disclosure(connection: object) -> None:
    """Best-effort close without leaking connection details or masking output."""

    try:
        connection.close()  # type: ignore[attr-defined]
    except Exception:
        pass


def main(
    argv: Sequence[str] | None = None,
    *,
    environment: Mapping[str, str] | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    environment = os.environ if environment is None else environment
    stdout = sys.stdout if stdout is None else stdout
    stderr = sys.stderr if stderr is None else stderr

    try:
        validate_identifiers(args.user_id, args.device_id)
        dsn = load_dsn(environment)
        if args.action == "bind":
            if not args.confirm_logins_enforced:
                raise ConfigurationError(
                    "Bind requires --confirm-logins-enforced"
                )
            if (
                args.confirm_user_id != args.user_id
                or args.confirm_device_id != args.device_id
            ):
                raise ConfigurationError(
                    "Bind requires exact --confirm-user-id and "
                    "--confirm-device-id values"
                )
    except (ConfigurationError, OSError, UnicodeError):
        _write_json(
            stderr,
            safe_error(
                action=args.action,
                code="SC_MIGRATION_CONFIGURATION_ERROR",
                message="Migration configuration is invalid; no database change was made",
            ),
        )
        return 2

    try:
        import psycopg2
    except ImportError:
        _write_json(
            stderr,
            safe_error(
                action=args.action,
                code="SC_MIGRATION_POSTGRES_DRIVER_MISSING",
                message="psycopg2 is required; no database change was made",
            ),
        )
        return 5

    connection = None
    try:
        connection = psycopg2.connect(
            dsn,
            connect_timeout=10,
            application_name="securechat_single_device_migration",
        )
        connection.set_session(
            readonly=args.action == "plan",
            autocommit=False,
            isolation_level=(
                psycopg2.extensions.ISOLATION_LEVEL_SERIALIZABLE
                if args.action == "bind"
                else psycopg2.extensions.ISOLATION_LEVEL_READ_COMMITTED
            ),
        )
        with connection.cursor() as cursor:
            cursor.execute("SET LOCAL statement_timeout = '10s'")
            cursor.execute("SET LOCAL lock_timeout = '5s'")
            if args.action == "plan":
                plan = read_plan(
                    cursor,
                    user_id=args.user_id,
                    selected_device_id=args.device_id,
                )
                result = plan.as_dict(action="plan")
            else:
                plan = bind_existing_device(
                    cursor,
                    user_id=args.user_id,
                    selected_device_id=args.device_id,
                )
                result = plan.as_dict(action="bind")
                result["status"] = "bound"
                result["can_bind"] = False
                result["binding"] = {"device_id": args.device_id}

        connection.commit()
        _write_json(stdout, result)
        return 0
    except MigrationError as error:
        if connection is not None:
            _rollback_without_disclosure(connection)
        _write_json(
            stderr,
            safe_error(
                action=args.action,
                code=error.code.value,
                message=error.message,
                blockers=error.blockers,
            ),
        )
        return 3
    except Exception:
        if connection is not None:
            _rollback_without_disclosure(connection)
        # Deliberately do not emit the driver exception: libpq errors can contain
        # host, database, or user details.  DSN and credentials are never printed.
        _write_json(
            stderr,
            safe_error(
                action=args.action,
                code="SC_MIGRATION_DATABASE_ERROR",
                message="PostgreSQL operation failed; no migration change was committed",
            ),
        )
        return 5
    finally:
        if connection is not None:
            _close_without_disclosure(connection)


if __name__ == "__main__":
    raise SystemExit(main())
