#!/bin/sh
set -eu

password_file="${INFRANEXUM_DATABASE_PASSWORD_FILE:-/run/infranexum-secrets/db-password}"
[ -r "$password_file" ] || { echo "Database password file is not readable: $password_file" >&2; exit 64; }
db_password=$(cat "$password_file")
[ -n "$db_password" ] || { echo 'Database password is empty' >&2; exit 64; }
export PGPASSWORD="$db_password"
host="${PGHOST:-postgres}"
port="${PGPORT:-5432}"

psql_admin="psql --no-psqlrc --set ON_ERROR_STOP=1 --host=$host --port=$port --username=postgres --dbname=postgres"
attempt=0
until $psql_admin --tuples-only --no-align --command 'SELECT 1' >/dev/null 2>&1; do
  attempt=$((attempt + 1)); [ "$attempt" -lt 60 ] || { echo 'PostgreSQL HA writer endpoint did not become ready' >&2; exit 69; }; sleep 1
done

if [ "$($psql_admin --tuples-only --no-align --command "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'")" -lt 2 ]; then
  echo 'Refusing PRO bootstrap before two PostgreSQL standbys are streaming' >&2
  exit 69
fi
if [ "$($psql_admin --tuples-only --no-align --command "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state IN ('sync','quorum')")" -lt 1 ]; then
  echo 'Refusing PRO bootstrap without a synchronous PostgreSQL standby' >&2
  exit 69
fi

role_exists=$($psql_admin --tuples-only --no-align --command "SELECT 1 FROM pg_roles WHERE rolname='infranexum'")
if [ "$role_exists" != 1 ]; then
  $psql_admin --set=db_password="$db_password" --command "CREATE ROLE infranexum LOGIN PASSWORD :'db_password'"
else
  $psql_admin --set=db_password="$db_password" --command "ALTER ROLE infranexum LOGIN PASSWORD :'db_password'"
fi

database_exists=$($psql_admin --tuples-only --no-align --command "SELECT 1 FROM pg_database WHERE datname='infranexum'")
if [ "$database_exists" != 1 ]; then
  createdb --host="$host" --port="$port" --username=postgres --maintenance-db=postgres --owner=infranexum infranexum
fi

echo 'PRO PostgreSQL bootstrap invariant verified'
