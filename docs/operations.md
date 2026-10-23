# Installation, Operations and Troubleshooting

## Prerequisites (per node)

* A PhilHealth-approved Linux host or VM with Docker Engine and Compose v2. A non-Docker install
  is scripted in [deploy/native](../deploy/native/README.md) (static checks only; not yet run on a PhilHealth host).
* A TLS certificate for the host name, saved as `deploy/tls/qms.crt` and `deploy/tls/qms.key`.
* For a site node, outbound HTTPS to the central node.

## Install a node

```bash
cd deploy
cp env/site.env.example env/site.env      # central: env/central.env.example
chmod 600 env/site.env
# Fill in passwords, QMS_JWT_SECRET (openssl rand -base64 48), QMS_CORS_ORIGINS, sync settings.
./scripts/install-site.sh
```

First login: user `superadmin`, with the password from `QMS_BOOTSTRAP_ADMIN_PASSWORD`. You must
change it immediately. Then remove `QMS_BOOTSTRAP_ADMIN_PASSWORD` from the env file and restart
the `api` service.

Setting up a site:

1. On **central**:
   1. Create the region and site.
   2. Issue the site key: `POST /api/v1/sites/{id}/sync-key`.
   3. Put the key into the site's env file.
2. On the **site** (Admin pages):
   1. Enable site services, choosing a prefix and hours for each.
   2. Create counters and choose the services each counter serves.
   3. Create agent and supervisor accounts.
   4. Register kiosks and displays. Each device key is shown once.
3. On each kiosk and display PC, open `https://<site-host>/kiosk` or `/display` and enter its
   device key on the setup screen.

## Native (non-Docker) installation

Where Docker is not permitted (OI-02), a node can be installed directly on Ubuntu 24.04 LTS or a
RHEL 9-family host: OpenJDK 21, PostgreSQL 17 (PGDG), nginx and a hardened systemd unit
(`qms-api`), with native backup and restore scripts. See
[deploy/native/README.md](../deploy/native/README.md) for prerequisites, installation, upgrade and
uninstall. The scripts have not yet been run end to end on a test host. On a native node, replace
the `docker compose` commands in the table below with `systemctl` / `journalctl -u qms-api` and
`deploy/native/backup-native.sh` / `restore-native.sh`.

## Day-to-day operations

| Task | How |
|---|---|
| Health | `curl -k https://<host>/actuator/health` returns `{"status":"UP"}` |
| Logs | `docker compose logs -f api` |
| Sync backlog | Admin, then System Status (`pending`, `failed`, oldest pending), or `GET /api/v1/system/status` |
| Retry failed sync | Admin, then Sync Outbox, then Retry |
| Scheduled report files | Stored under `QMS_REPORTS_DIR` (Docker volume `reports`) and deleted after `QMS_REPORTS_RETENTION_DAYS` (default 180). They can be regenerated from the database, so backing them up is optional. |
| Backup | `deploy/scripts/backup.sh` (nightly via `deploy/systemd/qms-backup.timer`). Copy files off-host. |
| Backup on central | `QMS_ENV_FILE=env/central.env deploy/scripts/backup.sh` (the scripts read `env/site.env` by default; Compose needs the node's env file). |
| Restore | `docker compose --env-file env/site.env stop api && deploy/scripts/restore.sh <qms-db-…>.dump.gpg [<qms-media-…>.tgz.gpg] && docker compose --env-file env/site.env start api`. The script re-applies the least-privilege grants that `pg_restore` loosens. On a **site node**, start the API with the WAN up and wait for one configuration pull (System & Sync, "Last config pull") before opening kiosks: the pull tells the node the highest ticket numbers central already holds, so it does not reissue them. Clients served after the backup remain on central (reconciliation then shows central holding more for that day, which is expected). |
| Upgrade | `docker compose pull` (or build), then `docker compose up -d`. Flyway applies migrations automatically at start. **Back up first.** |
| Data retention | Daily at 02:15 (`QMS_RETENTION_CRON`). Tickets with their events and feedback older than `QMS_RETENTION_TICKET_DAYS`, and audit entries older than `QMS_RETENTION_AUDIT_DAYS`, are deleted; both default to 0 = keep forever until PhilHealth sets the policy (OI-13). Ticket retention below 31 days is refused, and a site node never deletes tickets whose changes have not reached central. Delivered sync entries (`QMS_RETENTION_SENT_OUTBOX_DAYS`, 30) and expired sign-in tokens (`QMS_RETENTION_EXPIRED_TOKEN_DAYS`, 30) are also cleaned. Each run that deletes something writes a `RETENTION_PURGE` audit entry with the counts. The database functions `qms_purge_tickets`/`qms_purge_audit` (V11) are the only way the application role can delete that history. |
| Idle sign-out | `QMS_IDLE_TIMEOUT_MINUTES` (default 30; 0 = off). |
| Phone e-ticket links | QR codes point at the kiosk's own address. If phones cannot reach it, set `QMS_TICKET_LINK_BASE_URL` to an address they can (it must serve this site node's portal and API). |
| Rotate a kiosk/display key | Admin, then Devices, then Rotate key. Enter the new key on the device. |

## TLS between site and central

Sites call `QMS_CENTRAL_URL` over HTTPS. If central's certificate is issued by a PhilHealth CA that
the Java runtime does not trust, give the site API a truststore with that CA and point Java at it
in the site env file, for example:

```bash
keytool -importcert -noprompt -alias philhealth-ca -file philhealth-ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass changeit
# docker-compose override: mount it into the api container at /opt/qms/tls/truststore.p12
JAVA_OPTS=-XX:MaxRAMPercentage=70 -Duser.timezone=UTC -Djavax.net.ssl.trustStore=/opt/qms/tls/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12
```

The 2026-10-22 drill used exactly this setup (test-plan.md).

## Monitoring

1. Set `QMS_METRICS_TOKEN` (a long random value) on each node.
2. Point Prometheus at each node's internal API port (`:8080`, path `/actuator/prometheus`) with
   that token as a bearer credential.
3. Load the alert rules.

Example files are in [deploy/monitoring](../deploy/monitoring/): `prometheus.yml` and
`qms-alerts.yml`, with 13 rules covering node down, errors, latency, the database pool, heap, disk,
sync failures and backlog age, long queues, waiting clients with no agent, queue time, and failed
scheduled reports, plus an audit trail that is no longer append-only. The thresholds are starting points to tune during the pilot.

4. Import the Grafana dashboard
   [deploy/monitoring/grafana/dashboards/qms-overview.json](../deploy/monitoring/grafana/dashboards/qms-overview.json)
   ("PhilHealth QMS": service health, queues by site, waiting-time percentiles, agent sessions, sync
   backlog, scheduled reports; filter by node). For provisioning, mount `grafana/provisioning` at
   `/etc/grafana/provisioning` and `grafana/dashboards` at `/var/lib/grafana/dashboards/qms`.

The rules and every dashboard query were checked with Prometheus 3.13.5 scraping a running node
(`promtool check rules`; the 12 rules then present loaded with health `ok`; `QmsAuditTrailWritable` was added later and passes `promtool check rules`). Grafana itself has not been run
against them yet.

## Troubleshooting

| Symptom | Check |
|---|---|
| Kiosk shows "service not available" | The service is inactive or outside its open/close hours, or the catalog item is inactive. |
| Kiosk or display shows the device setup screen | The key is wrong, rotated, or the device was deactivated. The API returns 401. |
| Display does not update instantly | It updates within 15 s anyway (polling). Check that the proxy forwards `/ws` with Upgrade headers. |
| "Start a counter session first" | The agent must pick a counter in the Agent Console. |
| "You already have an open session at another counter" | End that session, or have a supervisor force-close it. |
| Login "Account temporarily locked" | Wait 15 minutes, or have an administrator reset the password. |
| `PASSWORD_CHANGE_REQUIRED` | The user must change the temporary password. |
| Sync `failed` rising | Check `lastError` in the Sync Outbox, central reachability (`curl $QMS_CENTRAL_URL/actuator/health`), and that the site key is valid. |
| Tickets from yesterday still waiting | The expiry job runs hourly (`QMS_EXPIRE_CRON`). Check the logs for "Ticket expiry failed". |

## Building from source

```bash
cd backend && mvn verify          # Java 21 + Maven 3.9; integration tests use embedded PostgreSQL (no Docker needed)
cd frontend && npm ci && npm test -- --run && npm run build
```
