from __future__ import annotations

import contextlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from validation.toolchains.checker import ToolchainChecker
from validation.toolchains.cli import main as cli_main

SOURCE = Path(__file__).resolve().parents[2]


class ToolchainCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repository"
        self.root.mkdir()
        for relative in (
            "toolchains.lock.json",
            ".node-version",
            ".python-version",
            "pom.xml",
            "Makefile",
            ".mvn/wrapper/maven-wrapper.properties",
            "src/applications/web/package.json",
            "src/applications/web/pnpm-lock.yaml",
            "src/applications/web/pnpm-workspace.yaml",
            ".github/workflows/foundation.yml",
            "tools/bootstrap-maven.ps1",
            "src/deployment/docker/compose.yaml",
        ):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE / relative, target)
        go_target = self.root / "src/applications/agent/go.mod"
        go_target.parent.mkdir(parents=True)
        shutil.copy2(SOURCE / "src/applications/agent/go.mod", go_target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def ids(self) -> set[str]:
        return {item.check_id for item in ToolchainChecker(self.root).run()}

    def lock(self) -> dict:
        return json.loads((self.root / "toolchains.lock.json").read_text(encoding="utf-8"))

    def write_lock(self, payload: object) -> None:
        (self.root / "toolchains.lock.json").write_text(json.dumps(payload), encoding="utf-8")

    def test_reference_toolchains_are_valid(self) -> None:
        self.assertEqual(set(), self.ids())

    def test_missing_or_invalid_lock_is_blocked(self) -> None:
        (self.root / "toolchains.lock.json").unlink()
        self.assertEqual({"CHECK-TOOLCHAIN-001"}, self.ids())
        self.write_lock("not-an-object")
        self.assertEqual({"CHECK-TOOLCHAIN-002"}, self.ids())
        (self.root / "toolchains.lock.json").write_text("{", encoding="utf-8")
        self.assertEqual({"CHECK-TOOLCHAIN-001"}, self.ids())

    def test_toolchain_set_and_exact_versions_are_enforced(self) -> None:
        payload = self.lock()
        del payload["toolchains"]["gcc"]
        payload["toolchains"]["unexpected"] = {"version": "1.0.0"}
        payload["toolchains"]["node"]["version"] = "24.x"
        payload["toolchains"]["pnpm"] = "11.17.0"
        self.write_lock(payload)
        self.assertTrue({"CHECK-TOOLCHAIN-003", "CHECK-TOOLCHAIN-004"} <= self.ids())

    def test_version_files_must_exist_and_match(self) -> None:
        (self.root / ".node-version").unlink()
        (self.root / ".python-version").write_text("3.12.0\n", encoding="utf-8")
        self.assertTrue({"CHECK-TOOLCHAIN-005", "CHECK-TOOLCHAIN-006"} <= self.ids())

    def test_maven_model_must_match_java_and_frameworks(self) -> None:
        path = self.root / "pom.xml"
        text = path.read_text(encoding="utf-8")
        text = text.replace("<java.version>25</java.version>", "<java.version>24</java.version>")
        text = text.replace("<spring-boot.version>4.1.0", "<spring-boot.version>4.0.0")
        text = text.replace("<spring-modulith.version>2.1.0", "<spring-modulith.version>2.0.0")
        path.write_text(text, encoding="utf-8")
        self.assertTrue({"CHECK-TOOLCHAIN-007", "CHECK-TOOLCHAIN-008"} <= self.ids())
        path.unlink()
        self.assertIn("CHECK-TOOLCHAIN-010", self.ids())

    def test_maven_wrapper_is_required_and_cryptographically_pinned(self) -> None:
        path = self.root / ".mvn/wrapper/maven-wrapper.properties"
        path.write_text("distributionUrl=https://example.invalid/maven.tar.gz\n", encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-012", self.ids())
        path.unlink()
        self.assertIn("CHECK-TOOLCHAIN-011", self.ids())


    def test_powershell_maven_bootstrap_handles_java_version_stderr_safely(self) -> None:
        path = self.root / "tools/bootstrap-maven.ps1"
        text = path.read_text(encoding="utf-8")
        path.write_text(
            text.replace(
                "$JavaStartInfo.RedirectStandardError = $true",
                "$JavaStartInfo.RedirectStandardError = $false",
            ),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-039", self.ids())

        shutil.copy2(SOURCE / "tools/bootstrap-maven.ps1", path)
        path.write_text("$JavaVersion = (& java -version 2>&1 | Select-Object -First 1)\n", encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-039", self.ids())

    def test_go_module_must_exist_and_match(self) -> None:
        path = self.root / "src/applications/agent/go.mod"
        path.write_text(path.read_text(encoding="utf-8").replace("toolchain go1.26.5", "toolchain go1.25.0"), encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-009", self.ids())
        path.unlink()
        self.assertIn("CHECK-TOOLCHAIN-013", self.ids())


    def test_web_runtime_package_and_lockfile_are_strictly_pinned(self) -> None:
        package_path = self.root / "src/applications/web/package.json"
        package = json.loads(package_path.read_text(encoding="utf-8"))
        package["packageManager"] = "pnpm@latest"
        package["engines"] = {"node": ">=22"}
        package["private"] = False
        package["type"] = "commonjs"
        package["scripts"] = {}
        package["dependencies"] = {"example": "^1.0.0"}
        package["devDependencies"] = []
        package_path.write_text(json.dumps(package), encoding="utf-8")
        self.assertTrue({
            "CHECK-TOOLCHAIN-016",
            "CHECK-TOOLCHAIN-017",
            "CHECK-TOOLCHAIN-018",
            "CHECK-TOOLCHAIN-019",
            "CHECK-TOOLCHAIN-020",
            "CHECK-TOOLCHAIN-021",
        } <= self.ids())

        package_path.write_text("[]", encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-015", self.ids())
        package_path.write_text("{", encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-014", self.ids())

    def test_web_lockfile_is_required_and_uses_expected_format(self) -> None:
        lock_path = self.root / "src/applications/web/pnpm-lock.yaml"
        lock_path.write_text("lockfileVersion: '8.0'\n", encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-023", self.ids())
        lock_path.unlink()
        self.assertIn("CHECK-TOOLCHAIN-022", self.ids())

    def test_web_workspace_settings_match_lockfile_and_npmrc_is_forbidden(self) -> None:
        workspace_path = self.root / "src/applications/web/pnpm-workspace.yaml"
        workspace_path.write_text("autoInstallPeers: true\n", encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-032", self.ids())

        workspace_path.write_text("[", encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-031", self.ids())

        workspace_path.unlink()
        self.assertIn("CHECK-TOOLCHAIN-030", self.ids())

        shutil.copy2(SOURCE / "src/applications/web/pnpm-workspace.yaml", workspace_path)
        npmrc_path = self.root / "src/applications/web/.npmrc"
        npmrc_path.write_text("auto-install-peers=false\n", encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-033", self.ids())

    def test_ci_java_selector_and_action_pins_are_enforced(self) -> None:
        payload = self.lock()
        del payload["github_actions"]
        payload["toolchains"]["java"].pop("github_actions_version", None)
        self.write_lock(payload)
        self.assertIn("CHECK-TOOLCHAIN-025", self.ids())

        shutil.copy2(SOURCE / "toolchains.lock.json", self.root / "toolchains.lock.json")
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(workflow.replace("25.0.4+7.0.LTS", "25.0.4+7"), encoding="utf-8")
        self.assertTrue({"CHECK-TOOLCHAIN-026", "CHECK-TOOLCHAIN-029"} <= self.ids())

        shutil.copy2(SOURCE / ".github/workflows/foundation.yml", workflow_path)
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(workflow.replace(" java-workers-smoke", ""), encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-029", self.ids())

    def test_ci_web_bootstrap_is_exact_and_legacy_free(self) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow = workflow.replace("runtime: node@24.18.1", "runtime: node@24")
        workflow += "\n# actions/setup-node@legacy\n# corepack prepare pnpm@latest --activate\n"
        workflow_path.write_text(workflow, encoding="utf-8")
        self.assertTrue({"CHECK-TOOLCHAIN-027", "CHECK-TOOLCHAIN-028"} <= self.ids())

    def test_ci_requires_source_integrity_preflight_for_all_jobs(self) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace("needs: source-integrity", "# source integrity dependency removed", 1),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-037", self.ids())

        shutil.copy2(SOURCE / ".github/workflows/foundation.yml", workflow_path)
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace(
                "make source-integrity-test source-integrity-check",
                "echo source-integrity-disabled",
            ),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-037", self.ids())

        shutil.copy2(SOURCE / ".github/workflows/foundation.yml", workflow_path)
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace("SOURCE_INTEGRITY_REQUIRE_STAGED=1 ", ""),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-037", self.ids())

        shutil.copy2(SOURCE / ".github/workflows/foundation.yml", workflow_path)
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace("SOURCE_INTEGRITY_REQUIRE_CHECKSUMS=1 ", ""),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-037", self.ids())

    def test_ci_archive_compatibility_runs_on_unix_only(self) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace(
                "make archive-compatibility-test archive-compatibility-check",
                "echo archive-check-disabled",
            ),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-038", self.ids())

        shutil.copy2(SOURCE / ".github/workflows/foundation.yml", workflow_path)
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace("runs-on: ubuntu-24.04", "runs-on: windows-latest", 1),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-038", self.ids())

    def test_ci_prepares_maven_wrapper_before_every_direct_execution(self) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace("run: chmod 0755 mvnw && test -x mvnw", "run: test -f mvnw", 1),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-034", self.ids())

    def test_full_reactor_verify_must_report_all_failing_modules(self) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace("--no-transfer-progress --fail-at-end verify", "--no-transfer-progress verify", 1),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-040", self.ids())

    def test_ci_independently_verifies_java_modules_after_dependency_install(self) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace("run: make java-module-verify", "run: echo module-gate-disabled"),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-041", self.ids())

        shutil.copy2(SOURCE / ".github/workflows/foundation.yml", workflow_path)
        makefile_path = self.root / "Makefile"
        makefile = makefile_path.read_text(encoding="utf-8")
        makefile_path.write_text(
            makefile.replace("-Dmaven.test.skip=true -Djacoco.skip=true install", "-DskipTests -Djacoco.skip=true install", 1),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-041", self.ids())


    def test_java_coverage_jobs_require_live_postgresql_contracts(self) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace("run: make postgresql-test-schema", "run: echo schema-disabled", 1),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-042", self.ids())

        shutil.copy2(SOURCE / ".github/workflows/foundation.yml", workflow_path)
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace(",PostgreSqlJdbcEntitlementPersistenceTest", "", 1),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-042", self.ids())

    def test_docker_compose_runtime_gate_is_mandatory_and_fail_closed(self) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace("make compose-smoke", "echo compose-smoke-disabled", 1),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-043", self.ids())

        shutil.copy2(SOURCE / ".github/workflows/foundation.yml", workflow_path)
        compose_path = self.root / "src/deployment/docker/compose.yaml"
        compose = compose_path.read_text(encoding="utf-8")
        compose_path.write_text(compose.replace("internal: true", "internal: false", 1), encoding="utf-8")
        self.assertIn("CHECK-TOOLCHAIN-043", self.ids())

    def test_ci_targeted_reactor_test_tolerates_upstream_modules_without_matches(self) -> None:
        workflow_path = self.root / ".github/workflows/foundation.yml"
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace(" -Dinfranexum.surefire.failIfNoTests=false", ""),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-035", self.ids())

        shutil.copy2(SOURCE / ".github/workflows/foundation.yml", workflow_path)
        workflow = workflow_path.read_text(encoding="utf-8")
        workflow_path.write_text(
            workflow.replace(" -Dsurefire.failIfNoSpecifiedTests=false", ""),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-035", self.ids())

    def test_surefire_strict_default_is_overridable_for_targeted_reactor_jobs(self) -> None:
        pom_path = self.root / "pom.xml"
        pom = pom_path.read_text(encoding="utf-8")
        pom_path.write_text(
            pom.replace(
                "<failIfNoTests>${infranexum.surefire.failIfNoTests}</failIfNoTests>",
                "<failIfNoTests>true</failIfNoTests>",
            ),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-036", self.ids())

        shutil.copy2(SOURCE / "pom.xml", pom_path)
        pom = pom_path.read_text(encoding="utf-8")
        pom_path.write_text(
            pom.replace(
                "<infranexum.surefire.failIfNoTests>true</infranexum.surefire.failIfNoTests>",
                "",
            ),
            encoding="utf-8",
        )
        self.assertIn("CHECK-TOOLCHAIN-036", self.ids())

    def test_ci_workflow_is_required(self) -> None:
        (self.root / ".github/workflows/foundation.yml").unlink()
        self.assertIn("CHECK-TOOLCHAIN-024", self.ids())

    def test_external_paths_are_rendered_absolutely(self) -> None:
        checker = ToolchainChecker(self.root)
        external = self.root.parent / "outside.txt"
        checker._add("TEST", external, "outside")
        self.assertEqual(external.resolve().as_posix(), checker.violations[0].path)

    def test_cli_writes_report_and_returns_nonzero_on_drift(self) -> None:
        report = self.root / "reports/toolchains.json"
        with patch.object(sys, "argv", ["toolchains", "--root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, cli_main())
        self.assertEqual(0, json.loads(report.read_text(encoding="utf-8"))["violation_count"])
        self.assertIn("infranexum.toolchain-validation/v1", output.getvalue())

        (self.root / ".node-version").write_text("0.0.0\n", encoding="utf-8")
        with patch.object(sys, "argv", ["toolchains", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, cli_main())


if __name__ == "__main__":
    unittest.main()
