"""Synapse 1.159.0 adapter for the SecureChat single-device guard."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Collection

from synapse.config import ConfigError
from synapse.module_api import ModuleApi, NOT_SPAM, errors

from .core import (
    Decision,
    DecisionCode,
    Mode,
    error_payload,
    inspect_login_state,
    verify_schema,
)


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class GuardConfig:
    mode: Mode


class SecureChatSingleDeviceModule:
    """Fail-closed, read-only login gate for SecureChat accounts."""

    def __init__(self, config: GuardConfig, api: ModuleApi):
        self._config = config
        self._api = api
        self._schema_ready = False

        # The callback is registered before asynchronous schema setup.  Enforce
        # mode therefore rejects logins during startup instead of bypassing the
        # guard.  Audit mode remains observational by design.
        self._api.register_spam_checker_callbacks(
            check_login_for_spam=self.check_login_for_spam,
        )
        self._api.run_as_background_process(
            "securechat_single_device_schema_check",
            self._setup_schema,
        )

    @staticmethod
    def parse_config(raw: dict[str, object]) -> GuardConfig:
        if not isinstance(raw, dict):
            raise ConfigError("SecureChat single-device config must be a mapping")

        unknown = set(raw) - {"mode"}
        if unknown:
            raise ConfigError(
                "Unknown SecureChat single-device config key(s): "
                + ", ".join(sorted(unknown))
            )

        raw_mode = raw.get("mode")
        if raw_mode not in (Mode.AUDIT.value, Mode.ENFORCE.value):
            raise ConfigError(
                "SecureChat single-device 'mode' is required and must be "
                "'audit' or 'enforce'"
            )

        return GuardConfig(mode=Mode(raw_mode))

    async def _setup_schema(self) -> None:
        try:
            await self._api.run_db_interaction(
                "securechat_single_device_verify_schema",
                verify_schema,
            )
        except Exception:
            # Database exceptions can carry connection or parameter details.  Do
            # not serialize them to either the client or the operational log.
            logger.error(
                "SecureChat single-device schema check failed; details suppressed"
            )
            return

        self._schema_ready = True
        logger.info(
            "SecureChat single-device guard ready mode=%s",
            self._config.mode.value,
        )

    async def check_login_for_spam(
        self,
        user_id: str,
        device_id: str | None,
        initial_display_name: str | None,
        request_info: Collection[tuple[str | None, str]],
        auth_provider_id: str | None = None,
    ):
        # User-Agent, IP, display name and auth provider are intentionally not
        # trust signals.  They are spoofable or unrelated to device ownership.
        del initial_display_name, request_info, auth_provider_id

        # A client-chosen device_id is not an enrollment authority.  Enforce
        # rejects both bound and unbound password logins, and audit only
        # observes.  The value is deliberately not used or logged.
        del device_id

        if not self._schema_ready:
            if self._config.mode is Mode.AUDIT:
                decision = Decision(True, DecisionCode.GUARD_NOT_READY)
            else:
                decision = Decision(False, DecisionCode.GUARD_NOT_READY)
            return self._finish(decision)

        try:
            decision = await self._api.run_db_interaction(
                "securechat_single_device_inspect",
                inspect_login_state,
                mode=self._config.mode,
                user_id=user_id,
            )
        except Exception:
            if self._config.mode is Mode.AUDIT:
                decision = Decision(True, DecisionCode.DATABASE_ERROR)
            else:
                decision = Decision(False, DecisionCode.DATABASE_ERROR)
            logger.error(
                "SecureChat single-device database decision failed "
                "mode=%s allowed=%s; details suppressed",
                self._config.mode.value,
                decision.allowed,
            )

        return self._finish(decision)

    def _finish(self, decision: Decision):
        # Do not log MXID, device ID, IP, User-Agent, tokens, or message data.
        log = logger.info if decision.allowed else logger.warning
        log(
            "SecureChat single-device decision mode=%s code=%s allowed=%s",
            self._config.mode.value,
            decision.code.value,
            decision.allowed,
        )

        if decision.allowed:
            return NOT_SPAM
        return errors.Codes.FORBIDDEN, error_payload(decision)
