from __future__ import annotations

import hashlib
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "src/distribution/migrations"


class LocalIdentityRepairMigrationTest(unittest.TestCase):
    def test_catalogue_declares_foundation_and_repair_in_order(self) -> None:
        catalogue = yaml.safe_load((MIGRATIONS / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [entry["id"] for entry in catalogue["entries"]]
        foundation_index = ids.index("0011")
        self.assertEqual("0012", ids[foundation_index + 1])
        repair = next(entry for entry in catalogue["entries"] if entry["id"] == "0012")
        self.assertEqual(
            "0012-local-identity-repair/migration.yaml",
            repair["path"],
        )

    def test_foundation_verification_is_machine_validatable(self) -> None:
        verification = yaml.safe_load(
            (MIGRATIONS / "0011-local-identity-foundation/verify.sql.yaml").read_text(encoding="utf-8")
        )
        self.assertGreaterEqual(len(verification["checks"]), 6)
        for check in verification["checks"]:
            self.assertTrue(check["id"])
            self.assertTrue(check["postgresql"])
            self.assertTrue(check["oracle"])

    def test_repair_is_idempotent_non_destructive_and_checksummed(self) -> None:
        migration = MIGRATIONS / "0012-local-identity-repair"
        manifest = yaml.safe_load((migration / "migration.yaml").read_text(encoding="utf-8"))
        self.assertEqual("0012", manifest["id"])
        self.assertEqual(["0011"], manifest["dependencies"])
        postgresql = (migration / "postgresql.sql").read_text(encoding="utf-8")
        self.assertIn("CREATE TABLE IF NOT EXISTS infranexum_iam.local_account", postgresql)
        self.assertIn("CREATE TABLE IF NOT EXISTS infranexum_iam.local_session", postgresql)
        self.assertNotIn("DROP TABLE", postgresql.upper())
        rollback = (migration / "rollback/postgresql.sql").read_text(encoding="utf-8")
        self.assertNotIn("DROP TABLE", rollback.upper())
        for relative, expected in manifest["checksums"].items():
            actual = hashlib.sha256((migration / relative).read_bytes()).hexdigest()
            self.assertEqual(expected, actual, relative)


if __name__ == "__main__":
    unittest.main()
