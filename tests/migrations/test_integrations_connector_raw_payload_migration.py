from __future__ import annotations

import hashlib
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2] / "src/distribution/migrations"
MIGRATION = ROOT / "0035-integrations-connector-raw-payload"


class IntegrationsConnectorRawPayloadMigrationTest(unittest.TestCase):
    def test_forward_migration_preserves_raw_payload_authority_for_both_databases(self) -> None:
        postgresql = (MIGRATION / "postgresql.sql").read_text(encoding="utf-8")
        oracle = (MIGRATION / "oracle.sql").read_text(encoding="utf-8")
        self.assertIn("payload_raw TEXT", postgresql)
        self.assertIn("payload_json::text", postgresql)
        self.assertIn("ALTER COLUMN payload_raw SET NOT NULL", postgresql)
        self.assertIn("PAYLOAD_RAW CLOB", oracle)
        self.assertIn("SET PAYLOAD_RAW = PAYLOAD_JSON", oracle)
        self.assertIn("MODIFY (PAYLOAD_RAW NOT NULL)", oracle)

    def test_descriptor_checksums_and_dependency_are_exact(self) -> None:
        descriptor = yaml.safe_load((MIGRATION / "migration.yaml").read_text(encoding="utf-8"))
        self.assertEqual("0035", descriptor["id"])
        self.assertEqual(["0034"], descriptor["dependencies"])
        for relative, expected in descriptor["checksums"].items():
            self.assertEqual(expected, hashlib.sha256((MIGRATION / relative).read_bytes()).hexdigest())

    def test_rollback_removes_only_shadow_column(self) -> None:
        self.assertEqual(
            "ALTER TABLE infranexum_integrations.connector_inbox DROP COLUMN payload_raw;",
            (MIGRATION / "rollback/postgresql.sql").read_text(encoding="utf-8").strip(),
        )
        self.assertEqual(
            "ALTER TABLE INFRANEXUM_INTEGRATION_INBOX DROP COLUMN PAYLOAD_RAW;",
            (MIGRATION / "rollback/oracle.sql").read_text(encoding="utf-8").strip(),
        )


if __name__ == "__main__":
    unittest.main()
