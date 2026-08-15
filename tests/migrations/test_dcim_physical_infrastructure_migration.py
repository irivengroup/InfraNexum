"""Migration and RBAC regressions for PGM-07-E05 physical infrastructure."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class DcimPhysicalInfrastructureMigrationTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    MIGRATIONS = ROOT / "src/distribution/migrations"
    PHYSICAL = MIGRATIONS / "0028-dcim-rack-equipment-cabling"
    PERMISSIONS = MIGRATIONS / "0029-identity-access-dcim-physical-permissions"

    def test_catalogue_orders_e05_after_facilities_and_bumps_catalogue(self) -> None:
        catalogue = yaml.safe_load((self.MIGRATIONS / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertLess(ids.index("0027"), ids.index("0028"))
        self.assertLess(ids.index("0028"), ids.index("0029"))
        self.assertGreaterEqual(tuple(map(int, catalogue["version"].split("."))), (1, 28, 0))

    def test_descriptors_have_exact_checksums_and_context_ownership(self) -> None:
        for directory, migration_id, owner, dependency in (
            (self.PHYSICAL, "0028", "dcim", "0027"),
            (self.PERMISSIONS, "0029", "identity-access", "0028"),
        ):
            descriptor = yaml.safe_load((directory / "migration.yaml").read_text(encoding="utf-8"))
            self.assertEqual(migration_id, str(descriptor["id"]).zfill(4))
            self.assertEqual(owner, descriptor["owner_context"])
            self.assertEqual([dependency], [str(value).zfill(4) for value in descriptor["dependencies"]])
            for relative, expected in descriptor["checksums"].items():
                self.assertEqual(expected, hashlib.sha256((directory / relative).read_bytes()).hexdigest(), relative)

    def test_physical_storage_has_internal_fks_only_and_reversible_objects(self) -> None:
        pg = (self.PHYSICAL / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.PHYSICAL / "oracle.sql").read_text(encoding="utf-8").lower()
        for forbidden in ("references infranexum_org", "references infranexum_iam", "references infranexum_rsot", "references infranexum_itam"):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        for token in ("equipment_model", "model_port_template", "rack", "equipment", "physical_port", "cable_connection", "physical_command_dedup"):
            self.assertIn(token, pg)
        for token in ("infranexum_dcim_model", "infranexum_dcim_model_port", "infranexum_dcim_rack", "infranexum_dcim_equip", "infranexum_dcim_port", "infranexum_dcim_cable", "infranexum_dcim_phys_dedup"):
            self.assertIn(token, oracle)
        rollback = "\n".join((self.PHYSICAL / rel).read_text(encoding="utf-8").lower() for rel in ("rollback/postgresql.sql", "rollback/oracle.sql"))
        for token in ("cable_connection", "physical_port", "equipment", "rack", "equipment_model"):
            self.assertIn(token, rollback)
        for token in ("infranexum_dcim_cable", "infranexum_dcim_port", "infranexum_dcim_equip", "infranexum_dcim_rack", "infranexum_dcim_model"):
            self.assertIn(token, rollback)

    def test_permissions_are_exact_organization_scoped_and_bootstrapped(self) -> None:
        expected = {
            "dcim.model.read", "dcim.model.create", "dcim.model.update", "dcim.model.archive",
            "dcim.rack.read", "dcim.rack.create", "dcim.rack.update", "dcim.rack.decommission",
            "dcim.equipment.read", "dcim.equipment.create", "dcim.equipment.update", "dcim.equipment.move", "dcim.equipment.decommission",
            "dcim.port.read", "dcim.cable.read", "dcim.cable.create", "dcim.cable.disconnect",
        }
        logical = json.loads((self.PERMISSIONS / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(expected, set(logical["objects"][0]["permissions"]))
        for relative in ("postgresql.sql", "oracle.sql"):
            sql = (self.PERMISSIONS / relative).read_text(encoding="utf-8")
            for permission in expected:
                self.assertIn(permission, sql)
            self.assertGreaterEqual(sql.count("ORGANIZATION"), len(expected))
            self.assertIn("019ffbda-1001-7e80-9ec8-7580467e9a85", sql)

    def test_verification_queries_cover_postgresql_and_oracle(self) -> None:
        for directory in (self.PHYSICAL, self.PERMISSIONS):
            checks = yaml.safe_load((directory / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
            self.assertGreaterEqual(len(checks), 1)
            for check in checks:
                self.assertTrue(check["postgresql"].strip())
                self.assertTrue(check["oracle"].strip())


if __name__ == "__main__":
    unittest.main()
