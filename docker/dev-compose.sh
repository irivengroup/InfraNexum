#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
compose_file="$script_dir/compose.yaml"
state_dir="$repo_root/.infranexum-dev/state"
backup_dir="$state_dir/backups"

compose() {
  if [ -f "$script_dir/.env" ]; then
    docker compose --env-file "$script_dir/.env" -f "$compose_file" "$@"
  else
    docker compose -f "$compose_file" "$@"
  fi
}

published_port() {
  service=$1
  container_port=$2
  binding=''
  if ! binding=$(compose port "$service" "$container_port" 2>/dev/null); then
    echo "docker compose port failed for $service/$container_port; falling back to docker inspect" >&2
  fi
  if [ -z "$binding" ]; then
    container_id=$(compose ps -q "$service")
    test -n "$container_id" || {
      echo "Compose service '$service' has no container to inspect" >&2
      exit 69
    }
    inspect_format="{{with (index .NetworkSettings.Ports \"${container_port}/tcp\")}}{{(index . 0).HostIp}}:{{(index . 0).HostPort}}{{end}}"
    binding=$(docker inspect --format "$inspect_format" "$container_id") || {
      echo "Unable to inspect published port for $service/$container_port" >&2
      exit 69
    }
  fi
  test -n "$binding" || {
    echo "Compose does not publish $service container port $container_port to the host" >&2
    exit 69
  }
  case "$binding" in
    127.0.0.1:[0-9]*) ;;
    *) echo "Unexpected Compose port binding for $service/$container_port: $binding" >&2; exit 69 ;;
  esac
  port=${binding##*:}
  case "$port" in
    ''|*[!0-9]*) echo "Unexpected Compose port binding for $service/$container_port: $binding" >&2; exit 69 ;;
  esac
  printf '%s
' "$port"
}

assert_service_running() {
  service=$1
  if ! compose ps --status running --services | grep -Fxq "$service"; then
    echo "Compose service '$service' is not running; current topology and recent logs follow." >&2
    compose ps >&2 || true
    compose logs --no-color --tail=200 "$service" >&2 || true
    echo "Compose service '$service' is not running" >&2
    exit 69
  fi
}

require_repo() {
  command -v docker >/dev/null 2>&1 || { echo "Docker CLI is required" >&2; exit 127; }
  docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 plugin is required" >&2; exit 127; }
  test -f "$repo_root/VERSION" || { echo "InfraNexum repository root not found: $repo_root" >&2; exit 66; }
  test -d "$repo_root/src/distribution/migrations" || { echo "Migration catalogue not found below $repo_root" >&2; exit 66; }
}

backup() {
  require_repo
  mkdir -p "$backup_dir"
  backup_file="$backup_dir/infranexum-$(date -u +%Y%m%dT%H%M%SZ).dump"
  remote_file="/tmp/infranexum-dev-backup.dump"
  compose exec -T postgres sh -eu -c 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; pg_dump --format=custom --no-owner --no-privileges --username=infranexum --dbname=infranexum --file=/tmp/infranexum-dev-backup.dump'
  compose cp "postgres:$remote_file" "$backup_file"
  compose exec -T postgres rm -f "$remote_file"
  test -s "$backup_file" || { echo "Backup is empty: $backup_file" >&2; exit 74; }
  echo "$backup_file"
}

restore() {
  require_repo
  backup_file=${BACKUP_FILE:?BACKUP_FILE is required}
  test "${CONFIRM_INFRANEXUM_RESTORE:-}" = "YES" || { echo "Refusing restore; set CONFIRM_INFRANEXUM_RESTORE=YES" >&2; exit 64; }
  test -s "$backup_file" || { echo "Backup does not exist or is empty: $backup_file" >&2; exit 66; }
  remote_file="/tmp/infranexum-dev-restore.dump"
  compose up --detach --wait postgres
  compose stop server >/dev/null 2>&1 || true
  compose cp "$backup_file" "postgres:$remote_file"
  cleanup_restore() { compose exec -T postgres rm -f "$remote_file" >/dev/null 2>&1 || true; }
  trap cleanup_restore EXIT HUP INT TERM
  compose exec -T postgres sh -eu -c 'export PGPASSWORD="$(cat /run/infranexum-secrets/db-password)"; dropdb --if-exists --username=infranexum infranexum; createdb --username=infranexum --owner=infranexum infranexum; pg_restore --exit-on-error --no-owner --no-privileges --username=infranexum --dbname=infranexum /tmp/infranexum-dev-restore.dump'
  cleanup_restore
  trap - EXIT HUP INT TERM
  compose run --rm migrate
  compose up --detach --wait server
}

smoke() {
  require_repo
  assert_service_running postgres
  assert_service_running server
  postgres_port=$(published_port postgres 5432)
  port=$(published_port server 8080)
  echo "Compose bindings: postgres=127.0.0.1:$postgres_port server=127.0.0.1:$port"
  readiness=$(mktemp)
  workers_metric=$(mktemp)
  build=$(mktemp)
  headers=$(mktemp)
  invalid_body=$(mktemp)
  invalid_headers=$(mktemp)
  trap 'rm -f "$readiness" "$workers_metric" "$build" "$headers" "$invalid_body" "$invalid_headers"' EXIT HUP INT TERM
  curl --fail --silent --show-error "http://127.0.0.1:$port/actuator/health/readiness" > "$readiness"
  grep -q '"status":"UP"' "$readiness"
  curl --fail --silent --show-error "http://127.0.0.1:$port/actuator/metrics/infranexum.workers.ready" > "$workers_metric"
  grep -q '"name":"infranexum.workers.ready"' "$workers_metric"
  correlation_id="018bcfe5-6800-7001-8000-000000000001"
  curl --fail --silent --show-error --dump-header "$headers" \
    --header "X-Correlation-ID: $correlation_id" \
    "http://127.0.0.1:$port/api/v1/system/build" > "$build"
  grep -q '"product":"InfraNexum"' "$build"
  grep -Eiq "^X-Correlation-ID:[[:space:]]*$correlation_id[[:space:]]*$" "$headers"
  invalid_status=$(curl --silent --show-error --output "$invalid_body" --dump-header "$invalid_headers" \
    --write-out '%{http_code}' --header 'X-Correlation-ID: invalid-secret-value' \
    "http://127.0.0.1:$port/api/v1/system/build")
  test "$invalid_status" = "400"
  grep -q 'INFRANEXUM_INVALID_CORRELATION_ID' "$invalid_body"
  ! grep -q 'invalid-secret-value' "$invalid_body"
  grep -Eiq '^X-Correlation-ID:[[:space:]]*[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}[[:space:]]*$' "$invalid_headers"
  rm -f "$readiness" "$workers_metric" "$build" "$headers" "$invalid_body" "$invalid_headers"
  trap - EXIT HUP INT TERM
  echo "compose-smoke: PASS"
}

command=${1:-help}
if [ "$#" -gt 0 ]; then
  shift
fi
case "$command" in
  config) require_repo; compose config --quiet ;;
  build) require_repo; compose config --quiet; compose build --pull ;;
  up) require_repo; compose config --quiet; compose up --detach --build --wait server ;;
  down) require_repo; compose down --remove-orphans ;;
  logs)
    require_repo
    if [ "$#" -gt 0 ]; then
      compose logs --no-color --tail=200 "$@"
    else
      compose logs --no-color --tail=200 server postgres migrate
    fi
    ;;
  smoke) smoke ;;
  backup) backup ;;
  restore) restore ;;
  rollback)
    require_repo
    : "${MIGRATION_ID:?MIGRATION_ID is required}"
    test "${CONFIRM_INFRANEXUM_ROLLBACK:-}" = "YES" || { echo "Refusing rollback; set CONFIRM_INFRANEXUM_ROLLBACK=YES" >&2; exit 64; }
    compose up --detach --wait postgres
    backup_file=$(backup)
    echo "Pre-rollback backup: $backup_file"
    compose stop server >/dev/null 2>&1 || true
    compose --profile maintenance run --rm -e MIGRATION_ID="$MIGRATION_ID" -e CONFIRM_INFRANEXUM_ROLLBACK=YES rollback
    echo "Rollback completed. Server remains stopped; restart only with a build compatible with migration $MIGRATION_ID."
    ;;
  reset)
    require_repo
    test "${CONFIRM_INFRANEXUM_VOLUME_DELETE:-}" = "YES" || { echo "Refusing volume deletion; set CONFIRM_INFRANEXUM_VOLUME_DELETE=YES" >&2; exit 64; }
    compose down --volumes --remove-orphans
    echo "InfraNexum developer Compose volumes removed"
    ;;
  help|-h|--help)
    cat <<'USAGE'
Usage: ./docker/dev-compose.sh COMMAND [SERVICE ...]
Commands: config build up down logs smoke backup restore rollback reset

Start the complete developer topology:
  ./docker/dev-compose.sh up

Equivalent direct Compose command:
  docker compose up --detach --build --wait server

Migration diagnostics:
  ./docker/dev-compose.sh logs migrate
  docker compose logs migrate

Destructive/restore operations require:
  BACKUP_FILE=... CONFIRM_INFRANEXUM_RESTORE=YES ./docker/dev-compose.sh restore
  MIGRATION_ID=0006 CONFIRM_INFRANEXUM_ROLLBACK=YES ./docker/dev-compose.sh rollback
  CONFIRM_INFRANEXUM_VOLUME_DELETE=YES ./docker/dev-compose.sh reset
USAGE
    ;;
  *) echo "Unknown command: $command" >&2; exit 64 ;;
esac
