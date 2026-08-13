"""Regression tests for PGM-04-E02 weak references and PGM-06-E01 RSOT migration foundations."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class RsotAuthorityFoundationMigrationTest(unittest.TestCase):
    """Protect migration ordering, isolation, authority matrix parity, and rollback compatibility."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.root = Path(__file__).resolve().parents[2]
        cls.migrations = cls.root / "src/distribution/migrations"
        cls.weak = cls.migrations / "0014-identity-access-weak-scope-references"
        cls.rsot = cls.migrations / "0015-rsot-authority-foundation"
        cls.weak_pg = (cls.weak / "postgresql.sql").read_text(encoding="utf-8")
        cls.weak_oracle = (cls.weak / "oracle.sql").read_text(encoding="utf-8")
        cls.rsot_pg = (cls.rsot / "postgresql.sql").read_text(encoding="utf-8")
        cls.rsot_oracle = (cls.rsot / "oracle.sql").read_text(encoding="utf-8")

    def test_catalogue_orders_weak_reference_remediation_before_rsot(self) -> None:
        catalogue = yaml.safe_load((self.migrations / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertEqual(["0013", "0014", "0015"], ids[-3:])
        self.assertEqual("1.14.0", catalogue["version"])

    def test_both_descriptors_have_exact_checksums_and_dependencies(self) -> None:
        for directory, migration_id, owner, dependency in (
            (self.weak, "0014", "identity-access", "0013"),
            (self.rsot, "0015", "rsot", "0014"),
        ):
            descriptor = yaml.safe_load((directory / "migration.yaml").read_text(encoding="utf-8"))
            self.assertEqual(migration_id, descriptor["id"])
            self.assertEqual(owner, descriptor["owner_context"])
            self.assertEqual([dependency], descriptor["dependencies"])
            for relative, expected in descriptor["checksums"].items():
                actual = hashlib.sha256((directory / relative).read_bytes()).hexdigest()
                self.assertEqual(expected, actual, relative)

    def test_0014_drops_exactly_the_cross_context_iam_organization_foreign_keys(self) -> None:
        pg = self.weak_pg.lower()
        expected_pg = {
            "user_membership_organization_id_fkey",
            "fk_inx_iam_membership_sub",
            "iam_group_organization_id_fkey",
            "permission_organization_id_fkey",
            "role_organization_id_fkey",
            "role_assignment_organization_id_fkey",
            "fk_inx_iam_ra_sub",
        }
        for constraint in expected_pg:
            self.assertIn(f"drop constraint if exists {constraint}", pg)
        self.assertEqual(7, pg.count("drop constraint if exists"))

        oracle = self.weak_oracle.upper()
        expected_oracle = {
            "FK_INX_IAM_MEM_ORG",
            "FK_INX_IAM_MEM_SUB",
            "FK_INX_IAM_GROUP_ORG",
            "FK_INX_IAM_PERM_ORG",
            "FK_INX_IAM_ROLE_ORG",
            "FK_INX_IAM_RA_ORG",
            "FK_INX_IAM_RA_SUB",
        }
        for constraint in expected_oracle:
            self.assertIn("DROP CONSTRAINT " + constraint, oracle)
        self.assertEqual(7, oracle.count("DROP CONSTRAINT FK_INX_IAM_"))
        self.assertIn("SQLCODE!=-2443", self.weak_oracle)

    def test_0014_rollback_never_recreates_forbidden_cross_context_constraints(self) -> None:
        rollback = "\n".join(
            (self.weak / relative).read_text(encoding="utf-8")
            for relative in ("rollback/postgresql.sql", "rollback/oracle.sql")
        ).upper()
        self.assertNotIn("ADD CONSTRAINT", rollback)
        self.assertNotIn("REFERENCES INFRANEXUM_ORG", rollback)
        logical = json.loads((self.weak / "logical-model.json").read_text(encoding="utf-8"))
        self.assertTrue(logical["security"]["cross_context_foreign_keys_forbidden"])
        self.assertTrue(logical["security"]["application_reference_validation_required"])
        self.assertEqual(7, len(logical["weak_references"]))

    def test_rsot_storage_is_private_and_contains_no_cross_context_foreign_key(self) -> None:
        pg = self.rsot_pg.lower()
        oracle = self.rsot_oracle.upper()
        self.assertIn("create schema if not exists infranexum_rsot", pg)
        for table in ("canonical_object", "attribute_authority_policy", "authority_matrix", "context_relationship"):
            self.assertIn("infranexum_rsot." + table, pg)
            self.assertIn("INFRANEXUM_RSOT_" + table.upper(), oracle)
        self.assertNotIn("references infranexum_org", pg)
        self.assertNotIn("references infranexum_iam", pg)
        self.assertNotIn("REFERENCES INFRANEXUM_ORG", oracle)
        self.assertNotIn("REFERENCES INFRANEXUM_IAM", oracle)

    def test_rsot_canonical_object_has_exact_common_identity_scope_and_lifecycle_foundation(self) -> None:
        pg = self.rsot_pg.lower()
        for token in (
            "id uuid primary key",
            "object_type varchar(160) not null",
            "version bigint not null",
            "organization_id uuid not null",
            "schema_version varchar(64) not null",
            "status varchar(16) not null",
            "effective_from timestamptz not null",
            "created_at timestamptz not null",
            "updated_at timestamptz not null",
        ):
            self.assertIn(token, pg)
        for status in ("PROPOSED", "VALIDATED", "RECONCILED", "DEPRECATED", "ARCHIVED"):
            self.assertIn("'" + status + "'", self.rsot_pg)
            self.assertIn("''" + status + "''", self.rsot_oracle)
        self.assertIn("7[0-9a-f]{3}", self.rsot_pg)
        self.assertIn("7[0-9a-f]{3}", self.rsot_oracle)

    def test_attribute_authority_policy_uses_all_normative_fields_and_forbids_global_wildcards(self) -> None:
        logical = json.loads((self.rsot / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(
            [
                "object_type",
                "attribute_path",
                "authority_context",
                "source_priority",
                "effective_from",
                "effective_until",
                "policy_version",
                "approval_ref",
            ],
            logical["authority"]["attribute_policy_fields"],
        )
        self.assertIn("object_type not in ('*','.*')", self.rsot_pg.lower())
        self.assertIn("attribute_path not in ('*','.*')", self.rsot_pg.lower())
        self.assertIn("OBJECT_TYPE NOT IN (''*'',''.*'')", self.rsot_oracle)

    def test_initial_authority_matrix_is_exactly_nine_normative_rows_on_both_databases(self) -> None:
        rows = (
            ("Organisation, subdivision", "Organisation", "l’autorité Organisation prévaut"),
            ("Identité utilisateur et groupes", "IAM", "l’autorité IAM prévaut"),
            ("Identité canonique d’un actif", "RSOT", "workflow RSOT"),
            ("Observation brute", "Discovery", "observation immuable, pas d’écrasement"),
            ("Localisation physique", "DCIM", "conflit remonté à DCIM/RSOT"),
            ("Adresse IP, préfixe, DNS, DHCP", "DDI", "DDI prévaut"),
            ("Contrat, garantie, licence patrimoniale", "ITAM", "ITAM prévaut"),
            ("Profil d’installation, quota, capability", "Core Capabilities", "Core prévaut"),
            ("Politique de qualité", "Governance/RSOT", "version active approuvée"),
        )
        for information, authority, conflict in rows:
            for sql in (self.rsot_pg, self.rsot_oracle):
                self.assertIn(information, sql)
                self.assertIn(authority, sql)
                self.assertIn(conflict, sql)
        self.assertEqual(9, self.rsot_pg.count("'2.0.0-draft.21'"))
        self.assertEqual(2, self.rsot_oracle.count("'2.0.0-draft.21'"))

    def test_context_map_is_ten_rows_and_direct_storage_writes_are_impossible(self) -> None:
        providers = (
            "Organization",
            "IAM",
            "Discovery",
            "DDI",
            "DCIM",
            "ITAM",
            "Governance",
            "Core Audit",
            "Core Contracts/Compatibility",
            "Core Capabilities",
        )
        for provider in providers:
            self.assertIn("'" + provider + "'", self.rsot_pg)
            self.assertIn("'" + provider + "'", self.rsot_oracle)
        self.assertIn("direct_storage_write_allowed = false", self.rsot_pg.lower())
        self.assertIn("DIRECT_STORAGE_WRITE_ALLOWED=0", self.rsot_oracle)

    def test_verification_contract_checks_isolation_matrix_context_and_global_authority(self) -> None:
        weak_checks = yaml.safe_load((self.weak / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
        rsot_checks = yaml.safe_load((self.rsot / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
        self.assertEqual(
            {"iam-organization-foreign-keys-absent", "iam-weak-reference-columns-preserved"},
            {check["id"] for check in weak_checks},
        )
        self.assertEqual(
            {
                "rsot-authority-matrix-exact-cardinality",
                "rsot-context-map-forbids-direct-storage-writes",
                "rsot-implicit-global-authority-absent",
                "rsot-canonical-storage-has-no-external-foreign-key",
            },
            {check["id"] for check in rsot_checks},
        )
        for check in weak_checks + rsot_checks:
            self.assertTrue(check["postgresql"].strip())
            self.assertTrue(check["oracle"].strip())

    def test_rsot_rollback_drops_only_rsot_owned_objects(self) -> None:
        rollback_pg = (self.rsot / "rollback/postgresql.sql").read_text(encoding="utf-8").lower()
        rollback_oracle = (self.rsot / "rollback/oracle.sql").read_text(encoding="utf-8").upper()
        self.assertIn("drop schema if exists infranexum_rsot", rollback_pg)
        self.assertIn("INFRANEXUM_RSOT_CANONICAL_OBJECT", rollback_oracle)
        self.assertNotIn("infranexum_iam", rollback_pg)
        self.assertNotIn("infranexum_org", rollback_pg)
        self.assertNotIn("INFRANEXUM_IAM", rollback_oracle)
        self.assertNotIn("INFRANEXUM_ORG", rollback_oracle)


if __name__ == "__main__":
    unittest.main()
