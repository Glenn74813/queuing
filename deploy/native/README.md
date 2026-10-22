# Native (non-Docker) installation

For PhilHealth hosts where Docker is not permitted (open issue OI-02). The node runs the same
software as the Docker Compose stack, installed directly on the host:

| Docker Compose service | Native equivalent |
|---|---|
| `db` (postgres:17) | PostgreSQL 17 from the PostgreSQL project's repository (PGDG) |
| `api` | `qms-api.service` (systemd), `java -jar qms-backend.jar` on OpenJDK 21, listening on 127.0.0.1:8080 |
| `web` | Built frontend files served by nginx from `/opt/philhealth-qms/web` |
| `proxy` (nginx) | Distribution nginx with `nginx-qms.conf` |

> **Status: not yet validated.** The scripts pass `bash -n` and a `--dry-run` on a development
> machine. They have **not** been run end to end on Ubuntu 24.04 or RHEL 9, nor on PhilHealth
> hardware. Do a full trial install, backup and restore on a test VM before using them on a site.

## Files

| File | Purpose |
|---|---|
| `install-native.sh` | Idempotent installer (Ubuntu 24.04 LTS, RHEL 9 family). `--dry-run` prints actions without executing. |
| `qms-api.service` | systemd unit for the API, with sandboxing (see the comments in the file). |
| `nginx-qms.conf` | TLS reverse proxy and SPA server; native equivalent of `deploy/nginx/qms.conf`. |
| `qms.env.example` | Environment template, installed as `/etc/philhealth-qms/qms.env`. |
| `backup-native.sh` | Encrypted backup: `pg_dump` + media tar, GPG, SHA-256 manifest, retention. |
| `restore-native.sh` | Restore of a backup (asks you to type `RESTORE`; stops and starts `qms-api`). |

## Prerequisites

* Ubuntu 24.04 LTS or RHEL 9 family (RHEL, Rocky, AlmaLinux, Oracle Linux), 64-bit, with root access.
  The proposed sizing is in [hardware-compatibility.md](../../docs/hardware-compatibility.md) (unvalidated).
* Outbound HTTPS from the host to the distribution mirrors and to the PGDG repository
  (`apt.postgresql.org` or `download.postgresql.org`), or an internal mirror of both. If PGDG
  cannot be reached, the installer stops and prints what to install by hand; it does not fall back
  to another PostgreSQL version.
* Ports 80 and 443 free (no other web server). PostgreSQL port 5432 free, or already used by the
  PostgreSQL 17 cluster that the installer configures.
* A TLS certificate and key for the host name, issued by PhilHealth's CA.
* Build artifacts: `qms-backend.jar` (`cd backend && mvn package`, file `backend/target/qms-backend.jar`)
  and the frontend `dist/` directory (`cd frontend && npm ci && npm run build`). Build them on a
  build machine; the server needs no JDK, Maven or Node.
* For backups: `gpg` with the public key of the PhilHealth backup custodian in root's keyring.

## Install

```bash
# On the server, with this deploy/native directory and the two artifacts copied to it:
sudo ./install-native.sh --dry-run --jar qms-backend.jar --web dist   # optional preview
sudo ./install-native.sh --jar qms-backend.jar --web dist
```

The first run installs the packages, creates the `qms` system user and the directories, copies
`qms.env.example` to `/etc/philhealth-qms/qms.env` and stops. Then:

1. Edit `/etc/philhealth-qms/qms.env`:
   * `QMS_DB_OWNER_PASSWORD` and `QMS_DB_PASSWORD` (two different strong passwords);
   * `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`, equal to `QMS_DB_OWNER` / `QMS_DB_OWNER_PASSWORD`;
   * `QMS_JWT_SECRET` (`openssl rand -base64 48`), `QMS_CORS_ORIGINS`, `QMS_BOOTSTRAP_ADMIN_PASSWORD`;
   * site node: `QMS_SITE_CODE`, `QMS_CENTRAL_URL`, `QMS_SYNC_SITE_KEY`; central node:
     `QMS_NODE_ROLE=CENTRAL`, `QMS_SYNC_ENABLED=false`.
   Values are not quoted (systemd reads the file).
2. Copy the certificate to `/opt/philhealth-qms/tls/qms.crt` and the key to `/opt/philhealth-qms/tls/qms.key`.
3. Re-run the same command. It then:
   1. creates the roles `qms_owner` (schema owner, runs Flyway) and `qms_app` (application login),
      sets their passwords from the env file, and creates database `qms` owned by `qms_owner`;
   2. grants `qms_app` only `CONNECT` and `USAGE` on schema `public` and revokes `CREATE` on
      `public` from `PUBLIC`, as `deploy/scripts/db-init.sh` does. Migration V3, run by Flyway as
      the owner at the first start, grants `qms_app` DML (see [database.md](../../docs/database.md));
   3. adds a managed block to `pg_hba.conf` allowing SCRAM password logins of the two roles from
      127.0.0.1 / ::1 only, and checks that `qms_app` can log in;
   4. installs the nginx site and the `qms-api` unit, enables and starts both, and waits until
      `https://127.0.0.1/actuator/health` reports `UP`.
4. Sign in as `superadmin` with `QMS_BOOTSTRAP_ADMIN_PASSWORD` and change the password. Remove
   `QMS_BOOTSTRAP_ADMIN_PASSWORD` from the env file and run `systemctl restart qms-api`.
5. Continue with the site setup in [operations.md](../../docs/operations.md#install-a-node).

Host-specific changes the installer makes:

| Host | Change |
|---|---|
| Ubuntu | Removes the `sites-enabled/default` nginx link. |
| RHEL | Removes `default_server` from the stock server block in `/etc/nginx/nginx.conf` (backup kept). With SELinux enabled: `setsebool -P httpd_can_network_connect 1`, and labels `web/` as `httpd_sys_content_t` and `tls/` as `cert_t`. |
| Both | Prepends a `# BEGIN/END philhealth-qms` block to `pg_hba.conf` (backup kept). |

The installer does **not** change the host firewall. If firewalld or ufw is active it prints the
command that would open HTTP/HTTPS; apply it according to PhilHealth network policy.

## Operations

| Task | Command |
|---|---|
| Status | `systemctl status qms-api nginx postgresql` (RHEL: `postgresql-17`) |
| Logs | `journalctl -u qms-api -f` |
| Health | `curl -fsk https://127.0.0.1/actuator/health` |
| Restart after an env change | `systemctl restart qms-api` |
| Backup | `QMS_BACKUP_GPG_RECIPIENT=<key id> /usr/local/sbin/backup-native.sh` (see below) |
| Restore | `restore-native.sh /var/backups/qms/qms-db-<stamp>.dump.gpg [/var/backups/qms/qms-media-<stamp>.tgz.gpg]` |

Scheduling backups: copy `backup-native.sh` to `/usr/local/sbin/`, and either add a root cron entry
(for example `0 20 * * * QMS_BACKUP_GPG_RECIPIENT=<key id> /usr/local/sbin/backup-native.sh`) or
adapt `deploy/systemd/qms-backup.service` by pointing `ExecStart=` at the script and
`EnvironmentFile=` at a root-only file holding the `QMS_BACKUP_*` variables. Copy backup files
off-host. The restore asks you to type `RESTORE`, stops `qms-api`, restores the database as the
owner role (so later migrations still work), optionally replaces the media directory, and starts
`qms-api` again. If a step fails it leaves `qms-api` stopped.

## Upgrade

1. Read the release notes for new or changed environment variables, and update `qms.env`.
2. Take a backup (`backup-native.sh`) and confirm the files exist.
3. `systemctl stop qms-api`
4. Re-run the installer with the new artifacts:
   `sudo ./install-native.sh --jar qms-backend.jar --web dist`. It replaces the jar and the web
   files, refreshes the unit and nginx site from this directory, and starts `qms-api`. Flyway
   applies any new migrations at start.
5. Confirm health, sign in, and check **System & Sync** for outbox errors.

Rollback: Flyway migrations are not reversible. To go back to the previous version, restore the
backup taken in step 2 and reinstall the previous jar and web files.

## Uninstall

```bash
systemctl disable --now qms-api
rm -f /etc/systemd/system/qms-api.service && systemctl daemon-reload
rm -f /etc/nginx/sites-enabled/philhealth-qms.conf /etc/nginx/sites-available/philhealth-qms.conf  # Ubuntu
rm -f /etc/nginx/conf.d/philhealth-qms.conf                                                        # RHEL
systemctl reload nginx
# Data removal is irreversible; take and verify a backup first.
runuser -u postgres -- psql -c 'DROP DATABASE qms' -c 'DROP ROLE qms_app' -c 'DROP ROLE qms_owner'
rm -rf /opt/philhealth-qms /etc/philhealth-qms
userdel qms
```

Then remove the `philhealth-qms` block from `pg_hba.conf`. On RHEL, restore `nginx.conf` from its
`.bak-qms-*` copy if needed. PostgreSQL, nginx and Java are left installed; remove them with the
package manager if nothing else uses them.

## File layout

| Path | Owner / mode | Contents |
|---|---|---|
| `/opt/philhealth-qms/app/qms-backend.jar` | root, 0644 | Backend application |
| `/opt/philhealth-qms/web/` | root, 0755/0644 | Built frontend (`index.html`, `assets/`) |
| `/opt/philhealth-qms/data/media/` | qms:qms, 0750 | CMS uploads (`QMS_MEDIA_DIR`); the only path the service may write |
| `/opt/philhealth-qms/tls/qms.crt`, `qms.key` | root, 0644 / 0600 | TLS certificate and key (read by nginx's master process) |
| `/etc/philhealth-qms/qms.env` | root:qms, 0600 | Configuration and secrets |
| `/etc/systemd/system/qms-api.service` | root, 0644 | API service unit |
| `/etc/nginx/sites-available/philhealth-qms.conf` (Ubuntu) or `/etc/nginx/conf.d/philhealth-qms.conf` (RHEL) | root, 0644 | nginx site |
| PostgreSQL data directory | postgres | Ubuntu: `/var/lib/postgresql/17/main`; RHEL: `/var/lib/pgsql/17/data` |
| `/var/backups/qms/` | root, 0700 | Default backup destination |

## Notes and limitations

* The API listens on 127.0.0.1 only (`SERVER_ADDRESS` in the env file); nginx is its only client.
* `nginx-qms.conf` uses `listen 443 ssl http2` instead of `http2 on;`, because the distribution
  nginx packages predate that directive. It listens on IPv4 only, like the Docker proxy.
* `MemoryDenyWriteExecute=` is intentionally absent from the unit: the JVM JIT needs writable and
  executable memory.
* Log rotation is handled by journald. Set its size limits (`SystemMaxUse=`) per PhilHealth policy.
* Kiosk and display devices are configured as for the Docker install; see
  [kiosk-lockdown.md](../../docs/kiosk-lockdown.md).
