# TOR compliance assessment

Date: 2026-10-23. Baseline: commit `a88cc8d`. Compared clause by clause with the Terms of Reference (the
original "PhilHealth Integrated Queueing Management System" development specification, sections 2-18).

Following the TOR rule, nothing is counted as met because it is planned. "Met" means built **and**
evidenced; no clause is yet *accepted*, because PhilHealth UAT and sign-off have not happened. The detailed
requirement-to-test mapping stays in [requirements-traceability-matrix.md](requirements-traceability-matrix.md).

| Status | Meaning |
|---|---|
| Met (tested) | Built and verified by automated tests or a recorded drill |
| Met (untested) | Built, but no test or drill evidence yet |
| Partial | Part is built; the remainder is stated |
| Not met | Not built |
| Blocked | Needs a PhilHealth decision, data, access or equipment |
| Process | Contractual or non-software obligation |

## Summary

| Status | Clauses | Share |
|---|---|---|
| Met (tested) | 89 | 58% |
| Met (untested) | 7 | 5% |
| Partial | 32 | 21% |
| Not met | 11 | 7% |
| Blocked | 7 | 5% |
| Process | 7 | 5% |
| **Total** | **153** | |

Rows group closely related TOR bullets (for example the full agent action list is one row), so the shares
are indicative; the per-section tables below are the authoritative view.

Section by section:

| TOR section | Met (tested) | Met (untested) | Partial | Not met | Blocked | Process |
|---|---|---|---|---|---|---|
| 2 Technology stack | 8 | 2 | 5 |  |  | 1 |
| 3 System architecture | 4 |  | 1 | 1 | 1 |  |
| 4 User roles and access control | 6 |  | 2 |  |  |  |
| 5.1 Ticket generation | 5 |  | 2 |  |  |  |
| 5.2 Service and queue configuration | 7 |  | 1 |  |  |  |
| 5.3 Initial service catalog | 1 |  |  |  | 1 |  |
| 5.4 Agent console | 4 |  |  |  |  |  |
| 5.5 Counter and physical queue management | 5 |  | 2 |  |  |  |
| 5.6 Priority queue management | 1 |  |  |  | 1 |  |
| 6 Equipment |  |  |  |  |  | 1 |
| 6.1 Interactive kiosk | 3 |  | 4 |  |  |  |
| 6.2 Digital queue display | 4 |  | 1 |  | 1 |  |
| 6.3 Hardware compatibility |  |  | 1 |  |  |  |
| 7 Content management | 4 | 1 |  | 1 |  |  |
| 8.1 Performance metrics | 2 |  | 1 | 1 |  |  |
| 8.2 Reporting dimensions |  | 1 |  |  |  |  |
| 8.3 Reporting features | 6 |  | 1 |  | 1 |  |
| 9 Integration with PhilHealth systems | 1 |  | 2 |  | 1 |  |
| 10 Offline operation and synchronization | 6 | 1 | 1 |  |  |  |
| 11 Scalability and performance | 3 |  | 1 | 3 |  |  |
| 12 Security | 9 | 1 | 1 | 1 |  | 1 |
| 13 Administration | 3 |  | 1 | 2 |  |  |
| 14 Source code and documentation | 3 | 1 | 3 |  |  |  |
| 15 Testing and acceptance | 3 |  | 2 | 2 | 1 | 2 |
| 16 Support and warranty |  |  |  |  |  | 1 |
| 17 Training and knowledge transfer |  |  |  |  |  | 1 |
| 18 Development standards | 1 |  |  |  |  |  |

**Reading it:** the queueing core (sections 4, 5, 10, 18) is built and tested, including a two-node
offline and restore drill. What separates the system from a compliant delivery is mostly evidence and
external input rather than features: load testing and SLOs, hardware (printers, kiosks) testing, the
CSMST integration, official report and display templates, VAPT, UAT, and the contractual items.

## Repository and CI (found during this review)

* GitHub `main` is at `69aacb8`; the five later commits (quick wins, drill fixes, counter hours, design
  and audit-log changes) exist only on this machine until they are pushed.
* The only CI run on GitHub and all Dependabot jobs ended in **startup_failure** with no job started. The
  workflow file parses; failures of jobs that do not use it point to an account or billing restriction
  on GitHub Actions for this private repository. Until CI runs, the TOR's "automated build and test" and
  "dependency scanning" clauses lack evidence.

## 2 Technology stack

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Java 17 (OpenJDK 17) | Partial | Built on **Java 21** (LTS) at your request; deviation recorded as OI-25, needs PhilHealth approval. |
| Spring Boot 3.x, Web, Security, JWT, JPA/Hibernate, Bean Validation, WebSocket, Scheduling, Flyway, Maven, OpenAPI | Met (tested) | Spring Boot 3.5.16; all listed modules in use; 65 backend tests. |
| React + TypeScript, Vite, React Router, Axios, Bootstrap, reusable components, role-based navigation, WebSocket client | Met (tested) | 69 frontend unit tests, 15 Playwright end-to-end tests. |
| Accessible interfaces | Partial | Labels, keyboard focus, ARIA and screen-reader text throughout; no formal WCAG audit (OI-24). |
| PostgreSQL 17, normalized schema, FKs, indexes, constraints, transactions, migrations | Met (tested) | Flyway V1-V13; tests run on real PostgreSQL. |
| Reporting indexes, optimized queries for high volume | Met (untested) | Indexes exist; not load-tested. |
| Local database storage and synchronization support | Met (tested) | Two-node Docker drill, 2026-10-22. |
| JWT with controlled expiry; secure renewal | Met (tested) | Rotating refresh tokens with reuse detection; sessions revocable (`SecurityIT`, `SessionIT`). |
| RBAC + permission-based access; CORS allowlist; BCrypt; rate limiting; validation; parameterized SQL; audit logging; secrets via environment | Met (tested) | `SecurityIT`, `DatabasePrivilegesIT`; see section 12. |
| HTTPS/TLS | Met (tested) | nginx TLS proxy; site-to-central TLS with a custom CA verified in the drill. |
| OWASP Top 10 controls | Met (untested) | Controls in place (security.md); no independent test yet. |
| Third-party VAPT | Process | Not done (OI-22). |
| Linux deployment; Docker and Compose | Met (tested) | Production Dockerfiles and Compose run as central + site nodes in the drill. |
| Installation on approved physical/virtual machines | Partial | Native installer (`deploy/native`) only dry-run; target infrastructure unknown (OI-02). |
| Environment-specific configuration (dev, test, staging, production) | Partial | Env templates for site, central and native; no separate staging profile or documented promotion path. |
| Automated build, test, deployment, backup and recovery | Partial | Backup/restore drilled; **CI has never run successfully on GitHub** (see "Repository and CI" below). |

## 3 System architecture

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| React frontend: portal, agent console, kiosk, display, reports, CMS | Met (tested) | All built; end-to-end tests. |
| Spring Boot API: auth, business logic, queue, reporting, integration, sync | Partial | All except the external-system integration layer (section 9). |
| PostgreSQL holds users, roles, sites, counters, services, tickets, feedback, reports, audit, sync metadata | Met (tested) | database.md data dictionary. |
| Local site deployment keeps working during WAN/central outages | Met (tested) | WAN cut while 18 clients were served (drill). |
| Central configuration distribution and data synchronization | Met (tested) | Config pull and outbox push over TLS; reconciliation MATCH (drill). |
| Integration layer with configurable adapters for PhilHealth systems | Not met | No adapter framework; CSMST interface unknown (OI-10, OI-11). |
| Architecture validated against PhilHealth infrastructure and Annex B | Blocked | Infrastructure and Annex B performance figures not provided (OI-01, OI-02). |

## 4 User roles and access control

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| The 9 required roles | Met (tested) | Seeded in V2; used across tests. |
| Hierarchical user administration; RO and LHIO groupings; site- and role-scoped permissions | Met (tested) | `SecurityIT.hierarchicalUserAdministration`, scope tests. |
| Configurable approval workflows | Partial | Only CMS content has an approval workflow (OI-18). |
| Activation, suspension, deactivation, status tracking | Met (tested) | `SecurityIT` (suspension effective immediately). |
| Account recovery and password reset | Partial | Admin reset with forced change; no self-service recovery (needs a mail/SMS channel, OI-19). |
| Session and token lifecycle | Met (tested) | Expiry, rotation, revocation, admin session sign-out, idle sign-out (`SessionIT`, unit tests). |
| Audit trails for users, permissions, approvals, admin actions | Met (tested) | Append-only `audit_log`; readable Audit Log page; CSV export. |
| Users only see data and functions within their permissions and scope | Met (tested) | Every endpoint checks scope; `SecurityIT`, `access.spec.ts`. |

## 5.1 Ticket generation

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Unique ticket numbers; no duplicates under concurrent requests | Met (tested) | `ConcurrencyIT`; restored sites never reissue numbers (`NumberFloorIT`). |
| Printed tickets through kiosk thermal printers | Partial | Browser printing on an 80 mm layout with QR code; no printer tested; no direct ESC/POS (OI-15). |
| Electronic ticketing where approved | Partial | On-screen ticket plus QR phone page with live status and feedback; SMS not built (OI-23). |
| Assign tickets by selected service | Met (tested) | `QueueFlowIT`. |
| Configurable prefixes, numbering rules, operating hours, reset schedules | Met (tested) | Per-service prefix and hours; per-site format and daily/weekly/monthly/yearly reset (`NumberingAndPhoneTicketIT`). |
| Each ticket linked to site, service, dispenser, date, status | Met (tested) | Ticket schema; device id on every kiosk ticket. |
| Configurable priorities and multi-stage workflows | Met (tested) | Priority ranks; next-stage routing (`RoutingIT`). |

## 5.2 Service and queue configuration

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Create, modify, activate, deactivate, reorder services without code changes | Met (tested) | Service Catalog and Site Services pages; `CatalogIT`. |
| Internal/external; Simple, Complex, Highly Technical A/B; G2C/G2B/G2G | Met (tested) | Catalog enums and forms. |
| Descriptions, eligibility, required documents | Met (tested) | Catalog fields. |
| Routing rules | Partial | One configured next stage per service; no conditional routing rules. |
| Estimated durations and configurable priority policies | Met (tested) | Duration per site service; priority categories with rank. |
| Multi-stage queues and transfers between units | Met (tested) | `RoutingIT`, transfer in `QueueFlowIT`. |
| Effective dates and versioned changes | Met (tested) | `CatalogIT.effectiveDatesControlAvailabilityAndEveryChangeIsVersioned`. |
| Different configuration per LHIO and Regional Office | Met (tested) | Site services, hours, prefixes and numbering per site. |

## 5.3 Initial service catalog

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| The 29 listed services, all editable | Met (tested) | Seeded in V2; editable in Service Catalog. |
| Validated against the approved Citizen's Charter | Blocked | PhilHealth validation pending (OI-05). |

## 5.4 Agent console

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Call next, recall, complete, transfer, hold, remove, break, void | Met (tested) | `QueueFlowIT`, `TicketStateMachineTest`, end-to-end tests. |
| Actions validated against state and permission | Met (tested) | State machine; 403/409 cases tested. |
| Timestamps, agent, counter, transitions, reasons recorded | Met (tested) | Append-only `ticket_event`. |
| No race conditions, duplicate calls or conflicting assignments | Met (tested) | Row locks, `SKIP LOCKED`; `ConcurrencyIT`. |

## 5.5 Counter and physical queue management

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Counters and agents; counter-specific eligibility | Met (tested) | Counters page; eligibility enforced on call-next. |
| Counter availability and operating schedules | Met (tested) | Weekly windows with breaks; kiosk honours them (`CounterScheduleIT`). |
| Agent login, logout, break, return to service | Met (tested) | `QueueFlowIT`. |
| Queue assignment and routing rules | Partial | Eligibility and next-stage only (see 5.2). |
| Agent reassignment and supervisor intervention | Met (tested) | Queue Monitor "Close counter" returns tickets to the queue (`supervisor.spec.ts`). |
| Workload and estimated waiting time | Met (tested) | Per-service waiting and estimates on dashboard and kiosk. |
| Supervisors redistribute workloads | Partial | By transfer and closing counters; no guided or automatic rebalancing. |

## 5.6 Priority queue management

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Senior, PWD, pregnant and other categories; configurable, auditable, minimal personal data | Met (tested) | Priority categories; no personal data stored; audited changes. |
| Rules consistent with approved PhilHealth policy | Blocked | Priority policy (ordering vs. regular lane) to be confirmed (OI-06). |

## 6 Equipment

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| 122 kiosks + printers, 122 paper rolls, 122 TVs, 112 PC servers | Process | Procurement; quantities to be validated against the BOQ and Annex C (OI-03). |

## 6.1 Interactive kiosk

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Services in configurable layouts; touchscreen | Partial | Touch-sized service buttons, configurable order and content; layout itself is fixed. |
| Issues tickets through thermal printers | Partial | See 5.1 printing. |
| Priority workflow; instructions, announcements, service information | Met (tested) | `kiosk-to-counter.spec.ts`. |
| Handles printer errors and unavailable services gracefully | Partial | Unavailable and closed services handled; the browser cannot detect printer errors (OI-15). |
| Prevents access to operating-system functions | Partial | Lock-down procedure documented (kiosk-lockdown.md); not validated on hardware (OI-16). |
| Idle screen and automatic reset | Met (tested) | Unit-tested idle reset. |
| Offline ticket generation when disconnected from central | Met (tested) | Drill: tickets issued with the WAN cut. |

## 6.2 Digital queue display

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Current and recent calls, counter assignments | Met (tested) | `kiosk-to-counter.spec.ts`. |
| Audio announcements | Met (tested) | `display-voice.spec.ts`. |
| Posters, announcements, audiovisual content, scheduled | Met (tested) | CMS content with start/end; `ContentDistributionIT`. |
| Full-screen mode; automatic recovery after network interruptions | Met (tested) | Reconnect and polling fallback; device-key recovery (`device-keys.spec.ts`). |
| Configurable layout and branding | Partial | Agency name and logo configurable at build time; layout fixed (gap A5). |
| Layouts based on PhilHealth-approved templates | Blocked | Templates not provided. |

## 6.3 Hardware compatibility

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Compatibility matrix: PCs, kiosks, printers, displays, network, servers, VMs; drivers, printer commands, procedures | Partial | Template and kiosk lock-down guide only; no hardware tested (OI-15, OI-16). |

## 7 Content management

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Announcements, advisories, posters, images, audiovisual presentations | Met (tested) | Announcement, image and video content. |
| Service instructions and informational messages | Met (untested) | As announcements; no dedicated per-service instruction field. |
| Display layouts and templates | Not met | Not built (gap A5). |
| Schedules and expiry; site- and region-specific content | Met (tested) | `ContentDistributionIT`. |
| Approval and publication workflow | Met (tested) | `ContentApprovalIT`, `StrictContentApprovalIT`. |
| Access control, upload validation (type and size), audit of publication | Met (tested) | `MediaStorageTest`; audited actions. |

## 8.1 Performance metrics

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Average queueing, waiting and processing time; clients served; abandoned tickets; rerouted tickets | Met (tested) | Report SQL reconciled with tickets in `QueueFlowIT`; definitions in metrics-definitions.md. |
| Client feedback per service and transaction | Met (tested) | Kiosk and phone feedback; reported per service. |
| Reappointed tickets | Not met | No "reappointment" concept yet; meaning to be defined (OI-09). |
| Explicit metric definitions (start/end, exclusions, holds, transfers, incomplete) | Partial | Written in metrics-definitions.md; official PhilHealth definitions pending (OI-07). |

## 8.2 Reporting dimensions

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Client type, service type, classification, multi-stage, LHIO, region, area, kiosk, channel, service, status, date | Met (untested) | All are grouping/filter options; only grouping by service is covered by a test. |

## 8.3 Reporting features

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Operational dashboards | Met (tested) | National and site dashboards; `dashboard.spec.ts`. |
| Daily, weekly, monthly, periodic and scheduled reports | Met (tested) | `ReportScheduleIT`, `reports.spec.ts`. |
| Site and regional comparisons | Partial | Grouping by site or region in tables; no comparison charts. |
| Waiting and processing trends; volume and abandonment analysis | Met (tested) | Trends tab with chart/table; abandonment in summaries. |
| Validation and exception reports; duplicate detection; traceability to transactions | Met (tested) | Exceptions and ticket-level reports; reconciliation. |
| Export CSV, Excel, PDF | Met (tested) | `ReportExporterTest`; Excel agent workbook. |
| Role-scoped access; audit of generation and export | Met (tested) | `SecurityIT`; audited exports. |
| Official PhilHealth report templates | Blocked | Not provided; generic layouts until then (OI-08). |

## 9 Integration with PhilHealth systems

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Documented, versioned REST APIs in JSON | Met (tested) | `/api/v1`, OpenAPI, api-contract.md. |
| Retry, idempotency, sync monitoring, error queue and reconciliation tools | Partial | Built for site-to-central sync (outbox, retry, reconciliation page); not for external systems. |
| Configurable authentication; data mapping and validation; audit of exchanges | Partial | Site keys and SHA-256 checks for sync; no per-system endpoint configuration. |
| CSMST and other PhilHealth systems | Blocked | Interface and credentials not confirmed (OI-10, OI-11); feedback is stored locally meanwhile. |

## 10 Offline operation and synchronization

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| During outages: tickets, counters, calling, events, display, local storage | Met (tested) | Drill (minutes-long outage). |
| Automatic synchronization when connectivity returns | Met (tested) | Drill: backlog drained, reconciliation MATCH. |
| Durable queues, retry with backoff, idempotency keys, duplicate detection, checkpoints | Met (tested) | `SyncIngestIT`, `BackoffTest`, drill. |
| Conflict resolution; integrity verification; reconciliation and audit trails | Met (tested) | Version checks, SHA-256, reconciliation (`SyncIngestIT`). |
| Unique site ids, globally unique transaction ids; ownership and direction per data category | Met (tested) | UUIDv7 ids; sync-design.md. |
| Retention per data category | Partial | Retention job built; periods await PhilHealth (OI-13). |
| No silent loss and no conflicting ticket numbers offline | Met (tested) | Drill; numbering floors after restore (`NumberFloorIT`). |
| Hours-long outage behaviour | Met (untested) | Only minutes tested; long outages not yet drilled. |

## 11 Scalability and performance

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Indexing, connection pooling, pagination, background jobs | Met (tested) | In place; exercised by tests. |
| Efficient WebSocket broadcasting | Partial | Topic per site; in-memory broker limits central to one API instance (gap B1). |
| Caching; load balancing | Not met | No caching layer; no load-balanced configuration. |
| Backup and recovery | Met (tested) | Encrypted backup and restore drilled twice. |
| Monitoring of CPU, memory, storage, latency, database | Met (tested) | Prometheus metrics, 13 alert rules, Grafana dashboard (rules run in Prometheus). |
| Capacity planning and performance/load testing | Not met | Not done; "17 million users" not clarified (OI-01). |
| Measurable SLOs (response time, throughput, sync delay, availability, recovery) | Not met | Not defined yet. |

## 12 Security

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Spring Security + JWT; fine-grained permission and scope checks on every endpoint | Met (tested) | `SecurityIT`, `access.spec.ts`. |
| BCrypt; strict CORS; TLS; secure headers and CSP | Met (tested) | Config and nginx; CSP in `qms.conf`. |
| SQL injection, XSS, CSRF, broken access control protections; validation; safe errors | Met (untested) | Parameterized SQL, React escaping, bearer tokens (no CSRF exposure), central error handler; not independently tested. |
| Rate limiting and brute-force protection | Met (tested) | Login limiter and lockout tested. |
| Secrets outside source control | Met (tested) | Env templates without secrets; credentials file git-ignored. |
| Audit and security event logging | Met (tested) | Audit log; login failures and lockouts recorded. |
| Least-privilege database accounts | Met (tested) | `DatabasePrivilegesIT`; restore re-applies grants; alert if broken (drill). |
| Encrypted backups | Met (tested) | GPG-encrypted backups (drill). |
| Dependency and vulnerability scanning | Partial | Configured in CI (OWASP, npm audit, Dependabot), but CI has not run; 2 moderate react-router advisories need React Router 7. |
| Secure file uploads | Met (tested) | Type detection and size limits (`MediaStorageTest`). |
| Backup restoration procedure | Met (tested) | operations.md; drilled. |
| Incident-response procedure | Not met | Not written (security.md lists it as pending). |
| Third-party VAPT and remediation | Process | Not done (OI-22). |

## 13 Administration

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Users and roles; RO and LHIO; service catalog; numbering; counters and agents; priorities; CMS and schedules | Met (tested) | Admin pages exercised by end-to-end tests. |
| Audit logs and system events; system health and connectivity | Met (tested) | Audit Log and System & Sync pages. |
| Integration endpoints and synchronization settings | Partial | Sync status, outbox and retry in the UI; endpoints and keys set in env files only. |
| Report templates | Not met | Not built (gap A4; official templates OI-08). |
| Backup and recovery status | Not met | Not shown in the UI (gap A4). |
| Configuration changes permission-controlled, validated, audited, without code changes | Met (tested) | Throughout. |

## 14 Source code and documentation

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Frontend, backend, migrations, API docs, install scripts, env templates, build instructions | Met (tested) | Repository, README, OpenAPI, deploy/. |
| Architecture and database diagrams; user/admin manuals; troubleshooting; backup procedures; security docs | Met (tested) | docs/ (architecture, database, user-guide, operations, security). |
| Test plans and results | Met (tested) | test-plan.md with drill records; this matrix and the RTM. |
| Hardware and printer integration documentation | Partial | Template and lock-down guide; no tested devices. |
| Integration specifications | Partial | Sync and API specs; CSMST spec blocked. |
| Version-controlled repository with documented release process | Partial | Git and GitHub; no written release/versioning procedure. |
| Buildable and maintainable by PhilHealth | Met (untested) | Builds reproducibly here and in Docker; no handover yet. |

## 15 Testing and acceptance

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Automated unit, integration, API, frontend, security and end-to-end tests | Met (tested) | 65 backend, 69 frontend, 15 end-to-end. |
| Functional, role/permission, concurrency and uniqueness tests | Met (tested) | See sections 4 and 5. |
| Offline/synchronization; data validation and report reconciliation | Met (tested) | Drill; `QueueFlowIT` reconciliation. |
| Backup restoration and disaster recovery | Partial | Restore drilled; no DR to a separate host or timed RPO. |
| Printer and kiosk compatibility | Blocked | Needs procured devices (OI-15). |
| Load and stress testing | Not met | Not done (OI-01, OI-02). |
| Cross-browser and responsive testing | Not met | Chrome only. |
| Third-party VAPT | Process | OI-22. |
| UAT at representative sites; signed acceptance | Process | Not scheduled. |
| Measurable, traceable acceptance criteria; defect and remediation records | Partial | RTM and test-plan defect records; acceptance criteria not agreed with PhilHealth. |

## 16 Support and warranty

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Two-year warranty, 120 man-hours, 24/7 on-call, on-site response, office-hours service, patches, maintenance, severity and escalation, SLA | Process | Contractual; to be defined in the SLA (OI-21). |

## 17 Training and knowledge transfer

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Training vouchers, curriculum, materials, exercises, completion records | Process | Not started; documentation exists to build on. |

## 18 Development standards

| Requirement | Status | Evidence or what is missing |
|---|---|---|
| Layered code, transactions, server-side validation, no hard-coded credentials, env configuration, consistent errors, central exception handling, migrations, health checks, logging, monitoring, repeatable builds | Met (tested) | Code structure, Flyway `validate`, `/actuator/health`, Prometheus; reviewed throughout. |

## What it would take to close the gaps

| Priority | Item | Owner |
|---|---|---|
| 1 | Push the repository and get CI running (check GitHub billing/Actions settings); add the NVD API key secret | You |
| 2 | Define SLOs and run a load test against agreed volumes (clarify the 17 million users, OI-01) | Development + PhilHealth |
| 3 | Integration adapter framework with a CSMST stub, error queue and endpoint admin screen | Development (CSMST spec: PhilHealth) |
| 4 | Admin screens for backup status and report templates; incident-response procedure; release procedure | Development |
| 5 | Configurable approval workflows beyond CMS; self-service password recovery (needs a mail/SMS channel) | Development + PhilHealth |
| 6 | Display layouts in the CMS; reappointment metric; regional comparison charts; conditional routing rules | Development (templates/definitions: PhilHealth) |
| 7 | Cross-browser and responsive tests; WCAG audit; hours-long offline drill; DR to a second host | Development |
| 8 | Printer/kiosk/TV compatibility testing; kiosk lock-down validation | Needs procured hardware |
| 9 | Citizen's Charter, priority policy, metric definitions, report and display templates, retention periods | PhilHealth |
| 10 | VAPT, UAT and sign-off, warranty/SLA, training | Process |
