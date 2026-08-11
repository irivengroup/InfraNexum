#!/bin/sh
set -eu

: "${MIGRATION_ID:?MIGRATION_ID is required, for example 0006}"
if [ "${CONFIRM_INFRANEXUM_ROLLBACK:-}" != "YES" ]; then
  echo "Refusing migration rollback. Set CONFIRM_INFRANEXUM_ROLLBACK=YES after taking a backup." >&2
  exit 64
fi
case "$MIGRATION_ID" in
  000[1-9]) ;;
  *) echo "Unsupported migration id: $MIGRATION_ID" >&2; exit 64 ;;
esac

migration_root="${INFRANEXUM_MIGRATION_ROOT:-/opt/infranexum/migrations}"
password_file="${INFRANEXUM_DATABASE_PASSWORD_FILE:-/run/infranexum-secrets/db-password}"
export PGPASSWORD="$(cat "$password_file")"
rollback_file="$(find "$migration_root" -maxdepth 3 -type f -path "*/${MIGRATION_ID}-*/rollback/postgresql.sql" -print -quit)"
if [ -z "$rollback_file" ] || [ ! -r "$rollback_file" ]; then
  echo "Rollback SQL not found for migration $MIGRATION_ID" >&2
  exit 65
fi

psql_base="psql --no-psqlrc --set ON_ERROR_STOP=1 --dbname=${PGDATABASE:?} --host=${PGHOST:?} --port=${PGPORT:-5432} --username=${PGUSER:?}"
control="$(mktemp)"
trap 'rm -f "$control"' EXIT HUP INT TERM
{
  echo '\\set ON_ERROR_STOP on'
  echo 'BEGIN;'
  echo 'SELECT pg_advisory_xact_lock(723091144);'
  echo "SELECT EXISTS(SELECT 1 FROM infranexum_core.schema_history WHERE migration_id = '$MIGRATION_ID') AS applied \\gset"
  echo '\\if :applied'
  echo "  SELECT NOT EXISTS(SELECT 1 FROM infranexum_core.schema_history WHERE migration_id > '$MIGRATION_ID') AS latest \\gset"
  echo '  \\if :latest'
  # Delete the history row first in the same transaction. If rollback SQL fails,
  # PostgreSQL restores it; migration 0001 may then safely drop schema_history.
  echo "    DELETE FROM infranexum_core.schema_history WHERE migration_id = '$MIGRATION_ID';"
  echo "    \\i $rollback_file"
  echo '  \\else'
  echo '    \\echo rollback refused because newer migrations are still applied'
  echo '    \\quit 46'
  echo '  \\endif'
  echo '\\else'
  echo "  \\echo migration $MIGRATION_ID is not applied"
  echo '  \\quit 47'
  echo '\\endif'
  echo 'COMMIT;'
} > "$control"
$psql_base --file "$control"
