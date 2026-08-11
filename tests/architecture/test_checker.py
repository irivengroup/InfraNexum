
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from validation.architecture.checker import ArchitectureChecker


SOURCE_ROOT = Path(__file__).resolve().parents[2]
POLICY = SOURCE_ROOT / "validation/architecture/policy.json"


class ArchitectureCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repository"
        shutil.copytree(SOURCE_ROOT, self.root, ignore=shutil.ignore_patterns(".git", ".coverage", "__pycache__", "coverage.out", "target", "bin"))

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_check(self):
        return ArchitectureChecker(self.root, self.root / "validation/architecture/policy.json").run()

    def assert_check(self, check_id: str) -> None:
        report = self.run_check()
        self.assertIn(check_id, {violation.check_id for violation in report.violations}, report.to_dict())

    def test_repository_passes(self) -> None:
        report = self.run_check()
        self.assertTrue(report.ok, report.to_dict())
        self.assertEqual("PASS", report.to_dict()["status"])

    def test_clean_generated_target_removes_artifacts_portably(self) -> None:
        generated_directory = self.root / "bin"
        generated_directory.mkdir(exist_ok=True)
        (generated_directory / "generated-binary").write_text("generated", encoding="utf-8")
        cache = self.root / "validation" / "__pycache__"
        cache.mkdir(exist_ok=True)
        (cache / "generated.pyc").write_bytes(b"generated")

        completed = subprocess.run(
            ["make", "clean-generated"],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertFalse(generated_directory.exists())
        self.assertFalse(cache.exists())

    def test_source_files_outside_governed_roots_are_blocked(self) -> None:
        rogue = self.root / "rogue.py"
        rogue.write_text("print('outside source root')\n", encoding="utf-8")
        self.assert_check("CHECK-ARCH-SRC-002")

    def test_source_root_policy_is_validated(self) -> None:
        policy_path = self.root / "validation/architecture/policy.json"
        payload = json.loads(policy_path.read_text(encoding="utf-8"))
        payload["source_root"] = ""
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-ARCH-SRC-001")

        payload["source_root"] = "."
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        self.assertNotIn("CHECK-ARCH-SRC-001", {item.check_id for item in self.run_check().violations})

        payload["source_root"] = "../escape"
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-ARCH-SRC-001")

        payload["source_root"] = "missing-sources"
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        ids = {item.check_id for item in self.run_check().violations}
        self.assertTrue({"CHECK-ARCH-SRC-003", "CHECK-ARCH-ROOT-001"} <= ids)

    def test_invalid_allowed_code_roots_policy_is_blocked(self) -> None:
        policy_path = self.root / "validation/architecture/policy.json"
        payload = json.loads(policy_path.read_text(encoding="utf-8"))
        payload["allowed_code_roots"] = ["applications/nested"]
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-ARCH-SRC-004")

    def test_invalid_support_roots_policy_is_blocked(self) -> None:
        policy_path = self.root / "validation/architecture/policy.json"
        payload = json.loads(policy_path.read_text(encoding="utf-8"))
        payload["allowed_support_roots"] = ["tests/nested"]
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-ARCH-SRC-005")

    def test_tests_are_allowed_outside_product_source_root(self) -> None:
        support = self.root / "tests" / "outside_product.py"
        support.write_text("VALUE = 1\n", encoding="utf-8")
        report = self.run_check()
        violations = [item for item in report.violations if item.path == "tests/outside_product.py"]
        self.assertEqual([], violations)

    def test_manifest_id_override_preserves_logical_adapter_identity(self) -> None:
        report = self.run_check()
        self.assertNotIn("CHECK-ARCH-MANIFEST-005", {item.check_id for item in report.violations})
        policy_path = self.root / "validation/architecture/policy.json"
        payload = json.loads(policy_path.read_text(encoding="utf-8"))
        payload["manifest_id_overrides"] = []
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-ARCH-MANIFEST-010")

    def test_missing_structural_space_is_blocked(self) -> None:
        shutil.rmtree(self.root / "src/engines")
        self.assert_check("CHECK-ARCH-ROOT-001")

    def test_fourth_application_is_blocked(self) -> None:
        (self.root / "src/applications/worker").mkdir()
        self.assert_check("CHECK-ARCH-APP-001")

    def test_missing_and_invalid_manifest_are_blocked(self) -> None:
        (self.root / "src/sdk/MANIFEST.json").unlink()
        self.assert_check("CHECK-ARCH-MANIFEST-001")
        (self.root / "src/sdk/MANIFEST.json").write_text("[]", encoding="utf-8")
        self.assert_check("CHECK-ARCH-MANIFEST-002")

    def test_manifest_contract_errors_are_blocked(self) -> None:
        path = self.root / "src/applications/server/MANIFEST.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        del payload["kind"]
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-ARCH-MANIFEST-003")

        payload["kind"] = "application"
        payload["schema"] = "wrong"
        payload["id"] = "wrong"
        payload["owner"] = "unknown"
        payload["lifecycle"] = "mystery"
        payload["source_baseline"] = []
        path.write_text(json.dumps(payload), encoding="utf-8")
        ids = {v.check_id for v in self.run_check().violations}
        self.assertTrue({"CHECK-ARCH-MANIFEST-004", "CHECK-ARCH-MANIFEST-005", "CHECK-ARCH-OWNER-001", "CHECK-ARCH-MANIFEST-006", "CHECK-ARCH-TRACE-001"} <= ids)

    def test_manifest_array_types_are_blocked(self) -> None:
        path = self.root / "src/applications/server/MANIFEST.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["languages"] = "java"
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-ARCH-MANIFEST-007")
        payload["languages"] = ["java"]
        payload["dependencies"] = "components.core.contracts"
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-ARCH-MANIFEST-008")

    def test_owner_registry_errors_are_blocked(self) -> None:
        path = self.root / "OWNERS.json"
        path.write_text("[]", encoding="utf-8")
        self.assert_check("CHECK-ARCH-OWNER-002")
        path.write_text(json.dumps({"owners": "invalid"}), encoding="utf-8")
        self.assert_check("CHECK-ARCH-OWNER-003")
        path.write_text(json.dumps({"owners": [{"id": "x"}, {"name": "missing"}]}), encoding="utf-8")
        self.assert_check("CHECK-ARCH-OWNER-004")

    def test_dependency_errors_are_blocked(self) -> None:
        path = self.root / "src/applications/server/MANIFEST.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["dependencies"] = ["applications.server", "missing.component", "missing.component"]
        path.write_text(json.dumps(payload), encoding="utf-8")
        ids = {v.check_id for v in self.run_check().violations}
        self.assertTrue({"CHECK-ARCH-DEP-001", "CHECK-ARCH-DEP-002", "CHECK-ARCH-DEP-003"} <= ids)

    def test_language_and_namespace_errors_are_blocked(self) -> None:
        bad_go = self.root / "src/applications/server/main/bad.go"
        bad_go.parent.mkdir(parents=True, exist_ok=True)
        bad_go.write_text("package bad", encoding="utf-8")
        bad_java = self.root / "src/components/core/contracts/main/Bad.java"
        bad_java.write_text("package wrong;", encoding="utf-8")
        ids = {v.check_id for v in self.run_check().violations}
        self.assertTrue({"CHECK-ARCH-LANG-001", "CHECK-ARCH-NS-001"} <= ids)

    def test_active_component_without_source_is_blocked(self) -> None:
        shutil.rmtree(self.root / "src/components/core/contracts/main")
        self.assert_check("CHECK-ARCH-CODE-001")

    def test_legacy_brand_and_artifact_are_blocked(self) -> None:
        legacy = self.root / "src/sdk/legacy.md"
        legacy.write_text("legacy open" + "infra namespace", encoding="utf-8")
        artifact = self.root / "src/sdk/__pycache__"
        artifact.mkdir()
        ids = {v.check_id for v in self.run_check().violations}
        self.assertTrue({"CHECK-BRAND-001", "CHECK-REPO-CLEAN-001"} <= ids)

    def test_git_metadata_is_excluded_from_repository_scans(self) -> None:
        git_dir = self.root / ".git"
        (git_dir / "__pycache__").mkdir(parents=True)
        (git_dir / "legacy.py").write_text("open" + "infra", encoding="utf-8")

        report = self.run_check()
        git_violations = [item for item in report.violations if item.path.startswith(".git/")]

        self.assertEqual([], git_violations)

    def test_secret_material_is_blocked_without_echoing_it(self) -> None:
        secret_path = self.root / "src/applications/agent/configs/compromised.json"
        secret_path.parent.mkdir(parents=True, exist_ok=True)
        fake_access_key = "AK" + "IA" + "ABCDEFGHIJKLMNOP"
        secret_path.write_text(json.dumps({"access_key": fake_access_key}), encoding="utf-8")
        report = self.run_check()
        violations = [item for item in report.violations if item.check_id == "CHECK-SECRET-001"]
        self.assertEqual(1, len(violations))
        self.assertNotIn(fake_access_key, violations[0].message)

    def test_secret_material_is_scanned_in_web_runtime_modules(self) -> None:
        secret_path = self.root / "src/applications/web/runtime/compromised.mjs"
        secret_path.parent.mkdir(parents=True, exist_ok=True)
        fake_token = "gh" + "p_" + "ABCDEFGHIJKLMNOPQRSTUVWX"
        secret_path.write_text(f"export const token = {fake_token!r};\n", encoding="utf-8")
        violations = [
            item for item in self.run_check().violations if item.check_id == "CHECK-SECRET-001"
        ]
        self.assertEqual(1, len(violations))
        self.assertNotIn(fake_token, violations[0].message)

    def test_invalid_secret_policy_is_blocked(self) -> None:
        policy_path = self.root / "validation/architecture/policy.json"
        payload = json.loads(policy_path.read_text(encoding="utf-8"))
        payload["secret_patterns"] = [{"id": "broken", "regex": "["}]
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-SECRET-POLICY-001")

    def test_invalid_secret_policy_field_types_are_blocked(self) -> None:
        policy_path = self.root / "validation/architecture/policy.json"
        payload = json.loads(policy_path.read_text(encoding="utf-8"))
        payload["secret_patterns"] = [{"id": 42, "regex": "value"}]
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-SECRET-POLICY-001")

    def test_secret_scan_skips_generated_large_and_non_utf8_files(self) -> None:
        generated = self.root / "src/sdk/node_modules/generated.json"
        generated.parent.mkdir(parents=True)
        generated.write_text("AK" + "IA" + "ABCDEFGHIJKLMNOP", encoding="utf-8")
        large = self.root / "src/sdk/large.json"
        large.write_bytes(b"x" * 1_048_577)
        binary = self.root / "src/sdk/binary.json"
        binary.write_bytes(b"\xff\xfe\xfd")
        secret_violations = [
            item for item in self.run_check().violations if item.check_id == "CHECK-SECRET-001"
        ]
        self.assertEqual([], secret_violations)

    def test_missing_applications_directory_is_handled(self) -> None:
        shutil.rmtree(self.root / "src/applications")
        report = self.run_check()
        self.assertIn("CHECK-ARCH-ROOT-001", {violation.check_id for violation in report.violations})

    def test_duplicate_manifest_identifier_is_blocked(self) -> None:
        path = self.root / "src/applications/server/MANIFEST.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["id"] = "applications.agent"
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assert_check("CHECK-ARCH-MANIFEST-009")

    def test_legacy_agent_import_namespace_is_blocked(self) -> None:
        path = self.root / "src/applications/agent/internal/legacy/legacy.go"
        path.parent.mkdir(parents=True)
        legacy_prefix = "open" + "infra"
        path.write_text(
            f'package legacy\n\nimport _ "{legacy_prefix}/agent/internal/config"\n',
            encoding="utf-8",
        )
        self.assert_check("CHECK-ARCH-NS-002")

    def test_external_violation_path_is_rendered_safely(self) -> None:
        checker = ArchitectureChecker(self.root, self.root / "validation/architecture/policy.json")
        checker._add("CHECK-TEST", Path(self.temp.name).parent / "outside", "outside root")
        self.assertEqual("CHECK-TEST", checker.violations[0].check_id)
        self.assertTrue(checker.violations[0].path.endswith("outside"))

    def test_report_is_deterministic_and_serializable(self) -> None:
        report = self.run_check()
        payload = report.to_dict()
        json.dumps(payload, sort_keys=True)
        self.assertEqual(0, payload["violation_count"])
        self.assertEqual(".", payload["root"])
        self.assertEqual("validation/architecture/policy.json", payload["policy"])


if __name__ == "__main__":
    unittest.main()
