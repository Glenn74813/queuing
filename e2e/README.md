# QMS browser end-to-end tests (Playwright)

Automated browser tests that drive the real React frontend (Vite dev server) against the real
Spring Boot backend and PostgreSQL. They complement the backend integration tests and the frontend
unit tests; nothing is mocked.

## What is covered

| Spec | Scenario |
|---|---|
| `tests/auth.spec.ts` | Wrong password shows "Invalid username or password"; an administrator-created agent is forced to **Change password** on first login and then lands in the app; a page reload keeps the session (regression: a password change used to revoke the refresh token so the next reload signed the user out); **Sign out** returns to the sign-in page and stays there after reload. |
| `tests/kiosk-to-counter.spec.ts` | Core flow in three browser contexts: `/kiosk` (device key in `localStorage['qms.deviceKey']`) → PMRF service → **Senior Citizen** → **Show on screen only** → number like `A-001`/`PA-001`; the agent starts a session at **Counter 1**, **Call next** shows the same number; `/display` shows it under **Now serving** with "Counter 1"; after **Complete** the display moves it to **Recent calls** (WebSocket, or the 15 s polling fallback, within 20 s). |
| `tests/supervisor.spec.ts` | With the agent serving a ticket at Counter 2 (set up via the API), the supervisor uses **Queue Monitor › Close counter** with a reason; the counter shows "Unstaffed", the ticket returns to **Waiting**, and the agent has no session. |
| `tests/reports.spec.ts` | Superadmin runs **Reports** for today: the **Total** row's Issued count equals the API's summary and includes the tickets the test issued; **Trends** shows the "not enough days" message for one day and the "Clients per day" chart for a week; **Show as table** shows the daily table. **Scheduled Reports**: create a daily CSV schedule, **Run now**, the files dialog shows a SUCCESS run and **Download** fires a browser download named `*.csv`. |
| `tests/access.spec.ts` | An agent sees no Administration links (Users, Counters, …) and `/admin/users` shows "Not authorized"; the superadmin (control) sees and opens **Users**. |

### Test data

`global-setup.ts` seeds everything **through the REST API only**, as the bootstrap `superadmin`
(whose first-login password change it performs): region `E2E-R`, site `E2E` (LHIO, Asia/Manila),
site services SVC-01 (PMRF, prefix `A`) and SVC-21 (walk-in inquiries, prefix `W`), counters
`Counter 1 (C1)` and `Counter 2 (C2)` serving both, users `e2e.agent` (AGENT) and
`e2e.supervisor` (SUPERVISOR) created with temporary passwords and then taken through their
first-login password change, and a KIOSK and a DISPLAY device. Ids, device API keys and passwords
go to `.auth/state.json` (git-ignored). Each step is find-or-create (device keys are rotated and
user passwords reset when they already exist), so it also works when re-run against the same
database.

Tests are independent of each other's order: tests that need queue data issue their own tickets
through the kiosk API, and the queue-dependent specs first reset the site's queue through the API
(close every staffed counter, void every open ticket). The auth spec creates its own new user per
run so the "must change password" state is always present.

## Running locally

Prerequisites: Node 20, JDK 21, `psql`, a PostgreSQL 17 server where `E2E_PG_OWNER` may create
databases and roles, the frontend dependencies (`cd frontend && npm ci`), Google Chrome (local
runs use the installed Chrome through Playwright's `channel: 'chrome'`, so no browser download is
needed) and a built backend jar.

```bash
cd e2e
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm ci
E2E_BACKEND_JAR=/path/to/qms-backend.jar ./scripts/run-local.sh            # whole suite
E2E_BACKEND_JAR=/path/to/qms-backend.jar ./scripts/run-local.sh tests/auth.spec.ts --headed
```

`scripts/run-local.sh`:

1. drops and recreates the `qms_e2e` database (refuses `qms`, `qms2` and system databases) and
   creates/updates the login role `qms_e2e_app`;
2. starts the backend jar on port 8095 (log: `e2e/.run/backend.log`) with Flyway running as the
   owner role and the app using `qms_e2e_app`, then waits for `/actuator/health` to be `UP`;
3. starts the Vite dev server from `frontend/` on port 5180 (log: `e2e/.run/frontend.log`),
   proxying `/api` and `/ws` to the backend;
4. runs `npx playwright test` with any extra arguments;
5. always stops the two processes it started, by PID/process group (never by name), and exits with
   Playwright's exit code. It refuses to start if port 8095 or 5180 is already in use.

Reports: `npx playwright show-report` (HTML report in `playwright-report/`, never auto-opened);
failure screenshots in `test-results/`.

To run against a stack you started yourself, skip the script and run `npx playwright test` with
`E2E_BASE_URL` / `E2E_API_URL` / `E2E_BOOTSTRAP_PASSWORD` pointing at it (the database must be
fresh, or already seeded by an earlier run of this suite).

## CI

The `e2e` job in `.github/workflows/ci.yml` (after `backend` and `frontend`) uses a `postgres:17`
service container (`qms_owner` / database `qms_e2e`), builds the jar with `mvn -B -DskipTests
package`, creates the `qms_e2e_app` role with `psql`, starts the jar on 8095 and the Vite dev
server on 5180, installs Playwright's bundled Chromium (`npx playwright install --with-deps
chromium`), runs `npx playwright test` (1 retry, trace on first retry) and uploads
`playwright-report/` and `test-results/` when it fails. The database is fresh per job, so no reset
is needed there.

## Environment variables

| Variable | Default | Used by |
|---|---|---|
| `E2E_BASE_URL` | `http://localhost:5180` | tests (Playwright `baseURL`) |
| `E2E_API_URL` | `http://localhost:8095/api/v1` | global set-up and API helpers |
| `E2E_BOOTSTRAP_PASSWORD` | `E2e-Bootstrap-Pass-1` | backend `QMS_BOOTSTRAP_ADMIN_PASSWORD`; first superadmin login |
| `E2E_SUPERADMIN_PASSWORD` | `E2e-Root-Admin-Pass-9` | password the set-up gives `superadmin` |
| `E2E_BROWSER_CHANNEL` | `chrome` locally, empty in CI | Playwright `channel`; empty = bundled Chromium |
| `CI` | – | enables 1 retry and `forbidOnly` |
| `E2E_BACKEND_JAR` | `backend/target/qms-backend*.jar` | run-local.sh |
| `E2E_PGHOST` / `E2E_PGPORT` | `localhost` / `55871` | run-local.sh |
| `E2E_PG_OWNER` / `E2E_PG_OWNER_PASSWORD` | `qms_owner` / `x` | run-local.sh (DB reset, Flyway) |
| `E2E_DB_NAME` | `qms_e2e` | run-local.sh |
| `E2E_DB_APP_USER` / `E2E_DB_APP_PASSWORD` | `qms_e2e_app` / `e2epass` | run-local.sh (application login) |
| `E2E_BACKEND_PORT` / `E2E_FRONTEND_PORT` | `8095` / `5180` | run-local.sh |
| `E2E_NODE_DIR` | – | run-local.sh: directory with `node`/`npx` when Node is not on `PATH` |

Business dates are evaluated in the site's time zone, so the browser runs with
`timezoneId: 'Asia/Manila'`.
