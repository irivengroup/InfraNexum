#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
compose_file="$script_dir/compose.yaml"
state_dir="$repo_root/.infranexum-dev/state"
backup_dir="$state_dir/backups"
cluster_services="etcd-1 etcd-2 etcd-3 postgres-1 postgres-2 postgres-3 postgres server-1 server-2 server-3 server-4 server"

compose() {
  if [ -f "$script_dir/.env" ]; then docker compose --env-file "$script_dir/.env" -f "$compose_file" "$@"
  else docker compose -f "$compose_file" "$@"; fi
}

require_repo() {
  command -v docker >/dev/null 2>&1 || { echo 'Docker CLI is required' >&2; exit 127; }
  docker compose version >/dev/null 2>&1 || { echo 'Docker Compose v2 plugin is required' >&2; exit 127; }
  test -f "$repo_root/VERSION" || { echo "InfraNexum repository root not found: $repo_root" >&2; exit 66; }
}

published_port() {
  service=$1; container_port=$2; binding=''
  if ! binding=$(compose port "$service" "$container_port" 2>/dev/null); then
    echo "docker compose port failed for $service/$container_port; falling back to docker inspect" >&2
  fi
  if [ -z "$binding" ]; then
    container_id=$(compose ps -q "$service"); test -n "$container_id" || { echo "No container for $service" >&2; exit 69; }
    inspect_format="{{with (index .NetworkSettings.Ports \"${container_port}/tcp\")}}{{(index . 0).HostIp}}:{{(index . 0).HostPort}}{{end}}"
    binding=$(docker inspect --format "$inspect_format" "$container_id")
  fi
  case "$binding" in 127.0.0.1:[0-9]*) ;; *) echo "Unexpected Compose port binding for $service/$container_port: $binding" >&2; exit 69;; esac
  printf '%s\n' "${binding##*:}"
}

assert_service_healthy() {
  service=$1
  compose ps --status running --services | grep -Fxq "$service" || { compose ps >&2 || true; compose logs --no-color --tail=200 "$service" >&2 || true; echo "Service $service is not running" >&2; exit 69; }
  cid=$(compose ps -q "$service")
  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid")
  test "$health" = healthy || { compose logs --no-color --tail=200 "$service" >&2 || true; echo "Service $service health=$health" >&2; exit 69; }
}

db_scalar() {
  sql=$1
  compose run --rm --no-deps --entrypoint /bin/sh migrate -eu -c \
    'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; exec psql --no-psqlrc --tuples-only --no-align --host=postgres --port=5432 --username=infranexum --dbname=infranexum --command "$1"' sh "$sql"
}

backup() {
  require_repo; mkdir -p "$backup_dir"
  backup_file="$backup_dir/infranexum-$(date -u +%Y%m%dT%H%M%SZ).dump"; remote=/tmp/infranexum-dev-backup.dump
  compose --profile maintenance up --detach db-admin
  cleanup() { compose stop db-admin >/dev/null 2>&1 || true; }; trap cleanup EXIT HUP INT TERM
  compose exec -T db-admin sh -eu -c 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; pg_dump --format=custom --no-owner --no-privileges --host=postgres --port=5432 --username=infranexum --dbname=infranexum --file=/tmp/infranexum-dev-backup.dump'
  compose cp "db-admin:$remote" "$backup_file"; compose exec -T db-admin rm -f "$remote"
  cleanup; trap - EXIT HUP INT TERM
  test -s "$backup_file" || { echo "Backup is empty: $backup_file" >&2; exit 74; }; printf '%s\n' "$backup_file"
}

smoke() {
  require_repo
  for service in $cluster_services; do assert_service_healthy "$service"; done
  writer_port=$(published_port postgres 5432); read_port=$(published_port postgres 5433); server_port=$(published_port server 8080)
  echo "Compose PRO bindings: writer=127.0.0.1:$writer_port replicas=127.0.0.1:$read_port server=127.0.0.1:$server_port"
  streaming=$(db_scalar "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'")
  test "$streaming" -ge 2 || { echo "Expected two streaming standbys; observed $streaming" >&2; exit 69; }
  synchronous=$(db_scalar "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state IN ('sync','quorum')")
  test "$synchronous" -ge 1 || { echo 'No synchronous PostgreSQL standby' >&2; exit 69; }
  curl --fail --silent --show-error "http://127.0.0.1:$server_port/actuator/health/readiness" | grep -q '"status":"UP"'
  curl --fail --silent --show-error "http://127.0.0.1:$server_port/actuator/metrics/infranexum.workers.ready" | grep -q '"name":"infranexum.workers.ready"'
  correlation_id=018bcfe5-6800-7001-8000-000000000001
  tmp=$(mktemp); headers=$(mktemp); trap 'rm -f "$tmp" "$headers"' EXIT HUP INT TERM
  curl --fail --silent --show-error --dump-header "$headers" --header "X-Correlation-ID: $correlation_id" "http://127.0.0.1:$server_port/api/v1/system/build" > "$tmp"
  grep -Eq '"instanceId":"server-pro-[1-4]"' "$tmp"; grep -Eiq "^X-Correlation-ID:[[:space:]]*$correlation_id[[:space:]]*$" "$headers"
  rm -f "$tmp" "$headers"; trap - EXIT HUP INT TERM
  echo "compose-smoke: PASS (streaming=$streaming synchronous=$synchronous Server=4)"
}

patroni_primary() {
  for service in postgres-1 postgres-2 postgres-3; do
    code=$(compose exec -T "$service" sh -c 'curl --silent --output /dev/null --write-out "%{http_code}" http://127.0.0.1:8008/primary' 2>/dev/null || true)
    test "$code" = 200 && { echo "$service"; return 0; }
  done
  return 1
}

ha_smoke() {
  require_repo; smoke
  primary=$(patroni_primary) || { echo 'Unable to identify Patroni primary' >&2; exit 69; }
  echo "Stopping current Patroni primary: $primary"; compose stop "$primary"
  restore_primary() { compose start "$primary" >/dev/null 2>&1 || true; }; trap restore_primary EXIT HUP INT TERM
  replacement=''; attempts=0
  while [ "$attempts" -lt 30 ]; do sleep 2; replacement=$(patroni_primary || true); test -n "$replacement" && break; attempts=$((attempts + 1)); done
  test -n "$replacement" && test "$replacement" != "$primary" || { echo 'No replacement primary within 60 seconds' >&2; exit 69; }
  test "$(db_scalar 'SELECT 1')" = 1 || { echo 'Writer endpoint did not recover' >&2; exit 69; }
  server_port=$(published_port server 8080); curl --fail --silent --show-error "http://127.0.0.1:$server_port/actuator/health/readiness" | grep -q '"status":"UP"'
  restore_primary; trap - EXIT HUP INT TERM
  attempts=0; while [ "$attempts" -lt 30 ]; do sleep 3; if (assert_service_healthy "$primary") >/dev/null 2>&1; then break; fi; attempts=$((attempts + 1)); done
  test "$attempts" -lt 30 || { echo "Former primary $primary did not rejoin healthy within 90 seconds" >&2; exit 69; }
  attempts=0; streaming=0; while [ "$attempts" -lt 30 ]; do sleep 3; streaming=$(db_scalar "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'" 2>/dev/null || echo 0); test "$streaming" -ge 2 && break; attempts=$((attempts + 1)); done
  test "$streaming" -ge 2 || { echo "Cluster did not return to two streaming standbys; observed $streaming" >&2; exit 69; }
  echo "compose-ha-smoke: PASS ($primary -> $replacement -> rejoined)"
}

restore() {
  require_repo; backup_file=${BACKUP_FILE:?BACKUP_FILE is required}; test "${CONFIRM_INFRANEXUM_RESTORE:-}" = YES || { echo 'Refusing restore; set CONFIRM_INFRANEXUM_RESTORE=YES' >&2; exit 64; }
  test -s "$backup_file" || { echo "Backup missing/empty: $backup_file" >&2; exit 66; }
  remote=/tmp/infranexum-dev-restore.dump
  compose up --detach --wait postgres; compose --profile maintenance up --detach db-admin; compose stop server server-1 server-2 server-3 server-4 >/dev/null 2>&1 || true
  compose cp "$backup_file" "db-admin:$remote"
  compose exec -T db-admin sh -eu -c 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; dropdb --if-exists --host=postgres --port=5432 --username=infranexum --maintenance-db=postgres infranexum; createdb --host=postgres --port=5432 --username=infranexum --maintenance-db=postgres --owner=infranexum infranexum; pg_restore --exit-on-error --no-owner --no-privileges --host=postgres --port=5432 --username=infranexum --dbname=infranexum /tmp/infranexum-dev-restore.dump'
  compose exec -T db-admin rm -f "$remote"; compose stop db-admin; compose run --rm migrate; compose up --detach --wait server
}

command=${1:-help}; test "$#" -gt 0 && shift || true
case "$command" in
  config) require_repo; compose config --quiet ;;
  build) require_repo; compose config --quiet; compose build --pull ;;
  up) require_repo; compose config --quiet; compose up --detach --build --wait server ;;
  down) require_repo; compose down --remove-orphans ;;
  logs) require_repo; if [ "$#" -gt 0 ]; then compose logs --no-color --tail=200 "$@"; else compose logs --no-color --tail=200 server server-1 server-2 server-3 server-4 postgres postgres-1 postgres-2 postgres-3 migrate; fi ;;
  smoke) smoke ;;
  ha-smoke) ha_smoke ;;
  backup) backup ;;
  restore) restore ;;
  rollback) require_repo; : "${MIGRATION_ID:?MIGRATION_ID is required}"; test "${CONFIRM_INFRANEXUM_ROLLBACK:-}" = YES || { echo 'Refusing rollback' >&2; exit 64; }; compose up --detach --wait postgres; backup_file=$(backup); echo "Pre-rollback backup: $backup_file"; compose stop server server-1 server-2 server-3 server-4 >/dev/null 2>&1 || true; compose --profile maintenance run --rm -e MIGRATION_ID="$MIGRATION_ID" -e CONFIRM_INFRANEXUM_ROLLBACK=YES rollback ;;
  reset) require_repo; test "${CONFIRM_INFRANEXUM_VOLUME_DELETE:-}" = YES || { echo 'Refusing volume deletion' >&2; exit 64; }; compose down --volumes --remove-orphans ;;
  help|-h|--help) echo 'Commands: config build up down logs smoke ha-smoke backup restore rollback reset' ;;
  *) echo "Unknown command: $command" >&2; exit 64 ;;
esac
