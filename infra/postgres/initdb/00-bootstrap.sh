#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# First-boot bootstrap (runs before the HIMS schema is applied):
#   1. Extensions used across Syncra HIMS
#   2. Least-privilege application role (DML only, no DDL) with default
#      privileges, so every table created by init-schema.sql is usable by it
#   3. Isolated `orthanc` database + role for the PACS index
# -----------------------------------------------------------------------------
set -Eeuo pipefail

: "${SYNCRA_APP_DB_USER:?SYNCRA_APP_DB_USER is required}"
: "${SYNCRA_APP_DB_PASSWORD:?SYNCRA_APP_DB_PASSWORD is required}"
: "${ORTHANC_DB_PASSWORD:?ORTHANC_DB_PASSWORD is required}"

echo "[syncra-initdb] bootstrapping roles, extensions and the orthanc database"

psql -v ON_ERROR_STOP=1 --no-psqlrc \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v db="$POSTGRES_DB" \
  -v owner="$POSTGRES_USER" \
  -v app_user="$SYNCRA_APP_DB_USER" \
  -v app_password="$SYNCRA_APP_DB_PASSWORD" \
  -v orthanc_password="$ORTHANC_DB_PASSWORD" <<'EOSQL'
-- 1. Extensions ---------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid(), digest() for ABDM payload hashing
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- fuzzy patient-name search at registration desk

-- 2. Application role ------------------------------------------------------------
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE :"app_user" SET statement_timeout = '30s';
ALTER ROLE :"app_user" SET idle_in_transaction_session_timeout = '60s';

REVOKE CONNECT ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO :"app_user";
GRANT USAGE ON SCHEMA public TO :"app_user";

-- Objects the schema owner creates later (init-schema.sql, migrations)
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" GRANT USAGE ON SCHEMAS TO :"app_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"app_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" GRANT EXECUTE ON FUNCTIONS TO :"app_user";

-- 3. Orthanc PACS index database (no access to clinical data) ----------------
CREATE ROLE orthanc LOGIN PASSWORD :'orthanc_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE DATABASE orthanc OWNER orthanc ENCODING 'UTF8';
REVOKE ALL ON DATABASE orthanc FROM PUBLIC;
EOSQL

echo "[syncra-initdb] bootstrap complete"
