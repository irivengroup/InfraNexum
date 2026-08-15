"""Migration parity and security regressions for PGM-07-E01."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class ItamPartnerMigrationTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    MIGRATIONS = ROOT / "src/distribution/migrations"
    PARTNER = MIGRATIONS / "0019-itam-partner-foundation"
    PERMISSIONS = MIGRATIONS / "0020-identity-access-itam-partner-permissions"

    def test_catalogue_orders_itam_foundation_before_permissions(self) -> None:
        catalogue = yaml.safe_load((self.MIGRATIONS / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertLess(ids.index("0018"), ids.index("0019"))
        self.assertLess(ids.index("0019"), ids.index("0020"))
        self.assertGreaterEqual(tuple(map(int, catalogue["version"].split("."))), (1, 19, 0))

    def test_descriptors_have_exact_checksums_dependencies_and_ownership(self) -> None:
        for directory, migration_id, owner, dependency in (
            (self.PARTNER, "0019", "itam", "0018"),
            (self.PERMISSIONS, "0020", "identity-access", "0019"),
        ):
            descriptor = yaml.safe_load((directory / "migration.yaml").read_text(encoding="utf-8"))
            self.assertEqual(migration_id, str(descriptor["id"]).zfill(4))
            self.assertEqual(owner, descriptor["owner_context"])
            self.assertEqual([dependency], [str(value).zfill(4) for value in descriptor["dependencies"]])
            for relative, expected in descriptor["checksums"].items():
                self.assertEqual(expected, hashlib.sha256((directory / relative).read_bytes()).hexdigest(), relative)

    def test_partner_schema_is_symmetric_role_based_and_has_no_cross_context_foreign_keys(self) -> None:
        pg = (self.PARTNER / "postgresql.sql").read_text(encoding="utf-8")
        oracle = (self.PARTNER / "oracle.sql").read_text(encoding="utf-8")
        logical = json.loads((self.PARTNER / "logical-model.json").read_text(encoding="utf-8"))
        for table in ("partner", "partner_role", "partner_alias", "partner_external_id", "partner_accreditation", "partner_contact", "partner_identity_token", "partner_command_dedup"):
            self.assertIn(table, pg.lower())
        for token in ("PARTNER", "PARTNER_ROLE", "PARTNER_ALIAS", "PARTNER_EXT_ID", "PARTNER_ACCRED", "PARTNER_CONTACT", "PARTNER_IDENT", "PARTNER_DEDUP"):
            self.assertIn(token, oracle.upper())
        for forbidden in ("REFERENCES INFRANEXUM_IAM", "REFERENCES INFRANEXUM_ORG"):
            self.assertNotIn(forbidden, oracle.upper())
            self.assertNotIn(forbidden.lower(), pg.lower())
        self.assertTrue(logical["invariants"]["single_partner_aggregate"])
        self.assertTrue(logical["invariants"]["role_filtered_catalogues"])
        self.assertFalse(logical["invariants"]["cross_context_foreign_keys"])

    def test_normative_roles_statuses_uuid_version_and_identity_dedup_are_constrained(self) -> None:
        pg = (self.PARTNER / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.PARTNER / "oracle.sql").read_text(encoding="utf-8").lower()
        for value in (
            "manufacturer", "software_publisher", "supplier", "third_party_support_provider", "integrator", "recycler",
            "draft", "pending_approval", "active", "suspended", "retired",
        ):
            self.assertIn(value, pg)
            self.assertIn(value, oracle)
        for text in (pg, oracle):
            self.assertIn("version", text)
            self.assertIn("identity", text)
        self.assertIn("unique", pg)
        self.assertIn("unique", oracle)

    def test_permissions_are_exact_organization_scoped_and_platform_admin_upgrade_access_is_seeded(self) -> None:
        expected = {
            "itam.partner.read", "itam.partner.create", "itam.partner.update", "itam.partner.approve",
            "itam.partner.suspend", "itam.audit.read",
        }
        logical = json.loads((self.PERMISSIONS / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(expected, set(logical["permissions"]))
        self.assertEqual("ORGANIZATION", logical["scope_kind"])
        for sql_path in ("postgresql.sql", "oracle.sql"):
            sql = (self.PERMISSIONS / sql_path).read_text(encoding="utf-8")
            for permission in expected:
                self.assertIn(permission, sql)
            self.assertIn("019ffbda-1001-7e80-9ec8-7580467e9a85", sql)

    def test_rollbacks_are_bounded_and_verification_is_defined_for_both_databases(self) -> None:
        partner_rollback = "\n".join(
            (self.PARTNER / relative).read_text(encoding="utf-8")
            for relative in ("rollback/postgresql.sql", "rollback/oracle.sql")
        ).upper()
        self.assertNotIn("INFRANEXUM_IAM", partner_rollback)
        self.assertNotIn("INFRANEXUM_ORG", partner_rollback)
        for directory in (self.PARTNER, self.PERMISSIONS):
            checks = yaml.safe_load((directory / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
            self.assertTrue(checks)
            for check in checks:
                self.assertTrue(check["postgresql"].strip())
                self.assertTrue(check["oracle"].strip())


if __name__ == "__main__":
    unittest.main()
