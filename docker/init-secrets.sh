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
# Replication password is shared only by Patroni PostgreSQL nodes in the private developer network.
create_secret "$secret_dir/replication-password" 0444 root:root
# Integrity key is retained for activation-specific tests outside the HA topology harness.
create_secret "$secret_dir/integrity-key" 0400 10001:10001

# Local developer administrator bootstrap secret. This volume is developer-only and never packaged for production.
if [ ! -s "$secret_dir/local-admin-password" ]; then
  umask 077
  tmp="$secret_dir/local-admin-password.tmp.$$"
  { head -c 24 /dev/urandom | base64 | tr -d '\n'; printf '!Aa1\n'; } > "$tmp"
  chmod 0444 "$tmp"
  chown root:root "$tmp"
  mv -f "$tmp" "$secret_dir/local-admin-password"
fi

test -s "$secret_dir/db-password"
test -s "$secret_dir/replication-password"
test -s "$secret_dir/integrity-key"
test -s "$secret_dir/local-admin-password"
