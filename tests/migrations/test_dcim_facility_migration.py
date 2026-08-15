"""Migration and RBAC regressions for PGM-07-E04 physical facilities."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class DcimFacilityMigrationTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    MIGRATIONS = ROOT / "src/distribution/migrations"
    FACILITIES = MIGRATIONS / "0026-dcim-facility-hierarchy"
    PERMISSIONS = MIGRATIONS / "0027-identity-access-dcim-facility-permissions"

    def test_catalogue_orders_facilities_and_permissions_after_rsot_web_parity(self) -> None:
        catalogue = yaml.safe_load((self.MIGRATIONS / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertLess(ids.index("0025"), ids.index("0026"))
        self.assertLess(ids.index("0026"), ids.index("0027"))
        self.assertGreaterEqual(tuple(map(int, catalogue["version"].split("."))), (1, 26, 0))

    def test_descriptors_have_exact_checksums_dependencies_and_ownership(self) -> None:
        for directory, migration_id, owner, dependency in (
            (self.FACILITIES, "0026", "dcim", "0025"),
            (self.PERMISSIONS, "0027", "identity-access", "0026"),
        ):
            descriptor = yaml.safe_load((directory / "migration.yaml").read_text(encoding="utf-8"))
            self.assertEqual(migration_id, str(descriptor["id"]).zfill(4))
            self.assertEqual(owner, descriptor["owner_context"])
            self.assertEqual([dependency], [str(value).zfill(4) for value in descriptor["dependencies"]])
            for relative, expected in descriptor["checksums"].items():
                self.assertEqual(expected, hashlib.sha256((directory / relative).read_bytes()).hexdigest(), relative)

    def test_site_address_and_kind_specific_constraints_are_symmetric(self) -> None:
        pg = (self.FACILITIES / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.FACILITIES / "oracle.sql").read_text(encoding="utf-8").lower()
        for token in ("address_line_1", "address_line_2", "postal_code", "city", "country_code", "timezone"):
            self.assertIn(token, pg)
            self.assertIn(token, oracle)
        for token in ("facility_building", "facility_floor", "facility_room"):
            self.assertIn(token, pg)
        self.assertIn("zone_req", oracle)
        self.assertIn("numeric(10,7)", pg)
        self.assertIn("number(10,7)", oracle)

    def test_cross_context_references_are_weak_and_only_parent_is_internal_fk(self) -> None:
        pg = (self.FACILITIES / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.FACILITIES / "oracle.sql").read_text(encoding="utf-8").lower()
        logical = json.loads((self.FACILITIES / "logical-model.json").read_text(encoding="utf-8"))
        for forbidden in ("references infranexum_org", "references infranexum_iam", "references infranexum_rsot", "references infranexum_itam"):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        node = logical["objects"][0]
        self.assertEqual(["organization_id", "subdivision_id"], node["weak_references"])
        self.assertEqual(["parent_id"], node["internal_foreign_keys"])
        self.assertEqual(["facility_kind,scope_id,code"], node["unique"])

    def test_permission_seed_is_exact_organization_scoped_and_bootstrapped(self) -> None:
        expected = {
            "dcim.site.read", "dcim.site.create", "dcim.site.update", "dcim.site.archive", "dcim.site.delete", "dcim.site.audit",
            "dcim.building.read", "dcim.building.create", "dcim.building.update", "dcim.building.archive", "dcim.building.delete",
            "dcim.floor.read", "dcim.floor.create", "dcim.floor.update", "dcim.floor.archive", "dcim.floor.delete",
            "dcim.room.read", "dcim.room.create", "dcim.room.update", "dcim.room.lock", "dcim.room.archive", "dcim.room.delete",
            "dcim.zone.read", "dcim.zone.create", "dcim.zone.update", "dcim.zone.delete",
        }
        logical = json.loads((self.PERMISSIONS / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(expected, set(logical["objects"][0]["permissions"]))
        for relative in ("postgresql.sql", "oracle.sql"):
            sql = (self.PERMISSIONS / relative).read_text(encoding="utf-8")
            for permission in expected:
                self.assertIn(permission, sql)
            self.assertGreaterEqual(sql.count("ORGANIZATION"), len(expected))
            self.assertIn("019ffbda-1001-7e80-9ec8-7580467e9a85", sql)

    def test_verification_and_rollback_are_bounded_to_dcim_owned_objects(self) -> None:
        checks = yaml.safe_load((self.FACILITIES / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
        self.assertIn("dcim-site-address-columns", {check["id"] for check in checks})
        for check in checks:
            self.assertTrue(check["postgresql"].strip())
            self.assertTrue(check["oracle"].strip())
        rollback = "\n".join((self.FACILITIES / relative).read_text(encoding="utf-8").upper() for relative in ("rollback/postgresql.sql", "rollback/oracle.sql"))
        for forbidden in ("DROP TABLE INFRANEXUM_IAM", "DROP TABLE INFRANEXUM_ORG", "DROP TABLE INFRANEXUM_RSOT", "DROP TABLE INFRANEXUM_ITAM"):
            self.assertNotIn(forbidden, rollback)


if __name__ == "__main__":
    unittest.main()
