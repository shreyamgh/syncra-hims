#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Preflight wrapper around the official postgres entrypoint.
#
# The stock image runs /docker-entrypoint-initdb.d only while the data volume
# is empty. If an init script fails, the half-initialised volume survives and
# the next (auto-)restart would bring PostgreSQL up WITHOUT the HIMS schema.
# This wrapper prevents that:
#   * empty volume  -> require ./database/init-schema.sql before initialising
#   * existing volume without the completion marker -> refuse to start
# The marker is written by initdb/99-mark-initialized.sh as the last step.
# -----------------------------------------------------------------------------
set -Eeuo pipefail

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
MARKER="$PGDATA/.syncra-initialized"
SCHEMA_FILE="/syncra/database/init-schema.sql"

log() { printf '[syncra-preflight] %s\n' "$*" >&2; }

if [[ -s "$PGDATA/PG_VERSION" ]]; then
  if [[ ! -f "$MARKER" ]]; then
    log "FATAL: the PostgreSQL volume exists but Syncra initialisation never completed"
    log "(an init script failed on first boot — see the earlier log lines)."
    log "Fix the cause, then recreate the database volume:"
    log "  docker compose rm -sf postgres && docker volume rm syncra-hims_pgdata && docker compose up -d"
    exit 1
  fi
elif [[ ! -s "$SCHEMA_FILE" ]]; then
  log "FATAL: $SCHEMA_FILE is missing or empty."
  log "Place the HIMS schema at ./database/init-schema.sql, then run: docker compose up -d"
  exit 1
fi

exec docker-entrypoint.sh "$@"
