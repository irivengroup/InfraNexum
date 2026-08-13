from __future__ import annotations

import contextlib
import hashlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

from validation.migrations.checker import MigrationChecker
from validation.migrations.cli import main as cli_main

SOURCE = Path(__file__).resolve().parents[2] / "src/distribution/migrations"


class MigrationCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "migrations"
        shutil.copytree(SOURCE, self.root)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def ids(self) -> set[str]:
        return {item.check_id for item in MigrationChecker(self.root).run()}

    def catalogue(self) -> dict:
        return yaml.safe_load((self.root / "catalogue.yaml").read_text(encoding="utf-8"))

    def write_catalogue(self, payload: object) -> None:
        (self.root / "catalogue.yaml").write_text(
            yaml.safe_dump(payload, sort_keys=False), encoding="utf-8"
        )

    def descriptor_path(self) -> Path:
        return self.root / "0001-core-schema-history/migration.yaml"

    def descriptor(self) -> dict:
        return yaml.safe_load(self.descriptor_path().read_text(encoding="utf-8"))

    def write_descriptor(self, payload: object) -> None:
        self.descriptor_path().write_text(
            yaml.safe_dump(payload, sort_keys=False), encoding="utf-8"
        )

    def refresh_checksum(self, relative: str) -> None:
        payload = self.descriptor()
        path = self.descriptor_path().parent / relative
        payload["checksums"][relative] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.write_descriptor(payload)

    def test_reference_catalogue_is_valid(self) -> None:
        self.assertEqual(set(), self.ids())

    def test_missing_invalid_and_non_object_catalogue_are_blocked(self) -> None:
        (self.root / "catalogue.yaml").unlink()
        self.assertEqual({"CHECK-MIG-CATALOGUE-001"}, self.ids())
        (self.root / "catalogue.yaml").write_text("entries: [", encoding="utf-8")
        self.assertEqual({"CHECK-MIG-CATALOGUE-001"}, self.ids())
        self.write_catalogue(["not-an-object"])
        self.assertEqual(set(), self.ids())

    def test_catalogue_entries_must_be_an_array(self) -> None:
        self.write_catalogue({"entries": "invalid"})
        self.assertEqual({"CHECK-MIG-CATALOGUE-002"}, self.ids())

    def test_orphan_migration_directory_is_blocked(self) -> None:
        orphan = self.root / "9998-orphan"
        orphan.mkdir()
        (orphan / "migration.yaml").write_text("id: '9998'\n", encoding="utf-8")
        self.assertIn("CHECK-MIG-CATALOGUE-004", self.ids())

    def test_catalogue_entries_require_string_id_and_path(self) -> None:
        self.write_catalogue({"entries": [None, {"id": 1, "path": "x"}, {"id": "0001"}]})
        self.assertEqual({"CHECK-MIG-CATALOGUE-003"}, self.ids())

    def test_ids_must_be_valid_unique_and_increasing(self) -> None:
        payload = self.catalogue()
        original = payload["entries"][0]
        payload["entries"] = [
            {"id": "bad", "path": original["path"]},
            {"id": "bad", "path": original["path"]},
        ]
        self.write_catalogue(payload)
        self.assertTrue({"CHECK-MIG-ID-001", "CHECK-MIG-ID-002", "CHECK-MIG-ID-003", "CHECK-MIG-ID-004"} <= self.ids())

    def test_catalogue_path_cannot_escape_root(self) -> None:
        self.write_catalogue({"entries": [{"id": "0001", "path": "../outside.yaml"}]})
        violations = MigrationChecker(self.root).run()
        self.assertEqual("CHECK-MIG-PATH-001", violations[0].check_id)
        self.assertTrue(violations[0].path.endswith("outside.yaml"))

    def test_missing_invalid_and_non_object_descriptor_are_blocked(self) -> None:
        self.descriptor_path().unlink()
        self.assertIn("CHECK-MIG-DESCRIPTOR-001", self.ids())
        self.descriptor_path().write_text("id: [", encoding="utf-8")
        self.assertIn("CHECK-MIG-DESCRIPTOR-001", self.ids())
        self.write_descriptor(["not-an-object"])
        self.assertEqual(set(), self.ids())

    def test_descriptor_id_and_required_fields_are_enforced(self) -> None:
        payload = self.descriptor()
        payload["id"] = "0002"
        del payload["owner_context"]
        self.write_descriptor(payload)
        self.assertTrue({"CHECK-MIG-ID-004", "CHECK-MIG-DESCRIPTOR-002"} <= self.ids())

    def test_dependencies_must_be_string_array(self) -> None:
        payload = self.descriptor()
        payload["dependencies"] = [1]
        self.write_descriptor(payload)
        self.assertIn("CHECK-MIG-DEP-001", self.ids())

    def test_unknown_and_non_preceding_dependencies_are_distinct(self) -> None:
        payload = self.descriptor()
        payload["dependencies"] = ["9999"]
        self.write_descriptor(payload)
        self.assertIn("CHECK-MIG-DEP-002", self.ids())
        self.assertNotIn("CHECK-MIG-DEP-003", self.ids())

        payload["dependencies"] = ["0001"]
        self.write_descriptor(payload)
        self.assertIn("CHECK-MIG-DEP-003", self.ids())

    def test_checksums_must_be_an_object(self) -> None:
        payload = self.descriptor()
        payload["checksums"] = []
        self.write_descriptor(payload)
        self.assertEqual({"CHECK-MIG-CHECKSUM-001"}, self.ids())

    def test_missing_paired_variant_is_blocked(self) -> None:
        (self.root / "0001-core-schema-history/oracle.sql").unlink()
        self.assertIn("CHECK-MIG-PAIR-001", self.ids())

    def test_missing_or_invalid_checksum_is_blocked(self) -> None:
        payload = self.descriptor()
        del payload["checksums"]["postgresql.sql"]
        payload["checksums"]["oracle.sql"] = "invalid"
        self.write_descriptor(payload)
        self.assertIn("CHECK-MIG-CHECKSUM-002", self.ids())

    def test_modified_file_is_blocked_by_checksum(self) -> None:
        path = self.root / "0001-core-schema-history/postgresql.sql"
        path.write_text(path.read_text(encoding="utf-8") + "-- modified\n", encoding="utf-8")
        self.assertIn("CHECK-MIG-CHECKSUM-003", self.ids())

    def test_invalid_or_empty_logical_model_is_blocked(self) -> None:
        path = self.root / "0001-core-schema-history/logical-model.json"
        path.write_text("{", encoding="utf-8")
        self.refresh_checksum("logical-model.json")
        self.assertIn("CHECK-MIG-MODEL-001", self.ids())

        path.write_text(json.dumps({"objects": []}), encoding="utf-8")
        self.refresh_checksum("logical-model.json")
        self.assertIn("CHECK-MIG-MODEL-002", self.ids())

    def test_invalid_verification_yaml_is_blocked(self) -> None:
        path = self.root / "0001-core-schema-history/verify.sql.yaml"
        path.write_text("checks: [", encoding="utf-8")
        self.refresh_checksum("verify.sql.yaml")
        self.assertIn("CHECK-MIG-VERIFY-001", self.ids())

    def test_verification_requires_nonempty_check_array(self) -> None:
        path = self.root / "0001-core-schema-history/verify.sql.yaml"
        path.write_text("checks: []\n", encoding="utf-8")
        self.refresh_checksum("verify.sql.yaml")
        self.assertIn("CHECK-MIG-VERIFY-002", self.ids())

        path.write_text("- not-an-object\n", encoding="utf-8")
        self.refresh_checksum("verify.sql.yaml")
        self.assertNotIn("CHECK-MIG-VERIFY-002", self.ids())

    def test_verification_requires_id_and_both_dialects(self) -> None:
        path = self.root / "0001-core-schema-history/verify.sql.yaml"
        path.write_text("checks:\n  - id: one\n    postgresql: SELECT 1\n", encoding="utf-8")
        self.refresh_checksum("verify.sql.yaml")
        self.assertIn("CHECK-MIG-VERIFY-003", self.ids())

    def test_external_violation_path_is_rendered_absolutely(self) -> None:
        checker = MigrationChecker(self.root)
        outside = self.root.parent / "outside.sql"
        checker._add("TEST", outside, "outside")
        self.assertEqual(outside.resolve().as_posix(), checker.violations[0].path)

    def test_cli_writes_report_and_returns_nonzero_on_failure(self) -> None:
        report = self.root / "reports/migrations.json"
        with patch.object(sys, "argv", ["migrations", "--root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, cli_main())
        payload = json.loads(report.read_text(encoding="utf-8"))
        self.assertEqual(0, payload["violation_count"])
        self.assertIn("infranexum.migration-validation/v1", output.getvalue())

        (self.root / "catalogue.yaml").unlink()
        with patch.object(sys, "argv", ["migrations", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, cli_main())


if __name__ == "__main__":
    unittest.main()
