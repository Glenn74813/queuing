#!/usr/bin/env bash
# Runs the Playwright E2E suite against a throw-away local stack:
#   1. drops and recreates the qms_e2e database (and ensures the qms_e2e_app login role),
#   2. starts the backend jar on E2E_BACKEND_PORT and waits for /actuator/health,
#   3. starts the Vite dev server for the frontend on E2E_FRONTEND_PORT and waits for it,
#   4. runs `npx playwright test` (extra arguments are passed through),
#   5. always stops the processes it started (by PID / process group, never by name pattern).
# The exit code is the Playwright exit code (or non-zero if the stack failed to start).
set -Eeuo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$E2E_DIR/.." && pwd)"

# ---------------------------------------------------------------- configuration
PGHOST="${E2E_PGHOST:-localhost}"
PGPORT="${E2E_PGPORT:-55871}"
PG_OWNER="${E2E_PG_OWNER:-qms_owner}"
PG_OWNER_PASSWORD="${E2E_PG_OWNER_PASSWORD:-x}"   # ignored with trust auth
DB_NAME="${E2E_DB_NAME:-qms_e2e}"
APP_ROLE="${E2E_DB_APP_USER:-qms_e2e_app}"
APP_ROLE_PASSWORD="${E2E_DB_APP_PASSWORD:-e2epass}"
BACKEND_PORT="${E2E_BACKEND_PORT:-8095}"
FRONTEND_PORT="${E2E_FRONTEND_PORT:-5180}"
BACKEND_JAR="${E2E_BACKEND_JAR:-}"
if [[ -z "$BACKEND_JAR" ]]; then
  BACKEND_JAR="$(ls -1 "$ROOT_DIR"/backend/target/qms-backend*.jar 2>/dev/null | grep -v original | head -n1 || true)"
fi
export E2E_BOOTSTRAP_PASSWORD="${E2E_BOOTSTRAP_PASSWORD:-E2e-Bootstrap-Pass-1}"
export E2E_BASE_URL="http://localhost:${FRONTEND_PORT}"
export E2E_API_URL="http://localhost:${BACKEND_PORT}/api/v1"
# Optional: a directory containing `node`/`npx` when Node is not on PATH.
if [[ -n "${E2E_NODE_DIR:-}" ]]; then export PATH="$E2E_NODE_DIR:$PATH"; fi

RUN_DIR="$E2E_DIR/.run"
mkdir -p "$RUN_DIR"
BACKEND_LOG="$RUN_DIR/backend.log"
FRONTEND_LOG="$RUN_DIR/frontend.log"
WORK_TMP="$(mktemp -d "${TMPDIR:-/tmp}/qms-e2e.XXXXXX")"

log() { printf '[run-local] %s\n' "$*" >&2; }

# ---------------------------------------------------------------- teardown
BACKEND_PID=""
FRONTEND_PID=""
stop_pid() {
  local pid="$1" name="$2"
  [[ -z "$pid" ]] && return 0
  if kill -0 "$pid" 2>/dev/null; then
    log "stopping $name (pid $pid)"
    # Each service runs in its own session/process group (setsid), whose id is its PID.
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 30); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
}
cleanup() {
  local rc=$?
  set +e
  stop_pid "$FRONTEND_PID" "vite"
  stop_pid "$BACKEND_PID" "backend"
  rm -rf "$WORK_TMP"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------------------------------------------------------------- preflight
command -v npx >/dev/null || { log "npx not found (set E2E_NODE_DIR)"; exit 2; }
command -v psql >/dev/null || { log "psql not found"; exit 2; }
command -v java >/dev/null || { log "java not found"; exit 2; }
[[ -f "$BACKEND_JAR" ]] || { log "backend jar not found: '$BACKEND_JAR' (set E2E_BACKEND_JAR)"; exit 2; }
[[ -x "$ROOT_DIR/frontend/node_modules/.bin/vite" ]] || { log "run 'npm ci' in frontend/ first"; exit 2; }
[[ -d "$E2E_DIR/node_modules/@playwright/test" ]] || { log "run 'PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm ci' in e2e/ first"; exit 2; }
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for p in "$BACKEND_PORT" "$FRONTEND_PORT"; do
  if port_busy "$p"; then log "port $p is already in use; refusing to start (nothing was stopped)"; exit 2; fi
done
case "$DB_NAME" in qms|qms2|postgres|template0|template1) log "refusing to reset database '$DB_NAME'"; exit 2;; esac

# ---------------------------------------------------------------- database reset
log "resetting database $DB_NAME on $PGHOST:$PGPORT"
export PGPASSWORD="$PG_OWNER_PASSWORD"
psql -v ON_ERROR_STOP=1 -q -h "$PGHOST" -p "$PGPORT" -U "$PG_OWNER" -d postgres <<SQL
DROP DATABASE IF EXISTS "$DB_NAME" WITH (FORCE);
CREATE DATABASE "$DB_NAME" OWNER "$PG_OWNER";
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_ROLE') THEN
    CREATE ROLE "$APP_ROLE" LOGIN PASSWORD '$APP_ROLE_PASSWORD';
  ELSE
    ALTER ROLE "$APP_ROLE" LOGIN PASSWORD '$APP_ROLE_PASSWORD';
  END IF;
END
\$\$;
GRANT CONNECT ON DATABASE "$DB_NAME" TO "$APP_ROLE";
SQL
psql -v ON_ERROR_STOP=1 -q -h "$PGHOST" -p "$PGPORT" -U "$PG_OWNER" -d "$DB_NAME" \
  -c "GRANT USAGE ON SCHEMA public TO \"$APP_ROLE\";"

# ---------------------------------------------------------------- backend
mkdir -p "$WORK_TMP/media" "$WORK_TMP/reports"
log "starting backend ($BACKEND_JAR) on :$BACKEND_PORT, log: $BACKEND_LOG"
QMS_PORT="$BACKEND_PORT" \
QMS_DB_URL="jdbc:postgresql://$PGHOST:$PGPORT/$DB_NAME" \
QMS_DB_USER="$APP_ROLE" \
QMS_DB_PASSWORD="$APP_ROLE_PASSWORD" \
SPRING_FLYWAY_USER="$PG_OWNER" \
SPRING_FLYWAY_PASSWORD="$PG_OWNER_PASSWORD" \
QMS_JWT_SECRET="$(openssl rand -base64 48)" \
QMS_CORS_ORIGINS="$E2E_BASE_URL" \
QMS_SITE_CODE="E2E" \
QMS_BOOTSTRAP_ADMIN_PASSWORD="$E2E_BOOTSTRAP_PASSWORD" \
QMS_MEDIA_DIR="$WORK_TMP/media" \
QMS_REPORTS_DIR="$WORK_TMP/reports" \
QMS_LOGIN_RATE_PER_MINUTE=1000 \
  setsid java -jar "$BACKEND_JAR" >"$BACKEND_LOG" 2>&1 < /dev/null &
BACKEND_PID=$!

wait_for() {
  local url="$1" pattern="$2" pid="$3" name="$4" timeout="$5" i body
  for ((i = 0; i < timeout; i++)); do
    if ! kill -0 "$pid" 2>/dev/null; then log "$name exited during startup"; return 1; fi
    body="$(curl -fsS --max-time 2 "$url" 2>/dev/null || true)"
    if [[ -n "$body" && "$body" == *"$pattern"* ]]; then log "$name is up (${i}s)"; return 0; fi
    sleep 1
  done
  log "$name did not become ready within ${timeout}s"
  return 1
}
if ! wait_for "http://localhost:$BACKEND_PORT/actuator/health" '"UP"' "$BACKEND_PID" backend 180; then
  tail -n 60 "$BACKEND_LOG" >&2 || true
  exit 3
fi

# ---------------------------------------------------------------- frontend (Vite dev server)
log "starting vite on :$FRONTEND_PORT, log: $FRONTEND_LOG"
(
  cd "$ROOT_DIR/frontend"
  QMS_API_TARGET="http://localhost:$BACKEND_PORT" \
    exec setsid ./node_modules/.bin/vite --port "$FRONTEND_PORT" --strictPort >"$FRONTEND_LOG" 2>&1 < /dev/null
) &
FRONTEND_PID=$!
if ! wait_for "http://localhost:$FRONTEND_PORT/" '<div id="root">' "$FRONTEND_PID" vite 90; then
  tail -n 40 "$FRONTEND_LOG" >&2 || true
  exit 3
fi

# ---------------------------------------------------------------- tests
cd "$E2E_DIR"
log "running playwright"
set +e
npx playwright test "$@"
TEST_RC=$?
set -e
log "playwright exited with $TEST_RC"
exit "$TEST_RC"
