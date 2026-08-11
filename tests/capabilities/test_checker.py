from __future__ import annotations

import contextlib
import csv
import io
import json
import runpy
import shutil
import sys
import tempfile
import unittest
import warnings
from pathlib import Path
from unittest.mock import patch

from validation.capabilities.checker import CapabilityChecker
from validation.capabilities.cli import main as cli_main

SOURCE = Path(__file__).resolve().parents[2]
FILES = (
    "pom.xml",
    "validation/architecture/policy.json",
    "src/components/core/capabilities/MANIFEST.json",
    "src/components/core/capabilities/pom.xml",
    "src/components/core/capabilities/resources/io/infranexum/core/capabilities/capability-contract-pack.json",
    "src/components/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv",
    "src/components/core/capabilities/resources/io/infranexum/core/capabilities/quota-catalog.csv",
    "src/components/core/capabilities/resources/io/infranexum/core/capabilities/quota-policy.json",
    "src/components/core/capabilities/main/io/infranexum/core/capabilities/CapabilityRegistry.java",
    "src/components/core/capabilities/main/io/infranexum/core/capabilities/CapabilityEnvironment.java",
    "src/components/core/capabilities/main/io/infranexum/core/capabilities/QuotaCatalog.java",
    "src/components/core/capabilities/main/io/infranexum/core/capabilities/QuotaPolicy.java",
    "src/applications/server/main/io/infranexum/server/platform/PlatformCapabilityController.java",
    "src/applications/server/main/io/infranexum/server/platform/PlatformCapabilityConfiguration.java",
    "src/applications/server/pom.xml",
    "src/applications/server/MANIFEST.json",
    "src/components/core/capabilities/main/io/infranexum/core/capabilities/QuotaDefinition.java",
)


class CapabilityCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repository"
        self.root.mkdir()
        for relative in FILES:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE / relative, target)
        (self.root / "src/components/domains").mkdir(parents=True, exist_ok=True)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def ids(self) -> set[str]:
        return {item.check_id for item in CapabilityChecker(self.root).run()}

    def reset(self, relative: str) -> None:
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SOURCE / relative, target)

    def mutate(self, relative: str, old: str, new: str = "") -> None:
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1), encoding="utf-8")

    def read_csv(self, relative: str) -> tuple[list[str], list[dict[str, str]]]:
        with (self.root / relative).open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            return list(reader.fieldnames or []), list(reader)

    def write_csv(self, relative: str, fields: list[str], rows: list[dict[str, str]]) -> None:
        with (self.root / relative).open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def test_reference_contract_is_valid(self) -> None:
        self.assertEqual(set(), self.ids())

    def test_missing_required_files_are_reported(self) -> None:
        for relative in (FILES[2], FILES[4], FILES[5], FILES[6], FILES[7], FILES[8], FILES[10], FILES[12], FILES[16]):
            path = self.root / relative
            saved = path.read_bytes()
            path.unlink()
            self.assertIn("CHECK-CAP-FILES-001", self.ids())
            path.write_bytes(saved)

    def test_contract_pack_metadata_checksums_and_shape_are_enforced(self) -> None:
        path = self.root / FILES[4]
        payload = json.loads(path.read_text())
        payload["quota_count"] = 1
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assertIn("CHECK-CAP-PACK-002", self.ids())
        self.reset(FILES[4])
        payload = json.loads(path.read_text())
        payload["files"] = []
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assertIn("CHECK-CAP-PACK-003", self.ids())
        self.reset(FILES[4])
        payload = json.loads(path.read_text())
        payload["files"]["quota-catalog.csv"] = "bad"
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assertIn("CHECK-CAP-PACK-004", self.ids())
        self.reset(FILES[4])
        self.mutate(FILES[6], "core,core.organizations.max", "core,core.organizations.changed")
        self.assertIn("CHECK-CAP-PACK-005", self.ids())
        path.write_text("[]", encoding="utf-8")
        self.assertIn("CHECK-CAP-PACK-001", self.ids())

    def test_quota_count_columns_duplicates_keys_and_values_are_enforced(self) -> None:
        fields, rows = self.read_csv(FILES[6])
        rows.pop()
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-003", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[0]["extra"] = "x"
        self.write_csv(FILES[6], fields + ["extra"], rows)
        self.assertIn("CHECK-CAP-QUOTA-004", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[1]["quota_key"] = rows[0]["quota_key"]
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-005", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[0]["quota_key"] = "INVALID"
        self.write_csv(FILES[6], fields, rows)
        self.assertTrue({"CHECK-CAP-QUOTA-006", "CHECK-CAP-QUOTA-007"} <= self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[0]["quota_class"] = "bad"
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-008", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[0]["generator_adjustable"] = "false"
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-009", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[0]["lite_fixed"] = "bad"
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-010", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[0]["lite_fixed"] = "-1"
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-011", self.ids())

    def test_quota_ceiling_ratio_fixed_scope_and_policy_are_enforced(self) -> None:
        fields, rows = self.read_csv(FILES[6])
        rows[0]["pro_advanced_ceiling"] = "1"
        rows[0]["pro_standard"] = "2"
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-012", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[0]["enterprise_ultimate_ceiling"] = "1"
        rows[0]["enterprise_standard"] = "2"
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-013", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[0]["pro_advanced_ceiling"] = rows[0]["enterprise_standard"]
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-014", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        fixed = next(row for row in rows if row["quota_class"] == "architectural_fixed")
        fixed["component"] = "core"
        fixed["quota_key"] = "core.nodes.max"
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-015", self.ids())
        self.reset(FILES[6])
        fields, rows = self.read_csv(FILES[6])
        rows[0]["quota_class"] = "architectural_fixed"
        rows[0]["generator_adjustable"] = "false"
        self.write_csv(FILES[6], fields, rows)
        self.assertIn("CHECK-CAP-QUOTA-016", self.ids())
        self.reset(FILES[6])
        policy_path = self.root / FILES[7]
        payload = json.loads(policy_path.read_text())
        payload["quotas"].pop(next(iter(payload["quotas"])))
        payload["catalog_version"] = "wrong"
        payload["rules"]["tiers_do_not_unlock_capabilities"] = False
        payload["rules"]["architectural_quotas_are_not_generator_adjustable"] = False
        policy_path.write_text(json.dumps(payload), encoding="utf-8")
        self.assertTrue({
            "CHECK-CAP-QUOTA-017", "CHECK-CAP-QUOTA-018", "CHECK-CAP-QUOTA-019", "CHECK-CAP-QUOTA-020"
        } <= self.ids())

    def test_capability_catalogue_restrictions_codes_and_flags_are_enforced(self) -> None:
        fields, rows = self.read_csv(FILES[5])
        rows.pop()
        self.write_csv(FILES[5], fields, rows)
        self.assertIn("CHECK-CAP-CATALOG-003", self.ids())
        self.reset(FILES[5])
        fields, rows = self.read_csv(FILES[5])
        rows[1]["capability_code"] = rows[0]["capability_code"]
        self.write_csv(FILES[5], fields, rows)
        self.assertIn("CHECK-CAP-CATALOG-004", self.ids())
        self.reset(FILES[5])
        fields, rows = self.read_csv(FILES[5])
        next(row for row in rows if row["capability_code"] == "agent.enabled")["allowed_profiles"] = "pro"
        next(row for row in rows if row["capability_code"] == "iam.ldap")["allowed_profiles"] = "enterprise"
        next(row for row in rows if row["capability_code"] == "iam.local-auth")["allowed_profiles"] = "pro"
        rows[0]["capability_code"] = "INVALID"
        rows[0]["activation_protected"] = "maybe"
        self.write_csv(FILES[5], fields, rows)
        self.assertTrue({
            "CHECK-CAP-CATALOG-005", "CHECK-CAP-CATALOG-006", "CHECK-CAP-CATALOG-007",
            "CHECK-CAP-CATALOG-008", "CHECK-CAP-CATALOG-009"
        } <= self.ids())

    def test_java_invariants_and_no_tier_surface_drift_are_enforced(self) -> None:
        registry_path = self.root / FILES[8]
        registry_path.write_text(
            registry_path.read_text(encoding="utf-8").replace("PROFILE_CAPABILITY_NOT_INSTALLED", "REMOVED"),
            encoding="utf-8",
        )
        self.assertIn("CHECK-CAP-JAVA-003", self.ids())
        self.reset(FILES[8])
        with (self.root / FILES[8]).open("a", encoding="utf-8") as stream:
            stream.write("\n// environment.allocationTier()\n")
        self.assertIn("CHECK-CAP-JAVA-004", self.ids())
        self.mutate(FILES[10], "architectural quota cannot be overridden")
        self.assertIn("CHECK-CAP-JAVA-005", self.ids())
        self.reset(FILES[10])
        self.mutate(FILES[16], "Math.multiplyExact(proAdvancedCeiling, 2L) >= enterpriseStandard")
        self.assertIn("CHECK-CAP-JAVA-005", self.ids())

    def test_reactor_server_manifest_and_policy_wiring_are_enforced(self) -> None:
        self.mutate(FILES[0], "    <module>src/components/core/capabilities</module>\n")
        self.assertIn("CHECK-CAP-WIRING-005", self.ids())
        self.reset(FILES[0])
        self.mutate(FILES[14], "infranexum-core-capabilities")
        self.assertIn("CHECK-CAP-WIRING-006", self.ids())
        self.reset(FILES[14])
        manifest = self.root / FILES[15]
        payload = json.loads(manifest.read_text())
        payload["dependencies"].remove("components.core.capabilities")
        manifest.write_text(json.dumps(payload), encoding="utf-8")
        self.assertIn("CHECK-CAP-WIRING-007", self.ids())
        self.reset(FILES[15])
        policy = self.root / FILES[1]
        payload = json.loads(policy.read_text())
        payload["required_manifest_paths"].remove("components/core/capabilities")
        policy.write_text(json.dumps(payload), encoding="utf-8")
        self.assertIn("CHECK-CAP-WIRING-008", self.ids())

    def test_domain_profile_branching_is_blocked(self) -> None:
        path = self.root / "src/components/domains/example/ProfileDriven.java"
        path.parent.mkdir(parents=True)
        path.write_text("class ProfileDriven { Object x = InstallationProfile.PRO; }", encoding="utf-8")
        self.assertIn("CHECK-CAP-DOMAIN-002", self.ids())
        path.unlink()
        broken = self.root / "src/components/domains/example/Broken.java"
        broken.write_text("class Broken {}", encoding="utf-8")
        with patch.object(Path, "read_text", side_effect=OSError("denied")):
            self.assertIn("CHECK-CAP-DOMAIN-001", self.ids())

    def test_malformed_inputs_external_paths_and_cli_are_covered(self) -> None:
        (self.root / FILES[6]).write_text('"unterminated', encoding="utf-8")
        self.assertIn("CHECK-CAP-QUOTA-001", self.ids())
        self.reset(FILES[6])
        (self.root / FILES[7]).write_text("{", encoding="utf-8")
        self.assertIn("CHECK-CAP-QUOTA-002", self.ids())
        self.reset(FILES[7])
        (self.root / FILES[8]).unlink()
        self.assertIn("CHECK-CAP-JAVA-001", self.ids())

        checker = CapabilityChecker(self.root)
        outside = self.root.parent / "outside"
        checker._add("TEST", outside, "outside")
        self.assertEqual(outside.resolve().as_posix(), checker.violations[0].path)

        report = self.root / "reports/capabilities.json"
        self.reset(FILES[8])
        with patch.object(sys, "argv", ["capabilities", "--root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, cli_main())
        self.assertEqual(0, json.loads(report.read_text())["violation_count"])
        (self.root / FILES[8]).unlink()
        with patch.object(sys, "argv", ["capabilities", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, cli_main())
        with patch.object(sys, "argv", ["capabilities", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()), warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_module("validation.capabilities.cli", run_name="__main__")
        self.assertEqual(1, raised.exception.code)


if __name__ == "__main__":
    unittest.main()
