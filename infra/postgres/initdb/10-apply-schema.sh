#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Step 2 of the build plan: execute ./database/init-schema.sql (mounted at
# /syncra/database) against the HIMS database as the schema owner.
# ON_ERROR_STOP makes any SQL error abort initialisation; the preflight wrapper
# then refuses to start on the incomplete volume.
# -----------------------------------------------------------------------------
set -Eeuo pipefail

SCHEMA_FILE="/syncra/database/init-schema.sql"

if [[ ! -s "$SCHEMA_FILE" ]]; then
  echo "[syncra-initdb] FATAL: $SCHEMA_FILE is missing or empty" >&2
  exit 1
fi

echo "[syncra-initdb] applying $SCHEMA_FILE to database '$POSTGRES_DB'"
psql -v ON_ERROR_STOP=1 --no-psqlrc \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --file "$SCHEMA_FILE"
echo "[syncra-initdb] schema applied"
