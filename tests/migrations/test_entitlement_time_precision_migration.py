from __future__ import annotations

import hashlib
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "src/distribution/migrations/0008-core-entitlement-time-precision"


class EntitlementTimePrecisionMigrationTest(unittest.TestCase):
    """Protect the whole-second temporal invariant and alpha.0.32 repair policy."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.postgresql = (MIGRATION / "postgresql.sql").read_text(encoding="utf-8")
        cls.oracle = (MIGRATION / "oracle.sql").read_text(encoding="utf-8")
        cls.rollback_postgresql = (MIGRATION / "rollback/postgresql.sql").read_text(encoding="utf-8")
        cls.rollback_oracle = (MIGRATION / "rollback/oracle.sql").read_text(encoding="utf-8")
        cls.descriptor = yaml.safe_load((MIGRATION / "migration.yaml").read_text(encoding="utf-8"))

    def test_postgresql_repairs_only_unsigned_installation_created_at(self) -> None:
        self.assertIn("UPDATE core_installation_identity", self.postgresql)
        self.assertIn("SET created_at = date_trunc('second', created_at)", self.postgresql)
        self.assertNotIn("UPDATE core_entitlement_state", self.postgresql)
        self.assertNotIn("UPDATE core_entitlement_integrity_proof", self.postgresql)
        self.assertNotIn("UPDATE core_activation_manifest", self.postgresql)
        self.assertIn("cannot normalize consumed entitlement timestamps", self.postgresql)

    def test_postgresql_enforces_all_entitlement_whole_second_boundaries(self) -> None:
        for constraint in (
            "ck_core_installation_created_second",
            "ck_core_entitlement_time_second",
            "ck_core_integrity_time_second",
            "ck_core_activation_time_second",
        ):
            self.assertIn(constraint, self.postgresql)
        for column in (
            "created_at", "evaluation_started_at", "last_reliable_at", "valid_until",
            "grace_until", "updated_at", "valid_from", "issued_at", "accepted_at",
        ):
            self.assertIn(column, self.postgresql)

    def test_oracle_fails_closed_instead_of_rewriting_temporal_data(self) -> None:
        self.assertIn("RAISE_APPLICATION_ERROR(-20081", self.oracle)
        self.assertIn("EXTRACT(SECOND FROM", self.oracle)
        self.assertNotIn("UPDATE core_installation_identity", self.oracle)
        self.assertNotIn("UPDATE core_activation_manifest", self.oracle)

    def test_rollback_only_drops_constraints(self) -> None:
        for text in (self.rollback_postgresql, self.rollback_oracle):
            self.assertNotIn("UPDATE ", text.upper())
            self.assertNotIn("INSERT ", text.upper())
            self.assertIn("DROP CONSTRAINT", text.upper())

    def test_descriptor_checksums_cover_all_paired_artifacts(self) -> None:
        for relative, expected in self.descriptor["checksums"].items():
            actual = hashlib.sha256((MIGRATION / relative).read_bytes()).hexdigest()
            self.assertEqual(expected, actual, relative)
        self.assertEqual(["0004", "0007"], self.descriptor["dependencies"])


if __name__ == "__main__":
    unittest.main()
