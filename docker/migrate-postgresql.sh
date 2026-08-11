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
  # POSIX echo is implementation-defined for backslash escapes. Alpine/BusyBox
  # turns a source argument such as "\\set" into a different byte stream than
  # dash/bash. Use printf with a data format so psql receives exactly one leading
  # backslash for every meta-command on every supported developer platform.
  {
    printf '%s\n' '\set ON_ERROR_STOP on'
    printf '%s\n' 'BEGIN;'
    printf '%s\n' 'SELECT pg_advisory_xact_lock(723091144);'
    printf '%s\n' "SELECT to_regclass('infranexum_core.schema_history') IS NOT NULL AS has_history \\gset"
    printf '%s\n' '\if :has_history'
    printf '%s\n' "  SELECT EXISTS(SELECT 1 FROM infranexum_core.schema_history WHERE migration_id = '$migration_id') AS applied \\gset"
    printf '%s\n' '  \if :applied'
    printf '%s\n' "    SELECT logical_checksum = '$logical_checksum' AS checksum_ok FROM infranexum_core.schema_history WHERE migration_id = '$migration_id' \\gset"
    printf '%s\n' '    \if :checksum_ok'
    printf '%s\n' "      \echo migration $migration_id already applied"
    printf '%s\n' '    \else'
    printf '%s\n' "      \echo migration $migration_id logical checksum mismatch"
    printf '%s\n' '      \quit 44'
    printf '%s\n' '    \endif'
    printf '%s\n' '  \else'
    printf '%s\n' "    \i $sql_file"
    printf '%s\n' "    INSERT INTO infranexum_core.schema_history (migration_id, logical_checksum, application_version, applied_by) VALUES ('$migration_id', '$logical_checksum', '$application_version', current_user);"
    printf '%s\n' '  \endif'
    printf '%s\n' '\else'
    if [ "$migration_id" = '0001' ]; then
      printf '%s\n' "  \i $sql_file"
      printf '%s\n' "  INSERT INTO infranexum_core.schema_history (migration_id, logical_checksum, application_version, applied_by) VALUES ('$migration_id', '$logical_checksum', '$application_version', current_user);"
    else
      printf '%s\n' "  \echo schema history is missing before migration $migration_id"
      printf '%s\n' '  \quit 45'
    fi
    printf '%s\n' '\endif'
    printf '%s\n' 'COMMIT;'
  } > "$control"
  $psql_base --file "$control"
  rm -f "$control"
  trap - EXIT HUP INT TERM
done

# A fresh deployment must have exactly one stable UUIDv7 installation identity before
# Entitlements starts. PostgreSQL 17 exposes gen_random_uuid() as UUIDv4 only, so construct
# the RFC 9562 UUIDv7 layout from the database clock and 74 random payload bits. This keeps
# the bootstrap aligned with DomainIdentifier without depending on a host-specific UUID tool.
installation_id="$($psql_base --tuples-only --no-align --command "
WITH source AS (
    SELECT LPAD(TO_HEX(FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT), 12, '0') AS ts,
           REPLACE(gen_random_uuid()::TEXT, '-', '') AS entropy
), shaped AS (
    SELECT ts, entropy,
           SUBSTRING('89ab89ab89ab89ab'
                     FROM STRPOS('0123456789abcdef', SUBSTRING(entropy FROM 4 FOR 1))
                     FOR 1) AS variant_nibble
      FROM source
)
SELECT (
    SUBSTRING(ts FROM 1 FOR 8) || '-' ||
    SUBSTRING(ts FROM 9 FOR 4) || '-7' ||
    SUBSTRING(entropy FROM 1 FOR 3) || '-' ||
    variant_nibble || SUBSTRING(entropy FROM 5 FOR 3) || '-' ||
    SUBSTRING(entropy FROM 8 FOR 12)
)::UUID::TEXT
FROM shaped;
")"
case "$installation_id" in
  ????????-????-7???-[89ab]???-????????????) ;;
  *)
    echo "Generated installation identity is not UUIDv7: $installation_id" >&2
    exit 66
    ;;
esac
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
      :'installation_id'::uuid, 'v1', :'fingerprint', date_trunc('second', CURRENT_TIMESTAMP)
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
