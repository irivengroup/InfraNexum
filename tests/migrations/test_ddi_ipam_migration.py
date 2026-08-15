"""Migration and RBAC regressions for PGM-08-E01 DDI/IPAM."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class DdiIpamMigrationTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    MIGRATIONS = ROOT / "src/distribution/migrations"
    IPAM = MIGRATIONS / "0030-ddi-ipam-foundation"
    PERMISSIONS = MIGRATIONS / "0031-identity-access-ddi-ipam-permissions"

    def test_catalogue_orders_ipam_after_dcim_and_bumps_catalogue(self) -> None:
        catalogue = yaml.safe_load((self.MIGRATIONS / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertLess(ids.index("0029"), ids.index("0030"))
        self.assertLess(ids.index("0030"), ids.index("0031"))
        self.assertGreaterEqual(tuple(map(int, catalogue["version"].split("."))), (1, 30, 0))

    def test_descriptors_have_exact_checksums_and_context_ownership(self) -> None:
        for directory, migration_id, owner, dependency in (
            (self.IPAM, "0030", "ddi", "0029"),
            (self.PERMISSIONS, "0031", "identity-access", "0030"),
        ):
            descriptor = yaml.safe_load((directory / "migration.yaml").read_text(encoding="utf-8"))
            self.assertEqual(migration_id, str(descriptor["id"]).zfill(4))
            self.assertEqual(owner, descriptor["owner_context"])
            self.assertEqual([dependency], [str(value).zfill(4) for value in descriptor["dependencies"]])
            for relative, expected in descriptor["checksums"].items():
                self.assertEqual(expected, hashlib.sha256((directory / relative).read_bytes()).hexdigest(), relative)

    def test_ipam_storage_has_only_internal_fks_and_indexed_overlap_keys(self) -> None:
        pg = (self.IPAM / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.IPAM / "oracle.sql").read_text(encoding="utf-8").lower()
        logical = json.loads((self.IPAM / "logical-model.json").read_text(encoding="utf-8"))
        for forbidden in (
            "references infranexum_org",
            "references infranexum_iam",
            "references infranexum_rsot",
            "references infranexum_itam",
        ):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        for token in ("first_key", "last_key", "start_key", "end_key"):
            self.assertIn(token, pg)
            self.assertIn(token, oracle)
        self.assertIn("ix_ddi_network_overlap", pg)
        self.assertIn("ix_ddi_pool_overlap", pg)
        self.assertIn("ix_ddi_network_overlap", oracle)
        self.assertIn("ix_ddi_pool_overlap", oracle)
        objects = {item["name"]: item for item in logical["objects"]}
        self.assertIn("organization_id", objects["ipam_network"]["weak_references"])
        self.assertIn("rsot_object_id", objects["ipam_address"]["weak_references"])
        self.assertIn("dcim_equipment_id", objects["ipam_address"]["weak_references"])

    def test_permissions_are_exact_organization_scoped_and_bootstrapped(self) -> None:
        expected = {
            "ddi.ipam.read",
            "ddi.ipam.vrf.create",
            "ddi.ipam.vrf.update",
            "ddi.ipam.vlan.create",
            "ddi.ipam.vlan.update",
            "ddi.ipam.network.create",
            "ddi.ipam.network.update",
            "ddi.ipam.pool.create",
            "ddi.ipam.address.read",
            "ddi.ipam.address.allocate",
            "ddi.ipam.address.release",
            "ddi.ipam.audit.read",
        }
        logical = json.loads((self.PERMISSIONS / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(12, logical["objects"][0]["count"])
        self.assertEqual("ORGANIZATION", logical["objects"][0]["scope"])
        for relative in ("postgresql.sql", "oracle.sql"):
            sql = (self.PERMISSIONS / relative).read_text(encoding="utf-8")
            for permission in expected:
                self.assertIn(permission, sql)
            self.assertGreaterEqual(sql.count("ORGANIZATION"), len(expected))
            self.assertIn("019ffbda-1001-7e80-9ec8-7580467e9a85", sql)

    def test_verification_queries_cover_postgresql_and_oracle(self) -> None:
        for directory in (self.IPAM, self.PERMISSIONS):
            checks = yaml.safe_load((directory / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
            self.assertGreaterEqual(len(checks), 1)
            for check in checks:
                self.assertTrue(check["postgresql"].strip())
                self.assertTrue(check["oracle"].strip())


if __name__ == "__main__":
    unittest.main()
