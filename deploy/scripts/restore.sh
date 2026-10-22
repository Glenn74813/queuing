#!/usr/bin/env bash
# Restores a database backup produced by backup.sh. DESTRUCTIVE: replaces the current database.
#   usage: restore.sh /var/backups/qms/qms-db-<stamp>.dump.gpg
# Stop the api service first: docker compose stop api
set -euo pipefail
cd "$(dirname "$0")/.."
FILE="${1:?usage: restore.sh <qms-db-*.dump.gpg>}"
STAMP="$(basename "$FILE" | sed -E 's/qms-db-(.*)\.dump\.gpg/\1/')"
SUMS="$(dirname "$FILE")/qms-$STAMP.sha256"
if [ -f "$SUMS" ]; then
  (cd "$(dirname "$FILE")" && grep "qms-db-$STAMP" "$SUMS" | sed "s#$(dirname "$FILE")/##" | sha256sum -c -)
fi
read -r -p "This replaces the QMS database with $FILE. Type RESTORE to continue: " ok
[ "$ok" = "RESTORE" ] || { echo "Aborted"; exit 1; }
gpg --batch --decrypt "$FILE" \
  | docker compose exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d qms --clean --if-exists --no-owner'
echo "Database restored. Start the API: docker compose start api"
