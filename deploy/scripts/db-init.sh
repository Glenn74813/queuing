#!/bin/sh
# Runs once when the database volume is created. Creates the application login role.
# The schema is owned by $POSTGRES_USER, which also runs Flyway migrations (SPRING_FLYWAY_USER).
# Migration V3 grants the application role DML only, with audit_log and ticket_event append-only.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname qms <<SQL
CREATE ROLE ${QMS_DB_USER} LOGIN PASSWORD '${QMS_DB_PASSWORD}';
GRANT CONNECT ON DATABASE qms TO ${QMS_DB_USER};
GRANT USAGE ON SCHEMA public TO ${QMS_DB_USER};
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
