# Security Controls

| Area | Control | Implementation |
|---|---|---|
| Authentication | JWT access tokens (HS256) of 15 min. Opaque refresh tokens of 12 h that rotate and are single-use, with recorded lineage. Reusing a revoked token revokes every token of that user. Exception: re-presenting a token rotated less than 60 s ago, while its replacement is still unused (a reload or dropped connection mid-refresh), issues a new pair and cancels the unused replacement. | `JwtService`, `AuthService`; tested in `SecurityIT.refreshTokensRotateAndReuseIsDetected` |
| Authorization | The JWT only identifies the user. Permissions and scope are reloaded from the DB on every request, so suspensions and role changes apply immediately. There is a coarse `@PreAuthorize` check per endpoint, plus `ScopeService` site/region checks in every service method. | `AuthUserLoader`, `ScopeService`; `SecurityIT.siteScopeIsEnforced`, `hierarchicalUserAdministration` |
| Devices | Kiosks and displays use per-device API keys (stored only as SHA-256), limited by authority to kiosk or display endpoints. Keys can be rotated and devices deactivated. | `DeviceAuthenticationFilter`, `DeviceService` |
| Site-to-central | A per-site sync key (SHA-256 stored), checked against the payload's site, with an SHA-256 integrity check on each entry. | `SiteKeyAuthenticationFilter`, `SyncIngestService` |
| Passwords | BCrypt (cost 12). At least 12 characters, with upper case, lower case and a digit, and not containing the username. Admin-set passwords must be changed at first login (enforced server-side). | `PasswordPolicy`, `PasswordChangeRequiredFilter` |
| Brute force | Account locks for 15 min after 5 failures. Per-IP sliding-window limit on login and refresh. Unknown users get a constant-time dummy hash check. | `AuthService`, `LoginRateLimiter`; `SecurityIT.accountLocksAfterRepeatedFailures` |
| CORS | Explicit allowlist from `QMS_CORS_ORIGINS`. No credentials or cookies. | `SecurityConfig`; `SecurityIT.corsAllowsOnlyConfiguredOrigin` |
| CSRF | Not applicable: no cookie or ambient credentials, since every call carries an explicit header. | `SecurityConfig` |
| Transport | TLS 1.2/1.3 at nginx, with HSTS. HTTP redirects to HTTPS. | `deploy/nginx/qms.conf` |
| Headers | CSP, X-Frame-Options DENY, nosniff, Referrer-Policy no-referrer, HSTS. | `SecurityConfig`, nginx |
| Injection | JPA, or JdbcTemplate with bind parameters everywhere. Report group-by and filter values come from fixed whitelists. CSV export neutralizes spreadsheet formulas. | `ReportService`, `ReportExporter` |
| XSS | React escapes output by default. The API returns JSON only. Uploaded media is served with the detected MIME type and nosniff. | – |
| Uploads | Type is detected from magic bytes (PNG/JPEG/WebP/MP4/WebM) with size limits of 10/200 MB. The file is saved under a server-generated name, and path traversal is blocked. Only published media is publicly readable. | `MediaStorage` |
| Errors | Uniform error body. No stack traces or SQL in responses; details are logged server-side. | `GlobalExceptionHandler` |
| Audit | Append-only `audit_log`, written in the same transaction as the change it records. It covers logins, lockouts, user/role/status changes, catalog/site/counter/device changes, ticket void/remove, content workflow, report generation/export and sync administration. | `AuditService` |
| Secrets | Read only from environment variables. Templates in `deploy/env/*.example`; real env files are git-ignored. | – |
| Database | Migrations run as the schema owner (`SPRING_FLYWAY_USER`). The application role has DML only and cannot change the schema. `audit_log` and `ticket_event` are append-only at the database level: the role has no UPDATE, DELETE or TRUNCATE on them. | `V3__app_role_privileges.sql`, `deploy/scripts/db-init.sh`; `DatabasePrivilegesIT` |
| Backups | `pg_dump` plus media, GPG-encrypted, with SHA-256 manifest and retention. | `deploy/scripts/backup.sh` |
| Metrics | `/actuator/prometheus` requires the scrape token (`QMS_METRICS_TOKEN`, compared in constant time) or `SYSTEM_MONITOR`. The token opens nothing else, and nginx blocks `/actuator/*` externally. Metric labels contain no personal data (site codes, statuses). | `MetricsTokenFilter`; `MetricsIT` |
| WebSocket | Server-to-client broadcast only. SEND frames are rejected and subscriptions are limited to the queue-topic pattern. No personal data in messages. | `WebSocketConfig` |

## Dependency vulnerability status (2026-10-22)

| Scope | Result | Action |
|---|---|---|
| Frontend production dependencies (`npm audit --omit=dev`) | No high or critical issues | axios was upgraded 1.7.7 → 1.20.0 (SSRF, prototype-pollution and header-injection advisories), and react-router-dom 6.28.0 → 6.30.6 (open redirect). |
| react-router 6.x, 2 moderate advisories | Accepted for now | The fix requires the React Router 7 major upgrade. One advisory is SSR-only (not used here). The other needs app navigation to user-controlled paths, which the app never does: the login redirect comes from router state it sets itself. Plan the v7 migration. |
| Frontend dev tooling (vite 5.4.21, vitest 2.1.9) | High/critical advisories remain in the dev server and test runner | They are never deployed. The dev server listens on localhost only, and the vitest UI is not used. The only fixed releases (vite 6.4.5, vitest 4.1.12) are less than 2 weeks old, so they were deliberately not adopted yet. Upgrade through Dependabot once they have aged. |
| Backend (OWASP Dependency-Check) | Not yet run | `mvn -Psecurity-scan verify` needs an NVD API key (CI secret `NVD_API_KEY`). |

## Not yet done (required before go-live)

* Third-party VAPT (OI-22) and remediation.
* Activate the CI pipeline (`.github/workflows/ci.yml`: build, tests, OWASP Dependency-Check,
  `npm audit`, image builds) on PhilHealth's CI platform, and add the `NVD_API_KEY` secret.
* The rate limiter is in memory, per node. Behind several API instances, use a shared limiter
  or rely on per-node limits.
* Security event forwarding to PhilHealth's SIEM; the format is to be agreed.
* Incident-response procedure document.
