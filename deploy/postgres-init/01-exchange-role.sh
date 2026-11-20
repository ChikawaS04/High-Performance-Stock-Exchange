#!/usr/bin/env bash
# Creates the venue's login role and database (P16-6, D7, the P15-9 role design). Run by the
# postgres image as the superuser `postgres`, ONLY when the data volume is empty (first start).
# Changing DB_PASSWORD later needs ALTER ROLE by hand; this script never runs again.
#
# The password travels as a psql variable, so it is never in this file, the process list or the
# server log.
set -euo pipefail

if [ -z "${DB_PASSWORD:-}" ]; then
    echo "01-exchange-role.sh: DB_PASSWORD is empty; refusing to create the exchange role" >&2
    exit 1
fi

psql -v ON_ERROR_STOP=1 -v pw="$DB_PASSWORD" --username postgres --dbname postgres <<'SQL'
CREATE ROLE exchange LOGIN PASSWORD :'pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION;
CREATE DATABASE exchange OWNER exchange;
REVOKE ALL ON DATABASE exchange FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE exchange TO exchange;
SQL

echo "01-exchange-role.sh: role exchange and database exchange created"
