# Gap analysis: specification vs. build

Date: 2026-10-22. Baseline: commit 69aacb8 (`origin/main`). Sources: the original development prompt, `requirements-traceability-matrix.md` (RTM), `open-issues.md`, and spot checks of the code.

Following the specification's own rule, nothing below is counted as compliant because it is planned. "Built" means the code exists. Acceptance is still pending for every requirement, because no UAT has been held.

## 1. Summary

| Category | Count | Meaning |
|---|---|---|
| A. Functional gaps that development can close | 14 | Features missing or incomplete. No PhilHealth input needed. |
| B. Verification gaps | 10 | The feature exists but has not been proven: no load, drill, hardware or cross-browser evidence. |
| C. Blocked on PhilHealth decisions or data | 10 | Development cannot finish these alone. |
| D. Process / contractual | 5 | Not software: VAPT, warranty, training, UAT. |
| E. Documentation drift | 2 | Documents that no longer match the code. |

The core queue flow is built and tested end to end: kiosk → ticket → call/serve → display with voice → reports and SLA → sync to central. The largest remaining risks are not features. They are:
- **(1) scale and outage proof (B1–B3):** no load test and no real two-node offline drill;
- **(2) the CSMST integration (C1):** blocked on PhilHealth;
- **(3) third-party VAPT (D1).**

## Progress since this analysis

Closed on 2026-10-22, all with automated tests:

| Item | What was built |
|---|---|
| A6 | Ticket numbering format (2–5 digits, hyphen or none) and reset schedule (daily, weekly, monthly, yearly) per site in Regions & Sites. A change never repeats a number issued the same day. |
| A8 | QR code on every ticket (screen and print) to a phone page with position, wait, "proceed to counter" and feedback. **Rate our service** on the kiosk home screen. SMS is still open (OI-23). |
| A9 | Grafana dashboard (17 panels) with provisioning files. The 12 alert rules and every query were run in Prometheus 3.13.5 against a live node. Grafana itself has not been run with it. |
| A11 | Users › **Sessions** lists sign-ins and ends one or all of them. Access tokens stop working as soon as their session ends. The portal signs out after 30 idle minutes, with a one-minute warning. |
| A12 | Daily retention job with audited, owner-defined purge functions. It never deletes unsynced site data. Periods default to "keep forever" until OI-13 is decided. |
| E1, E2 | RTM rows for tests, numbering, e-ticket, sessions, monitoring and retention updated; the WebSocket single-node limit is noted under R-11.1. |
| (extra) | Site Services › **Add all catalog services**. |

Closed on 2026-10-23:

| Item | What was built |
|---|---|
| A1 | Weekly opening hours per counter, with several windows a day for breaks. Kiosks stop ticketing a service when no eligible counter is open or staffed. Queue Monitor shows "Scheduled open, no agent" and "Staffed outside hours". Automatic workload rebalancing is still open. |
| (found) | Hibernate stored `LocalTime` values shifted by the JVM's time-zone offset (`hibernate.jdbc.time_zone: UTC` on a JVM in Manila time), so site-service hours were saved 8 hours off on non-UTC JVMs. Fixed with direct binding; databases written by a non-UTC JVM need their site-service hours re-entered. |

Verified on 2026-10-22 with a Docker Compose two-node drill (details in test-plan.md):

| Item | Result |
|---|---|
| B4 | Compose stacks built from the production Dockerfiles ran as a central and a site node behind TLS. The native installer is still not run. |
| B2 | WAN cut while 18 clients were served; central caught up with no loss, and reconciliation showed MATCH. Minutes-long outage only; an hours-long run is still open. |
| B3 | Backup and restore drilled twice (stop to healthy in 13–30 s). Found and fixed: backups failing under Compose, a TTY-corrupted media archive, restore not covering media, the audit trail losing its append-only grants after restore, a restored site reissuing ticket numbers that central then rejected, and slow catch-up after long outages. A DR restore onto another host is still open. |

## A. Functional gaps that development can close

| # | Spec ref | Gap | Evidence | Size |
|---|---|---|---|---|
| A1 | R-5.5 | Counters have no operating schedules (open/close hours, breaks). Workload redistribution exists only as force-close plus transfer, with no rebalancing suggestion. | No schedule table in V1–V10 | M |
| A2 | R-4.7 | Configurable approval workflows exist only for CMS content. The catalog, site services, SLA, user creation and priority changes take effect immediately. | RTM R-4.7, OI-18 | M |
| A3 | R-9 | No integration layer: no adapter interface, outbound queue, retry or error queue, reconciliation screen, or endpoint admin. The CSMST adapter itself is blocked (C1), but the framework and a stub adapter are not. | No `integration` package | L |
| A4 | R-13 | Missing admin screens: report templates, integration endpoints, backup/recovery status. | RTM R-13 | M |
| A5 | R-7.3, R-6.2.1 | Display layouts and templates cannot be managed in the CMS. The layout is fixed in code; only content items rotate. | RTM R-7.3 "Not built" | M |
| A6 | R-5.1.4 | Ticket numbers reset daily only. There is no configurable reset schedule and no number-format template. | `TicketNumberAllocator.format(prefix, seq)` | S |
| A7 | R-5.2.3 | Multi-stage routing is a manual "Next stage" only. There are no rule-based routes (service A → service B). | `QueueService.nextStage` | M |
| A8 | R-5.1.3, R-6.1 | The electronic ticket is on-screen only: no QR code to follow the queue on a phone, and no SMS. The client feedback page exists but no kiosk, display or ticket links to it. | `grep feedback/` finds no link | S–M |
| A9 | R-11.3 | No Grafana dashboards. The 12 alert rules have never been loaded into a running Prometheus. | RTM R-11.3 | S |
| A10 | R-4.5 | No self-service password recovery; an admin reset is the only route. Recovery needs a mail or SMS gateway, which is also a C item. | OI-19 | M |
| A11 | R-4 / OWASP | No admin view to list or revoke a user's active sessions (refresh tokens). The portal has no idle-timeout sign-out; tokens expire, but an unattended open screen stays signed in until then. | No controller touches `refresh_token` | S |
| A12 | R-12 / data privacy | No data retention or purge jobs for tickets, audit logs, media or report files. | OI-13 | S |
| A13 | R-6, R-7 | The UI and voice are English only. There is no Filipino option for kiosk screens or call announcements. | No i18n in the frontend | M |
| A14 | R-8 | Report gaps: the "reappointed" metric is not modelled (OI-09). Scheduled reports are stored but never emailed. Regional comparison is a table, with no chart. There is no holiday calendar, so non-working days count in averages. | ReportService, OI-09 | M |

Smaller usability items requested in this session but not built: a bulk "Add all catalog services" action on Site Services, and a dark-mode toggle.

## B. Verification gaps (built but not proven)

| # | Spec ref | Gap |
|---|---|---|
| B1 | R-11 SLOs | No load or stress test. WebSocket uses Spring's in-memory simple broker, so live updates cannot be shared across several central app instances. Running more than one central node needs a broker relay (RabbitMQ or ActiveMQ) or sticky sessions. Sizing for 112 sites is unproven. Update 2026-10-23: "17 million" is confirmed as client visits per year, not concurrent users; [capacity-plan.md](capacity-plan.md) derives the load and proposes SLOs and a load test plan. |
| B2 | R-10 | Offline-first sync is tested in integration tests only. There has been no WAN-cut drill between a real site node and central, and no clock-skew test. |
| B3 | R-2.5 | `backup.sh` and `restore.sh` have never been executed. There has been no restore drill and no RPO/RTO measurement. |
| B4 | R-2 | Docker Compose has never been run (OI-02). The native installer has only been dry-run. |
| B5 | R-6.3, R-5.1.2 | No hardware testing: thermal printers (browser print only, no ESC/POS agent, OI-15), kiosk OS lockdown (OI-16), touch screens, TVs. |
| B6 | R-15 | E2E runs in Chrome only, with no Firefox/Edge/Safari runs and no responsive-layout tests. |
| B7 | Accessibility | No WCAG audit (OI-24): no screen-reader pass, and kiosk contrast and target sizes not measured. |
| B8 | R-12.d | CI has never run green on GitHub. The backend dependency scan needs the `NVD_API_KEY` secret. |
| B9 | R-12.d | Pending dev-tool upgrades (vite 6, vitest 4) clear the remaining `npm audit` dev findings. React Router v7 migration is also pending; since 2026-10-22, `npm audit --omit=dev` reports two moderate react-router advisories that only v7 fixes (GHSA-wrjc-x8rr-h8h6, GHSA-337j-9hxr-rhxg). |
| B10 | R-8.2 | Report groupings other than by service have no test. |

## C. Blocked on PhilHealth decisions or data

| # | Item | Open issue |
|---|---|---|
| C1 | CSMST API specification and credentials | OI-10, OI-11 |
| C2 | Citizen's Charter validation of the 29 services, with processing times | OI-05 |
| C3 | Priority lane policy (ratio of priority to regular calls) | OI-06 |
| C4 | Official metric definitions | OI-07 |
| C5 | Official report templates and letterhead | OI-08 |
| C6 | SLA target values and how they are used in performance evaluation | OI-27 |
| C7 | Policy on Super Administrator self-approval of content | OI-26 |
| C8 | Equipment quantities per site; infrastructure; Docker permission | OI-02, OI-03 |
| C9 | Central identity: SSO/AD, and whether users sync central → sites | OI-04 |
| C10 | Approval of the Java 21 deviation (spec says 17) and of the logo usage | OI-25 |

## D. Process / contractual

D1 Third-party VAPT (OI-22) · D2 Two-year warranty, 120 man-hours, 24/7 on-call (OI-21) · D3 Training and knowledge transfer (R-17) · D4 UAT and formal acceptance of each RTM row · D5 Data Privacy Act PIA and registration of the system with the DPO.

## E. Documentation drift

- E1: The RTM R-15 row still says "45 backend, 47 frontend" and "no E2E". The current figures are 52 backend, 59 frontend and 14 Playwright E2E tests.
- E2: The RTM row for R-11.1 says "Built" but should note the single-node WebSocket broker limitation (B1).

## Recommended order

1. **Quick wins (S):** A6 numbering format and reset schedule; A8 feedback link plus a QR e-ticket; A9 Grafana dashboard; A11 session list/revoke and idle timeout; A12 retention jobs; E1–E2 RTM fixes; the bulk "Add all services" action.
2. **Operations readiness:** B4 Compose run; B3 backup/restore drill; B2 two-node offline drill; B8 CI green.
3. **Features (M/L):** A1 counter schedules; A3 integration framework with a CSMST stub; A4 admin screens; A2 approval workflows; A5 display templates; A13 Filipino.
4. **Scale:** B1 load test, plus a broker relay if more than one central node is required.
