-- Least privilege (OI-14). Migrations run as the schema owner (QMS_DB_MIGRATION_USER); the
-- application connects as ${appRole}, which gets data access only: no DDL, and no UPDATE/DELETE on
-- the append-only audit and ticket-history tables. Skipped when both are the same role
-- (single-account development setups and the automated tests' default database).
DO $$
DECLARE
    app text := '${appRole}';
BEGIN
    IF app <> current_user AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app);
        EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', app);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', app);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', app);
        EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', app);
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_log, ticket_event FROM %I', app);
        -- Tables created by later migrations (run by this owner) get the same DML grants.
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', app);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO %I', app);
    END IF;
END
$$;
