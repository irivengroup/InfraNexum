"""Regression tests for the PGM-03-E03 identity-access RBAC migration pair."""

from __future__ import annotations

import hashlib
import json
import re
import unittest
from pathlib import Path

import yaml


_PERMISSION_CODE = re.compile(r"'((?:iam|organization|platform)\.[a-z0-9_.]+)'")
_UUIDV7 = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


class IdentityAccessRbacMigrationTest(unittest.TestCase):
    """Protect RBAC parity, bootstrap compatibility, and rollback safety for migration 0013."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.root = Path(__file__).resolve().parents[2]
        cls.migration = cls.root / "src/distribution/migrations/0013-identity-access-rbac-foundation"
        cls.postgresql = (cls.migration / "postgresql.sql").read_text(encoding="utf-8")
        cls.oracle = (cls.migration / "oracle.sql").read_text(encoding="utf-8")
        cls.rollback_postgresql = (
            cls.migration / "rollback/postgresql.sql"
        ).read_text(encoding="utf-8")
        cls.rollback_oracle = (cls.migration / "rollback/oracle.sql").read_text(
            encoding="utf-8"
        )
        cls.descriptor = yaml.safe_load(
            (cls.migration / "migration.yaml").read_text(encoding="utf-8")
        )

    @staticmethod
    def _permission_codes(sql: str) -> set[str]:
        return {match.group(1) for match in _PERMISSION_CODE.finditer(sql)}

    def test_descriptor_catalogue_and_checksums_are_exact(self) -> None:
        self.assertEqual("0013", self.descriptor["id"])
        self.assertEqual("identity-access", self.descriptor["owner_context"])
        self.assertEqual(["0012"], self.descriptor["dependencies"])
        for relative, expected in self.descriptor["checksums"].items():
            actual = hashlib.sha256((self.migration / relative).read_bytes()).hexdigest()
            self.assertEqual(expected, actual, relative)

        catalogue = yaml.safe_load(
            (self.root / "src/distribution/migrations/catalogue.yaml").read_text(
                encoding="utf-8"
            )
        )
        entries = catalogue["entries"]
        ids = [str(entry["id"]).zfill(4) for entry in entries]
        self.assertEqual("0013", ids[-1])
        self.assertEqual(ids.index("0012") + 1, ids.index("0013"))

    def test_approved_permission_catalogue_is_identical_on_both_databases(self) -> None:
        postgresql_codes = self._permission_codes(self.postgresql)
        oracle_codes = self._permission_codes(self.oracle)
        self.assertEqual(postgresql_codes, oracle_codes)
        self.assertEqual(53, len(postgresql_codes))
        # draft.21 does not define this permission; E03 must not invent public policy codes.
        self.assertNotIn("organization.read", postgresql_codes)
        self.assertIn("organization.subdivision.read", postgresql_codes)
        self.assertIn("iam.permission.evaluate", postgresql_codes)
        self.assertIn("platform.profile.read", postgresql_codes)

    def test_system_role_and_local_account_projection_preserve_identifiers(self) -> None:
        role_id = "019ffbda-1001-7e80-9ec8-7580467e9a85"
        pg = " ".join(self.postgresql.lower().split())
        ora = " ".join(self.oracle.lower().split())
        for sql in (pg, ora):
            self.assertIn("system.platform_admin", sql)
            self.assertIn(role_id, sql)
            self.assertIn("local_account", sql)

        self.assertIn(
            "select id,username,null,display_name",
            pg,
        )
        self.assertIn(
            "select id,'019ffbda-1001-7e80-9ec8-7580467e9a85','user',id,'platform'",
            pg,
        )
        self.assertIn(
            "select id,username,display_name,status,created_at,updated_at from infranexum_iam_local_account",
            ora,
        )
        self.assertIn("values (s.id,s.username,null,s.display_name", ora)
        self.assertIn(
            "values (s.id,'019ffbda-1001-7e80-9ec8-7580467e9a85','user',s.id,'platform'",
            ora,
        )

    def test_schema_uses_native_boolean_representations_and_temporal_constraints(self) -> None:
        pg = self.postgresql.lower()
        ora = self.oracle.upper()
        self.assertIn("system_role boolean not null", pg)
        self.assertIn("system_defined boolean not null", pg)
        self.assertIn("SYSTEM_ROLE NUMBER(1) NOT NULL", ora)
        self.assertIn("SYSTEM_DEFINED NUMBER(1) NOT NULL", ora)
        self.assertIn(
            "effective_to is null or effective_to>effective_from",
            pg.replace("  ", " "),
        )
        self.assertIn("EFFECTIVE_TO IS NULL OR EFFECTIVE_TO>EFFECTIVE_FROM", ora)

    def test_seeded_identifiers_are_uuidv7(self) -> None:
        for source in (self.postgresql, self.oracle):
            literals = set(
                re.findall(
                    r"'([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})'",
                    source.lower(),
                )
            )
            self.assertGreaterEqual(len(literals), 54)
            invalid = sorted(value for value in literals if not _UUIDV7.fullmatch(value))
            self.assertEqual([], invalid)

    def test_verification_contract_covers_bootstrap_cycles_and_temporal_assignments(self) -> None:
        verification = yaml.safe_load(
            (self.migration / "verify.sql.yaml").read_text(encoding="utf-8")
        )
        ids = {item["id"] for item in verification["checks"]}
        self.assertEqual(
            {
                "platform-admin-role-present",
                "local-accounts-projected",
                "local-accounts-platform-admin",
                "nested-group-self-cycles-absent",
                "temporal-assignments-valid",
            },
            ids,
        )
        for check in verification["checks"]:
            self.assertTrue(check["postgresql"].strip())
            self.assertTrue(check["oracle"].strip())

    def test_logical_model_contains_all_rbac_objects(self) -> None:
        logical = json.loads((self.migration / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(
            {
                "infranexum_iam.iam_user",
                "infranexum_iam.user_membership",
                "infranexum_iam.iam_group",
                "infranexum_iam.group_user_member",
                "infranexum_iam.group_group_member",
                "infranexum_iam.permission",
                "infranexum_iam.role",
                "infranexum_iam.role_permission",
                "infranexum_iam.role_assignment",
            },
            set(logical["objects"]),
        )
        self.assertTrue(logical["security"]["deny_by_default"])
        self.assertTrue(logical["security"]["system_roles_protected"])
        self.assertTrue(logical["security"]["nested_group_cycles_forbidden"])

    def test_rollback_removes_only_rbac_foundation_objects(self) -> None:
        pg = self.rollback_postgresql.lower()
        ora = self.rollback_oracle.upper()
        self.assertIn("drop table if exists infranexum_iam.role_assignment", pg)
        self.assertIn("drop table if exists infranexum_iam.iam_user", pg)
        self.assertNotIn("drop table if exists infranexum_iam.local_account", pg)
        self.assertNotIn("drop table if exists infranexum_iam.local_session", pg)
        self.assertIn("INFRANEXUM_IAM_ROLE_ASSIGNMENT", ora)
        self.assertIn("INFRANEXUM_IAM_USER", ora)
        self.assertNotIn("DROP TABLE INFRANEXUM_IAM_LOCAL_ACCOUNT", ora)
        self.assertNotIn("DROP TABLE INFRANEXUM_IAM_LOCAL_SESSION", ora)


if __name__ == "__main__":
    unittest.main()
