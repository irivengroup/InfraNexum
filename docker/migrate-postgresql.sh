#!/bin/sh
set -eu

migration_root="${INFRANEXUM_MIGRATION_ROOT:-/opt/infranexum/migrations}"
password_file="${INFRANEXUM_DATABASE_PASSWORD_FILE:-/run/infranexum-secrets/db-password}"
application_version="${INFRANEXUM_VERSION:?INFRANEXUM_VERSION is required}"

if [ ! -d "$migration_root" ]; then
  echo "Migration root not found: $migration_root" >&2
  exit 64
fi
if [ ! -r "$password_file" ]; then
  echo "Database password file is not readable: $password_file" >&2
  exit 64
fi
export PGPASSWORD="$(cat "$password_file")"
if [ -z "$PGPASSWORD" ]; then
  echo "Database password is empty" >&2
  exit 64
fi

psql_base="psql --no-psqlrc --set ON_ERROR_STOP=1 --dbname=${PGDATABASE:?PGDATABASE required} --host=${PGHOST:?PGHOST required} --port=${PGPORT:-5432} --username=${PGUSER:?PGUSER required}"

wait_attempt=0
until $psql_base --tuples-only --no-align --command 'SELECT 1' >/dev/null 2>&1; do
  wait_attempt=$((wait_attempt + 1))
  if [ "$wait_attempt" -ge 60 ]; then
    echo "PostgreSQL did not become ready after 60 attempts" >&2
    exit 69
  fi
  sleep 1
done

extract_checksum() {
  manifest="$1"
  key="$2"
  awk -v key="$key" '
    $1 == key ":" { print $2; found=1 }
    END { if (!found) exit 1 }
  ' "$manifest"
}

for migration_dir in "$migration_root"/000*; do
  [ -d "$migration_dir" ] || continue
  manifest="$migration_dir/migration.yaml"
  sql_file="$migration_dir/postgresql.sql"
  migration_id="$(awk '$1 == "id:" { gsub(/\047/, "", $2); print $2; exit }' "$manifest")"
  logical_checksum="$(extract_checksum "$manifest" 'logical-model.json')"
  expected_sql_checksum="$(extract_checksum "$manifest" 'postgresql.sql')"
  actual_sql_checksum="$(sha256sum "$sql_file" | awk '{print $1}')"

  if [ -z "$migration_id" ] || [ -z "$logical_checksum" ]; then
    echo "Malformed migration manifest: $manifest" >&2
    exit 65
  fi
  if [ "$actual_sql_checksum" != "$expected_sql_checksum" ]; then
    echo "Migration checksum mismatch for $migration_id/postgresql.sql" >&2
    exit 65
  fi

  control="$(mktemp)"
  trap 'rm -f "$control"' EXIT HUP INT TERM
  {
    echo '\\set ON_ERROR_STOP on'
    echo 'BEGIN;'
    echo "SELECT pg_advisory_xact_lock(723091144);"
    echo "SELECT to_regclass('infranexum_core.schema_history') IS NOT NULL AS has_history \\gset"
    echo '\\if :has_history'
    echo "  SELECT EXISTS(SELECT 1 FROM infranexum_core.schema_history WHERE migration_id = '$migration_id') AS applied \\gset"
    echo '  \\if :applied'
    echo "    SELECT logical_checksum = '$logical_checksum' AS checksum_ok FROM infranexum_core.schema_history WHERE migration_id = '$migration_id' \\gset"
    echo '    \\if :checksum_ok'
    echo "      \\echo migration $migration_id already applied"
    echo '    \\else'
    echo "      \\echo migration $migration_id logical checksum mismatch"
    echo '      \\quit 44'
    echo '    \\endif'
    echo '  \\else'
    echo "    \\i $sql_file"
    echo "    INSERT INTO infranexum_core.schema_history (migration_id, logical_checksum, application_version, applied_by) VALUES ('$migration_id', '$logical_checksum', '$application_version', current_user);"
    echo '  \\endif'
    echo '\\else'
    if [ "$migration_id" = '0001' ]; then
      echo "  \\i $sql_file"
      echo "  INSERT INTO infranexum_core.schema_history (migration_id, logical_checksum, application_version, applied_by) VALUES ('$migration_id', '$logical_checksum', '$application_version', current_user);"
    else
      echo "  \\echo schema history is missing before migration $migration_id"
      echo '  \\quit 45'
    fi
    echo '\\endif'
    echo 'COMMIT;'
  } > "$control"
  $psql_base --file "$control"
  rm -f "$control"
  trap - EXIT HUP INT TERM
done

# A fresh deployment must have exactly one stable installation identity before Entitlements starts.
# Bootstrap is serialized with the same advisory lock as schema migrations so concurrent
# compose invocations cannot create two identities between validation and INSERT.
installation_id="$(cat /proc/sys/kernel/random/uuid)"
fingerprint="$(head -c 64 /dev/urandom | sha256sum | awk '{print $1}')"
identity_sql="$(mktemp)"
trap 'rm -f "$identity_sql"' EXIT HUP INT TERM
cat > "$identity_sql" <<'SQL'
\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(723091144);
SELECT COUNT(*) <= 1 AS identity_count_valid,
       COUNT(*) = 0 AS identity_insert_required
FROM core_installation_identity \gset
\if :identity_count_valid
  \if :identity_insert_required
    INSERT INTO core_installation_identity (
      installation_id, fingerprint_version, fingerprint, created_at
    ) VALUES (
      :'installation_id'::uuid, 'v1', :'fingerprint', CURRENT_TIMESTAMP
    );
  \else
    \echo installation identity already present
  \endif
\else
  \echo invalid installation state: more than one installation identity exists
  \quit 66
\endif
SELECT COUNT(*) = 1 AS identity_valid FROM core_installation_identity \gset
\if :identity_valid
  \echo installation identity invariant verified
\else
  \echo invalid installation state: expected exactly one installation identity
  \quit 66
\endif
COMMIT;
SQL
$psql_base \
  --set=installation_id="$installation_id" \
  --set=fingerprint="$fingerprint" \
  --file="$identity_sql"
rm -f "$identity_sql"
trap - EXIT HUP INT TERM
