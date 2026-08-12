from __future__ import annotations

import os
import pathlib
import re
import subprocess
import tempfile
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOCKER = ROOT / "docker"
COMPOSE = DOCKER / "compose.yaml"
ROOT_COMPOSE = ROOT / "compose.yaml"


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
        backend = self.document["networks"]["backend"]
        self.assertEqual("bridge", backend["driver"])
        self.assertFalse(backend["internal"], "published developer ports require a non-internal bridge")


    def test_developer_port_publication_is_compatible_with_backend_network(self) -> None:
        """An internal Docker network discards host publishing on affected engines.

        The developer topology therefore uses a normal bridge while restricting every
        host-published port to IPv4 loopback. Production deployment does not use Compose.
        """
        backend = self.document["networks"]["backend"]
        self.assertFalse(backend.get("internal", False))
        for service_name in ("postgres", "server"):
            ports = self.services[service_name].get("ports", [])
            self.assertTrue(ports, f"{service_name} must publish its developer port")
            for binding in ports:
                self.assertTrue(str(binding).startswith("127.0.0.1:"), binding)
        for service_name in ("secret-init", "migrate", "rollback"):
            self.assertNotIn("ports", self.services[service_name])

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

    def test_server_forwards_bounded_scheduling_overrides(self) -> None:
        environment = self.services["server"]["environment"]
        self.assertEqual(
            "${INFRANEXUM_SCHEDULING_POOL_SIZE:-2}",
            environment["INFRANEXUM_SCHEDULING_POOL_SIZE"],
        )
        self.assertEqual(
            "${INFRANEXUM_SCHEDULING_SHUTDOWN_TIMEOUT:-PT10S}",
            environment["INFRANEXUM_SCHEDULING_SHUTDOWN_TIMEOUT"],
        )

    def test_server_forwards_bounded_worker_runtime_overrides(self) -> None:
        environment = self.services["server"]["environment"]
        expected = {
            "INFRANEXUM_WORKERS_ENABLED": "${INFRANEXUM_WORKERS_ENABLED:-true}",
            "INFRANEXUM_WORKERS_CONCURRENCY": "${INFRANEXUM_WORKERS_CONCURRENCY:-2}",
            "INFRANEXUM_WORKERS_POLL_INTERVAL": "${INFRANEXUM_WORKERS_POLL_INTERVAL:-PT0.5S}",
            "INFRANEXUM_WORKERS_LEASE_DURATION": "${INFRANEXUM_WORKERS_LEASE_DURATION:-PT30S}",
            "INFRANEXUM_WORKERS_HEARTBEAT_INTERVAL": "${INFRANEXUM_WORKERS_HEARTBEAT_INTERVAL:-PT10S}",
            "INFRANEXUM_WORKERS_SHUTDOWN_TIMEOUT": "${INFRANEXUM_WORKERS_SHUTDOWN_TIMEOUT:-PT15S}",
            "INFRANEXUM_WORKERS_MAXIMUM_ATTEMPTS": "${INFRANEXUM_WORKERS_MAXIMUM_ATTEMPTS:-5}",
            "INFRANEXUM_WORKERS_INITIAL_RETRY_DELAY": "${INFRANEXUM_WORKERS_INITIAL_RETRY_DELAY:-PT1S}",
            "INFRANEXUM_WORKERS_MAXIMUM_RETRY_DELAY": "${INFRANEXUM_WORKERS_MAXIMUM_RETRY_DELAY:-PT1M}",
            "INFRANEXUM_WORKERS_JITTER_RATIO": "${INFRANEXUM_WORKERS_JITTER_RATIO:-0.2}",
        }
        for name, value in expected.items():
            self.assertEqual(value, environment[name])

    def test_server_and_postgres_are_published_on_loopback_only_by_default(self) -> None:
        self.assertEqual(
            "127.0.0.1:${INFRANEXUM_SERVER_PUBLISHED_PORT:-8080}:8080",
            self.services["server"]["ports"][0],
        )
        self.assertEqual(
            "127.0.0.1:${INFRANEXUM_POSTGRES_PUBLISHED_PORT:-5432}:5432",
            self.services["postgres"]["ports"][0],
        )
        compose_text = COMPOSE.read_text(encoding="utf-8")
        self.assertNotIn("host_ip:", compose_text)
        self.assertNotIn("published:", compose_text)

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
        self.assertIn("docker compose up --detach --build --wait server", readme)
        self.assertIn("compose-up:", makefile)
        self.assertIn("$(DOCKER_COMPOSE_SH) up", makefile)

    def test_docker_context_excludes_non_runtime_repository_content(self) -> None:
        ignored = set((ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines())
        for path in ("tests", "validation", "docs", "tools", "requirements", "artifacts", ".git", ".github"):
            self.assertIn(path, ignored)


    def test_repository_root_compose_loader_keeps_docker_model_canonical(self) -> None:
        loader = yaml.safe_load(ROOT_COMPOSE.read_text(encoding="utf-8"))
        self.assertEqual("infranexum-dev", loader["name"])
        self.assertEqual(["docker/compose.yaml"], loader["include"])

    def test_psql_meta_commands_are_rendered_with_printf_not_echo(self) -> None:
        """Prevent BusyBox/dash echo differences from corrupting psql control files."""
        for name in ("migrate-postgresql.sh", "rollback-postgresql.sh"):
            text = (DOCKER / name).read_text(encoding="utf-8")
            self.assertIn("printf '%s\\n' '\\set ON_ERROR_STOP on'", text, name)
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith("echo ") and any(
                    token in stripped for token in ("\\\\set", "\\\\gset", "\\\\if", "\\\\else", "\\\\endif", "\\\\quit", "\\\\i ")
                ):
                    self.fail(f"{name} renders a psql meta-command with non-portable echo: {stripped}")

    def test_unix_logs_command_forwards_requested_compose_services(self) -> None:
        """A service-scoped diagnostic must not be rewritten to the default service set."""
        with tempfile.TemporaryDirectory() as temporary:
            temp = pathlib.Path(temporary)
            trace = temp / "docker.trace"
            fake = temp / "docker"
            fake.write_text(
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$INFRANEXUM_DOCKER_TRACE\"\n",
                encoding="utf-8",
            )
            fake.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = f"{temp}{os.pathsep}{env.get('PATH', '')}"
            env["INFRANEXUM_DOCKER_TRACE"] = str(trace)
            completed = subprocess.run(
                [str(DOCKER / "dev-compose.sh"), "logs", "migrate"],
                cwd=ROOT,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            calls = trace.read_text(encoding="utf-8").splitlines()
            self.assertEqual("compose version", calls[0])
            self.assertIn("logs --no-color --tail=200 migrate", calls[-1])
            self.assertNotIn("server postgres migrate", calls[-1])

    def test_migration_log_commands_use_service_names(self) -> None:
        readme = (DOCKER / "README.md").read_text(encoding="utf-8")
        powershell = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        shell = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        self.assertIn("docker compose logs migrate", readme)
        self.assertIn(r".\docker\dev-compose.ps1 logs migrate", readme)
        self.assertIn("./docker/dev-compose.sh logs migrate", shell)
        self.assertIn("ValueFromRemainingArguments = $true", powershell)


    def test_installation_identity_bootstrap_generates_uuidv7_not_kernel_uuidv4(self) -> None:
        """The developer bootstrap must satisfy DomainIdentifier before Server startup."""
        migrate = (DOCKER / "migrate-postgresql.sh").read_text(encoding="utf-8")
        self.assertNotIn("/proc/sys/kernel/random/uuid", migrate)
        self.assertIn("gen_random_uuid()", migrate)
        self.assertIn("'-7'", migrate)
        self.assertIn("'89ab89ab89ab89ab'", migrate)
        self.assertIn("????????-????-7???-[89ab]???-????????????", migrate)

    def test_identity_bootstrap_uses_whole_second_database_timestamp(self) -> None:
        """Keep installer metadata aligned with InstallationIdentity temporal precision."""
        migrate = (DOCKER / "migrate-postgresql.sh").read_text(encoding="utf-8")
        self.assertIn("date_trunc('second', CURRENT_TIMESTAMP)", migrate)
        self.assertNotIn("'v1', :'fingerprint', CURRENT_TIMESTAMP", migrate)

    def test_uuidv7_repair_migration_precedes_identity_bootstrap(self) -> None:
        """Existing alpha.0.31 UUIDv4 identities must be repaired before Entitlements starts."""
        migrate = (DOCKER / "migrate-postgresql.sh").read_text(encoding="utf-8")
        migration_loop = migrate.index('for migration_dir in "$migration_root"/000*; do')
        bootstrap = migrate.index('installation_id="$($psql_base')
        self.assertLess(migration_loop, bootstrap)
        self.assertTrue((ROOT / "src/distribution/migrations/0007-core-installation-uuidv7/postgresql.sql").is_file())


    def test_smoke_requires_workers_readiness_and_metrics(self) -> None:
        """Runtime smoke must prove the Workers readiness contribution and metric endpoint."""
        shell = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        powershell = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        application = (ROOT / "src/applications/server/resources/application.yaml").read_text(encoding="utf-8")
        self.assertIn("/actuator/health/readiness", shell)
        self.assertIn("/actuator/metrics/infranexum.workers.ready", shell)
        self.assertIn("/actuator/metrics/infranexum.workers.ready", powershell)
        self.assertIn("include: health,info,metrics", application)
        self.assertIn("include: readinessState,workers", application)

    def test_smoke_resolves_effective_compose_bindings_instead_of_assuming_ports(self) -> None:
        """Smoke must use Docker's effective bindings, including docker/.env overrides."""
        shell = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        powershell = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        env_example = (DOCKER / ".env.example").read_text(encoding="utf-8")

        self.assertIn('published_port postgres 5432', shell)
        self.assertIn('published_port server 8080', shell)
        self.assertNotIn('port=${INFRANEXUM_SERVER_PUBLISHED_PORT:-8080}', shell)
        self.assertIn("Get-PublishedPort -Service 'postgres' -ContainerPort 5432", powershell)
        self.assertIn("Get-PublishedPort -Service 'server' -ContainerPort 8080", powershell)
        self.assertNotIn("$env:INFRANEXUM_SERVER_PUBLISHED_PORT) { $env:INFRANEXUM_SERVER_PUBLISHED_PORT", powershell)
        self.assertIn("INFRANEXUM_POSTGRES_PUBLISHED_PORT=5432", env_example)
        self.assertIn("INFRANEXUM_SERVER_PUBLISHED_PORT=8080", env_example)

    def test_powershell_smoke_has_no_ambiguous_variable_colon_interpolation(self) -> None:
        """PowerShell parses `$name:` as a scoped-variable expression unless the name is delimited."""
        powershell = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        ambiguous = re.compile(r"\$(?!env:)[A-Za-z_][A-Za-z0-9_]*:")
        self.assertIsNone(ambiguous.search(powershell))
        self.assertIn("${Service}/${ContainerPort}: $binding", powershell)

    def test_smoke_falls_back_to_container_inspect_when_compose_port_fails(self) -> None:
        """Docker Desktop/Compose port rendering defects must not make smoke diagnostics unusable."""
        shell = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        powershell = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        self.assertIn("falling back to docker inspect", shell)
        self.assertIn("docker inspect --format", shell)
        self.assertIn("falling back to docker inspect", powershell)
        self.assertIn("docker inspect --format", powershell)
        for script in (shell, powershell):
            self.assertIn("127.0.0.1", script)

    def test_smoke_fails_with_compose_diagnostics_when_server_is_not_running(self) -> None:
        """A stopped/restarting Server must yield topology diagnostics before HTTP probing."""
        shell = (DOCKER / "dev-compose.sh").read_text(encoding="utf-8")
        powershell = (DOCKER / "dev-compose.ps1").read_text(encoding="utf-8")
        self.assertIn("assert_service_running server", shell)
        self.assertIn('compose ps >&2 || true', shell)
        self.assertIn('compose logs --no-color --tail=200 "$service"', shell)
        self.assertIn("Assert-ComposeServiceRunning -Service 'server'", powershell)
        self.assertIn("Invoke-Compose ps", powershell)
        self.assertIn("Invoke-Compose logs --no-color --tail=200 $Service", powershell)

    def test_root_docker_is_explicitly_developer_only(self) -> None:
        readme = (DOCKER / "README.md").read_text(encoding="utf-8")
        source_layout = (ROOT / "docs/source-layout.md").read_text(encoding="utf-8")
        self.assertIn("development and test tooling", readme)
        self.assertIn("docker/", source_layout)
        self.assertIn("bare-metal or VM", source_layout)

    def test_smoke_covers_http_correlation_contract(self) -> None:
        shell = (ROOT / "docker/dev-compose.sh").read_text(encoding="utf-8")
        powershell = (ROOT / "docker/dev-compose.ps1").read_text(encoding="utf-8")
        compose = (ROOT / "docker/compose.yaml").read_text(encoding="utf-8")
        for text in (shell, powershell):
            self.assertIn("X-Correlation-ID", text)
            self.assertIn("INFRANEXUM_INVALID_CORRELATION_ID", text)
            self.assertIn("018bcfe5-6800-7001-8000-000000000001", text)
        self.assertIn("INFRANEXUM_LOG_FORMAT", compose)
        self.assertIn("INFRANEXUM_ENVIRONMENT", compose)



if __name__ == "__main__":
    unittest.main()
