#!/usr/bin/env bash
# Encrypted backup of a native (non-Docker) QMS node: database (pg_dump, custom format) and media.
# Native variant of deploy/scripts/backup.sh. Run as root (cron or a systemd timer).
#   QMS_BACKUP_DIR             destination directory (default /var/backups/qms)
#   QMS_BACKUP_GPG_RECIPIENT   GPG key id of the PhilHealth backup custodian (required; the public key
#                              must be in root's keyring, or in $GNUPGHOME)
#   QMS_BACKUP_RETENTION_DAYS  (default 30)
# Copy backups off-host per PhilHealth policy.
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "ERROR: run as root" >&2; exit 1; }

DEST="${QMS_BACKUP_DIR:-/var/backups/qms}"
RECIPIENT="${QMS_BACKUP_GPG_RECIPIENT:?set QMS_BACKUP_GPG_RECIPIENT}"
RETENTION="${QMS_BACKUP_RETENTION_DAYS:-30}"
DATA_DIR=/opt/philhealth-qms/data
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DB_FILE="$DEST/qms-db-$STAMP.dump.gpg"
MEDIA_FILE="$DEST/qms-media-$STAMP.tgz.gpg"

# pg_dump from the installed PostgreSQL 17 (Debian/Ubuntu or PGDG RHEL layout).
PG_DUMP=""
for c in /usr/lib/postgresql/17/bin/pg_dump /usr/pgsql-17/bin/pg_dump; do
  if [ -x "$c" ]; then PG_DUMP=$c; break; fi
done
[ -n "$PG_DUMP" ] || { echo "ERROR: PostgreSQL 17 pg_dump not found" >&2; exit 1; }

umask 077
mkdir -p "$DEST"
cd /

# Remove partial files if any step fails.
trap 'rm -f "$DB_FILE" "$MEDIA_FILE"' ERR

# The dump runs as the postgres superuser over the local socket (peer authentication); no password needed.
runuser -u postgres -- "$PG_DUMP" -d qms -Fc \
  | gpg --batch --yes --encrypt --recipient "$RECIPIENT" -o "$DB_FILE"
tar -C "$DATA_DIR" -cz media \
  | gpg --batch --yes --encrypt --recipient "$RECIPIENT" -o "$MEDIA_FILE"

trap - ERR
sha256sum "$DB_FILE" "$MEDIA_FILE" > "$DEST/qms-$STAMP.sha256"
find "$DEST" -maxdepth 1 -name 'qms-*' -type f -mtime +"$RETENTION" -delete
echo "Backup written: $DEST/qms-*-$STAMP.*"
