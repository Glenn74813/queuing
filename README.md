# PhilHealth Integrated Queueing Management System (QMS)

Web-based, local-first queueing system for PhilHealth Regional Offices and LHIOs. Each site runs
its own node, so queues keep working during WAN outages, and synchronizes with a central node for
national administration and reporting.

> **Project status: v0.1, a development baseline. It is not production-ready and not yet
> compliant.**
>
> * The core queue engine, security model, kiosk/display/agent/admin UIs, reporting and the
>   synchronization pipeline are built, with 65 backend, 69 frontend and 15 browser end-to-end tests passing.
> * Many procurement items depend on PhilHealth decisions or hardware. See
>   [docs/open-issues.md](docs/open-issues.md) and the
>   [requirements traceability matrix](docs/requirements-traceability-matrix.md).
> * The frontend has been smoke-tested by hand against the running backend: kiosk issue, agent
>   console, live display and reports. No automated browser end-to-end suite exists yet.

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Spring Boot 3.5 / Java 21 API, queue engine, Flyway migrations, tests |
| `frontend/` | React + TypeScript + Vite app: admin portal, agent console, supervisor monitor, reports, kiosk (`/kiosk`), display (`/display`) |
| `deploy/` | Docker Compose stack, nginx TLS proxy, env templates, install/backup/restore scripts, systemd timer |
| `docs/` | Architecture, API contract, metric definitions, sync design, security, operations, test plan, hardware matrix, open issues, RTM |

## Quick start (development)

Prerequisites: JDK 21, Maven 3.9, Node 20, and PostgreSQL 17 (or the Compose `db` service).

```bash
# 1. Database
createdb qms && createuser qms_app -P && psql -d qms -c "grant all on schema public to qms_app"

# 2. Backend (http://localhost:8080, Swagger UI at /swagger-ui.html)
cd backend
export QMS_DB_PASSWORD=... QMS_JWT_SECRET=$(openssl rand -base64 48) \
       QMS_CORS_ORIGINS=http://localhost:5173 QMS_BOOTSTRAP_ADMIN_PASSWORD='Change-Me-Now-1' \
       QMS_SITE_CODE=DEV
mvn spring-boot:run

# 3. Frontend (http://localhost:5173, proxies /api and /ws to :8080)
cd frontend && npm ci && npm run dev
```

Sign in as `superadmin` and change the password. Then create a region, site, site services,
counters, users and devices from the Admin menu.

## Tests

```bash
cd backend && mvn test             # unit + integration (embedded PostgreSQL 17, no Docker required)
cd frontend && npm test -- --run && npm run build
cd e2e && npm ci && scripts/run-local.sh   # browser end-to-end; see e2e/README.md for prerequisites
```

## Documentation

* [Architecture](docs/architecture.md)
* [API contract](docs/api-contract.md) (the running API also serves OpenAPI at `/v3/api-docs`)
* [Metric definitions (draft)](docs/metrics-definitions.md)
* [Offline operation and sync design](docs/sync-design.md)
* [Security controls](docs/security.md)
* [Installation and operations](docs/operations.md)
* [Test plan and results](docs/test-plan.md)
* [Hardware compatibility matrix (template)](docs/hardware-compatibility.md)
* [Open issues and dependencies](docs/open-issues.md)
* [Requirements traceability matrix](docs/requirements-traceability-matrix.md)
