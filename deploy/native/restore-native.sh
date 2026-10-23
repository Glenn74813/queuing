#!/usr/bin/env bash
# Restores a backup produced by backup-native.sh (or deploy/scripts/backup.sh) on a native node.
# DESTRUCTIVE: replaces the current database (and, if given, the media directory).
#   usage: restore-native.sh /var/backups/qms/qms-db-<stamp>.dump.gpg [/var/backups/qms/qms-media-<stamp>.tgz.gpg]
# Stops qms-api, restores, and starts qms-api again. Run as root; the GPG private key of the backup
# custodian must be available to gpg.
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "ERROR: run as root" >&2; exit 1; }

FILE="${1:?usage: restore-native.sh <qms-db-*.dump.gpg> [qms-media-*.tgz.gpg]}"
MEDIA="${2:-}"
ENV_FILE=/etc/philhealth-qms/qms.env
DATA_DIR=/opt/philhealth-qms/data
[ -f "$FILE" ] || { echo "ERROR: $FILE not found" >&2; exit 1; }
[ -z "$MEDIA" ] || [ -f "$MEDIA" ] || { echo "ERROR: $MEDIA not found" >&2; exit 1; }
FILE="$(realpath -- "$FILE")"
[ -z "$MEDIA" ] || MEDIA="$(realpath -- "$MEDIA")"

PG_BIN=""
for c in /usr/lib/postgresql/17/bin /usr/pgsql-17/bin; do
  if [ -x "$c/pg_restore" ]; then PG_BIN=$c; break; fi
done
[ -n "$PG_BIN" ] || { echo "ERROR: PostgreSQL 17 pg_restore not found" >&2; exit 1; }

# Objects are restored as the schema owner role so that Flyway (which runs as that role) can migrate later.
OWNER="$(grep -E '^QMS_DB_OWNER=' "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '[:space:]')"
OWNER="${OWNER:-qms_owner}"

# Verify checksums when the manifest is next to the backup.
STAMP="$(basename "$FILE" | sed -E 's/qms-db-(.*)\.dump\.gpg/\1/')"
SUMS="$(dirname "$FILE")/qms-$STAMP.sha256"
if [ -f "$SUMS" ]; then
  for f in "$FILE" ${MEDIA:+"$MEDIA"}; do
    grep -F " $f" "$SUMS" | sha256sum -c - || { echo "ERROR: checksum mismatch for $f" >&2; exit 1; }
  done
else
  echo "WARNING: no checksum manifest $SUMS; integrity not verified." >&2
fi

echo "This replaces the QMS database${MEDIA:+ and media} with:"
echo "  $FILE"
[ -z "$MEDIA" ] || echo "  $MEDIA"
read -r -p "The qms-api service will be stopped during the restore. Type RESTORE to continue: " ok
[ "$ok" = "RESTORE" ] || { echo "Aborted"; exit 1; }

cd /
systemctl stop qms-api
trap 'echo "Restore FAILED. qms-api is left stopped; investigate, then: systemctl start qms-api" >&2' ERR

gpg --batch --decrypt "$FILE" \
  | runuser -u postgres -- "$PG_BIN/pg_restore" -d qms --clean --if-exists --no-owner --role="$OWNER"
echo "Database restored."

# pg_restore recreates each table, which applies the owner's default grants (full DML for the
# application role); the dump only re-grants. Re-apply least privilege (V3, V11) so the audit trail
# stays append-only (found in the 2026-10-22 restore drill, docs/test-plan.md).
APP="$(grep -E '^QMS_DB_USER=' "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '[:space:]')"
APP="${APP:-qms_app}"
runuser -u postgres -- "$PG_BIN/psql" -v ON_ERROR_STOP=1 -q -d qms <<SQL
DO \$\$
BEGIN
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_log, ticket_event FROM %I', '$APP');
    EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', '$APP');
    IF to_regprocedure('qms_purge_audit(timestamptz, int)') IS NOT NULL THEN
        REVOKE ALL ON FUNCTION qms_purge_tickets(date, int), qms_purge_audit(timestamptz, int) FROM PUBLIC;
        EXECUTE format('GRANT EXECUTE ON FUNCTION qms_purge_tickets(date, int), qms_purge_audit(timestamptz, int) TO %I', '$APP');
    END IF;
END
\$\$;
SQL
echo "Least-privilege grants re-applied."

if [ -n "$MEDIA" ]; then
  rm -rf "$DATA_DIR/media.restore"
  mkdir -p "$DATA_DIR/media.restore"
  gpg --batch --decrypt "$MEDIA" | tar -C "$DATA_DIR/media.restore" -xz
  [ -d "$DATA_DIR/media.restore/media" ] || { echo "ERROR: archive does not contain media/" >&2; false; }
  rm -rf "$DATA_DIR/media.old"
  if [ -d "$DATA_DIR/media" ]; then mv "$DATA_DIR/media" "$DATA_DIR/media.old"; fi
  mv "$DATA_DIR/media.restore/media" "$DATA_DIR/media"
  rm -rf "$DATA_DIR/media.restore" "$DATA_DIR/media.old"
  chown -R qms:qms "$DATA_DIR/media"
  chmod 0750 "$DATA_DIR/media"
  echo "Media restored."
fi

trap - ERR
systemctl start qms-api
echo "qms-api started. Check: curl -fsk https://127.0.0.1/actuator/health"
