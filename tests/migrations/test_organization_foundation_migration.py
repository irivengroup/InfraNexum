"""Regression tests for the Organization foundation migration pair."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class OrganizationFoundationMigrationTest(unittest.TestCase):
    """Protect tenant isolation, UUIDv7, idempotency and database parity for migration 0010."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.root = Path(__file__).resolve().parents[2]
        cls.migration = cls.root / "src/distribution/migrations/0010-organization-foundation"
        cls.postgresql = (cls.migration / "postgresql.sql").read_text(encoding="utf-8")
        cls.oracle = (cls.migration / "oracle.sql").read_text(encoding="utf-8")
        cls.rollback_postgresql = (
            cls.migration / "rollback/postgresql.sql"
        ).read_text(encoding="utf-8")
        cls.rollback_oracle = (
            cls.migration / "rollback/oracle.sql"
        ).read_text(encoding="utf-8")
        cls.descriptor = yaml.safe_load(
            (cls.migration / "migration.yaml").read_text(encoding="utf-8")
        )

    def test_database_pair_enforces_organization_and_subdivision_isolation(self) -> None:
        pg = self.postgresql.lower()
        ora = self.oracle.upper()
        self.assertIn("unique(organization_id,code)", pg)
        self.assertIn("unique(organization_id,id)", pg)
        self.assertIn(
            "foreign key(organization_id,parent_subdivision_id) references infranexum_org.subdivision(organization_id,id)",
            pg,
        )
        self.assertIn(
            "foreign key(organization_id,subdivision_id) references infranexum_org.subdivision(organization_id,id)",
            pg,
        )
        self.assertIn("UQ_INX_SUB_ORG_CODE UNIQUE(ORGANIZATION_ID,CODE)", ora)
        self.assertIn("UQ_INX_SUB_ORG_ID UNIQUE(ORGANIZATION_ID,ID)", ora)
        self.assertIn(
            "FOREIGN KEY(ORGANIZATION_ID,PARENT_SUBDIVISION_ID) REFERENCES INFRANEXUM_ORG_SUBDIVISION(ORGANIZATION_ID,ID)",
            ora,
        )
        self.assertIn(
            "FOREIGN KEY(ORGANIZATION_ID,SUBDIVISION_ID) REFERENCES INFRANEXUM_ORG_SUBDIVISION(ORGANIZATION_ID,ID)",
            ora,
        )

    def test_uuidv7_lifecycle_scope_and_idempotency_constraints_exist_in_both_databases(self) -> None:
        pg = self.postgresql.lower()
        ora = self.oracle.lower()
        for token in [
            "ck_inx_org_uuidv7",
            "ck_inx_sub_uuidv7",
            "ck_inx_scope_uuidv7",
            "organization-transition",
            "deletion_pending",
            "cost_center",
            "administrative",
        ]:
            self.assertIn(token, pg)
            self.assertIn(token, ora)
        self.assertIn("valid_toisnullorvalid_to>valid_from", pg.replace(" ", ""))
        self.assertIn("valid_toisnullorvalid_to>valid_from", ora.replace(" ", ""))

    def test_descriptor_and_catalogue_are_exact_and_checksummed(self) -> None:
        self.assertEqual("0010", self.descriptor["id"])
        self.assertEqual("organization", self.descriptor["owner_context"])
        self.assertEqual(["0009"], self.descriptor["dependencies"])
        for relative, expected in self.descriptor["checksums"].items():
            actual = hashlib.sha256((self.migration / relative).read_bytes()).hexdigest()
            self.assertEqual(expected, actual, relative)

        catalogue = yaml.safe_load(
            (self.root / "src/distribution/migrations/catalogue.yaml").read_text(encoding="utf-8")
        )
        entries = catalogue["entries"]
        self.assertTrue(any(str(entry.get("id")) == "0010" for entry in entries))

    def test_logical_model_and_rollbacks_cover_all_four_objects(self) -> None:
        logical = json.loads((self.migration / "logical-model.json").read_text(encoding="utf-8"))
        names = {entry["logical_name"] for entry in logical["objects"]}
        self.assertEqual(
            {
                "organization.organization",
                "organization.subdivision",
                "organization.temporal_scope",
                "organization.command_dedup",
            },
            names,
        )
        self.assertIn("drop schema if exists infranexum_org cascade", self.rollback_postgresql.lower())
        for token in ["TEMP_SCOPE", "COMMAND_DEDUP", "SUBDIVISION", "ORGANIZATION"]:
            self.assertIn(token, self.rollback_oracle.upper())
        self.assertIn("SQLCODE!=-942", self.rollback_oracle.upper())


if __name__ == "__main__":
    unittest.main()
