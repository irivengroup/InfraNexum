from __future__ import annotations

import pathlib
import re
import subprocess
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOCKER = ROOT / "docker"
COMPOSE = DOCKER / "compose.yaml"
ROOT_COMPOSE = ROOT / "compose.yaml"
ETCD = ("etcd-1", "etcd-2", "etcd-3")
PG = ("postgres-1", "postgres-2", "postgres-3")
SERVER = ("server-1", "server-2", "server-3", "server-4")


class ComposeContractTest(unittest.TestCase):
    """Protect the PRO Docker/Compose HA topology and its safety boundaries."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = COMPOSE.read_text(encoding="utf-8")
        cls.doc = yaml.safe_load(cls.text)
        cls.services = cls.doc["services"]

    def test_exact_pro_service_set(self) -> None:
        expected = {"secret-init", *ETCD, *PG, "postgres", "db-bootstrap", "migrate", *SERVER, "server", "db-admin", "rollback"}
        self.assertEqual(expected, set(self.services))

    def test_three_postgres_and_four_server_nodes(self) -> None:
        self.assertTrue(all(name in self.services for name in PG))
        self.assertTrue(all(name in self.services for name in SERVER))

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

    def test_web_cluster_is_deferred(self) -> None:
        self.assertFalse(any(name == "web" or name.startswith("web-") for name in self.services))
        self.assertIn("Web cluster is intentionally deferred", (DOCKER / "README.md").read_text(encoding="utf-8"))

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

    def test_patroni_and_etcd_have_separate_persistent_volumes(self) -> None:
        expected = {*(f"etcd-{i}-data" for i in range(1, 4)), *(f"postgres-{i}-data" for i in range(1, 4)), "runtime-secrets"}
        self.assertEqual(expected, set(self.doc["volumes"]))

    def test_raw_cluster_nodes_are_not_host_published(self) -> None:
        for name in (*ETCD, *PG, *SERVER, "secret-init", "db-bootstrap", "migrate", "db-admin", "rollback"):
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
        self.assertIn("option httpchk GET /primary", cfg)
        self.assertIn("option httpchk GET /replica", cfg)
        self.assertIn("on-marked-down shutdown-sessions", cfg)

    def test_server_haproxy_routes_four_readiness_healthy_nodes(self) -> None:
        cfg = (DOCKER / "haproxy-server.cfg").read_text(encoding="utf-8")
        self.assertEqual("haproxy:3.2.21-alpine", self.services["server"]["image"])
        self.assertIn("balance roundrobin", cfg)
        self.assertIn("/actuator/health/readiness", cfg)
        for name in SERVER:
            self.assertIn(name, cfg)

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

    def test_smoke_requires_cluster_health_replication_and_worker_metric(self) -> None:
        for path in (DOCKER / "dev-compose.sh", DOCKER / "dev-compose.ps1"):
            text = path.read_text(encoding="utf-8")
            self.assertIn("pg_stat_replication", text)
            self.assertIn("sync_state", text)
            self.assertIn("infranexum.workers.ready", text)
            for name in (*ETCD, *PG, "postgres", *SERVER, "server"):
                self.assertIn(name, text)

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

    def test_powershell_sql_uses_environment_transport_not_nested_escaping(self) -> None:
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        block = ps.split("function Invoke-DatabaseScalar", 1)[1].split("function New-DatabaseBackup", 1)[0]
        self.assertIn("INFRANEXUM_SQL=$Sql", block)
        self.assertIn('$INFRANEXUM_SQL', block)
        self.assertNotIn("$Sql.Replace", block)

    def test_powershell_has_no_ambiguous_variable_colon_interpolation(self) -> None:
        ps = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        self.assertEqual([], re.findall(r'(?<!\\)\$(?!env:)([A-Za-z_][A-Za-z0-9_]*):', ps))

    def test_env_example_only_exposes_router_ports(self) -> None:
        env = (DOCKER / ".env.example").read_text(encoding="utf-8")
        self.assertIn("INFRANEXUM_POSTGRES_PUBLISHED_PORT=5432", env)
        self.assertIn("INFRANEXUM_POSTGRES_READ_PUBLISHED_PORT=5433", env)
        self.assertIn("INFRANEXUM_SERVER_PUBLISHED_PORT=8080", env)
        self.assertNotIn("SERVER_1_PUBLISHED_PORT", env)

    def test_root_compose_loader_remains_canonical(self) -> None:
        loader = yaml.safe_load(ROOT_COMPOSE.read_text(encoding="utf-8"))
        self.assertEqual("infranexum-dev", loader["name"])
        self.assertEqual(["docker/compose.yaml"], loader["include"])

    def test_all_posix_scripts_parse(self) -> None:
        for name in ("dev-compose.sh", "init-secrets.sh", "bootstrap-postgresql-ha.sh", "migrate-postgresql.sh", "rollback-postgresql.sh", "patroni-entrypoint.sh", "server-entrypoint.sh"):
            result = subprocess.run(["sh", "-n", str(DOCKER / name)], capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, f"{name}: {result.stderr}")

    def test_required_docker_files_exist_and_are_executable_where_applicable(self) -> None:
        for name in ("patroni-postgres.Dockerfile", "haproxy-postgres.cfg", "haproxy-server.cfg"):
            self.assertTrue((DOCKER / name).is_file(), name)
        for name in ("dev-compose.sh", "init-secrets.sh", "bootstrap-postgresql-ha.sh", "migrate-postgresql.sh", "rollback-postgresql.sh", "patroni-entrypoint.sh", "server-entrypoint.sh"):
            self.assertTrue((DOCKER / name).stat().st_mode & 0o111, name)

    def test_no_latest_image_tags(self) -> None:
        self.assertNotIn(":latest", self.text)

    def test_docker_context_excludes_non_runtime_repository_content(self) -> None:
        ignored = set((ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines())
        for path in ("tests", "validation", "docs", "tools", "requirements", "artifacts", ".git", ".github"):
            self.assertIn(path, ignored)


if __name__ == "__main__":
    unittest.main()
