-- Run with psql and three pre-created, explicitly reviewed roles. Example:
--   psql "$ADMIN_DSN" \
--     --set=securechat_schema_owner=securechat_guard_owner \
--     --set=securechat_runtime_role=synapse \
--     --set=securechat_migration_role=securechat_guard_migrator \
--     --file=001_bindings_v1.sql
--
-- The owner should be NOLOGIN. The Synapse runtime role receives SELECT only;
-- the migration role receives only SELECT/INSERT on this module-owned table.
-- Privileges needed to read/lock Synapse-owned tables are deliberately not
-- granted here and must be reviewed separately for the one-shot migration.
\set ON_ERROR_STOP on
\if :{?securechat_schema_owner}
\else
  \echo 'missing --set=securechat_schema_owner=<pre-created-role>'
  \quit 3
\endif
\if :{?securechat_runtime_role}
\else
  \echo 'missing --set=securechat_runtime_role=<synapse-runtime-role>'
  \quit 3
\endif
\if :{?securechat_migration_role}
\else
  \echo 'missing --set=securechat_migration_role=<migration-role>'
  \quit 3
\endif

BEGIN;

CREATE TABLE IF NOT EXISTS public.securechat_single_device_bindings_v1 (
    user_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL CHECK (length(device_id) > 0),
    bound_ts BIGINT NOT NULL,
    claim_id TEXT NOT NULL
);

ALTER TABLE public.securechat_single_device_bindings_v1
    OWNER TO :"securechat_schema_owner";
REVOKE ALL ON TABLE public.securechat_single_device_bindings_v1 FROM PUBLIC;
REVOKE ALL ON TABLE public.securechat_single_device_bindings_v1
    FROM :"securechat_runtime_role", :"securechat_migration_role";
GRANT SELECT ON TABLE public.securechat_single_device_bindings_v1
    TO :"securechat_runtime_role";
GRANT SELECT, INSERT ON TABLE public.securechat_single_device_bindings_v1
    TO :"securechat_migration_role";

COMMIT;
