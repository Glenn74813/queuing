# Performance Metric Definitions — v1.0-draft

**Status: DRAFT for PhilHealth validation (open issue OI-07).** The procurement specification
names the metrics but does not define them. The definitions below are what the code implements
(`backend/.../report/ReportService.java`, constant `METRIC_DEFINITIONS_VERSION`). Any change agreed
with PhilHealth must update this document, the SQL, the version constant and the reconciliation tests.

## Source data

Every metric is computed from the `ticket` table. Each status change also writes an append-only
`ticket_event` row. On every transition, the time spent in the previous status is added to one
of three per-ticket accumulators:

| Accumulator | Increases while the ticket is |
|---|---|
| `wait_seconds` | `WAITING` (in any queue, across every stage) |
| `service_seconds` | `CALLED` (assigned to a counter and being served) |
| `hold_seconds` | `ON_HOLD` (set aside at a counter, e.g. the client is completing a form) |

Timestamps are stored in UTC. Reports filter on `business_date`, which is the ticket's issue date
in the site's time zone (default Asia/Manila), so a day's report never splits at UTC midnight.

## Metrics

| Metric | Definition | Start → End | Included tickets | Notes |
|---|---|---|---|---|
| **Number issued** | count of tickets | – | all, including voided | Voided tickets are reported separately so totals reconcile. |
| **Number of clients served** | count with status `COMPLETED` | – | completed | A multi-stage client counts **once**, because a transfer keeps the same ticket. |
| **Average queueing time** | mean(`first_called_at − issued_at`) | ticket issued → **first** call to any counter | called at least once, not voided | Measures how long the client waited before first contact. Recalls do not restart it. |
| **Average waiting time** | mean(`wait_seconds`) | sum of every interval in `WAITING` | `COMPLETED` only | Includes waiting after a transfer (later stages). Excludes time on hold and time at a counter. |
| **Average processing time** | mean(`service_seconds`) | sum of every interval in `CALLED` | `COMPLETED` only | Starts at the call, so it includes the client walking to the counter. Excludes hold time. Covers all stages. |
| **Number of abandoned tickets** | count with status `REMOVED` or `EXPIRED` | – | – | `REMOVED` = agent or supervisor removed the ticket (no-show, left); `EXPIRED` = still open at the end of the business day (hourly job). Voided tickets are **not** abandoned. |
| **Number of voided tickets** | count with status `VOIDED` | – | – | Tickets issued in error. A reason is mandatory and audited. |
| **Number of reappointed / rerouted tickets** | count with `transfer_count > 0` | – | all | A ticket transferred several times counts once. "Reappointed" (scheduled return on another day) is **not modelled yet** (OI-09). |
| **Client feedback** | count and mean rating (1–5) of `feedback` rows | – | feedback exists only for `COMPLETED` tickets, at most one per ticket | Per service and per completed transaction. CSMST integration is pending (OI-10). |

### Treatment of special cases

* **Held tickets.** Time on hold is in neither waiting time nor processing time. It is kept
  separately in `hold_seconds` (shown in the ticket-level report).
* **Transferred tickets.** They stay one ticket. `stage_no` increases when the service changes.
  The summary groups a ticket under its **original** service (`original_site_service_id`), so the
  number issued per service is not inflated. The ticket-level report shows both the original and
  the current service.
* **Incomplete transactions.** Tickets still `WAITING`, `CALLED` or `ON_HOLD` count in "issued"
  only. Once they expire they count as abandoned. The exception report flags open tickets from
  past days whose expiry did not run.
* **Recalls.** They increment `recall_count` and update `last_called_at`, but do not change
  queueing time.
* **Priority clients.** They are included in every metric. Group or filter by priority with the
  ticket-level export (`priorityCode`).
* **Electronic vs printed.** Use the `CHANNEL` grouping.

## Agent performance against the SLA

SLA targets are set per region in **Admin › SLA Targets**:
* a waiting target (minutes before the client is called);
* a processing target (minutes at the counter);
* a compliance target (% of transactions within target).

A region without its own SLA uses the national default, and per-service overrides take precedence.
The report (**Reports › Agent performance**, `GET /reports/agent-performance`) is computed from
`ticket_event`, so every figure traces to the event log:

| Figure | Definition |
|---|---|
| Handled transaction | One ticket, at one service stage, called (or resumed) by the agent. A client transferred between agents is one transaction for each. |
| Processing time | Time in CALLED from each CALL/RESUME by the agent until the next status change. Recalls do not interrupt it. Time ON_HOLD is excluded. |
| Waiting time | The client's time in the queue before the agent's CALL (since issue, or since the transfer into this stage). |
| Processing within target | Share of the agent's finished transactions (completed or transferred; no-shows and in-progress excluded) with processing time ≤ the applicable target. |
| Waiting within target | Share of the agent's calls where the client waited ≤ the applicable waiting target. |
| SLA status | **MET** when processing within target ≥ the compliance target; **NOT MET** otherwise; **NO DATA** when nothing was finished in the period. Waiting compliance is reported but does not decide the status, because waiting depends on staffing and arrivals as much as on the agent (to be confirmed with PhilHealth, OI-27). |

## Traceability

* `GET /api/v1/reports/tickets` returns the exact rows behind each summary, with the per-ticket
  values.
* `GET /api/v1/tickets/{id}` returns the full event history of one ticket.
* `GET /api/v1/reports/exceptions` lists data-validation exceptions:
  * completed but never called;
  * time inversions (an end time before its start time);
  * open past the business day;
  * called without a counter;
  * processing time over 4 h;
  * a ticket with no event history.
* The integration test `QueueFlowIT.fullTicketLifecycle` drives a known scenario end to end and
  checks that the summary totals reconcile with it.
