#!/usr/bin/env bash
# Encrypted daily backup of the QMS database and media.
#   QMS_BACKUP_DIR      destination directory (default /var/backups/qms)
#   QMS_BACKUP_GPG_RECIPIENT  GPG key id of the PhilHealth backup custodian (required)
#   QMS_BACKUP_RETENTION_DAYS (default 30)
# Schedule with cron or the provided systemd timer. Copy backups off-host per PhilHealth policy.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST="${QMS_BACKUP_DIR:-/var/backups/qms}"
RECIPIENT="${QMS_BACKUP_GPG_RECIPIENT:?set QMS_BACKUP_GPG_RECIPIENT}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$DEST"
umask 077

docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d qms -Fc' \
  | gpg --batch --yes --encrypt --recipient "$RECIPIENT" -o "$DEST/qms-db-$STAMP.dump.gpg"
docker compose run --rm --no-deps --entrypoint sh api \
  -c "tar -C /opt/qms/data -cz media" \
  | gpg --batch --yes --encrypt --recipient "$RECIPIENT" -o "$DEST/qms-media-$STAMP.tgz.gpg"

sha256sum "$DEST/qms-db-$STAMP.dump.gpg" "$DEST/qms-media-$STAMP.tgz.gpg" > "$DEST/qms-$STAMP.sha256"
find "$DEST" -name 'qms-*' -mtime +"${QMS_BACKUP_RETENTION_DAYS:-30}" -delete
echo "Backup written: $DEST/qms-*-$STAMP.*"
