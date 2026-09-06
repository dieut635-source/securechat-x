# Analytics and crash reporting

Analytics and Sentry crash reporting are disabled by default. `ModulesConfig` only includes a
provider when `plugins/src/main/kotlin/config/BuildTimeConfig.kt` contains a complete
SecureChat-owned configuration:

- PostHog requires both `SERVICES_POSTHOG_HOST` and `SERVICES_POSTHOG_APIKEY`.
- Sentry requires `SERVICES_SENTRY_DSN`; `SERVICES_SENTRY_DSN_RUST` is optional for Rust SDK events.

Do not restore inherited endpoints or environment-variable names. Enabling either provider requires
a SecureChat privacy review, SecureChat-owned service credentials, updated user-facing policy text,
and tests confirming that opt-out remains effective. Remote bug-report upload is independently
disabled while `BUG_REPORT_URL` is null.
