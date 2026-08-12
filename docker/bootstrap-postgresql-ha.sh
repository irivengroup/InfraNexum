#!/bin/sh
set -eu

password_file="${INFRANEXUM_DATABASE_PASSWORD_FILE:-/run/infranexum-secrets/db-password}"
[ -r "$password_file" ] || { echo "Database password file is not readable: $password_file" >&2; exit 64; }
db_password=$(cat "$password_file")
[ -n "$db_password" ] || { echo 'Database password is empty' >&2; exit 64; }
export PGPASSWORD="$db_password"
# Keep the application password out of psql command-line arguments. psql reads
# it from the environment through \getenv when SQL interpolation is required.
export INFRANEXUM_BOOTSTRAP_DATABASE_PASSWORD="$db_password"
host="${PGHOST:-postgres}"
port="${PGPORT:-5432}"

psql_admin() {
  psql --no-psqlrc --set ON_ERROR_STOP=1 \
    --host="$host" --port="$port" --username=postgres --dbname=postgres "$@"
}

scalar() {
  psql_admin --tuples-only --no-align --command "$1"
}

wait_for_scalar_at_least() {
  description="$1"
  sql="$2"
  minimum="$3"
  attempts="${4:-60}"
  attempt=0
  last_value='unavailable'

  while [ "$attempt" -lt "$attempts" ]; do
    if value=$(scalar "$sql" 2>/dev/null); then
      last_value="$value"
      case "$value" in
        ''|*[!0-9]*) ;;
        *)
          if [ "$value" -ge "$minimum" ]; then
            return 0
          fi
          ;;
      esac
    fi
    attempt=$((attempt + 1))
    [ "$attempt" -lt "$attempts" ] && sleep 1
  done

  echo "PostgreSQL HA invariant did not become ready: $description (required >= $minimum, observed $last_value)" >&2
  return 69
}

attempt=0
until scalar 'SELECT 1' >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 60 ] || { echo 'PostgreSQL HA writer endpoint did not become ready' >&2; exit 69; }
  sleep 1
done

# Patroni node health and the HAProxy listener can become healthy slightly
# before PostgreSQL has exposed the full PRO replication invariant. Wait for
# that invariant instead of failing on a normal startup race.
wait_for_scalar_at_least \
  'two streaming standbys' \
  "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'" \
  2 60
wait_for_scalar_at_least \
  'one synchronous or quorum standby' \
  "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state IN ('sync','quorum')" \
  1 60

role_exists=$(scalar "SELECT 1 FROM pg_roles WHERE rolname='infranexum'")
if [ "$role_exists" != 1 ]; then
  psql_admin <<'SQL'
\getenv db_password INFRANEXUM_BOOTSTRAP_DATABASE_PASSWORD
CREATE ROLE infranexum LOGIN PASSWORD :'db_password';
SQL
else
  psql_admin <<'SQL'
\getenv db_password INFRANEXUM_BOOTSTRAP_DATABASE_PASSWORD
ALTER ROLE infranexum LOGIN PASSWORD :'db_password';
SQL
fi

# The bootstrap-only environment alias is no longer needed after role setup.
unset INFRANEXUM_BOOTSTRAP_DATABASE_PASSWORD

database_exists=$(scalar "SELECT 1 FROM pg_database WHERE datname='infranexum'")
if [ "$database_exists" != 1 ]; then
  createdb --host="$host" --port="$port" --username=postgres --maintenance-db=postgres --owner=infranexum infranexum
fi

echo 'PRO PostgreSQL bootstrap invariant verified'
