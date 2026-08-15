"""Migration parity, custody integrity and permission regressions for PGM-07-E02."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class ItamAssetMigrationTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    MIGRATIONS = ROOT / "src/distribution/migrations"
    ASSET = MIGRATIONS / "0021-itam-asset-lifecycle"
    PERMISSIONS = MIGRATIONS / "0022-identity-access-itam-asset-permissions"

    def test_catalogue_orders_asset_lifecycle_after_partner_and_before_permissions(self) -> None:
        catalogue = yaml.safe_load((self.MIGRATIONS / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertLess(ids.index("0020"), ids.index("0021"))
        self.assertLess(ids.index("0021"), ids.index("0022"))
        self.assertGreaterEqual(tuple(map(int, catalogue["version"].split("."))), (1, 21, 0))

    def test_descriptors_have_exact_checksums_dependencies_and_ownership(self) -> None:
        for directory, migration_id, owner, dependency in (
            (self.ASSET, "0021", "itam", "0020"),
            (self.PERMISSIONS, "0022", "identity-access", "0021"),
        ):
            descriptor = yaml.safe_load((directory / "migration.yaml").read_text(encoding="utf-8"))
            self.assertEqual(migration_id, str(descriptor["id"]).zfill(4))
            self.assertEqual(owner, descriptor["owner_context"])
            self.assertEqual([dependency], [str(value).zfill(4) for value in descriptor["dependencies"]])
            for relative, expected in descriptor["checksums"].items():
                self.assertEqual(expected, hashlib.sha256((directory / relative).read_bytes()).hexdigest(), relative)

    def test_asset_schema_is_postgresql_oracle_symmetric_and_cross_context_refs_are_weak(self) -> None:
        pg = (self.ASSET / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.ASSET / "oracle.sql").read_text(encoding="utf-8").lower()
        logical = json.loads((self.ASSET / "logical-model.json").read_text(encoding="utf-8"))
        for token in ("asset", "asset_custody_event", "asset_command_dedup"):
            self.assertIn(token, pg)
        for token in ("infranexum_itam_asset", "infranexum_itam_asset_custody", "infranexum_itam_asset_dedup"):
            self.assertIn(token, oracle)
        for forbidden in ("references infranexum_iam", "references infranexum_org", "references infranexum_rsot"):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        self.assertFalse(logical["invariants"]["cross_context_foreign_keys"])

    def test_lifecycle_custody_version_rsot_uniqueness_and_disposition_evidence_are_constrained(self) -> None:
        for relative in ("postgresql.sql", "oracle.sql"):
            sql = (self.ASSET / relative).read_text(encoding="utf-8").lower()
            for status in ("acquired", "received", "in_stock", "assigned", "deployed", "maintenance", "returned", "retired", "disposed"):
                self.assertIn(status, sql)
            for token in ("rsot_object_id", "version", "sequence", "evidence_reference", "unique"):
                self.assertIn(token, sql)
        verification = yaml.safe_load((self.ASSET / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
        rendered = json.dumps(verification).lower()
        self.assertIn("custody", rendered)
        self.assertIn("evidence", rendered)
        self.assertIn("version", rendered)

    def test_permissions_are_exact_organization_scoped_and_bootstrapped_for_platform_admin(self) -> None:
        expected = {"itam.asset.read", "itam.asset.create", "itam.asset.update"}
        logical = json.loads((self.PERMISSIONS / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(expected, set(logical["permissions"]))
        self.assertEqual("ORGANIZATION", logical["scope_kind"])
        for sql_path in ("postgresql.sql", "oracle.sql"):
            sql = (self.PERMISSIONS / sql_path).read_text(encoding="utf-8")
            for permission in expected:
                self.assertIn(permission, sql)
            self.assertIn("019ffbda-1001-7e80-9ec8-7580467e9a85", sql)

    def test_rollbacks_are_bounded_and_verification_exists_for_both_engines(self) -> None:
        asset_rollback = "\n".join((self.ASSET / relative).read_text(encoding="utf-8") for relative in ("rollback/postgresql.sql", "rollback/oracle.sql")).upper()
        self.assertNotIn("INFRANEXUM_IAM", asset_rollback)
        self.assertNotIn("INFRANEXUM_ORG", asset_rollback)
        self.assertNotIn("INFRANEXUM_RSOT", asset_rollback)
        for directory in (self.ASSET, self.PERMISSIONS):
            checks = yaml.safe_load((directory / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
            self.assertTrue(checks)
            for check in checks:
                self.assertTrue(check["postgresql"].strip())
                self.assertTrue(check["oracle"].strip())


if __name__ == "__main__":
    unittest.main()
