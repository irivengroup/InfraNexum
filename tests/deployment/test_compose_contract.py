from __future__ import annotations

import pathlib
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOCKER = ROOT / "docker"
COMPOSE = DOCKER / "compose.yaml"


class ComposeContractTest(unittest.TestCase):
    """Protect the developer Compose topology without making it a production dependency."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.document = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
        cls.services = cls.document["services"]

    def test_root_location_and_required_services(self) -> None:
        self.assertTrue(COMPOSE.is_file())
        self.assertFalse((ROOT / "src/deployment/docker").exists())
        self.assertEqual({"secret-init", "postgres", "migrate", "server", "rollback"}, set(self.services))
        self.assertEqual({"postgres-data", "runtime-secrets", "integrity-proof"}, set(self.document["volumes"]))
        self.assertTrue(self.document["networks"]["backend"]["internal"])

    def test_server_starts_only_after_initialization_database_health_and_migrations(self) -> None:
        dependencies = self.services["server"]["depends_on"]
        self.assertEqual("service_completed_successfully", dependencies["secret-init"]["condition"])
        self.assertEqual("service_healthy", dependencies["postgres"]["condition"])
        self.assertEqual("service_completed_successfully", dependencies["migrate"]["condition"])
        self.assertIn("healthcheck", self.services["postgres"])
        self.assertIn("healthcheck", self.services["server"])

    def test_images_are_pinned_and_no_latest_tag_is_used(self) -> None:
        text = COMPOSE.read_text(encoding="utf-8")
        self.assertIn("postgres:17.10-alpine3.24", text)
        self.assertNotIn(":latest", text)
        self.assertEqual("POSTGRESQL", self.services["server"]["environment"]["INFRANEXUM_PERSISTENCE_MODE"])

    def test_both_dockerfiles_are_real_compose_build_inputs(self) -> None:
        text = COMPOSE.read_text(encoding="utf-8")
        self.assertIn("docker/server.Dockerfile", text)
        self.assertIn("docker/postgres-tools.Dockerfile", text)
        for name in ("server.Dockerfile", "postgres-tools.Dockerfile"):
            self.assertTrue((DOCKER / name).is_file())

    def test_server_image_pins_java_archives_and_runs_non_root(self) -> None:
        dockerfile = (DOCKER / "server.Dockerfile").read_text(encoding="utf-8")
        self.assertIn("INFRANEXUM_EXPECTED_JAVA_VERSION=25.0.4", dockerfile)
        self.assertIn("TEMURIN_RELEASE=25.0.4_7", dockerfile)
        self.assertIn("sha256sum --check --strict", dockerfile)
        self.assertIn("USER 10001:10001", dockerfile)
        self.assertIn("COPY --chown=10001:10001 docker/server-entrypoint.sh", dockerfile)

    def test_postgres_tools_image_bakes_migration_and_secret_scripts(self) -> None:
        dockerfile = (DOCKER / "postgres-tools.Dockerfile").read_text(encoding="utf-8")
        self.assertIn("FROM postgres:17.10-alpine3.24", dockerfile)
        for name in ("init-secrets.sh", "migrate-postgresql.sh", "rollback-postgresql.sh"):
            self.assertIn(f"docker/{name}", dockerfile)

    def test_scripts_are_executable_and_posix_entrypoints_are_declared(self) -> None:
        for name in (
            "dev-compose.sh",
            "init-secrets.sh",
            "migrate-postgresql.sh",
            "rollback-postgresql.sh",
            "server-entrypoint.sh",
        ):
            path = DOCKER / name
            self.assertTrue(path.stat().st_mode & 0o111, name)

    def test_server_is_published_on_loopback_only_by_default(self) -> None:
        self.assertEqual(
            ["127.0.0.1:${INFRANEXUM_SERVER_PUBLISHED_PORT:-8080}:8080"],
            self.services["server"]["ports"],
        )

    def test_rollback_remains_maintenance_only_and_fail_closed(self) -> None:
        self.assertEqual(["maintenance"], self.services["rollback"]["profiles"])
        shell = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        self.assertIn("CONFIRM_INFRANEXUM_ROLLBACK", shell)
        rollback = shell.split('  rollback)')[1].split('  reset)')[0]
        self.assertIn("backup_file=$(backup)", rollback)
        self.assertIn("compose stop server", rollback)
        self.assertNotIn("compose up --detach --wait server", rollback)

    def test_restore_is_confirmed_and_reapplies_migrations_before_server_restart(self) -> None:
        shell = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        restore = shell.split("restore() {", 1)[1].split("\nsmoke() {", 1)[0]
        self.assertIn("CONFIRM_INFRANEXUM_RESTORE", restore)
        self.assertIn("compose run --rm migrate", restore)
        self.assertLess(restore.index("compose run --rm migrate"), restore.index("compose up --detach --wait server"))

    def test_volume_deletion_requires_explicit_confirmation(self) -> None:
        shell = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        reset = shell.split('  reset)')[1].split('  help|-h|--help)')[0]
        self.assertIn("CONFIRM_INFRANEXUM_VOLUME_DELETE", reset)
        self.assertIn("compose down --volumes --remove-orphans", reset)

    def test_start_commands_are_exposed_for_windows_unix_and_make(self) -> None:
        readme = (DOCKER / "README.md").read_text(encoding="utf-8")
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        self.assertIn(r".\docker\dev-compose.ps1 up", readme)
        self.assertIn("./docker/dev-compose.sh up", readme)
        self.assertIn("docker compose -f docker/compose.yaml up --detach --build --wait server", readme)
        self.assertIn("compose-up:", makefile)
        self.assertIn("$(DOCKER_COMPOSE_SH) up", makefile)

    def test_docker_context_excludes_non_runtime_repository_content(self) -> None:
        ignored = set((ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines())
        for path in ("tests", "validation", "docs", "tools", "requirements", "artifacts", ".git", ".github"):
            self.assertIn(path, ignored)

    def test_root_docker_is_explicitly_developer_only(self) -> None:
        readme = (DOCKER / "README.md").read_text(encoding="utf-8")
        source_layout = (ROOT / "docs/source-layout.md").read_text(encoding="utf-8")
        self.assertIn("development and test tooling", readme)
        self.assertIn("docker/", source_layout)
        self.assertIn("bare-metal or VM", source_layout)


if __name__ == "__main__":
    unittest.main()
