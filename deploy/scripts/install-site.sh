#!/usr/bin/env bash
# First-time installation of a site node on a PhilHealth-approved Linux host with Docker.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV=env/site.env
[ -f "$ENV" ] || { cp env/site.env.example "$ENV"; chmod 600 "$ENV"; echo "Edit $ENV, then re-run."; exit 1; }
grep -q '^QMS_JWT_SECRET=$' "$ENV" && { echo "QMS_JWT_SECRET is empty in $ENV"; exit 1; }
[ -f tls/qms.crt ] && [ -f tls/qms.key ] || { echo "Place the TLS certificate in deploy/tls/qms.crt and qms.key"; exit 1; }
docker compose --env-file "$ENV" up -d --build
echo "Waiting for API health..."
for i in $(seq 1 60); do
  if curl -fsk https://localhost/actuator/health >/dev/null; then echo "QMS is up."; exit 0; fi
  sleep 5
done
echo "API did not become healthy; check: docker compose logs api"; exit 1
