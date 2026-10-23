# Capacity plan and proposed service levels

Status: **draft for PhilHealth review**, 2026-10-23. The meaning of "17 million users" is confirmed
(OI-01); the service levels below are proposals until agreed, and no capacity is claimed until the load test in
the last section has been run on the target infrastructure.

## What "17 million users" means

Confirmed by the project owner on 2026-10-23: **17 million is the number of client visits to PhilHealth
offices per year, not 17 million people using the system at the same moment.** Clients do not sign in
to the QMS; each visit is one queue ticket. Still to confirm with PhilHealth: whether online-only
transactions are included in the figure.

## Load derived from 17 million visits per year

The annual total is confirmed. The distribution over days, hours and sites below is assumed, to be
replaced with PhilHealth's historical counts when available:

| Assumption | Value | Basis |
|---|---|---|
| Working days per year | 248 | Weekdays less regular and special non-working holidays |
| Service hours per day | 8 | 08:00-17:00 with lunch |
| Busiest hour vs. average hour | × 2 | Morning surge |
| Busiest day vs. average day | × 1.5 | Mondays, days after holidays, deadlines |
| Busiest site vs. average site | × 4 | Large NCR and regional-centre LHIOs |
| Ticket events per visit | 3.5 | Issue, call, complete, plus some recalls and transfers |

| Figure | Value |
|---|---|
| Visits per working day, nationwide | about 68,500 |
| Visits per site per day, average | about 610 |
| Busiest site, busy day | about 2,450 visits, peak hour about 920 tickets (0.26 per second) |
| National peak hour | about 25,700 tickets (7 per second), 25 ticket events per second arriving at central |
| Rows per year at central | 17 million tickets, about 60 million ticket events |
| Staff signed in at the same time | about 1,000-1,300 nationwide (agents at counters, supervisors, administrators) |
| Devices | 122 kiosks and 122 displays (the kiosks refresh their service list every 30 seconds) |

What this means for the design:

* **Site nodes:** under one ticket per second even at the busiest site. A single API process and
  PostgreSQL on the planned PC server are sufficient by a wide margin; the load test should confirm it on
  the actual hardware.
* **Central node:** about 25 events per second at the national peak, arriving in batches every
  15 seconds from 112 sites. This is light write traffic for PostgreSQL. The heavier load on central is
  **reporting** over a growing history, not ticketing.
* **No requirement for millions of concurrent users.** The TOR's concern about "17 million concurrent
  users" does not apply; the concurrent population is staff and devices (about 1,500).

## Storage

Measured on the current schema (average row width plus indexes and allowance for bloat): about 1 KB per
ticket and 0.4 KB per ticket event.

| Node | Growth per year | Notes |
|---|---|---|
| Central | about 40-50 GB | Tickets, events, feedback and audit for all sites |
| Average site | about 0.4 GB | Its own data only |
| Busiest site | about 1.5-2 GB | |

Backups are compressed and encrypted, and are typically much smaller than the database. Retention
(OI-13) bounds growth once PhilHealth sets periods; without it, central keeps every year. If national
reports over several years become slow, partitioning the ticket and event tables by year is the planned
remedy (not yet needed at these volumes).

## Proposed service-level objectives (SLOs)

| Area | Objective (proposed) | How it is measured |
|---|---|---|
| Kiosk ticket | 95% of tickets shown or printing within 3 s of the tap; 99% within 5 s | API latency of `POST /kiosk/tickets` plus print start |
| Agent actions | 95% of call, recall, complete and transfer requests within 300 ms at the site; 99% within 1 s | `http_server_requests` p95/p99 (Grafana) |
| Display update | A call appears on the display within 2 s (live connection), within 15 s on the polling fallback | End-to-end timing in the load test |
| Sync to central | 95% of ticket events at central within 60 s of creation while the WAN is up; after an outage, the backlog drains at 50 or more events per second per site | `qms_sync_outbox_oldest_pending_age_seconds`; drill |
| Reports | One site, one month: under 5 s. One region, one month: under 10 s. National, one month: under 30 s | Report endpoint timing on a year of data |
| Availability | Site node 99.5% during office hours; central 99.5% monthly; local queueing unaffected by central or WAN outages | Monitoring; outage drill |
| Recovery | Site node restored from backup within 1 hour (RTO). Data loss on restore limited to records since the last backup that have not yet reached central (RPO: last backup for unsynced records, the sync delay for synced ones) | Restore drill on target hardware |

## Load test plan (not yet run)

1. **Site node:** simulate the busiest site at three times its peak hour (about 2,800 tickets per hour)
   with 20 agents calling and completing, 2 kiosks and 2 displays. Pass: the agent-action and kiosk
   SLOs, no duplicate numbers, no errors.
2. **Central ingest:** 112 simulated sites pushing at three times the national peak (about 75 events
   per second), then a 4-hour backlog from 20 sites released at once. Pass: the sync SLOs and
   reconciliation MATCH for every site.
3. **Central reports:** load one year of synthetic history (17 million tickets, 60 million events) and
   time the report SLOs while ingest runs.
4. **Endurance:** 8 hours at the average load on both node types, watching memory, connection pool and
   disk.

Tools: k6 or Gatling for HTTP load, the existing Prometheus/Grafana setup for measurement. The runs
need hardware matching the procured site PC server and the central VM (OI-02); results go into
test-plan.md.
