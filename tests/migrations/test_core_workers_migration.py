"""Regression tests for the durable Core Workers migration pair."""

from __future__ import annotations

import hashlib
import unittest
from pathlib import Path

import yaml


class CoreWorkersMigrationTest(unittest.TestCase):
    """Protect portability, rollback safety and descriptor integrity for migration 0006."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.migration = (
            Path(__file__).resolve().parents[2]
            / "src"
            / "distribution"
            / "migrations"
            / "0006-core-workers"
        )
        cls.postgresql = (cls.migration / "postgresql.sql").read_text(encoding="utf-8")
        cls.oracle = (cls.migration / "oracle.sql").read_text(encoding="utf-8")
        cls.rollback_postgresql = (
            cls.migration / "rollback" / "postgresql.sql"
        ).read_text(encoding="utf-8")
        cls.rollback_oracle = (cls.migration / "rollback" / "oracle.sql").read_text(
            encoding="utf-8"
        )
        cls.verification = (cls.migration / "verify.sql.yaml").read_text(encoding="utf-8")

    def test_database_pair_preserves_core_worker_contract(self) -> None:
        """Past-due tasks remain legal and Oracle does not require extended VARCHAR2."""
        self.assertNotIn("requested_not_before >= created_at", self.postgresql.lower())
        self.assertNotIn("requested_not_before >= created_at", self.oracle.lower())
        self.assertNotIn("(?:", self.postgresql)
        self.assertIn("CHECKPOINT_TOKEN CLOB", self.oracle)
        self.assertIn("PARAMETER_VALUE CLOB NOT NULL", self.oracle)
        self.assertIn("INX_CORE_WORKER_CHECKPOINT_TRG", self.oracle)
        self.assertIn("INX_CORE_WORKER_PARAMETER_TRG", self.oracle)
        self.assertIn("DBMS_LOB.GETLENGTH(:NEW.CHECKPOINT_TOKEN)", self.oracle)
        self.assertIn("DBMS_LOB.GETLENGTH(:NEW.PARAMETER_VALUE)", self.oracle)
        self.assertIn("checkpoint_token VARCHAR(4096)", self.postgresql)
        self.assertIn("parameter_value VARCHAR(4096) NOT NULL", self.postgresql)

    def test_rollback_is_idempotent_and_fails_closed_with_durable_data(self) -> None:
        """A repeated rollback tolerates absent tables but refuses destructive task removal."""
        self.assertIn("to_regclass('infranexum_core.worker_task')", self.rollback_postgresql)
        self.assertIn("cannot roll back migration 0006", self.rollback_postgresql)
        self.assertIn("DROP TABLE IF EXISTS", self.rollback_postgresql)
        self.assertIn("USER_TABLES", self.rollback_oracle)
        self.assertIn("RAISE_APPLICATION_ERROR(-20006", self.rollback_oracle)
        self.assertIn("SQLCODE != -942", self.rollback_oracle)

    def test_verification_contract_checks_portability_indexes_and_cascade(self) -> None:
        """Post-migration verification covers the operational and portability invariants."""
        payload = yaml.safe_load(self.verification)
        identifiers = {entry["id"] for entry in payload["checks"]}
        self.assertTrue(
            {
                "worker-task-due-index-exists",
                "worker-task-lease-index-exists",
                "worker-task-checkpoint-token-portable-storage",
                "worker-task-parameter-value-portable-storage",
                "worker-task-parameter-cascade-exists",
                "worker-task-oracle-lob-invariant-triggers-exist",
            }.issubset(identifiers)
        )

    def test_descriptor_checksums_match_every_migration_asset(self) -> None:
        """The migration descriptor cannot drift from any executable or verification asset."""
        descriptor = yaml.safe_load((self.migration / "migration.yaml").read_text(encoding="utf-8"))
        for relative, expected in descriptor["checksums"].items():
            actual = hashlib.sha256((self.migration / relative).read_bytes()).hexdigest()
            self.assertEqual(expected, actual, relative)


if __name__ == "__main__":
    unittest.main()
