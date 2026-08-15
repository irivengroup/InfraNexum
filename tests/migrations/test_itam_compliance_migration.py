"""Migration parity, contractual evidence and permission regressions for PGM-07-E03."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class ItamComplianceMigrationTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    MIGRATIONS = ROOT / "src/distribution/migrations"
    COMPLIANCE = MIGRATIONS / "0023-itam-warranty-support-license"
    PERMISSIONS = MIGRATIONS / "0024-identity-access-itam-compliance-permissions"

    def test_catalogue_orders_compliance_after_asset_and_before_permissions(self) -> None:
        catalogue = yaml.safe_load((self.MIGRATIONS / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertLess(ids.index("0022"), ids.index("0023"))
        self.assertLess(ids.index("0023"), ids.index("0024"))
        self.assertGreaterEqual(tuple(map(int, catalogue["version"].split("."))), (1, 23, 0))

    def test_descriptors_have_exact_checksums_dependencies_and_ownership(self) -> None:
        for directory, migration_id, owner, dependency in (
            (self.COMPLIANCE, "0023", "itam", "0022"),
            (self.PERMISSIONS, "0024", "identity-access", "0023"),
        ):
            descriptor = yaml.safe_load((directory / "migration.yaml").read_text(encoding="utf-8"))
            self.assertEqual(migration_id, str(descriptor["id"]).zfill(4))
            self.assertEqual(owner, descriptor["owner_context"])
            self.assertEqual([dependency], [str(value).zfill(4) for value in descriptor["dependencies"]])
            for relative, expected in descriptor["checksums"].items():
                self.assertEqual(expected, hashlib.sha256((directory / relative).read_bytes()).hexdigest(), relative)

    def test_schema_is_postgresql_oracle_symmetric_and_cross_context_refs_are_weak(self) -> None:
        pg = (self.COMPLIANCE / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.COMPLIANCE / "oracle.sql").read_text(encoding="utf-8").lower()
        logical = json.loads((self.COMPLIANCE / "logical-model.json").read_text(encoding="utf-8"))
        for token in (
            "warranty_type", "warranty", "software_license_contract", "support_provider_authorization",
            "support_coverage", "compliance_revision", "compliance_command_dedup", "compliance_alert_dedup",
        ):
            self.assertIn(token, pg)
        for token in (
            "WARRANTY_TYPE", "WARRANTY", "SW_LICENSE", "SUPPORT_AUTH",
            "SUPPORT_COVERAGE", "COMP_REVISION", "COMP_DEDUP", "ALERT_DEDUP",
        ):
            self.assertIn(token, oracle.upper())
        for forbidden in ("references infranexum_iam", "references infranexum_org", "references infranexum_rsot", "references infranexum_partner"):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        self.assertFalse(logical["invariants"]["cross_context_foreign_keys"])
        self.assertTrue(logical["invariants"]["versioned_evidence_history"])
        self.assertTrue(logical["invariants"]["deadline_alerts_independent_of_updated_at"])

    def test_legacy_asset_producer_is_nullable_and_never_silently_backfilled(self) -> None:
        pg = (self.COMPLIANCE / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.COMPLIANCE / "oracle.sql").read_text(encoding="utf-8").lower()
        logical = json.loads((self.COMPLIANCE / "logical-model.json").read_text(encoding="utf-8"))
        self.assertIn("producer_partner_id", pg)
        self.assertIn("producer_partner_id", oracle)
        self.assertNotIn("producer_partner_id uuid not null", pg)
        self.assertNotIn("producer_partner_id raw(16) not null", oracle)
        self.assertNotRegex(pg, r"update\s+infranexum_itam\.asset\s+set\s+producer_partner_id")
        self.assertNotRegex(oracle, r"update\s+infranexum_itam_asset\s+set\s+producer_partner_id")
        self.assertTrue(logical["invariants"]["legacy_asset_producer_nullable"])

    def test_evidence_verification_versions_and_contract_dates_are_persisted(self) -> None:
        combined = "\n".join(
            (self.COMPLIANCE / relative).read_text(encoding="utf-8").lower()
            for relative in ("postgresql.sql", "oracle.sql")
        )
        for token in (
            "proof_reference", "verified_at", "verified_by", "version", "warranty_end_date",
            "manufacturer_support_end_date", "publisher_support_end_date", "valid_from", "starts_on", "ends_on",
        ):
            self.assertIn(token, combined)
        verification = yaml.safe_load((self.COMPLIANCE / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
        rendered = json.dumps(verification).lower()
        for token in ("revision", "warranty", "license", "support"):
            self.assertIn(token, rendered)

    def test_raw_license_secret_material_is_not_stored(self) -> None:
        logical = json.loads((self.COMPLIANCE / "logical-model.json").read_text(encoding="utf-8"))
        self.assertFalse(logical["invariants"]["raw_license_key_storage"])
        for relative in ("postgresql.sql", "oracle.sql"):
            text = (self.COMPLIANCE / relative).read_text(encoding="utf-8").lower()
            for forbidden in ("license_key", "product_key", "serial_key", "activation_key"):
                self.assertNotIn(forbidden, text)

    def test_permissions_are_exact_organization_scoped_and_platform_admin_is_bootstrapped(self) -> None:
        expected = {
            "itam.warranty.read", "itam.warranty.manage",
            "itam.support_coverage.read", "itam.support_coverage.manage",
            "itam.support_catalog.manage", "itam.license.read", "itam.license.manage",
        }
        logical = json.loads((self.PERMISSIONS / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(expected, set(logical["permissions"]))
        self.assertEqual("ORGANIZATION", logical["scope_kind"])
        self.assertNotIn("itam.audit.read", logical["permissions"])
        for sql_path in ("postgresql.sql", "oracle.sql"):
            sql = (self.PERMISSIONS / sql_path).read_text(encoding="utf-8")
            for permission in expected:
                self.assertIn(permission, sql)
            self.assertNotIn("'itam.audit.read'", sql)
            self.assertIn("019ffbda-1001-7e80-9ec8-7580467e9a85", sql)

    def test_rollbacks_are_bounded_and_verification_exists_for_both_engines(self) -> None:
        compliance_rollback = "\n".join(
            (self.COMPLIANCE / relative).read_text(encoding="utf-8")
            for relative in ("rollback/postgresql.sql", "rollback/oracle.sql")
        ).upper()
        for forbidden in ("DROP TABLE INFRANEXUM_IAM", "DROP TABLE INFRANEXUM_ORG", "DROP TABLE INFRANEXUM_RSOT"):
            self.assertNotIn(forbidden, compliance_rollback)
        for directory in (self.COMPLIANCE, self.PERMISSIONS):
            checks = yaml.safe_load((directory / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
            self.assertTrue(checks)
            for check in checks:
                self.assertTrue(check["postgresql"].strip())
                self.assertTrue(check["oracle"].strip())


if __name__ == "__main__":
    unittest.main()
