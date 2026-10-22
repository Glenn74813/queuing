# System Architecture

## Components

| Component | Technology | Location |
|---|---|---|
| Web UI (admin portal, agent console, supervisor monitor, reports, CMS) | React 18 + TypeScript + Vite, Bootstrap 5 | `frontend/` |
| Kiosk (`/kiosk`) and queue display (`/display`) | Same SPA, device-key authenticated, full-screen | `frontend/src/pages/device` |
| REST API, queue engine, reporting, synchronization | Java 21 (LTS), Spring Boot 3.5 (Web, Security, Data JPA, Validation, WebSocket/STOMP, Scheduling), Flyway, springdoc-openapi | `backend/` |
| Database | PostgreSQL 17 | `backend/src/main/resources/db/migration` |
| Reverse proxy / TLS | nginx | `deploy/nginx` |
| Packaging | Docker images + Docker Compose (per node) | `deploy/` |

Each of the 112 sites runs a **site node**: proxy, API, web and database on its local server.
One **central node** runs the same software with `QMS_NODE_ROLE=CENTRAL`. See
[sync-design.md](sync-design.md) for the offline and synchronization model.

## Backend modules (`ph.gov.philhealth.qms`)

| Package | Responsibility |
|---|---|
| `security` | JWT issuance and verification, device-key and site-key filters, `AuthUser` grants, `ScopeService` (organizational scope checks), login rate limiter, forced password change |
| `user` | Login, refresh-token rotation, lockout, user/role administration, password policy, bootstrap admin |
| `org` | Regions and sites |
| `catalog` | National service catalog, site services (prefix, hours), priority categories |
| `counter` | Counters, counter-service eligibility, devices (kiosks/displays), agent sessions and breaks |
| `queue` | Ticket entity and state machine, number allocation, `QueueService` (all status changes), agent sessions, read models (snapshot, kiosk config, display board), feedback, end-of-day expiry |
| `cms` | Content items, approval workflow, media storage with magic-byte validation |
| `report` | Metric SQL, summary, ticket-level and exception reports, CSV/XLSX export |
| `audit` | Append-only audit log and query API |
| `sync` | Transactional outbox, push job, central ingest, configuration bundle and pull, ownership guard, system status |
| `common` | Error model, exception handler, UUIDv7 ids, hashing, time service |

Layering: controllers do HTTP mapping and coarse `@PreAuthorize` checks. Services own the
transactions, scope checks, validation and auditing. Repositories handle persistence.

## Queue engine invariants

1. **The state machine is the only authority on transitions.** `TicketStateMachine` lists the legal
   source statuses for each action. Anything else returns `409 INVALID_TRANSITION`.
2. **Unique ticket numbers.** Numbers come from an atomic `INSERT … ON CONFLICT DO UPDATE …
   RETURNING` on `ticket_sequence`, backed by unique constraints. The concurrency test issues 60
   tickets in parallel and gets 60 distinct, gap-free numbers.
3. **No double calls.** "Call next" selects with `FOR UPDATE SKIP LOCKED`, so concurrent counters
   each get a different ticket. The test runs two agents draining 20 tickets in parallel with no
   duplicates.
4. **One session per agent and per counter.** This is enforced by partial unique indexes, not only
   by code.
5. **Row locks plus optimistic versioning.** Every ticket action locks the ticket row and is
   protected by `@Version`.
6. **Atomic history and sync.** Each status change writes a `ticket_event`, updates the time
   accumulators and writes the sync outbox in the same transaction. WebSocket broadcasts happen
   only after commit.

## Real-time updates

STOMP over WebSocket at `/ws`, topic `/topic/sites/{siteId}/queue`:

* Payloads contain only the ticket number, counter and service name, with no personal data.
* Clients cannot publish, and may subscribe only to topics matching that pattern.
* Clients also poll every 15 s and reconnect automatically, so a lost message or a network blip
  self-heals.

## Diagrams

* Database: ER diagram (Mermaid), data dictionary and privilege model in [database.md](database.md);
  the schema itself is defined by the Flyway migrations in `backend/src/main/resources/db/migration`.
* Ticket lifecycle: see the Javadoc diagram in `TicketStateMachine.java`.

## Related documents

* [Database: ER diagram, data dictionary and privileges](database.md)
* [Kiosk and display device lock-down](kiosk-lockdown.md)
* [User guide](user-guide.md)
