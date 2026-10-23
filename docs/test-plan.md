# Test Plan and Current Results

## Automated tests (run on every build)

Last full run: 2026-10-22 on OpenJDK 21.0.12.1 (Ubuntu package `openjdk-21`, `/usr/lib/jvm/java-21-openjdk-amd64`) with Maven 3.9.9, `mvn clean verify`. The produced jar targets class-file version 65 (Java 21).

| Suite | Command | Scope | Last result |
|---|---|---|---|
| Backend unit | `mvn test` | State machine, time accumulators, number format, CSV formula neutralization, upload magic-byte detection, sync backoff | Passing |
| Backend integration (embedded PostgreSQL 17, real Flyway schema) | `mvn test` | `QueueFlowIT`: full lifecycle (issue, idempotent retry, priority ordering, recall, hold, resume, transfer/multi-stage, complete, remove, void, feedback, display board, report reconciliation, XLSX export), counter occupancy, supervisor force-close. `ConcurrencyIT`: 60 parallel issues give unique, gap-free numbers; 2 parallel agents draining 20 tickets have no double calls. `SecurityIT`: 401/403 handling, headers, CORS allowlist, site and region scope, hierarchical user admin, forced password change, immediate suspension, lockout, refresh-token rotation and reuse detection, and list endpoints without optional filters. `DatabasePrivilegesIT`: the application role has DML only, cannot change the schema, and cannot update or delete audit or ticket history. `SyncIngestIT` also covers reconciliation (MATCH/MISMATCH, forged site rejected). Unit: reconciliation status rules, PDF export. `CatalogIT`: effective dates block or allow ticket issuance, every edit is versioned, and only national managers may edit the catalog. `ContentDistributionIT`: site/region/national targeting, media download authorization, corrupted or oversized downloads rejected, path traversal blocked, replicas read-only and never overwriting local content. `ReportScheduleIT`: schedule scope (own site only), run-now and scheduled runs, no double run, CSV/XLSX/PDF validity, another site cannot see or download, ticket-level and audit CSV exports. `ReportPeriodsTest`: day/week/month boundaries, leap February, exact 01:00 boundary. `RoutingIT`: next-stage configuration (loops, self and other-site targets rejected) and the one-click move with event history. `MetricsIT`: scrape-token protection and metric values. `SecurityIT`: refresh-token lost-response grace versus theft detection. `SyncIngestIT`: idempotency, integrity check, foreign-site rejection, version conflict | **52/52 passing** |
| Frontend unit/component | `npm test -- --run` | Permission guard, error rendering, agent action enablement, kiosk idempotent retry, metric formatting, upload checks, display call detection | **47/47 passing** |
| Frontend type check and build | `npm run build` | Strict TypeScript | Passing |
| Browser end-to-end (Playwright, real backend + PostgreSQL) | `e2e/scripts/run-local.sh` | Login, forced password change, session survives reload, sign-out; kiosk → agent → TV display full flow across three browsers; supervisor Close counter; reports, Trends and Scheduled Reports (Run now + download); role-based access (agent cannot reach admin pages); device keys (kiosk and display in one browser, rotated key returns to setup); display voice announcement on call and recall; a new site is selectable immediately; regional SLA set in the UI and agent performance reported against it (PDF and Excel downloads); dashboard national overview, drill-down to a site, and Services availed chart | **14/14 passing** (latest build) |

## Not yet performed

Each of these needs an environment, hardware or PhilHealth participation.

| Test | Needed |
|---|---|
| Cross-browser end-to-end runs (Firefox/WebKit, mobile viewports) | The suite runs on Chrome/Chromium only so far |
| Load and stress test (k6/Gatling) at agreed volumes | Capacity figures (OI-01) and target infrastructure (OI-02) |
| Offline drill over hours (the 2026-10-22 drill cut the WAN for minutes) | Two-node staging environment |
| Printer and kiosk compatibility | Procured devices (OI-15) |
| Cross-browser and responsive checks | Device and browser list |
| DR drill on target infrastructure (restore to a different host, timed RPO) | Staging environment |
| Accessibility audit | Standard confirmation (OI-24) |
| Third-party VAPT | Provider engagement (OI-22) |
| UAT at representative sites | PhilHealth scheduling |

## Docker Compose two-node, offline and restore drill — 2026-10-22

Environment: the production Dockerfiles and `deploy/docker-compose.yml` (unchanged), run as two
Compose projects on one Docker host: a central node and a site node (`DRILL01`), each with
PostgreSQL 17, API, web and the TLS proxy. Env files were made from `deploy/env/*.example`. The
site reached central over HTTPS through a separate "WAN" Docker network, trusting a drill CA through a
PKCS12 truststore (`JAVA_OPTS`, see operations.md). The drill script drove the public APIs only.

| Step | Result |
|---|---|
| Build both images from the Dockerfiles; start both stacks | Healthy behind TLS in about 1 minute |
| Central: create region and site, issue site key. Site: first config pull | Site row received over TLS within 2 s; 28 catalog services added on the site |
| Online: 5 tickets issued and served | Central caught up within 1 s (5 tickets, 15 events) |
| WAN cut (`docker network disconnect`); 18 more tickets issued, called, completed | Kiosk, agent and local API unaffected. Entries queued in the outbox: 31 at the peak. Metrics showed them within 30 s. An open keep-alive connection carried a few pushes for about 6 s after the cut. |
| WAN restored | Central caught up after 49 s: 23 tickets, 69 events, no loss or duplicates. Reconciliation: **MATCH** |
| `backup.sh` (encrypted to a GPG key); 4 more tickets; media file deleted; `restore.sh` with database and media | Backup in 4.5 s. Database and media restored; node healthy 30 s after stop (13 s in the second run) |
| Upgrade both nodes in place to a new image (migration V12 on live data) | Both healthy; previously rejected entries drained |

Defects found and fixed in the same session:

1. `backup.sh`/`restore.sh` ran `docker compose` without the node's env file, so Compose refused to
   start (`required variable QMS_DB_PASSWORD is missing`): **nightly backups would have failed on
   every node**. Both scripts now pass `QMS_ENV_FILE` (default `env/site.env`).
2. `backup.sh` streamed the media archive through `docker compose run` without `-T`; run from a
   terminal, the TTY would corrupt the archive. Added `-T`.
3. `restore.sh` restored only the database. It now also restores the media archive (second
   argument), and `QMS_RESTORE_CONFIRM=RESTORE` allows scripted drills.
4. **After a restore the audit trail was no longer append-only.** `pg_restore --clean` recreates
   tables with the schema's default grants (full DML for the application role), and the dump
   re-grants without revoking. `restore.sh` and the native `restore-native.sh` (same pattern; its SQL was run against a local database) now re-apply the least-privilege grants. A new gauge
   `qms_db_history_writable` and alert `QmsAuditTrailWritable` catch it in any case. Verified: a
   deliberate `GRANT DELETE` raised the gauge and logged an error within 30 s, and revoking cleared it.
5. **A site restored from an older backup reissued numbers central already held** (A-013 again).
   Central rejected those records with a unique-key error, so they would never have reached central.
   Fixes: central accepts the same number for different tickets (V12, central only), and each config
   pull gives the site the highest number central holds for the current period. Verified: the stuck
   entries drained after the upgrade, and after a second restore the site continued at A-018 above
   central's A-017.
6. After a long outage the exponential backoff (up to 15 min) delayed catch-up. Transport errors now
   retry at least every 60 s; rejections keep the long backoff.
7. The new native env template lines had inline `# comments`, which systemd `EnvironmentFile` would
   have passed into the values (for example `QMS_IDLE_TIMEOUT_MINUTES`). Moved them to their own lines.

Not covered: hours-long outages, a restore onto a different host, Docker on the target OS, and the
nginx `install-site.sh` wrapper (the drill started Compose directly).

## Upgrade and trend-chart check — 2026-10-22

* **Upgrade path:** the build was started against the existing V4 development database
  (two-account setup). Flyway applied V5 (effective dates and history) and V6 (content
  distribution) as the owner, with no errors.
* **Trends chart, rendered against 14 days of sample data:**
  * Crosshair tooltip values matched the database (Oct 12: 35 issued).
  * Arrow-key reading worked (Oct 20: 14.2 min average wait).
  * The table view works.
  * Two layout defects were found and fixed: an end label overlapping the x-axis, and the last
    date tick under the end labels.
* **Catalog page:** the version history view showed the migration-created version 1.
* **Chart palette:** validated with the dataviz palette validator (light and dark, all checks
  pass). Light-mode aqua is below 3:1 contrast, so the chart has direct labels and a table view.

## Production-like database check — 2026-10-22

A fresh database was set up with two accounts: `SPRING_FLYWAY_USER` as the owner, and the application
connecting as a separate login role.

* All 4 migrations applied, and the owner owns all 27 tables.
* The application started and Hibernate schema validation passed under the restricted role.
* The application role has only INSERT and SELECT on `audit_log`.
* Seeding through the API, kiosk issuance (A-001…A-003), the audit query and the PDF export
  (`%PDF-2.0`, rendered and inspected) all worked, with zero server errors.

## Manual smoke test — 2026-10-22 (development machine)

The backend jar ran against a local PostgreSQL 17, with the Vite dev server in a browser.

1. **Seeding (through the API):**
   1. bootstrap `superadmin` and a forced password change;
   2. region and site;
   3. 3 site services and 2 counters;
   4. agent and supervisor accounts;
   5. kiosk and display devices.
2. **Kiosk:** live service list, Senior Citizen priority step, and an on-screen ticket `A-001`.
3. **Agent:** login and forced password change, then the dashboard, a session at Counter 1,
   Call next (A-001), and Complete.
4. **Display:** showed "Now serving A-001 → Counter 1" and moved it to recent calls within 2 s of
   completion (WebSocket).
5. **Reports:** the summary and CSV export reconciled (1 issued, 1 served).

Defects found and fixed during the run:

* After a password change, the UI kept the revoked refresh token, so the session ended at the
  next page load.
* `GET /audit` and `GET /users` without optional filters failed with HTTP 500 (null-parameter
  typing). A regression test now covers this.

Acceptance criteria per requirement are tracked in
[requirements-traceability-matrix.md](requirements-traceability-matrix.md).
