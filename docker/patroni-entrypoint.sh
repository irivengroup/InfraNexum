#!/bin/sh
set -eu

: "${PATRONI_NAME:?PATRONI_NAME is required}"
PATRONI_SCOPE="${PATRONI_SCOPE:-infranexum-pro}"
PATRONI_ETCD3_HOSTS="${PATRONI_ETCD3_HOSTS:-etcd-1:2379,etcd-2:2379,etcd-3:2379}"
db_password_file="${INFRANEXUM_DATABASE_PASSWORD_FILE:-/run/infranexum-secrets/db-password}"
replication_password_file="${INFRANEXUM_REPLICATION_PASSWORD_FILE:-/run/infranexum-secrets/replication-password}"

for secret in "$db_password_file" "$replication_password_file"; do
  [ -r "$secret" ] || { echo "Required Patroni secret is not readable: $secret" >&2; exit 64; }
  [ -s "$secret" ] || { echo "Required Patroni secret is empty: $secret" >&2; exit 64; }
done

db_password=$(cat "$db_password_file")
replication_password=$(cat "$replication_password_file")
# Runtime-generated secrets use base64 and therefore cannot break single-quoted YAML scalars.
case "$db_password$replication_password" in *"'"*) echo 'Patroni secrets contain unsupported quote characters' >&2; exit 64;; esac

mkdir -p /var/lib/postgresql/data/pgdata /var/run/postgresql
chown -R postgres:postgres /var/lib/postgresql/data /var/run/postgresql
config=/tmp/patroni.yml
umask 077
cat > "$config" <<EOF_CFG
scope: ${PATRONI_SCOPE}
namespace: /infranexum/
name: ${PATRONI_NAME}

restapi:
  listen: 0.0.0.0:8008
  connect_address: ${PATRONI_NAME}:8008

etcd3:
  hosts: ${PATRONI_ETCD3_HOSTS}

bootstrap:
  dcs:
    ttl: 30
    loop_wait: 10
    retry_timeout: 10
    maximum_lag_on_failover: 1048576
    synchronous_mode: true
    synchronous_mode_strict: true
    synchronous_node_count: 1
    postgresql:
      use_pg_rewind: true
      use_slots: true
      parameters:
        wal_level: replica
        hot_standby: 'on'
        wal_log_hints: 'on'
        max_wal_senders: 10
        max_replication_slots: 10
        password_encryption: scram-sha-256
        synchronous_commit: 'on'
  initdb:
    - encoding: UTF8
    - data-checksums
  pg_hba:
    - host all all 0.0.0.0/0 scram-sha-256
    - host replication replicator 0.0.0.0/0 scram-sha-256

postgresql:
  listen: 0.0.0.0:5432
  connect_address: ${PATRONI_NAME}:5432
  data_dir: /var/lib/postgresql/data/pgdata
  bin_dir: /usr/local/bin
  pgpass: /tmp/pgpass-${PATRONI_NAME}
  authentication:
    superuser:
      username: postgres
      password: '${db_password}'
    replication:
      username: replicator
      password: '${replication_password}'
  parameters:
    unix_socket_directories: /var/run/postgresql

tags:
  nofailover: false
  noloadbalance: false
  clonefrom: false
  nosync: false
EOF_CFG
chown postgres:postgres "$config"
chmod 0600 "$config"
exec su-exec postgres /opt/patroni/bin/patroni "$config"
