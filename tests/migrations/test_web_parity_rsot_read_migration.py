"""Migration regressions for the alpha.0.80 canonical RSOT read permission."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class WebParityRsotReadMigrationTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    MIGRATIONS = ROOT / "src/distribution/migrations"
    MIGRATION = MIGRATIONS / "0025-identity-access-rsot-read-permission"

    def test_catalogue_orders_rsot_read_after_itam_compliance(self) -> None:
        catalogue = yaml.safe_load((self.MIGRATIONS / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertLess(ids.index("0024"), ids.index("0025"))
        self.assertEqual("1.24.0", catalogue["version"])
        self.assertEqual("0025-identity-access-rsot-read-permission/migration.yaml", catalogue["entries"][-1]["path"])

    def test_permission_is_cdc_named_organization_scoped_and_bootstrapped(self) -> None:
        logical = json.loads((self.MIGRATION / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(["rsot.read"], logical["permissions"])
        self.assertEqual("ORGANIZATION", logical["scope_kind"])
        self.assertEqual("system.platform_admin", logical["bootstrap_role"])
        for relative in ("postgresql.sql", "oracle.sql"):
            sql = (self.MIGRATION / relative).read_text(encoding="utf-8")
            self.assertIn("rsot.read", sql)
            self.assertIn("ORGANIZATION", sql)
            self.assertNotIn("rsot.object.read", sql)

    def test_descriptor_checksums_and_rollbacks_are_exact(self) -> None:
        descriptor = yaml.safe_load((self.MIGRATION / "migration.yaml").read_text(encoding="utf-8"))
        self.assertEqual("0025", str(descriptor["id"]).zfill(4))
        self.assertEqual(["0024"], [str(value).zfill(4) for value in descriptor["dependencies"]])
        for relative, expected in descriptor["checksums"].items():
            self.assertEqual(expected, hashlib.sha256((self.MIGRATION / relative).read_bytes()).hexdigest(), relative)
        for relative in ("rollback/postgresql.sql", "rollback/oracle.sql"):
            rollback = (self.MIGRATION / relative).read_text(encoding="utf-8")
            self.assertIn("rsot.read", rollback)
            self.assertNotIn("DROP TABLE", rollback.upper())


if __name__ == "__main__":
    unittest.main()
