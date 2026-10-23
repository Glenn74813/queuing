# Requirements Traceability Matrix

**Rule:** "Implemented" does not mean "compliant". A requirement is compliant only after it is
**Accepted**: verified against the approved specification, with test evidence, and signed off by
PhilHealth. **No requirement has been accepted yet.**

Status legend:

* **Built+Tested**: the code exists and an automated test exercises it.
* **Built**: the code exists but no automated test covers it yet.
* **Partial**: part of the requirement is built; the remainder is listed.
* **Not built**: nothing is built yet.
* **Blocked**: needs a PhilHealth decision or input (see [open-issues.md](open-issues.md)).
* **Process**: a contractual or service deliverable, not software.

Section numbers refer to the project development prompt or specification.

## 2–3 Technology and architecture

| ID | Requirement | Implementation | Test / evidence | Status | Acceptance |
|---|---|---|---|---|---|
| R-2.1 | Java 17, Spring Boot 3.x, Web, Security, JWT, JPA, Validation, WebSocket, Scheduling, Flyway, Maven, OpenAPI | `backend/pom.xml` (Boot 3.5.16, **Java 21**) | Build passes | Built+Tested; **deviation**: Java 21 instead of the specified 17 | Pending (OI-20, OI-25) |
| R-2.2 | React + TypeScript + Vite, Router, Axios, Bootstrap, WebSocket client | `frontend/package.json` | `npm run build` | Built+Tested | Pending |
| R-2.3 | PostgreSQL 17, normalized schema, FKs, indexes, constraints, migrations | `V1__core_schema.sql`, `V2__reference_data.sql` | Integration tests run against PostgreSQL 17 | Built+Tested | Pending |
| R-2.4 | Docker / Compose, env-specific configuration; installation on approved hosts | `deploy/docker-compose.yml`, `deploy/env/*.example`; non-Docker installer `deploy/native` (systemd, nginx, PostgreSQL) | Compose run from the production Dockerfiles as a central and a site node with TLS (test-plan, 2026-10-22 drill); native installer dry-run and static checks only | Built+Tested (Compose); native not yet run | Pending (OI-02) |
| R-2.5 | Automated build, test, deploy, backup, recovery | Maven/npm builds; CI workflow; `install-site.sh`, `backup.sh`, `restore.sh` (database and media; re-applies least privilege) | Backup and restore drilled twice on a Compose site node (4 defects found and fixed, see test-plan); CI not yet activated | Partial: CI not active; no DR drill on target infrastructure | Pending |
| R-3.1 | Local site operation independent of the central node | Per-site node; outbox and pull jobs isolated from request handling | Design only; no outage drill done | Built | Pending |
| R-3.2 | Central management and synchronization | `sync` package | `SyncIngestIT` | Partial (see sync-design limitations) | Pending |
| R-3.3 | Integration layer for PhilHealth systems | – | – | Blocked (OI-10, OI-11) | – |

## 4 Users, roles and access control

| ID | Requirement | Implementation | Test / evidence | Status | Acceptance |
|---|---|---|---|---|---|
| R-4.1 | The 9 required roles | `V2__reference_data.sql` `app_role` | Used across the ITs | Built+Tested | Pending (role-permission matrix to be validated) |
| R-4.2 | Permission-based access with geographic scope | `AuthUser.Grant`, `ScopeService` | `SecurityIT.siteScopeIsEnforced` | Built+Tested | Pending |
| R-4.3 | Hierarchical user administration | `UserService.requireCanGrant` | `SecurityIT.hierarchicalUserAdministration` | Built+Tested | Pending |
| R-4.4 | Activation, suspension, deactivation and status tracking | `UserService.setStatus` | `SecurityIT` (suspension takes effect immediately) | Built+Tested | Pending |
| R-4.5 | Account recovery and password reset | Admin reset plus forced change | `SecurityIT` (forced change) | Partial: no self-service recovery (OI-19) | – |
| R-4.6 | Session and token lifecycle | Access/refresh tokens, rotation, logout, revoke on password/role/status change; sign-in sessions listed and ended by administrators (Users › Sessions), with access tokens refused as soon as their session ends (V11); idle sign-out with one-minute warning (`QMS_IDLE_TIMEOUT_MINUTES`) | `SecurityIT.refreshTokensRotateAndReuseIsDetected`, `SessionIT`, `IdleSignOut` unit tests | Built+Tested | Pending |
| R-4.7 | Configurable approval workflows | CMS only | – | Partial (OI-18) | – |
| R-4.8 | Audit of user/permission/admin actions | `AuditService` | `SecurityIT` checks the audit rows | Built+Tested | Pending |

## 5 Core queueing

| ID | Requirement | Implementation | Test / evidence | Status | Acceptance |
|---|---|---|---|---|---|
| R-5.1.1 | Unique ticket numbers; no conflicts under concurrency | `TicketNumberAllocator`, unique constraints | `ConcurrencyIT` | Built+Tested | Pending |
| R-5.1.2 | Printed tickets via kiosk thermal printers | Browser print, 80 mm stylesheet | Frontend unit tests only; no printer tested | Partial (OI-15) | – |
| R-5.1.3 | Electronic ticketing | Kiosk "show on screen only" (channel ELECTRONIC); every ticket (screen and print) has a QR code to a public phone page (`/t/{token}`: position, estimated wait, "proceed to counter", feedback); random 128-bit token stored hashed, site-local, expires 2 days after the business date | `NumberingAndPhoneTicketIT.phoneLinkShowsProgressAndTakesFeedbackOnce`, `QrCode` unit test | Partial: no SMS (OI-23); phones must reach the site server (`QMS_TICKET_LINK_BASE_URL`) | – |
| R-5.1.4 | Prefixes, numbering, operating hours, reset schedule | Per-service prefix; per-site number format (2–5 digits, hyphen or none) and reset schedule (daily, weekly, monthly, yearly) in Regions & Sites, synced to site nodes; open/close time | `QueueFlowIT` (numbering), `NumberingAndPhoneTicketIT` (format, monthly reset without repeating the day's numbers) | Built+Tested; operating hours not covered by a test | Pending |
| R-5.1.5 | Ticket linked to site, service, dispenser, date and status | `ticket` columns | `QueueFlowIT` | Built+Tested | Pending |
| R-5.1.6 | Configurable priorities and multi-stage workflows | Priority rank; transfer with stage_no | `QueueFlowIT` | Built+Tested | Pending (OI-06) |
| R-5.2.1 | Create/modify/activate/reorder services without code changes | Catalog and site-service APIs and admin UI | API built; no IT for catalog CRUD | Built | Pending |
| R-5.2.2 | Classifications, G2C/G2B/G2G, internal/external, descriptions, documents, eligibility, durations | `service_item` columns | – | Built | Pending (OI-05) |
| R-5.2.3 | Routing rules, transfers between units | Manual transfer to any service; configurable next stage per site service with loop prevention, one-click **Next stage** in the agent console | `QueueFlowIT`, `RoutingIT` | Built+Tested; conditional (rule-based) routing not built | Pending |
| R-5.2.4 | Effective dates and versioned configuration | `effective_from`/`effective_to` enforced for kiosk issuance; full snapshot of every version in `service_item_history` (UI: Catalog → History) | `CatalogIT` | Built+Tested | Pending |
| R-5.2.5 | Per-site configuration | `site_service` | `QueueFlowIT` | Built+Tested | Pending |
| R-5.3 | Initial 29-service catalog | `V2__reference_data.sql` | Seed loads in every IT | Built | Blocked (OI-05: validate against the Citizen's Charter) |
| R-5.4 | Agent actions: call, recall, complete, transfer, hold, remove, break, void | `QueueService`, `AgentSessionService`, Agent Console UI | `QueueFlowIT`, `TicketStateMachineTest` | Built+Tested | Pending |
| R-5.4.1 | Validate action vs state and permissions; record who, when, counter, reason | State machine, `lockForAction`, `ticket_event` | `QueueFlowIT` (event sequence, 403/409 cases) | Built+Tested | Pending |
| R-5.4.2 | Prevent races and duplicate calls | Row locks, SKIP LOCKED, partial unique indexes | `ConcurrencyIT`, `QueueFlowIT.secondAgentCannotTakeOccupiedCounter` | Built+Tested | Pending |
| R-5.5 | Counters, eligibility, agent login/logout/break, supervisor intervention, workload and estimated wait | `counter` package (weekly opening windows per counter, V13), `forceEnd` (Queue Monitor → Close counter), `QueueQueryService`; kiosks offer a service only while an eligible counter is open or staffed; Queue Monitor availability (scheduled open but unstaffed, staffed outside hours) | `QueueFlowIT.agentCannotEndSessionWithTicketAtCounter`, `CounterScheduleIT`, `counterHours` unit tests; Close counter and hours editor checked in the portal | Built+Tested; no automatic workload rebalancing beyond transfer and close counter | Pending |
| R-5.6 | Priority categories: senior, PWD, pregnant, others; auditable; minimal PII | `priority_category`; no client PII stored | `QueueFlowIT` (priority served first) | Built+Tested | Blocked (OI-06) |

## 6–7 Kiosk, display, hardware, CMS

| ID | Requirement | Implementation | Test / evidence | Status | Acceptance |
|---|---|---|---|---|---|
| R-6.1 | Kiosk: configurable services, touch, print, priority flow, announcements, error handling, idle reset, offline from central | `KioskPage`, `/kiosk/*`; **Rate our service** on the home screen (on-screen keypad, finds today's completed ticket, then the feedback page) | Frontend unit tests; API in `QueueFlowIT`, `NumberingAndPhoneTicketIT.kioskFindsTodaysCompletedTicketByNumber` | Built | Pending (OI-15, OI-16) |
| R-6.1.1 | Prevent access to OS functions | `docs/kiosk-lockdown.md` (Windows and Ubuntu procedures, verification checklist) | Not validated on hardware | Partial (OI-16) | – |
| R-6.2 | Display: now serving, recent calls, counters, audio, media rotation, scheduling, full screen, auto-recovery | `DisplayPage`, `/display/board`, WebSocket | `QueueFlowIT` (board API); frontend tests | Built | Pending (OI-17) |
| R-6.2.1 | Layouts based on PhilHealth templates | – | – | Blocked: templates not provided | – |
| R-6.3 | Hardware compatibility matrix | `hardware-compatibility.md` (template) | No hardware tested | Partial | – |
| R-6.4 | Equipment quantities (122/122/122/112) | – | – | Blocked (OI-03) | – |
| R-7.1 | CMS: announcements, images, video, schedules, site/region scope, approval workflow | `cms` package, Content admin UI | `MediaStorageTest`; no workflow IT | Built | Pending |
| R-7.2 | Upload validation (type/size), access control, audit | `MediaStorage`, `ContentService` | `MediaStorageTest` | Built+Tested (detection only) | Pending |
| R-7.3 | Display layouts and templates managed in the CMS | – | – | Not built | – |
| R-7.4 | Central distribution of national content to sites | Config pull carries published national/regional/site content; sites download media with size and SHA-256 verification; replicas are read-only | `ContentDistributionIT` | Built+Tested | Pending |

## 8 Reporting

| ID | Requirement | Implementation | Test / evidence | Status | Acceptance |
|---|---|---|---|---|---|
| R-8.1 | Queueing, waiting and processing time; feedback; served; abandoned; rerouted | `ReportService` SQL | `QueueFlowIT` reconciliation | Built+Tested | Blocked (OI-07 definitions, OI-09 reappointment) |
| R-8.2 | Dimensions: client type, service type, classification, multi-stage, LHIO, region, area, kiosk, channel, status, date | `GroupBy` and filters | Summary by SERVICE tested; other groupings untested | Built | Pending |
| R-8.3.1 | Dashboards | Dashboard page: national / regional all-sites overview (totals, services across sites, per-site table) and live per-site view (stats, Services availed per site for today / 7 / 30 days as pie (top 5 + Other), bars or table); Reports → Trends (daily clients and waiting-time charts, accessible table view) | `trend.test.ts`; rendered and checked in the browser | Built+Tested | Pending |
| R-8.3.2 | Daily/weekly/monthly/periodic reports, site/regional comparison, trends | Date-range summary with DAY/SITE/REGION grouping; daily trend charts; scheduled daily/weekly/monthly reports (CSV/XLSX/PDF) stored for download | `ReportScheduleIT`, `ReportPeriodsTest`; checked in the portal | Built+Tested; no email delivery (needs an approved mail gateway) | Pending |
| R-8.3.3 | Export CSV, Excel, PDF | CSV, XLSX and PDF (OpenPDF, landscape A4) | `QueueFlowIT` (XLSX), `ReportExporterTest` (PDF), live check of the PDF endpoint | Built+Tested; generic layout until official templates (OI-08) | Pending |
| R-8.3.4 | Validation and exception reports, traceability | `/reports/exceptions`, `/reports/tickets` | – | Built | Pending |
| R-8.3.5 | Role-scoped access, audit of generation and export | `ReportService.where`, `ReportController`, schedule creation/download scope checks; ticket-level and audit CSV exports | `SecurityIT` (report scope), `ReportScheduleIT` | Built+Tested | Pending |
| R-8.3.7 | Agent performance against service levels (owner request, 2026-10-22) | SLA targets per region with national default and per-service overrides (Admin › SLA Targets); agent performance report with CSV/PDF export and a multi-sheet Excel workbook of every agent (summary, daily, transactions, targets) | `SlaIT` (hand-computed scenario, workbook parsed), E2E `sla.spec` | Built+Tested | Pending (OI-27) |
| R-8.3.6 | Official PhilHealth templates | – | – | Blocked (OI-08) | – |

## 9–10 Integration and offline sync

| ID | Requirement | Implementation | Test / evidence | Status | Acceptance |
|---|---|---|---|---|---|
| R-9.1 | Integration with CSMST and other systems | Feedback stored locally | – | Blocked (OI-10, OI-11) | – |
| R-9.2 | Documented, versioned REST APIs | `/api/v1`, OpenAPI at `/v3/api-docs`, `api-contract.md` | – | Built | Pending |
| R-10.1 | Local operation during outages (tickets, counters, calls, events, display, storage) | Site node architecture | Two-node drill: WAN cut while 18 clients were ticketed and served; outbox drained after reconnection with reconciliation MATCH (test-plan, 2026-10-22) | Built+Tested (minutes-long outage) | Pending |
| R-10.2 | Durable outbound queue, retry with backoff, idempotency keys, duplicate detection | `sync_outbox`, `SyncPushJob` (transport errors retried at least every 60 s), `sync_inbox` | `SyncIngestIT`, `BackoffTest`, two-node drill | Built+Tested | Pending |
| R-10.3 | Conflict resolution, data ownership per category | `sync-design.md`, version check, `OwnershipGuard` | `SyncIngestIT` (stale version) | Built+Tested | Pending |
| R-10.4 | Checkpoints, monitoring, admin reconciliation | `sync_state` watermark, `/system/status`, `/sync/outbox`, retry; hourly site daily summaries compared on central (`/sync/reconciliation`, System page) | `SyncIngestIT.reconciliationComparesSiteCountsWithCentral`, `ReconciliationStatusTest` | Built+Tested | Pending |
| R-10.5 | Integrity verification | SHA-256 per entry | `SyncIngestIT` (tampered entry) | Built+Tested | Pending |
| R-10.6 | Inbound configuration sync | `ConfigSyncService`; also carries the highest ticket numbers central holds so a restored site does not reissue them | `ContentDistributionIT`, `NumberFloorIT`, `SyncIngestIT`; two-node drill over TLS | Built+Tested | Pending |

## 11–12 Performance and security

| ID | Requirement | Implementation | Test / evidence | Status | Acceptance |
|---|---|---|---|---|---|
| R-11.1 | Indexing, pooling, pagination, efficient broadcast, background jobs | Partial indexes, Hikari, paged APIs, topic per site | – | Built; WebSocket uses Spring's in-memory broker, so more than one central API instance needs a broker relay or sticky sessions (gap B1) | Pending |
| R-11.2 | Capacity and load testing, SLOs | [capacity-plan.md](capacity-plan.md): load derived from 17 million visits (OI-01 clarified), storage growth, proposed SLOs, load test plan | – | Partial: SLOs proposed, not agreed; load test not run (needs target hardware, OI-02) | – |
| R-11.3 | Monitoring of CPU, memory, latency, DB | Prometheus endpoint (token-protected) with QMS queue/sync/report metrics and JVM/HTTP/DB/disk metrics; 12 alert rules and a scrape config in `deploy/monitoring`; Grafana dashboard `deploy/monitoring/grafana/dashboards/qms-overview.json` (17 panels) with provisioning files | `MetricsIT`; Prometheus 3.13.5 scraping a live node loaded all 12 rules (health ok) and ran every dashboard query | Built+Tested; Grafana not yet run with the dashboard | Pending |
| R-12.x | Security controls (see `security.md`) | Multiple, including DB least privilege and append-only audit (V3) | `SecurityIT`, `DatabasePrivilegesIT` | Built+Tested (core controls) | Pending |
| R-12.r | Data retention and disposal (Data Privacy Act) | `RetentionJob` (daily): tickets with events and feedback, audit entries, delivered sync entries, expired tokens; owner-defined purge functions (V11) are the application's only way to delete history; never purges unsynced site data; audited | `RetentionIT`, `DatabasePrivilegesIT` | Built+Tested; periods default to "keep forever" until PhilHealth decides (OI-13) | Blocked (OI-13) |
| R-12.v | Third-party VAPT | – | – | Not done (OI-22) | – |
| R-12.d | Dependency vulnerability scanning | `.github/workflows/ci.yml` (OWASP Dependency-Check profile `security-scan`, `npm audit`), Dependabot | `npm audit` run: production deps clean after upgrades (see `security.md`) | Partial: CI not yet activated; backend scan needs an NVD API key | – |

## 13–18 Administration, documentation, testing, support, training, standards

| ID | Requirement | Implementation | Test / evidence | Status | Acceptance |
|---|---|---|---|---|---|
| R-13 | Admin UIs: users, org, catalog, numbering, counters, priorities, CMS, audit, sync, system health | `frontend/src/pages/admin/*` (ticket numbering in Regions & Sites; user sessions; bulk "Add all catalog services") | Frontend build; not exercised end to end | Partial: no report-template, integration-endpoint or backup-status admin screens | – |
| R-14.1 | Source, migrations, API docs, env templates, build instructions | Repository | – | Built | Pending |
| R-14.2 | User and administrator manuals, troubleshooting guide | `user-guide.md` (agents, supervisors, administrators, reporting), `operations.md`, `deploy/native/README.md` | Written from the implemented screens; not reviewed by PhilHealth users | Built | Pending |
| R-14.3 | Architecture and DB diagrams | `architecture.md`, `sync-design.md`, `database.md` (ER diagram, data dictionary) | Diagram cross-checked against the migration SQL; Mermaid not rendered locally | Built | Pending |
| R-15 | Automated unit/integration/API/frontend tests | 65 backend, 69 frontend, 15 browser end-to-end (Playwright); manual browser smoke test (see test-plan) | Passing | Partial: no load tests, cross-browser runs or third-party security scans (see `test-plan.md`) | – |
| R-16 | Two-year warranty, 120 man-hours, 24/7 on-call, SLA | – | – | Process (OI-21) | – |
| R-17 | Training and knowledge transfer | – | – | Process | – |
| R-18 | Layered code, transactions, server-side validation, central error handling, migrations, health checks | Throughout | Code review | Built | Pending |
