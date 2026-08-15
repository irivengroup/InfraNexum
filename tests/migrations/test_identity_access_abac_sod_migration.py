"""Regression tests for PGM-03-E04 ABAC/SoD migration 0016."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class IdentityAccessAbacSodMigrationTest(unittest.TestCase):
    """Protect E04 schema isolation, system bridge, uniqueness and rollback semantics."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.root = Path(__file__).resolve().parents[2]
        cls.migrations = cls.root / "src/distribution/migrations"
        cls.directory = cls.migrations / "0016-identity-access-abac-sod"
        cls.pg = (cls.directory / "postgresql.sql").read_text(encoding="utf-8")
        cls.oracle = (cls.directory / "oracle.sql").read_text(encoding="utf-8")

    def test_catalogue_appends_0016_after_rsot_foundation(self) -> None:
        catalogue = yaml.safe_load((self.migrations / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertLess(ids.index("0015"), ids.index("0016"))
        self.assertLess(ids.index("0016"), ids.index("0017"))
        self.assertLess(ids.index("0017"), ids.index("0018"))
        self.assertGreaterEqual(tuple(map(int, catalogue["version"].split("."))), (1, 19, 0))

    def test_descriptor_checksums_and_dependency_are_exact(self) -> None:
        descriptor = yaml.safe_load((self.directory / "migration.yaml").read_text(encoding="utf-8"))
        self.assertEqual("0016", descriptor["id"])
        self.assertEqual("identity-access", descriptor["owner_context"])
        self.assertEqual(["0015"], descriptor["dependencies"])
        for relative, expected in descriptor["checksums"].items():
            actual = hashlib.sha256((self.directory / relative).read_bytes()).hexdigest()
            self.assertEqual(expected, actual, relative)

    def test_logical_model_defines_closed_fail_closed_policy_foundation(self) -> None:
        logical = json.loads((self.directory / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(
            [
                "infranexum_iam.access_policy",
                "infranexum_iam.access_policy_rule",
                "infranexum_iam.access_policy_condition",
                "infranexum_iam.sod_constraint",
            ],
            logical["objects"],
        )
        self.assertEqual("deny-overrides", logical["authorization"]["combining_algorithm"])
        self.assertEqual("system.rbac-bridge@1", logical["authorization"]["system_bridge"])
        self.assertTrue(logical["security"]["default_deny"])
        self.assertTrue(logical["security"]["active_policy_immutable"])
        self.assertFalse(logical["security"]["arbitrary_expression_execution"])
        self.assertFalse(logical["security"]["cross_context_foreign_keys"])

    def test_postgresql_and_oracle_create_exact_e04_objects_without_cross_context_fk(self) -> None:
        pg = self.pg.lower()
        oracle = self.oracle.upper()
        for table in ("access_policy", "access_policy_rule", "access_policy_condition", "sod_constraint"):
            self.assertIn("infranexum_iam." + table, pg)
            self.assertIn("INFRANEXUM_IAM_" + table.upper(), oracle)
        for forbidden in ("references infranexum_org", "references infranexum_rsot"):
            self.assertNotIn(forbidden, pg)
        for forbidden in ("REFERENCES INFRANEXUM_ORG", "REFERENCES INFRANEXUM_RSOT"):
            self.assertNotIn(forbidden, oracle)

    def test_system_rbac_bridge_is_seeded_active_and_only_trusts_server_rbac_flag(self) -> None:
        for sql in (self.pg, self.oracle):
            self.assertIn("system.rbac-bridge", sql)
            self.assertIn("RBAC", sql)
            self.assertIn("permitted", sql)
            self.assertIn("EQUALS", sql)
            self.assertIn("true", sql)
            self.assertIn("ACTIVE", sql)

    def test_platform_scope_uniqueness_handles_null_organization_on_both_databases(self) -> None:
        pg = self.pg.lower()
        oracle = self.oracle.upper()
        self.assertIn("where organization_id is null", pg)
        self.assertIn("state='active'", pg)
        self.assertIn("NVL", oracle)
        self.assertIn("CASE WHEN STATE=''ACTIVE''", oracle)
        self.assertIn("CREATE UNIQUE INDEX", oracle)

    def test_verification_contract_covers_bridge_isolation_and_closed_language(self) -> None:
        checks = yaml.safe_load((self.directory / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
        self.assertEqual(
            {
                "iam-system-rbac-bridge-active",
                "iam-system-rbac-bridge-condition-exact",
                "iam-policy-no-cross-context-foreign-keys",
                "iam-policy-language-closed",
            },
            {check["id"] for check in checks},
        )
        for check in checks:
            self.assertTrue(check["postgresql"].strip())
            self.assertTrue(check["oracle"].strip())

    def test_rollback_drops_only_e04_objects_and_preserves_rbac_rsot_organization(self) -> None:
        rollback_pg = (self.directory / "rollback/postgresql.sql").read_text(encoding="utf-8").lower()
        rollback_oracle = (self.directory / "rollback/oracle.sql").read_text(encoding="utf-8").upper()
        for table in ("sod_constraint", "access_policy_condition", "access_policy_rule", "access_policy"):
            self.assertIn(table, rollback_pg)
            self.assertIn("INFRANEXUM_IAM_" + table.upper(), rollback_oracle)
        for forbidden in ("user_account", "role_assignment", "infranexum_rsot", "infranexum_org"):
            self.assertNotIn(forbidden, rollback_pg)
        for forbidden in ("INFRANEXUM_RSOT", "INFRANEXUM_ORG", "ROLE_ASSIGNMENT"):
            self.assertNotIn(forbidden, rollback_oracle)


if __name__ == "__main__":
    unittest.main()
