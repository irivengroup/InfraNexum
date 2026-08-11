from __future__ import annotations
import pathlib
import unittest
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "src/deployment/docker/compose.yaml"

class ComposeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
        cls.services = cls.document["services"]

    def test_required_services_and_private_network_exist(self):
        self.assertEqual({"secret-init", "postgres", "migrate", "server", "rollback"}, set(self.services))
        self.assertTrue(self.document["networks"]["backend"]["internal"])
        self.assertEqual({"postgres-data", "runtime-secrets", "integrity-proof"}, set(self.document["volumes"]))

    def test_server_is_ordered_after_health_and_one_shot_initializers(self):
        deps = self.services["server"]["depends_on"]
        self.assertEqual("service_completed_successfully", deps["secret-init"]["condition"])
        self.assertEqual("service_healthy", deps["postgres"]["condition"])
        self.assertEqual("service_completed_successfully", deps["migrate"]["condition"])
        self.assertIn("healthcheck", self.services["server"])
        self.assertIn("healthcheck", self.services["postgres"])

    def test_images_are_pinned_and_server_is_non_memory_postgresql(self):
        text = COMPOSE.read_text(encoding="utf-8")
        self.assertIn("postgres:17.10-alpine3.24", text)
        self.assertNotIn(":latest", text)
        env = self.services["server"]["environment"]
        self.assertEqual("POSTGRESQL", env["INFRANEXUM_PERSISTENCE_MODE"])
        self.assertEqual("true", env["INFRANEXUM_ENTITLEMENTS_ENABLED"])
        self.assertEqual("0.0.0.0", env["INFRANEXUM_SERVER_ADDRESS"])

    def test_destructive_rollback_is_isolated_behind_maintenance_profile(self):
        self.assertEqual(["maintenance"], self.services["rollback"]["profiles"])
        self.assertEqual("no", self.services["rollback"]["restart"])


    def test_migrator_bootstraps_identity_and_rollback_finds_nested_sql(self):
        migrate = (ROOT / "src/deployment/docker/migrate-postgresql.sh").read_text(encoding="utf-8")
        rollback = (ROOT / "src/deployment/docker/rollback-postgresql.sh").read_text(encoding="utf-8")
        self.assertIn("FROM core_installation_identity \\gset", migrate)
        self.assertIn("pg_advisory_xact_lock(723091144)", migrate)
        self.assertIn("--set=installation_id=", migrate)
        self.assertIn("--file=\"$identity_sql\"", migrate)
        self.assertNotIn("--command \"INSERT INTO core_installation_identity", migrate)
        self.assertIn("-maxdepth 3", rollback)
        self.assertIn("rollback/postgresql.sql", rollback)
        self.assertLess(
            rollback.index("DELETE FROM infranexum_core.schema_history"),
            rollback.index(r'echo "    \\i $rollback_file"'),
        )

    def test_rollback_is_fail_closed_and_does_not_restart_current_server(self):
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        rollback_block = makefile.split("compose-rollback:", 1)[1].split("\ncompose-reset:", 1)[0]
        self.assertIn("CONFIRM_INFRANEXUM_ROLLBACK", rollback_block)
        self.assertIn("$(MAKE) compose-backup", rollback_block)
        self.assertIn("up --detach --wait postgres", rollback_block)
        self.assertNotIn("up --detach --wait server", rollback_block)
        self.assertIn("Server remains stopped", rollback_block)




    def test_docker_build_context_excludes_non_product_validation_content(self):
        ignored = set((ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines())
        for path in ("tests", "validation", "docs", "tools", "requirements", "artifacts", ".git", ".github"):
            self.assertIn(path, ignored)

    def test_server_image_pins_exact_temurin_archives_and_runs_non_root(self):
        dockerfile = (ROOT / "src/deployment/docker/server.Dockerfile").read_text(encoding="utf-8")
        self.assertIn("INFRANEXUM_EXPECTED_JAVA_VERSION=25.0.4", dockerfile)
        self.assertIn("TEMURIN_RELEASE=25.0.4_7", dockerfile)
        self.assertIn("OpenJDK25U-jdk_x64_linux_hotspot_${TEMURIN_RELEASE}.tar.gz", dockerfile)
        self.assertIn("OpenJDK25U-jre_x64_linux_hotspot_${TEMURIN_RELEASE}.tar.gz", dockerfile)
        self.assertIn("sha256sum --check --strict", dockerfile)
        self.assertIn("Unsupported Docker architecture", dockerfile)
        self.assertGreaterEqual(dockerfile.count('java -version 2>&1 | grep -F "${INFRANEXUM_EXPECTED_JAVA_VERSION}"'), 2)
        self.assertIn("USER 10001:10001", dockerfile)

    def test_restore_can_start_from_a_stopped_topology_before_recreating_database(self):
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        restore = makefile.split("compose-restore:", 1)[1].split("\ncompose-rollback:", 1)[0]
        self.assertIn("up --detach --wait postgres", restore)
        self.assertIn("pg_restore --exit-on-error", restore)
        self.assertIn("run --rm migrate", restore)
        self.assertIn("up --detach --wait server", restore)

    def test_server_defaults_do_not_encode_optional_maps_as_empty_scalars(self):
        application = yaml.safe_load((ROOT / "src/applications/server/resources/application.yaml").read_text(encoding="utf-8"))
        platform = application["infranexum"]["platform"]
        self.assertNotIn("dependencies", platform)
        self.assertNotIn("quota-overrides", platform)

    def test_deployment_manifest_is_active_and_readme_advertises_compose(self):
        import json
        manifest = json.loads((ROOT / "src/deployment/MANIFEST.json").read_text(encoding="utf-8"))
        self.assertEqual("active", manifest["lifecycle"])
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("reference Docker Compose topology", readme)
        self.assertNotIn("Docker Compose is deliberately not shipped yet", readme)

    def test_product_scripts_are_executable_and_tests_live_outside_src(self):
        for name in ("init-secrets.sh", "migrate-postgresql.sh", "rollback-postgresql.sh", "server-entrypoint.sh"):
            path = ROOT / "src/deployment/docker" / name
            self.assertTrue(path.exists())
            self.assertTrue(path.stat().st_mode & 0o111)
        self.assertFalse(any((ROOT / "src").rglob("test_compose_contract.py")))

if __name__ == "__main__":
    unittest.main()
