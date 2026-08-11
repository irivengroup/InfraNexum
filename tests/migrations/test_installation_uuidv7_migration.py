from __future__ import annotations

import hashlib
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "src/distribution/migrations/0007-core-installation-uuidv7"


class InstallationUuidV7MigrationTest(unittest.TestCase):
    """Protect the persisted installation-identifier UUIDv7 invariant and legacy repair policy."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.postgresql = (MIGRATION / "postgresql.sql").read_text(encoding="utf-8")
        cls.oracle = (MIGRATION / "oracle.sql").read_text(encoding="utf-8")
        cls.rollback_postgresql = (MIGRATION / "rollback/postgresql.sql").read_text(encoding="utf-8")
        cls.rollback_oracle = (MIGRATION / "rollback/oracle.sql").read_text(encoding="utf-8")
        cls.descriptor = yaml.safe_load((MIGRATION / "migration.yaml").read_text(encoding="utf-8"))

    def test_postgresql_repairs_only_unconsumed_legacy_identity(self) -> None:
        self.assertIn("uuid_extract_version(installation_id) IS DISTINCT FROM 7", self.postgresql)
        self.assertIn("core_entitlement_state", self.postgresql)
        self.assertIn("core_entitlement_integrity_proof", self.postgresql)
        self.assertIn("core_activation_manifest", self.postgresql)
        self.assertIn("dependent_count <> 0", self.postgresql)
        self.assertIn("cannot automatically replace non-UUIDv7 installation identity", self.postgresql)
        self.assertIn("UPDATE core_installation_identity", self.postgresql)

    def test_postgresql_uuidv7_layout_has_version_and_rfc_variant_bits(self) -> None:
        self.assertIn("'-7'", self.postgresql)
        self.assertIn("'89ab89ab89ab89ab'", self.postgresql)
        self.assertIn("gen_random_uuid()", self.postgresql)
        self.assertIn("uuid_extract_version(replacement_id) IS DISTINCT FROM 7", self.postgresql)
        self.assertIn("CHECK (uuid_extract_version(installation_id) = 7)", self.postgresql)

    def test_oracle_is_fail_closed_for_preexisting_invalid_identity(self) -> None:
        self.assertIn("RAISE_APPLICATION_ERROR(-20072", self.oracle)
        self.assertIn("-7[0-9a-fA-F]{3}-[89aAbB]", self.oracle)
        self.assertNotIn("UPDATE core_installation_identity", self.oracle)
        self.assertIn("CK_CORE_INSTALL_UUIDV7".lower(), self.oracle.lower())

    def test_rollback_never_restores_or_recreates_invalid_identifier(self) -> None:
        for text in (self.rollback_postgresql, self.rollback_oracle):
            self.assertNotIn("UPDATE core_installation_identity", text)
            self.assertNotIn("INSERT INTO core_installation_identity", text)
            self.assertIn("DROP CONSTRAINT", text.upper())

    def test_descriptor_checksums_cover_all_paired_artifacts(self) -> None:
        checksums = self.descriptor["checksums"]
        for relative, expected in checksums.items():
            actual = hashlib.sha256((MIGRATION / relative).read_bytes()).hexdigest()
            self.assertEqual(expected, actual, relative)
        self.assertEqual(["0004", "0006"], self.descriptor["dependencies"])


if __name__ == "__main__":
    unittest.main()
