#!/bin/sh
set -eu

secret_dir="${INFRANEXUM_SECRET_DIRECTORY:-/run/infranexum-secrets}"
mkdir -p "$secret_dir"

create_secret() {
  target="$1"
  mode="$2"
  owner="$3"
  if [ ! -s "$target" ]; then
    umask 077
    tmp="${target}.tmp.$$"
    head -c 32 /dev/urandom | base64 > "$tmp"
    chmod "$mode" "$tmp"
    chown "$owner" "$tmp"
    mv -f "$tmp" "$target"
  fi
}

# Database password is read by PostgreSQL, migrator and Server (different UIDs) inside an isolated volume.
create_secret "$secret_dir/db-password" 0444 root:root
# Integrity key is intentionally readable only by the non-root Server UID.
create_secret "$secret_dir/integrity-key" 0400 10001:10001

test -s "$secret_dir/db-password"
test -s "$secret_dir/integrity-key"
