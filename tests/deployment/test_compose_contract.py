from __future__ import annotations

import os
import pathlib
import re
import subprocess
import tempfile
import textwrap
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOCKER = ROOT / "docker"
COMPOSE = DOCKER / "compose.yaml"
ROOT_COMPOSE = ROOT / "compose.yaml"
ETCD = ("etcd-1", "etcd-2", "etcd-3")
PG = ("postgres-1", "postgres-2", "postgres-3")
SERVER = ("server-1", "server-2", "server-3", "server-4")
WEB = ("web-1", "web-2")


class ComposeContractTest(unittest.TestCase):
    """Protect the PRO Docker/Compose HA topology and its safety boundaries."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = COMPOSE.read_text(encoding="utf-8")
        cls.doc = yaml.safe_load(cls.text)
        cls.services = cls.doc["services"]

    def test_exact_pro_service_set(self) -> None:
        expected = {"secret-init", *ETCD, *PG, "postgres", "db-bootstrap", "migrate", *SERVER, "server", *WEB, "web", "db-admin", "rollback"}
        self.assertEqual(expected, set(self.services))

    def test_three_postgres_four_server_and_two_web_nodes(self) -> None:
        self.assertTrue(all(name in self.services for name in PG))
        self.assertTrue(all(name in self.services for name in SERVER))
        self.assertTrue(all(name in self.services for name in WEB))

    def test_all_server_nodes_are_pro_high_availability_regional(self) -> None:
        for index, name in enumerate(SERVER, 1):
            env = self.services[name]["environment"]
            self.assertEqual("PRO", env["INFRANEXUM_PROFILE"])
            self.assertEqual("HIGH_AVAILABILITY", env["INFRANEXUM_TOPOLOGY"])
            self.assertEqual("REGIONAL", env["INFRANEXUM_SERVER_MODE"])
            self.assertEqual("SERVER", env["INFRANEXUM_ROLES"])
            self.assertEqual("local", env["INFRANEXUM_SERVER_REGION"])
            self.assertEqual("local", env["INFRANEXUM_SERVER_SITE"])
            self.assertEqual(f"server-pro-{index}", env["INFRANEXUM_SERVER_INSTANCE_ID"])

    def test_integrations_start_with_no_configured_endpoint_maps(self) -> None:
        """Regression: Spring must not flatten empty YAML maps into scalar empty strings."""
        application_yaml = (ROOT / "src/applications/server/resources/application.yaml").read_text(encoding="utf-8")
        for forbidden in ("    endpoints: {}", "      connectors: {}", "      endpoints: {}"):
            self.assertNotIn(forbidden, application_yaml)
        for name in SERVER:
            environment = self.services[name]["environment"]
            self.assertEqual("true", environment["INFRANEXUM_INTEGRATIONS_ENABLED"])
            self.assertNotIn("INFRANEXUM_INTEGRATIONS_ENDPOINTS", environment)

    def test_web_cluster_is_two_private_nodes_behind_loopback_router(self) -> None:
        for name in WEB:
            service = self.services[name]
            self.assertEqual("infranexum/web:${INFRANEXUM_VERSION:-2.0.0-alpha.0.124}", service["image"])
            self.assertEqual("service_healthy", service["depends_on"]["server"]["condition"])
            self.assertNotIn("ports", service)
            self.assertEqual("local", service["environment"]["INFRANEXUM_WEB_ENVIRONMENT"])
            self.assertEqual("/api", service["environment"]["INFRANEXUM_WEB_API_BASE_URL"])
        self.assertEqual(["127.0.0.1:${INFRANEXUM_WEB_PUBLISHED_PORT:-8081}:8080"], self.services["web"]["ports"])
        self.assertNotIn("Web cluster is intentionally deferred", (DOCKER / "README.md").read_text(encoding="utf-8"))

    def test_etcd_is_three_member_pinned_cluster(self) -> None:
        for name in ETCD:
            service = self.services[name]
            self.assertEqual("gcr.io/etcd-development/etcd:v3.6.14", service["image"])
            self.assertIn("--initial-cluster=etcd-1=http://etcd-1:2380,etcd-2=http://etcd-2:2380,etcd-3=http://etcd-3:2380", " ".join(service["command"]))
            self.assertIn("healthcheck", service)

    def test_etcd_healthchecks_are_shell_free_exec_form(self) -> None:
        expected = [
            "CMD",
            "/usr/local/bin/etcdctl",
            "--endpoints=http://127.0.0.1:2379",
            "endpoint",
            "health",
        ]
        for name in ETCD:
            healthcheck = self.services[name]["healthcheck"]
            self.assertEqual(expected, healthcheck["test"], name)
            self.assertNotIn("CMD-SHELL", healthcheck["test"], name)

    def test_patroni_is_pinned_on_postgres_17(self) -> None:
        dockerfile = (DOCKER / "patroni-postgres.Dockerfile").read_text(encoding="utf-8")
        self.assertIn("FROM postgres:17.10-alpine3.24", dockerfile)
        self.assertIn("ARG PATRONI_VERSION=4.1.4", dockerfile)
        self.assertIn('"patroni[psycopg3,etcd3]==${PATRONI_VERSION}"', dockerfile)
        self.assertIn('PATRONI_VERSION: "4.1.4"', self.text)

    def test_patroni_enforces_synchronous_replication(self) -> None:
        entry = (DOCKER / "patroni-entrypoint.sh").read_text(encoding="utf-8")
        for fragment in ("synchronous_mode: true", "synchronous_mode_strict: true", "synchronous_node_count: 1", "synchronous_commit: 'on'"):
            self.assertIn(fragment, entry)

    def test_database_bootstrap_waits_for_two_streaming_and_one_sync_standby(self) -> None:
        bootstrap = (DOCKER / "bootstrap-postgresql-ha.sh").read_text(encoding="utf-8")
        self.assertIn("pg_stat_replication", bootstrap)
        self.assertIn("state='streaming'", bootstrap)
        self.assertIn("sync_state IN ('sync','quorum')", bootstrap)
        self.assertIn("createdb", bootstrap)
        self.assertEqual("service_completed_successfully", self.services["migrate"]["depends_on"]["db-bootstrap"]["condition"])

    def test_database_bootstrap_uses_psql_stdin_for_password_interpolation(self) -> None:
        """Regression: psql -c must never receive psql-only :'variable' syntax."""
        bootstrap = (DOCKER / "bootstrap-postgresql-ha.sh").read_text(encoding="utf-8")
        self.assertIn(r"\getenv db_password INFRANEXUM_BOOTSTRAP_DATABASE_PASSWORD", bootstrap)
        self.assertIn("psql_admin <<'SQL'", bootstrap)
        self.assertNotRegex(bootstrap, r"--command .*PASSWORD :'db_password'")
        self.assertNotIn("--set=db_password=", bootstrap)

    def test_database_bootstrap_executes_with_psql_only_interpolation_on_stdin(self) -> None:
        """Execute the bootstrap against deterministic command stubs reproducing alpha.0.47."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            temp = pathlib.Path(temporary_directory)
            password = temp / "db-password"
            password.write_text("fixture-secret-with-safe-quote-'\n", encoding="utf-8")
            bin_dir = temp / "bin"
            bin_dir.mkdir()
            psql_log = temp / "psql.log"

            psql = bin_dir / "psql"
            psql.write_text(textwrap.dedent(r'''
                #!/bin/sh
                set -eu
                printf 'ARGS:%s\n' "$*" >> "$PSQL_STUB_LOG"
                command_sql=''
                previous=''
                for argument in "$@"; do
                  if [ "$previous" = '--command' ]; then
                    command_sql="$argument"
                    break
                  fi
                  previous="$argument"
                done
                case "$command_sql" in
                  *"SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state"*)
                    state="$PSQL_STUB_STATE_DIR/sync-ready"
                    if [ -f "$state" ]; then printf '1\n'; else : > "$state"; printf '0\n'; fi
                    exit 0 ;;
                  *"SELECT count(*) FROM pg_stat_replication WHERE state='streaming'"*)
                    state="$PSQL_STUB_STATE_DIR/streaming-ready"
                    if [ -f "$state" ]; then printf '2\n'; else : > "$state"; printf '1\n'; fi
                    exit 0 ;;
                  *"SELECT 1 FROM pg_roles"*) exit 0 ;;
                  *"SELECT 1 FROM pg_database"*) printf '1\n'; exit 0 ;;
                  'SELECT 1') printf '1\n'; exit 0 ;;
                  *":'db_password'"*) echo 'psql -c forwarded psql-only interpolation to the server' >&2; exit 1 ;;
                esac
                if [ -z "$command_sql" ]; then
                  input=$(cat)
                  printf 'STDIN:%s\n' "$input" >> "$PSQL_STUB_LOG"
                  printf '%s\n' "$input" | grep -F '\getenv db_password INFRANEXUM_BOOTSTRAP_DATABASE_PASSWORD' >/dev/null
                  printf '%s\n' "$input" | grep -F "CREATE ROLE infranexum LOGIN PASSWORD :'db_password';" >/dev/null
                  [ "${INFRANEXUM_BOOTSTRAP_DATABASE_PASSWORD:-}" = "fixture-secret-with-safe-quote-'" ]
                  exit 0
                fi
                echo "Unexpected psql invocation: $*" >&2
                exit 70
            ''').lstrip(), encoding="utf-8")
            psql.chmod(0o755)

            createdb = bin_dir / "createdb"
            createdb.write_text("#!/bin/sh\nexit 70\n", encoding="utf-8")
            createdb.chmod(0o755)
            sleep = bin_dir / "sleep"
            sleep.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            sleep.chmod(0o755)

            environment = os.environ.copy()
            environment.update({
                "PATH": f"{bin_dir}{os.pathsep}{environment.get('PATH', '')}",
                "INFRANEXUM_DATABASE_PASSWORD_FILE": str(password),
                "PSQL_STUB_LOG": str(psql_log),
                "PSQL_STUB_STATE_DIR": str(temp),
                "PGHOST": "postgres",
                "PGPORT": "5432",
            })
            result = subprocess.run(
                ["sh", str(DOCKER / "bootstrap-postgresql-ha.sh")],
                cwd=ROOT,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("PRO PostgreSQL bootstrap invariant verified", result.stdout)
            log = psql_log.read_text(encoding="utf-8")
            self.assertIn("STDIN:", log)
            self.assertGreaterEqual(log.count("state='streaming'"), 4)
            argument_log = "\n".join(line for line in log.splitlines() if line.startswith("ARGS:"))
            self.assertNotIn("fixture-secret-with-safe-quote-'", argument_log)

    def test_database_bootstrap_has_bounded_replication_waits(self) -> None:
        bootstrap = (DOCKER / "bootstrap-postgresql-ha.sh").read_text(encoding="utf-8")
        self.assertIn("wait_for_scalar_at_least", bootstrap)
        self.assertIn('while [ "$attempt" -lt "$attempts" ]', bootstrap)
        self.assertIn("observed $last_value", bootstrap)

    def test_patroni_and_etcd_have_separate_persistent_volumes(self) -> None:
        expected = {*(f"etcd-{i}-data" for i in range(1, 4)), *(f"postgres-{i}-data" for i in range(1, 4)), "runtime-secrets"}
        self.assertEqual(expected, set(self.doc["volumes"]))

    def test_raw_cluster_nodes_are_not_host_published(self) -> None:
        for name in (*ETCD, *PG, *SERVER, *WEB, "secret-init", "db-bootstrap", "migrate", "db-admin", "rollback"):
            self.assertNotIn("ports", self.services[name], name)

    def test_only_stable_routers_publish_loopback_ports(self) -> None:
        self.assertEqual([
            "127.0.0.1:${INFRANEXUM_POSTGRES_PUBLISHED_PORT:-5432}:5432",
            "127.0.0.1:${INFRANEXUM_POSTGRES_READ_PUBLISHED_PORT:-5433}:5433",
        ], self.services["postgres"]["ports"])
        self.assertEqual(["127.0.0.1:${INFRANEXUM_SERVER_PUBLISHED_PORT:-8080}:8080"], self.services["server"]["ports"])

    def test_backend_is_non_internal_bridge_for_loopback_publication(self) -> None:
        backend = self.doc["networks"]["backend"]
        self.assertEqual("bridge", backend["driver"])
        self.assertFalse(backend["internal"])

    def test_database_haproxy_routes_writer_by_patroni_primary(self) -> None:
        cfg = (DOCKER / "haproxy-postgres.cfg").read_text(encoding="utf-8")
        self.assertEqual("haproxy:3.2.21-alpine", self.services["postgres"]["image"])
        self.assertIn("option httpchk HEAD /primary", cfg)
        self.assertIn("option httpchk HEAD /replica", cfg)
        self.assertNotIn("option httpchk GET /primary", cfg)
        self.assertNotIn("option httpchk GET /replica", cfg)
        self.assertIn("on-marked-down shutdown-sessions", cfg)

    def test_server_haproxy_routes_four_readiness_healthy_nodes(self) -> None:
        cfg = (DOCKER / "haproxy-server.cfg").read_text(encoding="utf-8")
        self.assertEqual("haproxy:3.2.21-alpine", self.services["server"]["image"])
        self.assertIn("balance roundrobin", cfg)
        self.assertIn("/actuator/health/readiness", cfg)
        for name in SERVER:
            self.assertIn(name, cfg)

    def test_web_haproxy_routes_two_readiness_healthy_nodes(self) -> None:
        cfg = (DOCKER / "haproxy-web.cfg").read_text(encoding="utf-8")
        self.assertEqual("haproxy:3.2.21-alpine", self.services["web"]["image"])
        self.assertIn("balance roundrobin", cfg)
        self.assertIn("/health/ready", cfg)
        for name in WEB:
            self.assertIn(name, cfg)

    def test_web_runtime_image_is_pinned_verified_and_non_root(self) -> None:
        dockerfile = (DOCKER / "web.Dockerfile").read_text(encoding="utf-8")
        self.assertIn("ARG NODE_VERSION=24.18.1", dockerfile)
        self.assertIn("d6c664df3f3f61458e8c277585571328522d705166723a7c7823a9253a4d15a0", dockerfile)
        self.assertIn("7201e3a09dc825bac57867c81913e2b8f0ef87d04cb9082af4cda82f6ff3d88c", dockerfile)
        self.assertIn("sha256sum --check --strict", dockerfile)
        self.assertIn("USER 10002:10002", dockerfile)
        self.assertIn('CMD ["node", "runtime/main.mjs"]', dockerfile)

    def test_server_nodes_wait_for_migrations(self) -> None:
        for name in SERVER:
            deps = self.services[name]["depends_on"]
            self.assertEqual("service_completed_successfully", deps["migrate"]["condition"])
            self.assertEqual("service_healthy", deps["postgres"]["condition"])

    def test_database_router_waits_for_all_patroni_nodes(self) -> None:
        for name in PG:
            self.assertEqual("service_healthy", self.services["postgres"]["depends_on"][name]["condition"])

    def test_server_router_waits_for_all_four_nodes(self) -> None:
        for name in SERVER:
            self.assertEqual("service_healthy", self.services["server"]["depends_on"][name]["condition"])

    def test_web_router_waits_for_both_nodes(self) -> None:
        for name in WEB:
            self.assertEqual("service_healthy", self.services["web"]["depends_on"][name]["condition"])

    def test_web_router_healthcheck_options_are_nested_under_healthcheck(self) -> None:
        """Regression: Compose must not see healthcheck timing keys as service properties."""
        web = self.services["web"]
        healthcheck = web["healthcheck"]
        self.assertEqual("5s", healthcheck["interval"])
        self.assertEqual("5s", healthcheck["timeout"])
        self.assertEqual(20, healthcheck["retries"])
        self.assertEqual("5s", healthcheck["start_period"])
        for key in ("interval", "timeout", "retries", "start_period"):
            self.assertNotIn(key, web)

    def test_entitlements_bypass_is_explicit_topology_harness_only(self) -> None:
        for name in SERVER:
            self.assertEqual("false", str(self.services[name]["environment"]["INFRANEXUM_ENTITLEMENTS_ENABLED"]).lower())
        self.assertIn("Docker PRO is a topology harness", self.text)
        self.assertIn("never use this bypass in production", self.text)

    def test_replication_secret_is_generated(self) -> None:
        init = (DOCKER / "init-secrets.sh").read_text(encoding="utf-8")
        self.assertIn("replication-password", init)
        for name in PG:
            self.assertIn("runtime-secrets:/run/infranexum-secrets:ro", self.services[name]["volumes"])

    def test_migrate_and_servers_use_writer_router(self) -> None:
        self.assertEqual("postgres", self.services["migrate"]["environment"]["PGHOST"])
        for name in SERVER:
            self.assertEqual("jdbc:postgresql://postgres:5432/infranexum", self.services[name]["environment"]["INFRANEXUM_DATABASE_URL"])

    def test_maintenance_services_are_profile_scoped(self) -> None:
        self.assertEqual(["maintenance"], self.services["db-admin"]["profiles"])
        self.assertEqual(["maintenance"], self.services["rollback"]["profiles"])

    def test_web_ingress_routes_api_same_origin_through_server_router(self) -> None:
        haproxy = (DOCKER / "haproxy-web.cfg").read_text(encoding="utf-8")
        self.assertIn("acl api_request path_beg /api/", haproxy)
        self.assertIn("use_backend infranexum-server-router if api_request", haproxy)
        self.assertIn("server server-router server:8080 check", haproxy)
        for name in WEB:
            self.assertEqual("/api", self.services[name]["environment"]["INFRANEXUM_WEB_API_BASE_URL"])
        for name in SERVER:
            self.assertEqual(
                "true",
                str(self.services[name]["environment"]["INFRANEXUM_ORGANIZATION_API_ENABLED"]).lower(),
            )
            self.assertEqual("${INFRANEXUM_ENVIRONMENT:-local}", self.services[name]["environment"]["INFRANEXUM_ENVIRONMENT"])

    def test_smoke_requires_cluster_health_replication_and_worker_metric(self) -> None:
        for path in (DOCKER / "dev-compose.sh", DOCKER / "dev-compose.ps1"):
            text = path.read_text(encoding="utf-8")
            self.assertIn("pg_stat_replication", text)
            self.assertIn("sync_state", text)
            self.assertIn("infranexum.workers.ready", text)
            for name in (*ETCD, *PG, "postgres", *SERVER, "server", *WEB, "web"):
                self.assertIn(name, text)
            self.assertIn("runtime-config.json", text)
            self.assertIn("Web=2", text)

    def test_replication_smoke_uses_privileged_diagnostic_connection_without_elevating_application_role(self) -> None:
        """Regression: pg_stat_replication detail must not be filtered by application-role visibility."""
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        bootstrap = (DOCKER / "bootstrap-postgresql-ha.sh").read_text(encoding="utf-8")

        self.assertIn("db_scalar()", sh)
        self.assertIn("--username=infranexum --dbname=infranexum", sh)
        self.assertIn("cluster_admin_db_scalar()", sh)
        self.assertIn("application_admin_db_scalar()", sh)
        self.assertIn("--username=postgres --dbname=postgres", sh)
        self.assertIn("--username=postgres --dbname=infranexum", sh)
        self.assertIn('streaming=$(cluster_admin_db_scalar "SELECT count(*) FROM pg_stat_replication', sh)
        self.assertIn('synchronous=$(cluster_admin_db_scalar "SELECT count(*) FROM pg_stat_replication', sh)
        self.assertIn('iam_history=$(application_admin_db_scalar "SELECT count(*) FROM infranexum_core.schema_history', sh)
        self.assertNotIn('streaming=$(db_scalar "SELECT count(*) FROM pg_stat_replication', sh)

        self.assertIn("function Invoke-DatabaseScalar", ps)
        self.assertIn("--username=infranexum --dbname=infranexum", ps)
        self.assertIn("function Invoke-ClusterDatabaseAdminScalar", ps)
        self.assertIn("function Invoke-ApplicationDatabaseAdminScalar", ps)
        self.assertIn("--username=postgres --dbname=postgres", ps)
        self.assertIn("--username=postgres --dbname=infranexum", ps)
        self.assertIn('[int](Invoke-ClusterDatabaseAdminScalar "SELECT count(*) FROM pg_stat_replication', ps)
        self.assertIn('[int](Invoke-ApplicationDatabaseAdminScalar "SELECT count(*) FROM infranexum_core.schema_history', ps)
        self.assertNotIn('[int](Invoke-DatabaseScalar "SELECT count(*) FROM pg_stat_replication', ps)

        # Least privilege is preserved: the fix must not grant broad monitoring roles
        # to the application identity merely to make a developer smoke test pass.
        for role in ("pg_monitor", "pg_read_all_stats"):
            self.assertNotIn(f"GRANT {role.upper()}", bootstrap.upper())

    def test_schema_diagnostics_never_query_application_objects_in_postgres_database(self) -> None:
        """Regression: PostgreSQL schemas are database-local; IAM/history live in database infranexum."""
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")

        for application_relation in (
            "infranexum_core.schema_history",
            "infranexum_iam.local_account",
            "infranexum_iam.local_session",
        ):
            self.assertNotRegex(
                sh,
                rf"cluster_admin_db_scalar[^\n]*{re.escape(application_relation)}",
            )
            self.assertNotRegex(
                ps,
                rf"Invoke-ClusterDatabaseAdminScalar[^\n]*{re.escape(application_relation)}",
            )

        self.assertRegex(sh, r"application_admin_db_scalar[^\n]*infranexum_core\.schema_history")
        self.assertRegex(ps, r"Invoke-ApplicationDatabaseAdminScalar[^\n]*infranexum_core\.schema_history")

    def test_posix_smoke_observes_replication_with_admin_role_when_application_stats_are_filtered(self) -> None:
        """Execute smoke with PostgreSQL-style statistics filtering for the application role."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            temp = pathlib.Path(temporary_directory)
            bin_dir = temp / "bin"
            bin_dir.mkdir()

            docker = bin_dir / "docker"
            docker.write_text(textwrap.dedent(r'''#!/bin/sh
set -eu
if [ "${1:-}" = compose ]; then
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --env-file|-f) shift 2 ;;
      *) break ;;
    esac
  done
  command=${1:-}; [ "$#" -gt 0 ] && shift || true
  case "$command" in
    version) exit 0 ;;
    ps)
      if [ "${1:-}" = --status ]; then
        printf '%s\n' etcd-1 etcd-2 etcd-3 postgres-1 postgres-2 postgres-3 postgres server-1 server-2 server-3 server-4 server web-1 web-2 web
        exit 0
      fi
      if [ "${1:-}" = -q ]; then printf 'cid-%s\n' "$2"; exit 0; fi
      exit 0 ;;
    port)
      case "$1:$2" in
        postgres:5432) echo '127.0.0.1:5432' ;;
        postgres:5433) echo '127.0.0.1:5433' ;;
        server:8080) echo '127.0.0.1:8080' ;;
        web:8080) echo '127.0.0.1:8081' ;;
        *) exit 1 ;;
      esac
      exit 0 ;;
    run)
      all="$*"
      case "$all" in
        *"SELECT count(*) FROM infranexum_core.schema_history WHERE migration_id IN ('0011','0012','0013')"*) echo 3; exit 0 ;;
        *"to_regclass('infranexum_iam.local_account')"*) echo 1; exit 0 ;;
        *"to_regclass('infranexum_iam.local_session')"*) echo 1; exit 0 ;;
        *"to_regclass('infranexum_iam.iam_user')"*) echo 1; exit 0 ;;
        *"system.platform_admin"*) echo 1; exit 0 ;;
        *"username='admin' AND must_change=TRUE AND status='ACTIVE'"*) echo 0; exit 0 ;;
        *"SELECT count(*) FROM infranexum_iam.local_account"*) echo 1; exit 0 ;;
        *"information_schema.tables"*"local_session"*) echo 1; exit 0 ;;
        *pg_stat_replication*)
          case "$all" in
            *--username=postgres*)
              case "$all" in *sync_state*) echo 1 ;; *) echo 2 ;; esac
              exit 0 ;;
            *--username=infranexum*) echo 0; exit 0 ;;
          esac ;;
      esac
      echo "unexpected compose run: $all" >&2
      exit 70 ;;
  esac
fi
if [ "${1:-}" = inspect ]; then
  case "$*" in *State.Health*) echo healthy; exit 0 ;; esac
fi
echo "unexpected docker invocation: $*" >&2
exit 70
'''), encoding="utf-8")
            docker.chmod(0o755)

            curl = bin_dir / "curl"
            curl.write_text(textwrap.dedent(r'''#!/bin/sh
set -eu
dump=''
correlation=''
output=''
writeout=''
url=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dump-header) dump=$2; shift 2 ;;
    --header) correlation=${2#X-Correlation-ID: }; shift 2 ;;
    --output) output=$2; shift 2 ;;
    --write-out) writeout=$2; shift 2 ;;
    --fail|--silent|--show-error) shift ;;
    *) url=$1; shift ;;
  esac
done
case "$url" in
  */actuator/health/readiness) printf '%s' '{"status":"UP"}' ;;
  */actuator/metrics/infranexum.workers.ready) printf '%s' '{"name":"infranexum.workers.ready"}' ;;
  */api/v1/system/build)
    [ -z "$dump" ] || printf 'HTTP/1.1 200 OK\r\nX-Correlation-ID: %s\r\n\r\n' "$correlation" > "$dump"
    printf '%s' '{"instanceId":"server-pro-2"}' ;;
  */api/v1/iam/organizations?limit=1)
    [ -z "$dump" ] || printf 'HTTP/1.1 401 Unauthorized\r\nX-Correlation-ID: %s\r\n\r\n' "$correlation" > "$dump"
    body=$(printf '{"status":401,"correlation_id":"%s"}' "$correlation")
    [ -z "$output" ] || printf '%s' "$body" > "$output"
    if [ -n "$writeout" ]; then printf '%s' '401'; else printf '%s' "$body"; fi ;;
  */health/ready) printf '%s' '{"status":"UP"}' ;;
  */runtime-config.json) printf '%s' '{"component":"web","version":"2.0.0-alpha.0.124","apiBaseUrl":"/api"}' ;;
  *) echo "unexpected curl URL: $url" >&2; exit 70 ;;
esac
'''), encoding="utf-8")
            curl.chmod(0o755)

            environment = os.environ.copy()
            environment["PATH"] = f"{bin_dir}{os.pathsep}{environment.get('PATH', '')}"
            result = subprocess.run(
                ["sh", str(DOCKER / "dev-compose.sh"), "smoke"],
                cwd=ROOT,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("compose-smoke: PASS (streaming=2 synchronous=1 Server=4 Web=2 LocalAuth=ENFORCED CredentialLogin=SKIPPED_CHANGED)", result.stdout)

    def test_ha_smoke_waits_for_haproxy_writer_after_patroni_election(self) -> None:
        # Regression: Patroni leadership may precede HAProxy writer convergence.
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        self.assertIn("wait_writer_ready()", sh)
        self.assertIn("wait_http_marker()", sh)
        self.assertIn("wait_server_router_ready()", sh)
        self.assertIn("wait_web_router_ready()", sh)
        self.assertIn("wait_writer_ready", sh.split("ha_smoke() {", 1)[1])
        self.assertGreaterEqual(sh.split("ha_smoke() {", 1)[1].count('wait_server_router_ready "$server_port"'), 2)
        self.assertIn('wait_web_router_ready "$web_port"', sh.split("ha_smoke() {", 1)[1])
        self.assertNotIn('test "$(db_scalar \'SELECT 1\')" = 1', sh)
        self.assertIn("function Wait-DatabaseWriterReady", ps)
        self.assertIn("Wait-DatabaseWriterReady -TimeoutSeconds 60 -PollSeconds 2", ps)
        self.assertIn("function Wait-HttpJsonEndpoint", ps)
        self.assertIn("function Wait-ServerRouterReady", ps)
        self.assertIn("function Wait-WebRouterReady", ps)
        self.assertIn("Wait-ServerRouterReady -Port $port -TimeoutSeconds 60 -PollSeconds 2", ps)
        self.assertIn("Wait-ServerRouterReady -Port $serverPort -TimeoutSeconds 60 -PollSeconds 2", ps)
        self.assertIn("Wait-WebRouterReady -Port $webPort -TimeoutSeconds 60 -PollSeconds 2", ps)
        self.assertNotIn("if ((Invoke-DatabaseScalar 'SELECT 1') -ne '1')", ps)

        with tempfile.TemporaryDirectory() as temporary_directory:
            temp = pathlib.Path(temporary_directory)
            bin_dir = temp / "bin"
            state_dir = temp / "state"
            bin_dir.mkdir()
            state_dir.mkdir()
            attempts_file = state_dir / "writer-attempts"
            attempts_file.write_text("0", encoding="utf-8")
            server_attempts_file = state_dir / "server-attempts"
            server_attempts_file.write_text("0", encoding="utf-8")

            docker = bin_dir / "docker"
            docker.write_text(textwrap.dedent(r'''#!/bin/sh
set -eu
state=${INFRANEXUM_TEST_STATE:?}
if [ "${1:-}" = compose ]; then
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --env-file|-f) shift 2 ;;
      *) break ;;
    esac
  done
  command=${1:-}; [ "$#" -gt 0 ] && shift || true
  case "$command" in
    version) exit 0 ;;
    ps)
      if [ "${1:-}" = --status ]; then
        printf '%s\n' etcd-1 etcd-2 etcd-3 postgres-1 postgres-2 postgres-3 postgres server-1 server-2 server-3 server-4 server web-1 web-2 web
        exit 0
      fi
      if [ "${1:-}" = -q ]; then printf 'cid-%s\n' "$2"; exit 0; fi
      exit 0 ;;
    port)
      case "$1:$2" in
        postgres:5432) echo '127.0.0.1:5432' ;;
        postgres:5433) echo '127.0.0.1:5433' ;;
        server:8080) echo '127.0.0.1:8080' ;;
        web:8080) echo '127.0.0.1:8081' ;;
        *) exit 1 ;;
      esac
      exit 0 ;;
    exec)
      service=$2
      case "$*" in
        */primary*)
          if [ -f "$state/failover" ]; then [ "$service" = postgres-2 ] && echo 200 || echo 503
          else [ "$service" = postgres-1 ] && echo 200 || echo 503
          fi
          exit 0 ;;
      esac
      exit 0 ;;
    stop)
      [ "${1:-}" != postgres-1 ] || : > "$state/failover"
      exit 0 ;;
    start) exit 0 ;;
    logs) exit 0 ;;
    run)
      all="$*"
      case "$all" in
        *"SELECT count(*) FROM infranexum_core.schema_history WHERE migration_id IN ('0011','0012','0013')"*) echo 3; exit 0 ;;
        *"to_regclass('infranexum_iam.local_account')"*) echo 1; exit 0 ;;
        *"to_regclass('infranexum_iam.local_session')"*) echo 1; exit 0 ;;
        *"to_regclass('infranexum_iam.iam_user')"*) echo 1; exit 0 ;;
        *"system.platform_admin"*) echo 1; exit 0 ;;
        *"username='admin' AND must_change=TRUE AND status='ACTIVE'"*) echo 0; exit 0 ;;
        *"SELECT count(*) FROM infranexum_iam.local_account"*) echo 1; exit 0 ;;
        *"information_schema.tables"*"local_session"*) echo 1; exit 0 ;;
        *pg_stat_replication*)
          case "$all" in *sync_state*) echo 1 ;; *) echo 2 ;; esac
          exit 0 ;;
        *"SELECT 1"*)
          count=$(cat "$state/writer-attempts")
          count=$((count + 1)); printf '%s' "$count" > "$state/writer-attempts"
          if [ "$count" -lt 3 ]; then
            echo 'psql: error: connection to server at "postgres", port 5432 failed: server closed the connection unexpectedly' >&2
            exit 2
          fi
          echo 1
          exit 0 ;;
      esac
      echo "unexpected compose run: $all" >&2
      exit 70 ;;
  esac
fi
if [ "${1:-}" = inspect ]; then
  case "$*" in *State.Health*) echo healthy; exit 0 ;; esac
fi
echo "unexpected docker invocation: $*" >&2
exit 70
'''), encoding="utf-8")
            docker.chmod(0o755)

            curl = bin_dir / "curl"
            curl.write_text(textwrap.dedent(r'''#!/bin/sh
set -eu
state=${INFRANEXUM_TEST_STATE:?}
dump=''; correlation=''; output=''; writeout=''; url=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dump-header) dump=$2; shift 2 ;;
    --header) correlation=${2#X-Correlation-ID: }; shift 2 ;;
    --output) output=$2; shift 2 ;;
    --write-out) writeout=$2; shift 2 ;;
    --fail|--silent|--show-error) shift ;;
    *) url=$1; shift ;;
  esac
done
case "$url" in
  *127.0.0.1:8080/actuator/health/readiness)
    if [ -f "$state/failover" ]; then
      count=$(cat "$state/server-attempts")
      count=$((count + 1)); printf '%s' "$count" > "$state/server-attempts"
      if [ "$count" -lt 3 ]; then
        echo 'curl: (22) The requested URL returned error: 503' >&2
        exit 22
      fi
    fi
    printf '%s' '{"status":"UP"}' ;;
  */actuator/health/readiness) printf '%s' '{"status":"UP"}' ;;
  */actuator/metrics/infranexum.workers.ready) printf '%s' '{"name":"infranexum.workers.ready"}' ;;
  */api/v1/system/build)
    [ -z "$dump" ] || printf 'HTTP/1.1 200 OK\r\nX-Correlation-ID: %s\r\n\r\n' "$correlation" > "$dump"
    printf '%s' '{"instanceId":"server-pro-2"}' ;;
  */api/v1/iam/organizations?limit=1)
    [ -z "$dump" ] || printf 'HTTP/1.1 401 Unauthorized\r\nX-Correlation-ID: %s\r\n\r\n' "$correlation" > "$dump"
    body=$(printf '{"status":401,"correlation_id":"%s"}' "$correlation")
    [ -z "$output" ] || printf '%s' "$body" > "$output"
    if [ -n "$writeout" ]; then printf '%s' '401'; else printf '%s' "$body"; fi ;;
  */health/ready) printf '%s' '{"status":"UP"}' ;;
  */runtime-config.json) printf '%s' '{"component":"web","version":"2.0.0-alpha.0.124","apiBaseUrl":"/api"}' ;;
  *) echo "unexpected curl URL: $url" >&2; exit 70 ;;
esac
'''), encoding="utf-8")
            curl.chmod(0o755)

            sleep = bin_dir / "sleep"
            sleep.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            sleep.chmod(0o755)

            environment = os.environ.copy()
            environment["PATH"] = f"{bin_dir}{os.pathsep}{environment.get('PATH', '')}"
            environment["INFRANEXUM_TEST_STATE"] = str(state_dir)
            result = subprocess.run(
                ["sh", str(DOCKER / "dev-compose.sh"), "ha-smoke"],
                cwd=ROOT,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("3", attempts_file.read_text(encoding="utf-8"))
            self.assertGreaterEqual(int(server_attempts_file.read_text(encoding="utf-8")), 3)
            self.assertIn("compose-ha-smoke: PASS", result.stdout)

    def test_patroni_health_probes_are_header_only_and_ha_smoke_rejects_python_transport_tracebacks(self) -> None:
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        compose = (DOCKER / "compose.yaml").read_text(encoding="utf-8")
        haproxy = (DOCKER / "haproxy-postgres.cfg").read_text(encoding="utf-8")

        self.assertIn("option httpchk HEAD /primary", haproxy)
        self.assertIn("option httpchk HEAD /replica", haproxy)
        self.assertIn("curl --fail --silent --show-error --head", compose)
        self.assertIn("curl --silent --head --output /dev/null", sh)
        self.assertIn("curl --silent --head --output /dev/null", ps)
        for text in (sh, ps):
            self.assertIn("ConnectionResetError:", text)
            self.assertIn("BrokenPipeError:", text)
            self.assertIn("Traceback", text)
            self.assertIn("PatroniPythonErrors=0", text)

    def test_smoke_proves_bootstrap_credential_login_when_password_is_still_unmodified(self) -> None:
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        for text in (sh, ps):
            self.assertIn("must_change", text)
            self.assertIn("INX_SESSION", text)
            self.assertIn("INX_XSRF", text)
            self.assertIn("/api/v1/iam/local-auth/session", text)
            self.assertIn("CredentialLogin", text)

    def test_ha_smoke_http_router_retries_are_bounded_and_idempotent(self) -> None:
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        self.assertIn('while [ "$http_attempts" -lt 30 ]', sh)
        self.assertIn("HTTP probe failed without diagnostic output", sh)
        self.assertIn("did not recover within 60 seconds. Last diagnostic:", sh)
        self.assertIn("Invoke-RestMethod -Uri $Uri", ps)
        self.assertIn("AddSeconds($TimeoutSeconds)", ps)
        self.assertIn("did not recover within $TimeoutSeconds seconds. Last diagnostic:", ps)
        http_helper = ps.split("function Wait-HttpJsonEndpoint", 1)[1].split("function Wait-ServerRouterReady", 1)[0]
        self.assertNotIn("Invoke-WebRequest", http_helper)
        self.assertNotIn("-Method Post", http_helper)

    def test_ha_smoke_is_bounded_and_rejoins_primary(self) -> None:
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        for text in (sh, ps):
            self.assertIn("ha-smoke", text)
            self.assertIn("/primary", text)
            self.assertIn("stop", text)
            self.assertIn("start", text)
        self.assertIn('"$attempts" -lt 30', sh)
        self.assertIn("AddSeconds(60)", ps)
        self.assertIn("AddSeconds(90)", ps)

    def test_ha_smoke_does_not_delete_volumes(self) -> None:
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        block = sh.split("ha_smoke() {", 1)[1].split("\nrestore() {", 1)[0]
        self.assertNotIn("--volumes", block)
        self.assertNotIn("volume rm", block)

    def test_ha_smoke_verifies_server_and_web_node_failover(self) -> None:
        for path in (DOCKER / "dev-compose.sh", DOCKER / "dev-compose.ps1"):
            text = path.read_text(encoding="utf-8")
            self.assertIn("Stopping Server node server-1", text)
            self.assertIn("Stopping Web node web-1", text)
            self.assertIn("runtime-config.json", text)
            self.assertIn("Server and Web node failover verified", text)

    def test_backup_restore_and_rollback_are_cluster_aware(self) -> None:
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        self.assertIn("--host=postgres", sh)
        self.assertIn("db-admin", sh)
        self.assertIn("CONFIRM_INFRANEXUM_RESTORE", sh)
        self.assertIn("CONFIRM_INFRANEXUM_ROLLBACK", sh)
        self.assertIn("server-1 server-2 server-3 server-4", sh)

    def test_volume_delete_remains_explicitly_confirmed(self) -> None:
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        self.assertIn("CONFIRM_INFRANEXUM_VOLUME_DELETE", sh)
        self.assertIn("compose down --volumes --remove-orphans", sh)

    def test_powershell_compose_wrappers_keep_native_switches_out_of_parameter_binding(self) -> None:
        """Regression: native ``-e`` must not bind to PowerShell common parameters."""
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        invoke_block = ps.split("function Invoke-Compose {", 1)[1].split("function Invoke-ComposeCapture {", 1)[0]
        capture_block = ps.split("function Invoke-ComposeCapture {", 1)[1].split("function Assert-Repository {", 1)[0]
        for block in (invoke_block, capture_block):
            self.assertNotIn("param(", block)
            self.assertNotIn("@Arguments", block)
        self.assertIn("@args", invoke_block)
        self.assertIn("@($args)", capture_block)
        self.assertIn("ArgumentList.Add", capture_block)
        self.assertIn('run --rm --no-deps -e "INFRANEXUM_SQL=$Sql"', ps)
        self.assertIn('-e "MIGRATION_ID=$($env:MIGRATION_ID)" -e CONFIRM_INFRANEXUM_ROLLBACK=YES', ps)

    def test_powershell_capture_keeps_compose_progress_off_stdout(self) -> None:
        """Regression: Compose lifecycle stderr must not corrupt SQL scalar stdout."""
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        block = ps.split("function Invoke-ComposeCapture {", 1)[1].split("function Assert-Repository {", 1)[0]
        self.assertIn("RedirectStandardOutput = $true", block)
        self.assertIn("RedirectStandardError = $true", block)
        self.assertIn("ReadToEndAsync()", block)
        self.assertIn("$process.ExitCode", block)
        self.assertIn("Write-Verbose $stderr.Trim()", block)
        self.assertIn('$stdout -split "`r?`n"', block)
        self.assertNotIn("2>&1", block)

    def test_smoke_captures_expected_401_as_response_and_validates_correlation_contract(self) -> None:
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        self.assertIn("PowerShell 7 or later is required", ps)
        self.assertIn("-SkipHttpErrorCheck", ps)
        self.assertIn("$anonymousResponse.StatusCode", ps)
        self.assertIn("$anonymousResponse.Headers['X-Correlation-ID']", ps)
        self.assertIn("$problemCorrelationProperty", ps)
        self.assertIn("$problemCorrelation", ps)
        self.assertIn("body='$anonymousRawBody'", ps)
        self.assertIn("function Convert-WebResponseContentToText", ps)
        self.assertIn("$Content -is [byte[]]", ps)
        self.assertIn("[System.Text.Encoding]::UTF8.GetString($Content)", ps)
        self.assertIn("Convert-WebResponseContentToText $anonymousResponse.Content", ps)
        self.assertNotIn("$anonymousRawBody = [string]$anonymousResponse.Content", ps)
        smoke_block = ps.split("function Invoke-Smoke", 1)[1].split("function Get-PatroniPrimaryService", 1)[0]
        self.assertNotIn("Exception.Response.Headers", smoke_block)
        self.assertIn('grep -Eiq "^X-Correlation-ID:', sh)
        self.assertIn('grep -Fq \"\\\"correlation_id\\\":\\\"$correlation_id\\\"\"', sh)
        auth_filter = (ROOT / "src/applications/server/main/io/infranexum/server/identity/LocalAuthenticationFilter.java").read_text(encoding="utf-8")
        problem_support = (ROOT / "src/applications/server/main/io/infranexum/server/http/ApiProblemSupport.java").read_text(encoding="utf-8")
        problem_model = (ROOT / "src/applications/server/main/io/infranexum/server/http/ApiProblem.java").read_text(encoding="utf-8")
        self.assertIn("ApiProblemSupport", auth_filter)
        self.assertIn("problems.write", auth_filter)
        self.assertIn("response.resetBuffer()", problem_support)
        self.assertIn("response.setContentLength(body.length)", problem_support)
        self.assertIn("response.flushBuffer()", problem_support)
        self.assertIn("correlation_id", problem_model)
        self.assertIn("trace_id", problem_model)

    def test_powershell_http_body_normalizer_decodes_binary_content_before_json_parsing(self) -> None:
        """Regression: PowerShell may expose application/problem+json as bytes."""
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        block = ps.split("function Convert-WebResponseContentToText {", 1)[1].split("function Assert-Repository {", 1)[0]
        self.assertIn("$Content -is [byte[]]", block)
        self.assertIn("[System.Text.Encoding]::UTF8.GetString($Content)", block)
        self.assertIn("$Content -is [System.Net.Http.HttpContent]", block)
        self.assertIn("$Content -is [System.IO.Stream]", block)
        self.assertIn("$allBytes", block)
        self.assertIn("[byte[]]$items", block)
        self.assertNotIn("return ($Content | Out-String)", block)

    def test_powershell_sql_uses_environment_transport_not_nested_escaping(self) -> None:
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        block = ps.split("function Invoke-DatabaseScalar", 1)[1].split("function New-DatabaseBackup", 1)[0]
        self.assertIn("INFRANEXUM_SQL=$Sql", block)
        self.assertIn('$INFRANEXUM_SQL', block)
        self.assertNotIn("$Sql.Replace", block)

    def test_powershell_health_failure_distinguishes_missing_container_from_unhealthy_container(self) -> None:
        """Regression: a failed build must not be reported as health=unknown."""
        powershell = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        self.assertIn("Service $Service is not running: no container exists", powershell)
        self.assertIn("resolve any build/start failure first", powershell)
        self.assertIn("Service $Service is not healthy (container=$cid health=$health)", powershell)

    def test_powershell_has_no_ambiguous_variable_colon_interpolation(self) -> None:
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        self.assertEqual([], re.findall(r'(?<!\\)\$(?!env:)([A-Za-z_][A-Za-z0-9_]*):', ps))

    def test_env_example_only_exposes_router_ports(self) -> None:
        env = (DOCKER / ".env.example").read_text(encoding="utf-8")
        self.assertIn("INFRANEXUM_POSTGRES_PUBLISHED_PORT=5432", env)
        self.assertIn("INFRANEXUM_POSTGRES_READ_PUBLISHED_PORT=5433", env)
        self.assertIn("INFRANEXUM_SERVER_PUBLISHED_PORT=8080", env)
        self.assertIn("INFRANEXUM_WEB_PUBLISHED_PORT=8081", env)
        self.assertNotIn("SERVER_1_PUBLISHED_PORT", env)
        self.assertNotIn("WEB_1_PUBLISHED_PORT", env)

    def test_root_compose_loader_remains_canonical(self) -> None:
        loader = yaml.safe_load(ROOT_COMPOSE.read_text(encoding="utf-8"))
        self.assertEqual("infranexum-dev", loader["name"])
        self.assertEqual(["docker/compose.yaml"], loader["include"])

    def test_all_posix_scripts_parse(self) -> None:
        for name in ("dev-compose.sh", "init-secrets.sh", "bootstrap-postgresql-ha.sh", "migrate-postgresql.sh", "rollback-postgresql.sh", "patroni-entrypoint.sh", "server-entrypoint.sh"):
            result = subprocess.run(["sh", "-n", str(DOCKER / name)], capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, f"{name}: {result.stderr}")


    def test_migration_runner_is_catalogue_driven_and_covers_repair_migration(self) -> None:
        script = (DOCKER / "migrate-postgresql.sh").read_text(encoding="utf-8")
        self.assertIn('catalogue="$migration_root/catalogue.yaml"', script)
        self.assertIn("awk '$1 == \"path:\" { print $2 }'", script)
        self.assertIn('migration directory is not declared in catalogue'.lower(), script.lower())
        self.assertNotIn('"$migration_root"/000*', script)
        self.assertTrue((ROOT / "src/distribution/migrations/0011-local-identity-foundation").is_dir())
        self.assertTrue((ROOT / "src/distribution/migrations/0012-local-identity-repair").is_dir())

    def test_developer_up_replays_one_shot_bootstrap_without_deleting_volumes(self) -> None:
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        self.assertIn('rm --stop --force migrate db-bootstrap secret-init', ps)
        self.assertIn('rm --stop --force migrate db-bootstrap secret-init', sh)
        for text in (ps, sh):
            up_line = next(line for line in text.splitlines() if "'up'" in line or line.strip().startswith('up)'))
            self.assertNotIn('--volumes', up_line)

    def test_developer_seed_is_separate_from_migrations_read_only_and_runs_after_up(self) -> None:
        seed = DOCKER / "dev-seed-postgresql.sql"
        self.assertTrue(seed.is_file())
        self.assertFalse(str(seed).startswith(str(ROOT / "src/distribution/migrations")))
        self.assertEqual(
            ["runtime-secrets:/run/infranexum-secrets:ro", "./dev-seed-postgresql.sql:/opt/infranexum/dev-seed-postgresql.sql:ro"],
            self.services["db-admin"]["volumes"],
        )

        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        self.assertIn("'seed' { Invoke-DeveloperSeedData }", ps)
        self.assertIn("seed) seed_dev_data ;;", sh)
        ps_up = next(line for line in ps.splitlines() if line.lstrip().startswith("'up'"))
        sh_up = next(line for line in sh.splitlines() if line.strip().startswith("up)"))
        self.assertLess(ps_up.index("--wait web"), ps_up.index("Invoke-DeveloperSeedData"))
        self.assertLess(sh_up.index("--wait web"), sh_up.index("seed_dev_data"))

    def test_developer_seed_is_idempotent_non_destructive_and_uses_application_role(self) -> None:
        seed = (DOCKER / "dev-seed-postgresql.sql").read_text(encoding="utf-8")
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")

        self.assertIn("pg_advisory_xact_lock", seed)
        self.assertGreaterEqual(seed.count("ON CONFLICT DO NOTHING"), 20)
        self.assertNotRegex(seed.upper(), r"\b(?:DELETE|TRUNCATE|DROP)\b")
        self.assertNotIn("infranexum_iam.local_account", seed)
        self.assertNotRegex(seed, r"(?i)password|private[_ -]?key|secret")
        for wrapper in (ps, sh):
            seed_lines = "\n".join(line for line in wrapper.splitlines() if "dev-seed-postgresql.sql" in line)
            self.assertIn("--username=infranexum", seed_lines)
            self.assertNotIn("--username=postgres", seed_lines)
            self.assertIn("--set=ON_ERROR_STOP=1", seed_lines)

    def test_developer_seed_covers_current_operator_workspaces_without_real_credentials(self) -> None:
        seed = (DOCKER / "dev-seed-postgresql.sql").read_text(encoding="utf-8")
        for table in (
            "infranexum_org.organization",
            "infranexum_org.subdivision",
            "infranexum_iam.iam_user",
            "infranexum_iam.user_membership",
            "infranexum_rsot.canonical_object",
            "infranexum_core.schema_registry_entry",
            "infranexum_itam.partner",
            "infranexum_itam.asset",
            "infranexum_dcim.facility_node",
            "infranexum_dcim.rack",
            "infranexum_dcim.equipment",
            "infranexum_ddi.ipam_network",
            "infranexum_ddi.ipam_address",
            "infranexum_integrations.connector_inbox",
        ):
            self.assertIn(table, seed)
        self.assertIn("@demo.invalid", seed)
        self.assertNotIn("INSERT INTO infranexum_iam.local_account", seed)

    def test_admin_reactivation_is_bounded_auditable_and_never_grants_missing_role(self) -> None:
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")

        for wrapper in (ps, sh):
            self.assertIn("admin-reactivate", wrapper)
            self.assertIn("UPDATE infranexum_iam.local_account", wrapper)
            self.assertIn("status='ACTIVE'", wrapper)
            self.assertIn("failed_attempts=0", wrapper)
            self.assertIn("locked_until=NULL", wrapper)
            self.assertIn("security_epoch=security_epoch+1", wrapper)
            self.assertIn("UPDATE infranexum_iam.iam_user", wrapper)
            self.assertIn("system.platform_admin", wrapper)
            self.assertIn("ROLE_MISSING", wrapper)
            self.assertNotIn("INSERT INTO infranexum_iam.role_assignment", wrapper)

    def test_required_docker_files_exist_and_are_executable_where_applicable(self) -> None:
        for name in ("patroni-postgres.Dockerfile", "web.Dockerfile", "haproxy-postgres.cfg", "haproxy-server.cfg", "haproxy-web.cfg"):
            self.assertTrue((DOCKER / name).is_file(), name)
        for name in ("dev-compose.sh", "init-secrets.sh", "bootstrap-postgresql-ha.sh", "migrate-postgresql.sh", "rollback-postgresql.sh", "patroni-entrypoint.sh", "server-entrypoint.sh"):
            self.assertTrue((DOCKER / name).stat().st_mode & 0o111, name)

    def test_no_latest_image_tags(self) -> None:
        self.assertNotIn(":latest", self.text)

    def test_docker_context_excludes_non_runtime_repository_content(self) -> None:
        ignored = set((ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines())
        for path in ("tests", "validation", "docs", "tools", "requirements", "artifacts", ".git", ".github"):
            self.assertIn(path, ignored)

    def test_patroni_entrypoint_repairs_pgdata_mode_before_exec(self) -> None:
        entrypoint = (ROOT / "docker/patroni-entrypoint.sh").read_text(encoding="utf-8")
        self.assertIn('chmod 0700 "$data_dir"', entrypoint)
        self.assertIn('chown -R postgres:postgres /var/lib/postgresql/data', entrypoint)
        self.assertLess(
            entrypoint.index('chmod 0700 "$data_dir"'),
            entrypoint.index('exec su-exec postgres'),
        )



    def test_local_authentication_is_enabled_only_for_developer_pro_topology(self) -> None:
        for name in SERVER:
            env = self.services[name]["environment"]
            self.assertEqual("true", env["INFRANEXUM_LOCAL_AUTH_ENABLED"])
            self.assertEqual("false", env["INFRANEXUM_LOCAL_AUTH_COOKIE_SECURE"])
            self.assertEqual("admin", env["INFRANEXUM_BOOTSTRAP_ADMIN_USERNAME"])
            self.assertEqual("/run/infranexum-secrets/local-admin-password", env["INFRANEXUM_BOOTSTRAP_ADMIN_PASSWORD_FILE"])
        for name in WEB:
            self.assertEqual("true", self.services[name]["environment"]["INFRANEXUM_WEB_LOCAL_AUTH_ENABLED"])

    def test_local_admin_bootstrap_secret_is_generated_in_runtime_secret_volume(self) -> None:
        script = (DOCKER / "init-secrets.sh").read_text(encoding="utf-8")
        self.assertIn("local-admin-password", script)
        self.assertIn('chmod 0444 "$tmp"', script)
        self.assertNotIn("echo $local_admin_password", script)
        self.assertNotIn("set -x", script)

    def test_credentials_command_is_explicit_and_never_part_of_smoke_output(self) -> None:
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        for text in (ps, sh):
            self.assertIn("credentials", text)
            self.assertIn("local-admin-password", text)
            self.assertIn("Password:", text)
            self.assertIn("must be changed at first sign-in", text)
        self.assertNotIn("local-admin-password", "\n".join(line for line in ps.splitlines() if "compose-smoke: PASS" in line))
        self.assertNotIn("local-admin-password", "\n".join(line for line in sh.splitlines() if "compose-smoke: PASS" in line))

    def test_smoke_proves_local_authentication_boundary_is_enforced(self) -> None:
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        sh = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        for text in (ps, sh):
            self.assertIn("LocalAuth=ENFORCED", text)
            self.assertIn("/api/v1/iam/organizations?limit=1", text)
            self.assertIn("401", text)
        self.assertTrue((ROOT / "src/distribution/migrations/0011-local-identity-foundation").is_dir())

    def test_local_auth_openapi_documents_cookie_and_csrf_boundaries(self) -> None:
        spec = yaml.safe_load((ROOT / "src/applications/server/resources/openapi/local-auth.yaml").read_text(encoding="utf-8"))
        self.assertIn("/api/v1/iam/local-auth/session", spec["paths"])
        self.assertEqual("INX_SESSION", spec["components"]["securitySchemes"]["LocalSessionCookie"]["name"])
        self.assertEqual("X-CSRF-Token", spec["components"]["parameters"]["CsrfToken"]["name"])
        self.assertIn("writeOnly", str(spec["components"]["schemas"]["LoginRequest"]))


if __name__ == "__main__":
    unittest.main()
