"""Migration parity and security regressions for PGM-06-E03."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import yaml


class SchemaRegistryMigrationTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    MIGRATIONS = ROOT / "src/distribution/migrations"
    REGISTRY = MIGRATIONS / "0017-core-schema-registry"
    PERMISSIONS = MIGRATIONS / "0018-identity-access-rsot-schema-permissions"

    def test_catalogue_orders_registry_before_permissions(self) -> None:
        catalogue = yaml.safe_load((self.MIGRATIONS / "catalogue.yaml").read_text(encoding="utf-8"))
        ids = [str(entry["id"]).zfill(4) for entry in catalogue["entries"]]
        self.assertLess(ids.index("0016"), ids.index("0017"))
        self.assertLess(ids.index("0017"), ids.index("0018"))
        self.assertGreaterEqual(tuple(map(int, catalogue["version"].split("."))), (1, 17, 0))

    def test_descriptors_have_exact_checksums_dependencies_and_ownership(self) -> None:
        for directory, migration_id, owner, dependency in (
            (self.REGISTRY, "0017", "core-compatibility", "0016"),
            (self.PERMISSIONS, "0018", "identity-access", "0017"),
        ):
            descriptor = yaml.safe_load((directory / "migration.yaml").read_text(encoding="utf-8"))
            self.assertEqual(migration_id, str(descriptor["id"]).zfill(4))
            self.assertEqual(owner, descriptor["owner_context"])
            self.assertEqual([dependency], [str(value).zfill(4) for value in descriptor["dependencies"]])
            for relative, expected in descriptor["checksums"].items():
                actual = hashlib.sha256((directory / relative).read_bytes()).hexdigest()
                self.assertEqual(expected, actual, relative)

    def test_registry_schema_is_private_symmetric_and_has_no_cross_context_foreign_keys(self) -> None:
        pg = (self.REGISTRY / "postgresql.sql").read_text(encoding="utf-8")
        oracle = (self.REGISTRY / "oracle.sql").read_text(encoding="utf-8")
        logical = json.loads((self.REGISTRY / "logical-model.json").read_text(encoding="utf-8"))
        for semantic in ("SCHEMA_REGISTRY", "SCHEMA_PROFILE", "SCHEMA_PROFILE_MEMBER"):
            self.assertIn(semantic, oracle.upper())
        for table in ("schema_registry_entry", "schema_profile", "schema_profile_member"):
            self.assertIn(table, pg.lower())
        for forbidden in ("REFERENCES INFRANEXUM_IAM", "REFERENCES INFRANEXUM_ORG", "REFERENCES INFRANEXUM_RSOT"):
            self.assertNotIn(forbidden, oracle.upper())
            self.assertNotIn(forbidden.lower(), pg.lower())
        self.assertTrue(logical["invariants"]["published_schema_immutable"])
        self.assertTrue(logical["invariants"]["optimistic_revision_required"])
        self.assertFalse(logical["invariants"]["extension_code_storage"])
        self.assertFalse(logical["invariants"]["cross_context_foreign_keys"])

    def test_registry_constraints_cover_lifecycle_revision_checksum_and_json(self) -> None:
        pg = (self.REGISTRY / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.REGISTRY / "oracle.sql").read_text(encoding="utf-8").upper()
        for status in ("DRAFT", "PUBLISHED", "DEPRECATED"):
            self.assertIn(status.lower(), pg)
            self.assertIn(status, oracle)
        self.assertIn("revision", pg)
        self.assertIn("REVISION", oracle)
        self.assertIn("checksum_sha256", pg)
        self.assertIn("CHECKSUM_SHA256", oracle)
        self.assertIn("jsonb", pg)
        self.assertIn("IS JSON", oracle)

    def test_permissions_are_exact_and_platform_admin_upgrade_access_is_seeded(self) -> None:
        expected = {
            "rsot.schema.create", "rsot.schema.read", "rsot.schema.update",
            "rsot.schema.deprecate", "rsot.schema.publish", "rsot.audit",
        }
        logical = json.loads((self.PERMISSIONS / "logical-model.json").read_text(encoding="utf-8"))
        self.assertEqual(expected, set(logical["permissions"]))
        for sql_path in ("postgresql.sql", "oracle.sql"):
            sql = (self.PERMISSIONS / sql_path).read_text(encoding="utf-8")
            for permission in expected:
                self.assertIn(permission, sql)
            self.assertIn("019ffbda-1001-7e80-9ec8-7580467e9a85", sql)

    def test_rollback_keeps_context_boundaries_and_verification_exists_for_both_databases(self) -> None:
        registry_rollback = "\n".join(
            (self.REGISTRY / relative).read_text(encoding="utf-8")
            for relative in ("rollback/postgresql.sql", "rollback/oracle.sql")
        ).upper()
        self.assertNotIn("INFRANEXUM_IAM", registry_rollback)
        self.assertNotIn("INFRANEXUM_ORG", registry_rollback)
        for directory in (self.REGISTRY, self.PERMISSIONS):
            checks = yaml.safe_load((directory / "verify.sql.yaml").read_text(encoding="utf-8"))["checks"]
            self.assertTrue(checks)
            for check in checks:
                self.assertTrue(check["postgresql"].strip())
                self.assertTrue(check["oracle"].strip())


if __name__ == "__main__":
    unittest.main()
