# Offline Operation and Synchronization Design

## Topology

```
 [Kiosk] [Display] [Agent PCs]                       [Kiosk] [Display] [Agent PCs]
      \     |      /                                      \     |      /
   ┌─────── Site node (LHIO 1) ───────┐               ┌─────── Site node (LHIO 112) ──┐
   │ nginx TLS │ API │ PostgreSQL     │  ... WAN ...  │ nginx TLS │ API │ PostgreSQL    │
   └───────────────┬──────────────────┘               └───────────────┬───────────────┘
                   │  push outbox (HTTPS, X-Site-Key)                  │
                   │  pull configuration                               │
                   ▼                                                   ▼
             ┌────────────────── Central node (QMS_NODE_ROLE=CENTRAL) ─────────────────┐
             │ national administration, consolidated reporting, configuration source  │
             └─────────────────────────────────────────────────────────────────────────┘
```

Each site node has its own server and database (the 112 PC servers). Kiosks, displays and agent
PCs talk **only** to their site node over the LAN. A WAN or central outage therefore cannot stop
any of these at the site:

* issuing tickets;
* calling or serving clients;
* displays;
* site administration;
* site reports.

Two background jobs synchronize the site with central:

* `SyncPushJob`
* `ConfigSyncService.pull`

Each job runs on its own schedule. A failure is logged and retried, and never affects request
handling.

## Identifiers and ticket numbers

* Every record uses a UUIDv7 primary key, generated on the node that creates it. The key is
  globally unique, so replicated rows never collide.
* Ticket numbers (`A-012`) are allocated only by the site that issues them, from
  `ticket_sequence (site_id, prefix, business_date)`. They are unique per site per day, which is
  enforced by two unique constraints. Because no other node issues tickets for a site, an outage
  cannot produce conflicting numbers.
* Central reports identify a ticket by its site and display number, or by its UUID.

## Data ownership and direction

| Data category | Owner (source of truth) | Direction | Conflict policy |
|---|---|---|---|
| Regions, sites | Central | central → sites (pull) | Central wins. Local edits are refused on a synchronized site node (`OwnershipGuard`). |
| National service catalog, priority categories | Central | central → sites | Central wins (as above). |
| Site services (prefix, hours, enablement), counters, devices | Site | site → central | Site wins (upsert by id). Device keys are never replicated: central stores an unusable placeholder hash. |
| Tickets, ticket events | Site | site → central | Ticket: the snapshot replaces the central copy only if its `version` ≥ the stored version. Events are append-only and inserted once. |
| Feedback | Site | site → central | Insert once. |
| Audit log | Node where the action happened | site → central | Insert once (append-only). |
| Users and role assignments | Node-local (see OI-04) | not synchronized | – |
| Agent sessions, breaks | Site | not synchronized | Operational state only. |
| CMS content | Central for national/regional content; sites for their own local content | central → sites (pull) | Content created on central and PUBLISHED (or later ARCHIVED) is sent to every site it targets, and is read-only there (`origin = CENTRAL`). Site-created content stays local. A replica never overwrites local content. |

Retention: tickets and events are kept indefinitely in v0.1. The retention period must be defined
under the PhilHealth records-retention policy and the Data Privacy Act (OI-13).

## Outbox (site → central)

1. The business transaction (for example, call a ticket) writes its change **and** a
   `sync_outbox` row in one database transaction. A committed change is therefore always queued
   for synchronization, even if the server loses power a millisecond later.
2. `SyncPushJob` runs every `QMS_SYNC_PUSH_INTERVAL_MS` (15 s). It takes up to 200 due entries in
   creation order and POSTs them to `{central}/api/v1/sync/ingest`.
3. Each entry carries:
   * `idempotencyKey`: `<siteCode>:<outboxId>`, stable across retries;
   * `payload`: JSON;
   * `payloadSha256`: for integrity verification.
4. Central answers with `accepted`, `duplicates` and `rejected`:
   * accepted and duplicate entries are marked `SENT`;
   * rejected entries, and every entry in a batch that hit a transport failure, become `FAILED`.
5. Failed entries are retried with exponential backoff: 5 s, 10 s, 20 s, and so on, capped at
   15 min.
6. Administrators see backlog and failures at `GET /system/status` and `GET /sync/outbox`, and can
   force a retry with `POST /sync/outbox/{id}/retry`.

## Inbox (central)

For each entry, central:

1. checks that the entry's site matches the authenticated site key;
2. verifies the SHA-256;
3. inserts the idempotency key into `sync_inbox` and applies the change, in **one** transaction.

A duplicate key means the entry was already applied, so it is acknowledged and not applied again.
A rejected entry is **not** recorded, so the site's retry can succeed later (for example, once a
referenced counter has arrived). The integration test `SyncIngestIT` covers:

* idempotency;
* tampering;
* foreign-site data;
* stale-version handling.

## Configuration pull (central → site)

`GET /api/v1/sync/config?since=<watermark>` returns the central-owned rows changed since the
watermark, minus a 5-minute overlap. The site upserts them by id and stores the server time as
the new watermark.

## Content distribution (central → site)

1. The config bundle includes content from central that is PUBLISHED or ARCHIVED and targets
   the calling site, the site's region, or everyone.
2. For each published item with media not yet on disk, the site downloads
   `GET /api/v1/sync/media/{id}` (site key) into a temporary file. It checks the size and
   SHA-256 from the bundle, then moves the file into place atomically.
3. Only then is the content row upserted (`origin = CENTRAL`), so displays never reference
   a missing file. If a download fails, the row is skipped and retried at the next pull.

## Bootstrapping a site node

1. On central:
   1. Create the region and the site.
   2. Issue the site's key: `POST /sites/{id}/sync-key`.
2. On the site node:
   1. Set `QMS_SITE_CODE`, `QMS_CENTRAL_URL`, `QMS_SYNC_SITE_KEY` and `QMS_SYNC_ENABLED=true`, then
      start it. The first config pull creates the regions, sites, catalog and priorities with the
      same ids as on central.
   2. Using the bootstrap admin, create local administrators, site services, counters and devices.
3. Do **not** create regions or sites locally on a site node that will be synchronized: their
   ids would differ from central's.

## Reconciliation

Every hour (`QMS_SYNC_RECONCILE_CRON`), each site queues a `DAILY_SUMMARY` outbox entry for each of
the last 7 business days. Each entry holds the site's own counts of tickets, ticket events and
feedback, plus how many outbox entries were still unsent. Because summaries travel through the same
ordered outbox, a summary normally arrives after the records it counts. Central stores the latest
summary per site and day (`sync_reconciliation`), and `GET /api/v1/sync/reconciliation` (System
page) compares it with the rows central holds:

| Status | Meaning |
|---|---|
| MATCH | Central holds exactly what the site reported. |
| IN_TRANSIT | Central is behind, but the site still had unsent entries; it should catch up. |
| MISMATCH | Central is behind with nothing left to send (records were lost or rejected), or central holds more than the site. Investigate using the outbox's `lastError` and the ticket-level report. |

A site administrator can queue summaries immediately with **Send summaries now** on the System page.

## Restoring a site node from backup

A restored site no longer has the records it created after the backup, but central does. Two rules
keep both sides consistent:

* **Numbering floor.** Every configuration bundle carries, per prefix, the highest ticket number
  central holds for the site in the current numbering period (`numberFloors`). The site raises its
  sequence to at least that value (never lowers it), so once it has pulled configuration it does
  not reissue a number central already knows.
* **Central keys tickets by id only.** On central, V12 removes the per-day uniqueness of ticket
  numbers. If a restored site issues a number again before its first pull (for example with the
  WAN down), central keeps both clients' records instead of rejecting the newer one.

Reconciliation then shows central holding more records for that day than the site. This is
expected: those are the clients served between the backup and the restore.

## Known limitations (v0.1)

* Reconciliation compares **counts**, not individual record ids. It detects that records are
  missing or extra for a site and day, but not which ones. To locate them, compare
  `GET /reports/tickets` on both nodes for that day.
* Ordering: entries are sent in creation order, but a rejected entry does not block later ones.
  A ticket event can therefore arrive before the counter it references. It is rejected and then
  retried after the counter arrives.
* Central-managed users are not distributed to sites (OI-04).
* Media downloads are retried on the next config pull. The watermark is not advanced while any
  download fails, so a large video over a slow WAN is retried rather than skipped.
