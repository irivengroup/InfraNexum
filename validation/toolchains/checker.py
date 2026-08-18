from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

_VERSION = re.compile(r"^[0-9]+(?:\.[0-9]+){1,2}(?:\+[0-9]+)?$")
_REQUIRED = {
    "java", "maven", "spring-boot", "spring-modulith", "go", "node",
    "pnpm", "typescript", "python", "cmake", "gcc",
}


@dataclass(frozen=True, order=True)
class ToolchainViolation:
    check_id: str
    path: str
    message: str


class ToolchainChecker:
    """Checks exact polyglot toolchain pins and their build-file wiring."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.violations: list[ToolchainViolation] = []

    def run(self) -> tuple[ToolchainViolation, ...]:
        lock_path = self.root / "toolchains.lock.json"
        payload = self._load_lock(lock_path)
        if payload is None:
            return tuple(sorted(set(self.violations)))
        tools = payload.get("toolchains") if isinstance(payload, dict) else None
        if not isinstance(tools, dict):
            self._add("CHECK-TOOLCHAIN-002", lock_path, "toolchains must be an object")
            return tuple(sorted(set(self.violations)))

        missing = sorted(_REQUIRED - tools.keys())
        extra = sorted(tools.keys() - _REQUIRED)
        if missing or extra:
            self._add(
                "CHECK-TOOLCHAIN-003",
                lock_path,
                f"toolchain set mismatch; missing={missing}, extra={extra}",
            )
        for name, item in tools.items():
            version = item.get("version") if isinstance(item, dict) else None
            if not isinstance(version, str) or not _VERSION.fullmatch(version):
                self._add("CHECK-TOOLCHAIN-004", lock_path, f"{name} must have an exact version")

        self._match_file(lock_path, ".node-version", tools, "node")
        self._match_file(lock_path, ".python-version", tools, "python")
        self._check_maven(tools)
        self._check_go(tools)
        self._check_web(tools)
        self._check_ci_workflow(payload, tools)
        return tuple(sorted(set(self.violations)))

    def _load_lock(self, path: Path) -> Any | None:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add("CHECK-TOOLCHAIN-001", path, f"invalid toolchain lock: {error}")
            return None

    def _match_file(self, lock_path: Path, relative: str, tools: dict[str, Any], key: str) -> None:
        file_path = self.root / relative
        text = self._read_text(file_path, "CHECK-TOOLCHAIN-005", "version file cannot be read")
        if text is None:
            return
        expected = self._version(tools, key)
        if text.strip() != expected:
            self._add("CHECK-TOOLCHAIN-006", file_path, f"version must match {lock_path.name}")

    def _check_maven(self, tools: dict[str, Any]) -> None:
        pom_path = self.root / "pom.xml"
        text = self._read_text(pom_path, "CHECK-TOOLCHAIN-010", "root Maven model cannot be read")
        if text is not None:
            java_major = self._version(tools, "java").split(".", 1)[0]
            if f"<java.version>{java_major}</java.version>" not in text:
                self._add("CHECK-TOOLCHAIN-007", pom_path, "Java major does not match toolchain lock")
            for key, property_name in (
                ("spring-boot", "spring-boot.version"),
                ("spring-modulith", "spring-modulith.version"),
            ):
                version = self._version(tools, key)
                if f"<{property_name}>{version}</{property_name}>" not in text:
                    self._add("CHECK-TOOLCHAIN-008", pom_path, f"{key} does not match toolchain lock")

        wrapper_path = self.root / ".mvn/wrapper/maven-wrapper.properties"
        wrapper = self._read_text(
            wrapper_path,
            "CHECK-TOOLCHAIN-011",
            "Maven Wrapper properties cannot be read",
        )
        if wrapper is not None:
            maven_version = self._version(tools, "maven")
            expected_fragment = f"/apache-maven-{maven_version}-bin.tar.gz"
            if expected_fragment not in wrapper or "distributionSha512Sum=" not in wrapper:
                self._add(
                    "CHECK-TOOLCHAIN-012",
                    wrapper_path,
                    "Maven distribution and SHA-512 pin must match the toolchain lock",
                )

        bootstrap_path = self.root / "tools/bootstrap-maven.ps1"
        bootstrap = self._read_text(
            bootstrap_path,
            "CHECK-TOOLCHAIN-039",
            "PowerShell Maven bootstrap cannot be read",
        )
        if bootstrap is not None:
            required_tokens = (
                "System.Diagnostics.ProcessStartInfo",
                "RedirectStandardError = $true",
                "RedirectStandardOutput = $true",
                "$JavaProcess.ExitCode",
                "$JavaProcess.Dispose()",
            )
            fragile_tokens = (
                "& java -version 2>&1",
                "(& java -version",
            )
            if any(token not in bootstrap for token in required_tokens) or any(
                token in bootstrap for token in fragile_tokens
            ):
                self._add(
                    "CHECK-TOOLCHAIN-039",
                    bootstrap_path,
                    "PowerShell Java detection must not convert java -version stderr into NativeCommandError",
                )

    def _check_go(self, tools: dict[str, Any]) -> None:
        path = self.root / "src/applications/agent/go.mod"
        text = self._read_text(path, "CHECK-TOOLCHAIN-013", "Go module cannot be read")
        if text is None:
            return
        version = self._version(tools, "go")
        if f"toolchain go{version}" not in text:
            self._add("CHECK-TOOLCHAIN-009", path, "Go toolchain does not match lock")

    def _check_web(self, tools: dict[str, Any]) -> None:
        package_path = self.root / "src/applications/web/package.json"
        try:
            package = json.loads(package_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add("CHECK-TOOLCHAIN-014", package_path, f"invalid Web package model: {error}")
            return
        if not isinstance(package, dict):
            self._add("CHECK-TOOLCHAIN-015", package_path, "Web package model must be an object")
            return

        node_version = self._version(tools, "node")
        pnpm_version = self._version(tools, "pnpm")
        expected_engine = f">={node_version} <25"
        if package.get("packageManager") != f"pnpm@{pnpm_version}":
            self._add("CHECK-TOOLCHAIN-016", package_path, "Web packageManager must match pnpm toolchain lock")
        engines = package.get("engines")
        if not isinstance(engines, dict) or engines.get("node") != expected_engine:
            self._add("CHECK-TOOLCHAIN-017", package_path, "Web Node engine must match toolchain lock and LTS major")
        if package.get("private") is not True or package.get("type") != "module":
            self._add("CHECK-TOOLCHAIN-018", package_path, "Web package must be private and ESM-only")
        scripts = package.get("scripts")
        required_scripts = {"start", "test", "smoke", "verify"}
        if not isinstance(scripts, dict) or not required_scripts.issubset(scripts):
            self._add("CHECK-TOOLCHAIN-019", package_path, "Web package scripts are incomplete")
        for field in ("dependencies", "devDependencies", "optionalDependencies"):
            values = package.get(field, {})
            if not isinstance(values, dict):
                self._add("CHECK-TOOLCHAIN-020", package_path, f"{field} must be an object when present")
                continue
            floating = sorted(name for name, version in values.items() if not isinstance(version, str) or not _VERSION.fullmatch(version))
            if floating:
                self._add("CHECK-TOOLCHAIN-021", package_path, f"Web dependencies must use exact versions: {floating}")

        lock_path = self.root / "src/applications/web/pnpm-lock.yaml"
        lock_text = self._read_text(lock_path, "CHECK-TOOLCHAIN-022", "Web pnpm lockfile cannot be read")
        if lock_text is not None and ("lockfileVersion: '9.0'" not in lock_text or "importers:" not in lock_text):
            self._add("CHECK-TOOLCHAIN-023", lock_path, "Web pnpm lockfile format is invalid")

        workspace_path = self.root / "src/applications/web/pnpm-workspace.yaml"
        workspace_text = self._read_text(
            workspace_path,
            "CHECK-TOOLCHAIN-030",
            "Web pnpm workspace configuration cannot be read",
        )
        if workspace_text is not None:
            try:
                workspace = yaml.safe_load(workspace_text)
            except yaml.YAMLError as error:
                self._add("CHECK-TOOLCHAIN-031", workspace_path, f"invalid pnpm workspace configuration: {error}")
            else:
                expected_settings = {
                    "autoInstallPeers": False,
                    "strictPeerDependencies": True,
                    "engineStrict": True,
                    "saveExact": True,
                    "ignoreScripts": True,
                }
                if not isinstance(workspace, dict) or any(
                    workspace.get(key) is not value for key, value in expected_settings.items()
                ):
                    self._add(
                        "CHECK-TOOLCHAIN-032",
                        workspace_path,
                        "pnpm workspace settings must match the immutable lockfile and secure install policy",
                    )

        npmrc_path = self.root / "src/applications/web/.npmrc"
        if npmrc_path.exists():
            self._add(
                "CHECK-TOOLCHAIN-033",
                npmrc_path,
                "Project pnpm settings must be stored in pnpm-workspace.yaml; committed .npmrc is forbidden",
            )

    def _check_ci_workflow(self, payload: dict[str, Any], tools: dict[str, Any]) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = self._read_text(
            workflow_path,
            "CHECK-TOOLCHAIN-024",
            "Foundation workflow cannot be read",
        )
        if workflow is None:
            return

        action_pins = payload.get("github_actions")
        java_item = tools.get("java")
        java_selector = java_item.get("github_actions_version") if isinstance(java_item, dict) else None
        setup_java = action_pins.get("setup-java") if isinstance(action_pins, dict) else None
        pnpm_setup = action_pins.get("pnpm-setup") if isinstance(action_pins, dict) else None
        setup_java_sha = setup_java.get("sha") if isinstance(setup_java, dict) else None
        pnpm_setup_sha = pnpm_setup.get("sha") if isinstance(pnpm_setup, dict) else None
        if (
            not isinstance(java_selector, str)
            or not java_selector.endswith(".LTS")
            or not isinstance(setup_java_sha, str)
            or not re.fullmatch(r"[0-9a-f]{40}", setup_java_sha)
            or not isinstance(pnpm_setup_sha, str)
            or not re.fullmatch(r"[0-9a-f]{40}", pnpm_setup_sha)
        ):
            self._add(
                "CHECK-TOOLCHAIN-025",
                self.root / "toolchains.lock.json",
                "GitHub Actions selectors and immutable action SHAs are incomplete",
            )
            return

        java_action = f"actions/setup-java@{setup_java_sha}"
        java_version = f"java-version: '{java_selector}'"
        if workflow.count(java_action) < 3 or workflow.count(java_version) < 3:
            self._add(
                "CHECK-TOOLCHAIN-026",
                workflow_path,
                "All Java jobs must use the resolvable exact Temurin selector and pinned setup-java action",
            )

        pnpm_action = f"pnpm/setup@{pnpm_setup_sha}"
        pnpm_version = self._version(tools, "pnpm")
        node_version = self._version(tools, "node")
        required_pnpm_tokens = (
            pnpm_action,
            f"version: '{pnpm_version}'",
            f"runtime: node@{node_version}",
            "cache: true",
            "cache-dependency-path: src/applications/web/pnpm-lock.yaml",
            "install: false",
        )
        if any(token not in workflow for token in required_pnpm_tokens):
            self._add(
                "CHECK-TOOLCHAIN-027",
                workflow_path,
                "Web CI must bootstrap exact Node and pnpm through pinned pnpm/setup",
            )
        if any(token in workflow for token in ("actions/setup-node@", "pnpm/action-setup@", "corepack prepare")):
            self._add(
                "CHECK-TOOLCHAIN-028",
                workflow_path,
                "Legacy Web toolchain bootstrap is forbidden because pnpm must exist before cache resolution",
            )

        source_integrity_requirements = (
            "SOURCE_INTEGRITY_REQUIRE_GIT=1",
            "SOURCE_INTEGRITY_REQUIRE_STAGED=1",
            "SOURCE_INTEGRITY_REQUIRE_CHECKSUMS=1",
            "make source-integrity-test source-integrity-check",
        )
        job_pattern = re.compile(
            r"(?ms)^  (?P<name>[A-Za-z0-9_-]+):\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)"
        )
        job_bodies = {match.group("name"): match.group("body") for match in job_pattern.finditer(workflow)}
        source_body = job_bodies.get("source-integrity", "")
        dependent_jobs = (
            "connector-sdk",
            "archive-compatibility",
            "architecture",
            "agent",
            "web",
            "java",
            "java-module-coverage",
            "postgresql-integration",
        )
        if not all(token in source_body for token in source_integrity_requirements) or any(
            "needs: source-integrity" not in job_bodies.get(name, "") for name in dependent_jobs
        ):
            self._add(
                "CHECK-TOOLCHAIN-037",
                workflow_path,
                "All build/test jobs must depend on the Git-backed staged and checksummed source-integrity preflight",
            )

        archive_job = job_bodies.get("archive-compatibility", "")
        required_archive_tokens = (
            "runs-on: ubuntu-24.04",
            "make archive-compatibility-test archive-compatibility-check",
        )
        if any(token not in archive_job for token in required_archive_tokens) or "windows-latest" in workflow:
            self._add(
                "CHECK-TOOLCHAIN-038",
                workflow_path,
                "Archive compatibility must run on Unix CI and Windows runners are forbidden for foundation gates",
            )

        architecture_job = job_bodies.get("architecture", "")
        required_java_smokes = (
            "java-contract-smoke",
            "java-eventing-smoke",
            "java-workers-smoke",
            "java-audit-smoke",
            "java-jdbc-smoke",
            "java-capabilities-smoke",
            "java-entitlements-smoke",
            "java-entitlement-runtime-smoke",
            "java-activation-operations-smoke",
        )
        if (
            java_action not in architecture_job
            or java_version not in architecture_job
            or any(target not in architecture_job for target in required_java_smokes)
        ):
            self._add(
                "CHECK-TOOLCHAIN-029",
                workflow_path,
                "The architecture job must install the exact Java toolchain and execute every dependency-free smoke",
            )

        prepare_wrapper = "run: chmod 0755 mvnw && test -x mvnw"
        unprepared_jobs = []
        for match in job_pattern.finditer(workflow):
            body = match.group("body")
            first_call = body.find("run: ./mvnw")
            if first_call < 0:
                continue
            preparation = body.find(prepare_wrapper)
            if preparation < 0 or preparation > first_call:
                unprepared_jobs.append(match.group("name"))
        if unprepared_jobs:
            self._add(
                "CHECK-TOOLCHAIN-034",
                workflow_path,
                f"Maven Wrapper must be made executable before use in jobs: {sorted(unprepared_jobs)}",
            )

        reactor_verify = "run: ./mvnw --batch-mode --no-transfer-progress --fail-at-end verify"
        if reactor_verify not in workflow:
            self._add(
                "CHECK-TOOLCHAIN-040",
                workflow_path,
                "The full Java reactor verify must use --fail-at-end so one CI run exposes every failing module",
            )

        module_coverage_job = job_bodies.get("java-module-coverage", "")
        makefile_path = self.root / "Makefile"
        makefile = self._read_text(
            makefile_path,
            "CHECK-TOOLCHAIN-041",
            "Makefile cannot be read for independent Java module verification",
        )
        required_modules = (
            "src/components/core/contracts",
            "src/components/core/events",
            "src/components/core/workers",
            "src/components/core/capabilities",
            "src/components/core/entitlements",
            "src/components/core/audit",
            "src/components/adapters/jdbc",
            "src/applications/server",
        )
        independent_contract = (
            makefile is not None
            and "java-module-verify:" in makefile
            and "-Dmaven.test.skip=true -Djacoco.skip=true install" in makefile
            and '-pl "$$module" clean verify' in makefile
            and all(module in makefile for module in required_modules)
        )
        if (
            java_action not in module_coverage_job
            or java_version not in module_coverage_job
            or "run: make java-module-verify" not in module_coverage_job
            or not independent_contract
        ):
            self._add(
                "CHECK-TOOLCHAIN-041",
                workflow_path,
                "CI must independently verify every Java module after a production-only dependency install that skips test compilation so upstream coverage failures cannot hide downstream defects",
            )

        jdbc_coverage_jobs = {
            "java": job_bodies.get("java", ""),
            "java-module-coverage": module_coverage_job,
        }
        missing_live_jdbc = []
        for name, body in jdbc_coverage_jobs.items():
            required_fragments = (
                "image: postgres:17",
                "INFRANEXUM_POSTGRESQL_TEST_URL: jdbc:postgresql://127.0.0.1:5432/infranexum",
                "run: make postgresql-test-schema",
            )
            if any(fragment not in body for fragment in required_fragments):
                missing_live_jdbc.append(name)
        required_live_jdbc_tests = (
            "PostgreSqlJdbcEntitlementPersistenceTest",
            "PostgreSqlJdbcConnectorInboxRepositoryTest",
            "PostgreSqlJdbcConnectorSyncRepositoryTest",
        )
        postgresql_integration_job = job_bodies.get("postgresql-integration", "")
        if missing_live_jdbc or any(test not in postgresql_integration_job for test in required_live_jdbc_tests):
            self._add(
                "CHECK-TOOLCHAIN-042",
                workflow_path,
                "Java coverage jobs must run the live PostgreSQL JDBC contracts, including entitlement, connector inbox and durable connector-sync persistence, so JaCoCo does not depend on skipped integration tests",
            )

        makefile_text = makefile or ""
        compose_path = self.root / "docker/compose.yaml"
        compose_text = self._read_text(
            compose_path,
            "CHECK-TOOLCHAIN-043",
            "Root developer Docker Compose topology cannot be read",
        )
        required_make_targets = (
            "compose-contract-test:",
            "compose-config:",
            "compose-build:",
            "compose-up:",
            "compose-down:",
            "compose-logs:",
            "compose-smoke:",
            "compose-backup:",
            "compose-restore:",
            "compose-rollback:",
            "compose-reset:",
        )
        forbidden_ci_tokens = ("docker-compose:", "make compose-up", "make compose-smoke")
        if (
            any(token in workflow for token in forbidden_ci_tokens)
            or "src/deployment/docker" in makefile_text
            or any(token not in makefile_text for token in required_make_targets)
            or compose_text is None
            or "service_healthy" not in compose_text
            or "service_completed_successfully" not in compose_text
            or "internal: false" not in compose_text
            or "127.0.0.1:${INFRANEXUM_POSTGRES_PUBLISHED_PORT:-5432}:5432" not in compose_text
            or "127.0.0.1:${INFRANEXUM_SERVER_PUBLISHED_PORT:-8080}:8080" not in compose_text
            or "docker/server.Dockerfile" not in compose_text
            or "docker/postgres-tools.Dockerfile" not in compose_text
        ):
            self._add(
                "CHECK-TOOLCHAIN-043",
                workflow_path,
                "Docker/Compose must remain root-level developer tooling with complete Make commands and must not become a product CI/deployment dependency",
            )

        targeted_lines = [
            line.strip()
            for line in workflow.splitlines()
            if "PostgreSqlJdbcTransactionalEventStoreTest" in line
        ]
        required_targeted_flags = (
            "-Dinfranexum.surefire.failIfNoTests=false",
            "-Dsurefire.failIfNoSpecifiedTests=false",
        )
        if len(targeted_lines) != 1 or any(
            flag not in targeted_lines[0] for flag in required_targeted_flags
        ):
            self._add(
                "CHECK-TOOLCHAIN-035",
                workflow_path,
                "Targeted reactor tests must tolerate upstream modules without matching tests",
            )

        root_pom_path = self.root / "pom.xml"
        root_pom = self._read_text(
            root_pom_path,
            "CHECK-TOOLCHAIN-036",
            "Unable to read root Maven POM",
        )
        if root_pom is not None:
            expected_property = (
                "<infranexum.surefire.failIfNoTests>true</infranexum.surefire.failIfNoTests>"
            )
            expected_binding = (
                "<failIfNoTests>${infranexum.surefire.failIfNoTests}</failIfNoTests>"
            )
            if expected_property not in root_pom or expected_binding not in root_pom:
                self._add(
                    "CHECK-TOOLCHAIN-036",
                    root_pom_path,
                    "Surefire failIfNoTests must be controlled by the overridable InfraNexum Maven property",
                )

    @staticmethod
    def _version(tools: dict[str, Any], key: str) -> str:
        item = tools.get(key)
        return item.get("version", "") if isinstance(item, dict) else ""

    def _read_text(self, path: Path, check_id: str, message: str) -> str | None:
        try:
            return path.read_text(encoding="utf-8")
        except OSError as error:
            self._add(check_id, path, f"{message}: {error}")
            return None

    def _add(self, check_id: str, path: Path, message: str) -> None:
        try:
            rendered = path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            rendered = path.resolve().as_posix()
        self.violations.append(ToolchainViolation(check_id, rendered, message))
