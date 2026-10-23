#!/usr/bin/env bash
# Restores a backup produced by backup.sh. DESTRUCTIVE: replaces the current database (and media).
#   usage: restore.sh /var/backups/qms/qms-db-<stamp>.dump.gpg [/var/backups/qms/qms-media-<stamp>.tgz.gpg]
#   QMS_ENV_FILE  node env file (default env/site.env; central: env/central.env)
#   QMS_RESTORE_CONFIRM=RESTORE skips the interactive prompt (scripted restore drills)
# Stop the api service first: docker compose stop api
set -euo pipefail
cd "$(dirname "$0")/.."
# Compose needs the node's env file to resolve the passwords in docker-compose.yml.
ENV_FILE="${QMS_ENV_FILE:-env/site.env}"
[ -f "$ENV_FILE" ] || { echo "Env file $ENV_FILE not found; set QMS_ENV_FILE (e.g. env/central.env)" >&2; exit 1; }
dc() { QMS_ENV_FILE="$ENV_FILE" docker compose --env-file "$ENV_FILE" "$@"; }
FILE="${1:?usage: restore.sh <qms-db-*.dump.gpg> [qms-media-*.tgz.gpg]}"
MEDIA="${2:-}"
STAMP="$(basename "$FILE" | sed -E 's/qms-db-(.*)\.dump\.gpg/\1/')"
SUMS="$(dirname "$FILE")/qms-$STAMP.sha256"
if [ -f "$SUMS" ]; then
  (cd "$(dirname "$FILE")" && grep "qms-db-$STAMP" "$SUMS" | sed "s#$(dirname "$FILE")/##" | sha256sum -c -)
  if [ -n "$MEDIA" ]; then
    (cd "$(dirname "$MEDIA")" && grep "$(basename "$MEDIA")" "$SUMS" | sed "s#$(dirname "$MEDIA")/##" | sha256sum -c -)
  fi
fi
ok="${QMS_RESTORE_CONFIRM:-}"
[ -n "$ok" ] || read -r -p "This replaces the QMS database with $FILE. Type RESTORE to continue: " ok
[ "$ok" = "RESTORE" ] || { echo "Aborted"; exit 1; }
gpg --batch --decrypt "$FILE" \
  | dc exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d qms --clean --if-exists --no-owner'
# pg_restore recreates each table, which applies the schema's default grants (full DML for the
# application role); the dump only re-grants, it does not revoke. Re-apply least privilege (V3, V11)
# so the audit trail stays append-only.
APP_ROLE="$(dc exec -T db printenv QMS_DB_USER | tr -d '\r')"
dc exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d qms' <<SQL
DO \$\$
DECLARE app text := '${APP_ROLE:-qms_app}';
BEGIN
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_log, ticket_event FROM %I', app);
    EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', app);
    -- Backups taken before V11 have no purge functions; the API's migration creates them at start.
    IF to_regprocedure('qms_purge_audit(timestamptz, int)') IS NOT NULL THEN
        REVOKE ALL ON FUNCTION qms_purge_tickets(date, int), qms_purge_audit(timestamptz, int) FROM PUBLIC;
        EXECUTE format('GRANT EXECUTE ON FUNCTION qms_purge_tickets(date, int), qms_purge_audit(timestamptz, int) TO %I', app);
    END IF;
END
\$\$;
SQL
echo "Least-privilege grants re-applied."
if [ -n "$MEDIA" ]; then
  gpg --batch --decrypt "$MEDIA" \
    | dc run --rm --no-deps -T --entrypoint sh api -c "rm -rf /opt/qms/data/media/* && tar -C /opt/qms/data -xz"
  echo "Media restored."
fi
echo "Database restored. Start the API: docker compose start api"
