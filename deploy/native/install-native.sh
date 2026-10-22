#!/usr/bin/env bash
# Native (non-Docker) installation of a PhilHealth QMS node: OpenJDK 21 + PostgreSQL 17 (PGDG) + nginx.
# Supported hosts: Ubuntu 24.04 LTS and RHEL 9 family (RHEL, Rocky, AlmaLinux, Oracle Linux).
#
#   usage: sudo ./install-native.sh --jar <qms-backend.jar> --web <frontend dist dir> [--dry-run]
#
# Idempotent: safe to re-run. The first run creates /etc/philhealth-qms/qms.env from the template
# and stops so that you can fill it in; re-run after editing it and placing the TLS certificate.
# --jar / --web may be omitted on a re-run when an application is already installed.
# --dry-run prints every action without changing the system (it may be run without root to preview).
# Secrets from the env file are never printed.
set -euo pipefail
umask 022

readonly PREFIX=/opt/philhealth-qms
readonly ETC_DIR=/etc/philhealth-qms
readonly ENV_FILE=$ETC_DIR/qms.env
readonly UNIT_FILE=/etc/systemd/system/qms-api.service
readonly DB_NAME=qms
readonly PG_MAJOR=17
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

JAR=""
WEB=""
DRY_RUN=0

log()  { printf '==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# Runs a command, or prints it in --dry-run mode. Never pass secrets as arguments.
run() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run]'; printf ' %q' "$@"; printf '\n'
  else
    "$@"
  fi
}

# Prints a description in --dry-run mode; otherwise returns 0 so that the caller performs the action.
#   if act "write X"; then ...; fi
act() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run] %s\n' "$*"
    return 1
  fi
  return 0
}

# ------------------------------------------------------------------------------------------- arguments
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) [ $# -ge 2 ] || die "--jar needs a path"; JAR="$2"; shift 2 ;;
    --web) [ $# -ge 2 ] || die "--web needs a path"; WEB="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done

if [ "$(id -u)" -ne 0 ]; then
  if [ "$DRY_RUN" -eq 1 ]; then
    warn "not running as root: dry-run only shows the planned actions."
  else
    die "must be run as root (sudo $0 ...)"
  fi
fi

if [ -n "$JAR" ]; then
  JAR="$(realpath -e -- "$JAR")" || die "--jar: file not found"
  [ -f "$JAR" ] || die "--jar: not a regular file: $JAR"
  case "$JAR" in *.jar) ;; *) die "--jar: expected a .jar file: $JAR" ;; esac
fi
if [ -n "$WEB" ]; then
  WEB="$(realpath -e -- "$WEB")" || die "--web: directory not found"
  [ -f "$WEB/index.html" ] || die "--web: $WEB does not contain index.html (pass the frontend dist/ directory)"
fi
cd /   # commands run as postgres must not inherit an unreadable working directory

# ------------------------------------------------------------------------------------------- OS detection
[ -r /etc/os-release ] || die "/etc/os-release not found; unsupported OS"
OS_ID="$(. /etc/os-release && echo "${ID:-}")"
OS_LIKE="$(. /etc/os-release && echo "${ID_LIKE:-}")"
OS_VERSION="$(. /etc/os-release && echo "${VERSION_ID:-}")"
case " $OS_ID $OS_LIKE " in
  *" ubuntu "*|*" debian "*) FAMILY=debian ;;
  *" rhel "*|*" fedora "*|*" centos "*|*" rocky "*|*" almalinux "*|*" ol "*) FAMILY=rhel ;;
  *) die "unsupported OS '$OS_ID'. Supported: Ubuntu 24.04 LTS, RHEL 9 family." ;;
esac
if [ "$FAMILY" = debian ] && [ "$OS_ID $OS_VERSION" != "ubuntu 24.04" ]; then
  warn "tested target is Ubuntu 24.04 LTS; detected $OS_ID $OS_VERSION. Continuing."
fi
if [ "$FAMILY" = rhel ] && [ "${OS_VERSION%%.*}" != 9 ]; then
  warn "tested target is the RHEL 9 family; detected $OS_ID $OS_VERSION. Continuing."
fi
log "Detected $OS_ID $OS_VERSION ($FAMILY family)"

if [ "$FAMILY" = debian ]; then
  PG_BIN=/usr/lib/postgresql/$PG_MAJOR/bin
  PG_SERVICE=postgresql
  NGINX_SITE=/etc/nginx/sites-available/philhealth-qms.conf
else
  PG_BIN=/usr/pgsql-$PG_MAJOR/bin
  PG_SERVICE=postgresql-$PG_MAJOR
  NGINX_SITE=/etc/nginx/conf.d/philhealth-qms.conf
fi
PSQL=$PG_BIN/psql

pgdg_unreachable() {
  cat >&2 <<EOF
ERROR: the PostgreSQL (PGDG) package repository is not reachable from this host:
         $1
       PostgreSQL $PG_MAJOR is not part of the distribution's default repositories, so the installer
       stops here instead of guessing. Either:
         * allow outbound HTTPS to the URL above (or set https_proxy) and re-run, or
         * configure an internal mirror of the PGDG repository approved by PhilHealth ICT, install
           the packages below yourself, then re-run this script (it skips steps already done):
EOF
  if [ "$FAMILY" = debian ]; then
    echo "             apt-get install postgresql-$PG_MAJOR postgresql-client-$PG_MAJOR" >&2
  else
    echo "             dnf install postgresql$PG_MAJOR-server && $PG_BIN/postgresql-$PG_MAJOR-setup initdb" >&2
  fi
  echo "       Reference: https://www.postgresql.org/download/linux/" >&2
  exit 2
}

# ------------------------------------------------------------------------------------------- packages
install_packages_debian() {
  export DEBIAN_FRONTEND=noninteractive
  log "Installing OpenJDK 21 JRE, nginx and PGDG prerequisites (apt)"
  run apt-get update -q
  run apt-get install -y -q ca-certificates curl gnupg openjdk-21-jre-headless nginx postgresql-common

  if [ -e /etc/apt/sources.list.d/pgdg.list ] || [ -e /etc/apt/sources.list.d/pgdg.sources ]; then
    log "PGDG apt repository already configured"
  else
    local url=https://apt.postgresql.org/pub/repos/apt/
    if [ "$DRY_RUN" -eq 0 ] && ! curl -fsS --max-time 20 -o /dev/null "$url"; then
      pgdg_unreachable "$url"
    fi
    log "Adding the PGDG apt repository"
    run /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y
  fi
  run apt-get install -y -q "postgresql-$PG_MAJOR" "postgresql-client-$PG_MAJOR"

  # Debian packaging creates and starts cluster $PG_MAJOR/main; it must be the one on port 5432.
  if [ "$DRY_RUN" -eq 0 ]; then
    local port
    port="$(pg_lsclusters -h 2>/dev/null | awk -v v="$PG_MAJOR" '$1==v && $2=="main" {print $3}')"
    [ -n "$port" ] || die "PostgreSQL $PG_MAJOR cluster 'main' not found (pg_lsclusters). Create it: pg_createcluster $PG_MAJOR main --start"
    [ "$port" = 5432 ] || die "PostgreSQL $PG_MAJOR/main listens on port $port, not 5432 (another cluster holds 5432). Fix the port or QMS_DB_URL and re-run."
  fi
  run systemctl enable --now "$PG_SERVICE"
}

install_packages_rhel() {
  log "Installing OpenJDK 21 JRE, nginx and tools (dnf)"
  run dnf install -y -q java-21-openjdk-headless nginx curl gnupg2 tar policycoreutils-python-utils

  if rpm -q pgdg-redhat-repo >/dev/null 2>&1; then
    log "PGDG yum repository already configured"
  else
    local base=https://download.postgresql.org/pub/repos/yum/reporpms/
    local rpm_url="${base}EL-9-$(uname -m)/pgdg-redhat-repo-latest.noarch.rpm"
    if [ "$DRY_RUN" -eq 0 ] && ! curl -fsS --max-time 20 -o /dev/null "$rpm_url"; then
      pgdg_unreachable "$rpm_url"
    fi
    log "Adding the PGDG yum repository"
    run dnf install -y -q "$rpm_url"
  fi
  # The distribution's postgresql module would shadow the PGDG packages.
  run dnf -qy module disable postgresql || true
  run dnf install -y -q "postgresql$PG_MAJOR-server"
  if [ -f "/var/lib/pgsql/$PG_MAJOR/data/PG_VERSION" ]; then
    log "PostgreSQL $PG_MAJOR data directory already initialized"
  else
    run "$PG_BIN/postgresql-$PG_MAJOR-setup" initdb
  fi
  run systemctl enable --now "$PG_SERVICE"
}

# ------------------------------------------------------------------------------------------- env file
# Reads KEY from the env file without sourcing it (values may contain spaces, e.g. JAVA_OPTS).
ENV_SOURCE=""
env_get() {
  [ -n "$ENV_SOURCE" ] || return 0
  grep -E "^$1=" "$ENV_SOURCE" | tail -n 1 | cut -d= -f2- | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

prepare_env_file() {
  run install -d -m 0750 -o root -g qms "$ETC_DIR"
  if [ ! -f "$ENV_FILE" ]; then
    run install -m 0600 -o root -g qms "$SCRIPT_DIR/qms.env.example" "$ENV_FILE"
    if [ "$DRY_RUN" -eq 1 ]; then
      warn "$ENV_FILE does not exist yet; the dry-run continues with the template's values."
      ENV_SOURCE="$SCRIPT_DIR/qms.env.example"
      return
    fi
    cat <<EOF

Created $ENV_FILE from the template. Edit it now:
  * QMS_DB_OWNER_PASSWORD, QMS_DB_PASSWORD, SPRING_FLYWAY_PASSWORD (= QMS_DB_OWNER_PASSWORD)
  * QMS_JWT_SECRET            (openssl rand -base64 48)
  * QMS_BOOTSTRAP_ADMIN_PASSWORD (first start only), QMS_CORS_ORIGINS
  * QMS_SITE_CODE, QMS_CENTRAL_URL, QMS_SYNC_SITE_KEY (site node) or QMS_NODE_ROLE=CENTRAL
Then place the TLS certificate in $PREFIX/tls/qms.crt and qms.key, and re-run this script.
EOF
    exit 1
  fi
  run chown root:qms "$ENV_FILE"
  run chmod 0600 "$ENV_FILE"
  if [ -r "$ENV_FILE" ]; then
    ENV_SOURCE="$ENV_FILE"
  else
    warn "$ENV_FILE is not readable by this user; the dry-run uses the template's values."
    ENV_SOURCE="$SCRIPT_DIR/qms.env.example"
  fi
}

validate_env() {
  local problems=0 k
  for k in QMS_DB_OWNER QMS_DB_OWNER_PASSWORD QMS_DB_USER QMS_DB_PASSWORD QMS_JWT_SECRET \
           QMS_DB_URL SPRING_FLYWAY_USER SPRING_FLYWAY_PASSWORD QMS_MEDIA_DIR; do
    if [ -z "$(env_get "$k")" ]; then warn "$k is empty in $ENV_FILE"; problems=1; fi
  done
  for k in QMS_DB_OWNER QMS_DB_USER; do
    if ! [[ "$(env_get "$k")" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
      warn "$k must be a lower-case PostgreSQL identifier"; problems=1
    fi
  done
  if [ "$(env_get QMS_DB_OWNER)" = "$(env_get QMS_DB_USER)" ]; then
    warn "QMS_DB_OWNER and QMS_DB_USER must be different roles (least privilege)"; problems=1
  fi
  if [ "$(env_get SPRING_FLYWAY_USER)" != "$(env_get QMS_DB_OWNER)" ]; then
    warn "SPRING_FLYWAY_USER must equal QMS_DB_OWNER"; problems=1
  fi
  if [ "$(env_get SPRING_FLYWAY_PASSWORD)" != "$(env_get QMS_DB_OWNER_PASSWORD)" ]; then
    warn "SPRING_FLYWAY_PASSWORD must equal QMS_DB_OWNER_PASSWORD (values not shown)"; problems=1
  fi
  if [ "$(env_get QMS_NODE_ROLE)" != CENTRAL ] && [ -z "$(env_get QMS_SITE_CODE)" ]; then
    warn "QMS_SITE_CODE is required when QMS_NODE_ROLE is SITE"; problems=1
  fi
  if [ "$(env_get QMS_MEDIA_DIR)" != "$PREFIX/data/media" ]; then
    warn "QMS_MEDIA_DIR is not $PREFIX/data/media; the systemd unit only allows writes under $PREFIX/data"
  fi
  case "$(env_get QMS_DB_URL)" in
    *//127.0.0.1:5432/$DB_NAME|*//localhost:5432/$DB_NAME) ;;
    *) warn "QMS_DB_URL does not point to the local database (127.0.0.1:5432/$DB_NAME); the installer only configures the local server" ;;
  esac
  if [ "$problems" -ne 0 ]; then
    if [ "$DRY_RUN" -eq 1 ]; then
      warn "the env file is incomplete; a real run would stop here."
    else
      die "fix $ENV_FILE and re-run."
    fi
  fi
}

# ------------------------------------------------------------------------------------------- database
# Quotes a value for psql's \set (backslashes and single quotes escaped).
psql_quote() {
  local s=${1//\\/\\\\}
  s=${s//\'/\'\'}
  printf "'%s'" "$s"
}

wait_for_postgres() {
  act "wait for PostgreSQL to accept connections" || return 0
  local i
  for i in $(seq 1 30); do
    if runuser -u postgres -- "$PG_BIN/pg_isready" -q; then return 0; fi
    sleep 2
  done
  die "PostgreSQL did not become ready; check: systemctl status $PG_SERVICE"
}

# Creates the owner and application roles and the database, mirroring deploy/scripts/db-init.sh.
# The SQL (with passwords) goes to psql on stdin, never on a command line, and is not echoed.
setup_database() {
  local owner app
  owner="$(env_get QMS_DB_OWNER)"
  app="$(env_get QMS_DB_USER)"
  log "Configuring database '$DB_NAME' (owner role $owner, application role $app)"
  act "psql as postgres: create/alter roles $owner and $app (passwords from $ENV_FILE), create database $DB_NAME owned by $owner, GRANT CONNECT + USAGE ON SCHEMA public to $app, REVOKE CREATE ON SCHEMA public FROM PUBLIC" || return 0
  {
    echo '\set ON_ERROR_STOP on'
    echo '\set VERBOSITY terse'
    printf '\\set owner %s\n' "$(psql_quote "$owner")"
    printf '\\set owner_pw %s\n' "$(psql_quote "$(env_get QMS_DB_OWNER_PASSWORD)")"
    printf '\\set app %s\n' "$(psql_quote "$app")"
    printf '\\set app_pw %s\n' "$(psql_quote "$(env_get QMS_DB_PASSWORD)")"
    cat <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'owner') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'owner') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', :'owner', :'owner_pw') \gexec
SELECT format('CREATE ROLE %I LOGIN', :'app') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', :'app', :'app_pw') \gexec
SELECT format('CREATE DATABASE qms OWNER %I', :'owner') WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'qms') \gexec
\connect qms
SELECT format('GRANT CONNECT ON DATABASE qms TO %I', :'app') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'app') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
  } | runuser -u postgres -- "$PSQL" -X -q -d postgres >/dev/null
}

# Allows password (SCRAM) logins of the two QMS roles over loopback TCP, which the JDBC URL uses.
configure_pg_hba() {
  local owner app hba block
  owner="$(env_get QMS_DB_OWNER)"
  app="$(env_get QMS_DB_USER)"
  act "add a managed block to pg_hba.conf: host $DB_NAME $owner,$app 127.0.0.1/32 and ::1/128 scram-sha-256; reload PostgreSQL" || return 0
  hba="$(runuser -u postgres -- "$PSQL" -X -A -t -q -c 'SHOW hba_file')"
  [ -f "$hba" ] || die "pg_hba.conf not found at '$hba'"
  block="# BEGIN philhealth-qms (managed by install-native.sh)
host    $DB_NAME    $owner,$app    127.0.0.1/32    scram-sha-256
host    $DB_NAME    $owner,$app    ::1/128         scram-sha-256
# END philhealth-qms"
  if [ "$(sed -n '/^# BEGIN philhealth-qms/,/^# END philhealth-qms/p' "$hba")" = "$block" ]; then
    log "pg_hba.conf already contains the QMS block"
  else
    cp -p "$hba" "$hba.bak-qms-$(date +%Y%m%d%H%M%S)"
    # Remove an outdated managed block (e.g. after a role rename), then prepend the current one:
    # pg_hba.conf is first-match, so the block must precede broader rules.
    sed -i '/^# BEGIN philhealth-qms/,/^# END philhealth-qms/d' "$hba"
    { printf '%s\n' "$block"; cat "$hba"; } > "$hba.new"
    chown --reference="$hba" "$hba.new"
    chmod --reference="$hba" "$hba.new"
    mv "$hba.new" "$hba"
    log "Added the QMS block to $hba (backup kept next to it)"
  fi
  runuser -u postgres -- "$PSQL" -X -q -c 'SELECT pg_reload_conf()' >/dev/null
}

# Proves that the application role can log in over TCP with the configured password.
check_app_login() {
  act "test login of $(env_get QMS_DB_USER) to 127.0.0.1:5432/$DB_NAME using a temporary PGPASSFILE" || return 0
  local passfile pw rc=0
  passfile="$(mktemp)"
  chmod 600 "$passfile"
  pw="$(env_get QMS_DB_PASSWORD)"
  pw=${pw//\\/\\\\}
  pw=${pw//:/\\:}
  printf '127.0.0.1:5432:%s:%s:%s\n' "$DB_NAME" "$(env_get QMS_DB_USER)" "$pw" > "$passfile"
  PGPASSFILE="$passfile" "$PSQL" -X -q -w -h 127.0.0.1 -p 5432 -U "$(env_get QMS_DB_USER)" -d "$DB_NAME" \
    -c 'SELECT 1' >/dev/null 2>&1 || rc=$?
  rm -f "$passfile"
  [ "$rc" -eq 0 ] || die "the application role cannot log in to $DB_NAME over 127.0.0.1 (check QMS_DB_PASSWORD and pg_hba.conf)"
  log "Application role login OK"
}

# ------------------------------------------------------------------------------------------- files
install_layout() {
  if id qms >/dev/null 2>&1; then
    log "System user qms exists"
  else
    run useradd --system --user-group --home-dir "$PREFIX" --no-create-home --shell /usr/sbin/nologin qms
  fi
  run install -d -m 0755 -o root -g root "$PREFIX" "$PREFIX/app" "$PREFIX/web"
  run install -d -m 0750 -o qms -g qms "$PREFIX/data" "$PREFIX/data/media"
  run install -d -m 0700 -o root -g root "$PREFIX/tls"
}

check_tls() {
  if [ -f "$PREFIX/tls/qms.crt" ] && [ -f "$PREFIX/tls/qms.key" ]; then
    run chmod 0644 "$PREFIX/tls/qms.crt"
    run chmod 0600 "$PREFIX/tls/qms.key"
    return 0
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    warn "TLS certificate missing ($PREFIX/tls/qms.crt, qms.key); a real run would stop here."
  else
    die "place the TLS certificate and key in $PREFIX/tls/qms.crt and $PREFIX/tls/qms.key, then re-run."
  fi
}

install_artifacts() {
  if [ -n "$JAR" ]; then
    log "Installing backend jar from $JAR"
    run install -m 0644 -o root -g root "$JAR" "$PREFIX/app/qms-backend.jar.new"
    run mv -f "$PREFIX/app/qms-backend.jar.new" "$PREFIX/app/qms-backend.jar"
  elif [ ! -f "$PREFIX/app/qms-backend.jar" ]; then
    if [ "$DRY_RUN" -eq 1 ]; then
      warn "no --jar given and none installed; a real run would stop here."
    else
      die "no application installed yet: pass --jar <qms-backend.jar>"
    fi
  else
    log "Keeping the installed backend jar (no --jar given)"
  fi

  if [ -n "$WEB" ]; then
    log "Installing web UI from $WEB"
    run rm -rf "$PREFIX/web.new" "$PREFIX/web.old"
    run cp -R -- "$WEB" "$PREFIX/web.new"
    run chown -R root:root "$PREFIX/web.new"
    run chmod -R u=rwX,go=rX "$PREFIX/web.new"
    run mv "$PREFIX/web" "$PREFIX/web.old"
    run mv "$PREFIX/web.new" "$PREFIX/web"
    run rm -rf "$PREFIX/web.old"
  elif [ ! -f "$PREFIX/web/index.html" ]; then
    if [ "$DRY_RUN" -eq 1 ]; then
      warn "no --web given and none installed; a real run would stop here."
    else
      die "no web UI installed yet: pass --web <frontend dist directory>"
    fi
  else
    log "Keeping the installed web UI (no --web given)"
  fi
}

check_java() {
  act "check that 'java' is OpenJDK 21" || return 0
  local v
  v="$(/usr/bin/java -version 2>&1 | head -n 1)" || die "/usr/bin/java not found"
  case "$v" in
    *'version "21'*) log "Java: $v" ;;
    *) die "/usr/bin/java is not Java 21 ($v). Select it with update-alternatives --config java and re-run." ;;
  esac
}

# ------------------------------------------------------------------------------------------- nginx
configure_nginx() {
  log "Installing nginx site $NGINX_SITE"
  run install -m 0644 -o root -g root "$SCRIPT_DIR/nginx-qms.conf" "$NGINX_SITE"
  if [ "$FAMILY" = debian ]; then
    run ln -sfn "$NGINX_SITE" /etc/nginx/sites-enabled/philhealth-qms.conf
    if [ -L /etc/nginx/sites-enabled/default ]; then
      run rm -f /etc/nginx/sites-enabled/default
    fi
  elif grep -qE '^[^#]*listen.*default_server' /etc/nginx/nginx.conf; then
    # The stock RHEL nginx.conf declares a default server on port 80, which would conflict with ours.
    log "Removing 'default_server' from the stock server block in /etc/nginx/nginx.conf (backup kept)"
    if act "sed: strip default_server from /etc/nginx/nginx.conf"; then
      cp -p /etc/nginx/nginx.conf "/etc/nginx/nginx.conf.bak-qms-$(date +%Y%m%d%H%M%S)"
      sed -i -E '/^[^#]*listen/ s/[[:space:]]+default_server//' /etc/nginx/nginx.conf
    fi
  fi

  if [ "$FAMILY" = rhel ] && command -v selinuxenabled >/dev/null 2>&1 && selinuxenabled; then
    log "SELinux is enforcing/permissive: labelling files and allowing nginx to proxy to 127.0.0.1:8080"
    run setsebool -P httpd_can_network_connect 1
    for spec in "httpd_sys_content_t $PREFIX/web(/.*)?" "cert_t $PREFIX/tls(/.*)?"; do
      local type=${spec%% *} path=${spec#* }
      if act "semanage fcontext -a -t $type '$path' (or -m if it exists)"; then
        semanage fcontext -a -t "$type" "$path" 2>/dev/null || semanage fcontext -m -t "$type" "$path"
      fi
    done
    run restorecon -R "$PREFIX/web" "$PREFIX/tls"
  fi
  run nginx -t
}

firewall_hint() {
  if command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
    log "firewalld is active. If clients cannot connect, open HTTP/HTTPS (per PhilHealth network policy):"
    echo "      firewall-cmd --permanent --add-service=http --add-service=https && firewall-cmd --reload"
  elif command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
    log "ufw is active. If clients cannot connect, open HTTP/HTTPS (per PhilHealth network policy):"
    echo "      ufw allow 'Nginx Full'"
  fi
}

# ------------------------------------------------------------------------------------------- services
start_services() {
  log "Installing and starting qms-api.service"
  run install -m 0644 -o root -g root "$SCRIPT_DIR/qms-api.service" "$UNIT_FILE"
  run systemctl daemon-reload
  run systemctl enable nginx qms-api
  run systemctl restart qms-api
  run systemctl reload-or-restart nginx
}

wait_for_health() {
  act "poll https://127.0.0.1/actuator/health until it reports UP (up to 5 minutes)" || return 0
  log "Waiting for https://127.0.0.1/actuator/health (Flyway migrations run on the first start)"
  local i body
  for i in $(seq 1 60); do
    body="$(curl -fsk --max-time 5 https://127.0.0.1/actuator/health 2>/dev/null || true)"
    case "$body" in
      *'"status":"UP"'*) log "QMS is up."; return 0 ;;
    esac
    sleep 5
  done
  die "the API did not become healthy. Check: journalctl -u qms-api -n 200 --no-pager ; nginx -t ; systemctl status $PG_SERVICE"
}

# ------------------------------------------------------------------------------------------- main
if [ "$DRY_RUN" -eq 1 ]; then log "DRY RUN: nothing will be changed"; fi
if [ "$FAMILY" = debian ]; then install_packages_debian; else install_packages_rhel; fi
check_java
install_layout
prepare_env_file
validate_env
check_tls
install_artifacts
wait_for_postgres
setup_database
configure_pg_hba
check_app_login
configure_nginx
start_services
wait_for_health
firewall_hint
if [ -n "$(env_get QMS_BOOTSTRAP_ADMIN_PASSWORD)" ]; then
  log "Sign in as 'superadmin' with QMS_BOOTSTRAP_ADMIN_PASSWORD and change the password, then clear"
  echo "    QMS_BOOTSTRAP_ADMIN_PASSWORD in $ENV_FILE and run: systemctl restart qms-api"
fi
log "Done."
