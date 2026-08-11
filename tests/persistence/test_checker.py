from __future__ import annotations

import contextlib
import io
import json
import runpy
import shutil
import sys
import tempfile
import unittest
import warnings
from pathlib import Path
from unittest.mock import patch

from validation.persistence.checker import PersistenceChecker
from validation.persistence.cli import main as cli_main

SOURCE = Path(__file__).resolve().parents[2]
FILES = (
    "pom.xml",
    "Makefile",
    "validation/architecture/policy.json",
    "components/core/events/main/io/infranexum/core/events/EventTransaction.java",
    "components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcTransactionalEventStore.java",
    "components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcDatabaseDialect.java",
    "distribution/migrations/0003-core-inbox-reservation/postgresql.sql",
    "distribution/migrations/0003-core-inbox-reservation/oracle.sql",
    "distribution/migrations/0003-core-inbox-reservation/rollback/postgresql.sql",
    "distribution/migrations/0003-core-inbox-reservation/rollback/oracle.sql",
    "applications/server/main/io/infranexum/server/persistence/EventPersistenceConfiguration.java",
    "applications/server/MANIFEST.json",
    "applications/server/pom.xml",
    "applications/server/main/io/infranexum/server/persistence/UnavailableDataSource.java",
)


class PersistenceCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repository"
        self.root.mkdir()
        for relative in FILES:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def ids(self) -> set[str]:
        return {item.check_id for item in PersistenceChecker(self.root).run()}

    def mutate(self, relative: str, old: str, new: str = "") -> None:
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new), encoding="utf-8")

    def test_reference_contract_is_valid(self) -> None:
        self.assertEqual(set(), self.ids())

    def test_missing_files_are_reported(self) -> None:
        for relative, expected in (
            (FILES[3], "CHECK-JDBC-UOW-001"),
            (FILES[4], "CHECK-JDBC-STORE-001"),
            (FILES[5], "CHECK-JDBC-DIALECT-001"),
            (FILES[6], "CHECK-JDBC-MIGRATION-001"),
            (FILES[8], "CHECK-JDBC-ROLLBACK-001"),
            (FILES[10], "CHECK-JDBC-SERVER-001"),
        ):
            path = self.root / relative
            saved = path.read_bytes()
            path.unlink()
            self.assertIn(expected, self.ids())
            path.write_bytes(saved)

    def test_store_transaction_and_vendor_boundaries_are_enforced(self) -> None:
        relative = FILES[4]
        self.mutate(relative, "ThreadLocal<Connection>")
        self.assertIn("CHECK-JDBC-STORE-002", self.ids())
        shutil.copy2(SOURCE / relative, self.root / relative)
        self.mutate(relative, "connection.commit()")
        self.assertTrue({"CHECK-JDBC-STORE-002", "CHECK-JDBC-STORE-003"} <= self.ids())
        shutil.copy2(SOURCE / relative, self.root / relative)
        with (self.root / relative).open("a", encoding="utf-8") as stream:
            stream.write("\nimport org.postgresql.Driver;\n")
        self.assertIn("CHECK-JDBC-STORE-004", self.ids())

    def test_post_commit_order_is_enforced(self) -> None:
        relative = FILES[4]
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        text = text.replace("runPostCommitActions(actions)", "runSignals(actions)")
        path.write_text(text, encoding="utf-8")
        self.assertIn("CHECK-JDBC-STORE-003", self.ids())

    def test_dialect_claim_deduplication_and_binding_invariants_are_enforced(self) -> None:
        relative = FILES[5]
        for token in (
            "ORACLE",
            "FOR UPDATE SKIP LOCKED",
            "ON CONFLICT (consumer_name, event_id) DO NOTHING",
            "connection.setSavepoint()",
            "failure.getErrorCode() == 1",
            "java.sql.Types.OTHER",
        ):
            shutil.copy2(SOURCE / relative, self.root / relative)
            self.mutate(relative, token)
            self.assertIn("CHECK-JDBC-DIALECT-002", self.ids())
        shutil.copy2(SOURCE / relative, self.root / relative)
        self.mutate(relative, "LIMIT ?\n                        FOR UPDATE SKIP LOCKED", "FOR UPDATE SKIP LOCKED\n                        LIMIT ?")
        self.assertIn("CHECK-JDBC-DIALECT-003", self.ids())

    def test_unit_of_work_inbox_contract_is_enforced(self) -> None:
        relative = FILES[3]
        self.mutate(relative, "InboxDecision beginInbox(InboxReservation reservation)", "InboxDecision beginInbox(InboxKey key)")
        self.assertIn("CHECK-JDBC-UOW-002", self.ids())
        shutil.copy2(SOURCE / relative, self.root / relative)
        self.mutate(relative, "void completeInbox(InboxKey key, Instant completedAt)", "void completeInbox(InboxKey key)")
        self.assertIn("CHECK-JDBC-UOW-003", self.ids())

    def test_migration_and_rollback_state_guards_are_enforced(self) -> None:
        relative = FILES[6]
        self.mutate(relative, "PROCESSING", "ACTIVE")
        self.assertIn("CHECK-JDBC-MIGRATION-002", self.ids())
        shutil.copy2(SOURCE / relative, self.root / relative)
        self.mutate(relative, "completed_at IS NULL", "completed_at IS NOT NULL")
        self.assertIn("CHECK-JDBC-MIGRATION-003", self.ids())
        relative = FILES[8]
        self.mutate(relative, "cannot roll back migration 0003", "rollback allowed")
        self.assertIn("CHECK-JDBC-ROLLBACK-002", self.ids())

    def test_server_composition_root_is_enforced(self) -> None:
        relative = FILES[10]
        self.mutate(relative, 'havingValue = "ORACLE"')
        self.assertIn("CHECK-JDBC-SERVER-002", self.ids())
        shutil.copy2(SOURCE / relative, self.root / relative)
        self.mutate(FILES[11], "components.adapters.persistence-jdbc")
        self.assertIn("CHECK-JDBC-SERVER-003", self.ids())
        shutil.copy2(SOURCE / FILES[11], self.root / FILES[11])
        self.mutate(FILES[12], "infranexum-adapter-persistence-jdbc")
        self.assertIn("CHECK-JDBC-SERVER-004", self.ids())
        shutil.copy2(SOURCE / FILES[12], self.root / FILES[12])
        self.mutate(FILES[10], "memoryDataSource()", "memoryDataSourceDisabled()")
        self.assertIn("CHECK-JDBC-SERVER-005", self.ids())
        shutil.copy2(SOURCE / FILES[10], self.root / FILES[10])
        self.mutate(FILES[13], "throw unavailable()", "return null")
        self.assertIn("CHECK-JDBC-SERVER-005", self.ids())

    def test_reactor_and_policy_registration_are_enforced(self) -> None:
        self.mutate("pom.xml", "    <module>components/adapters/jdbc</module>\n")
        self.assertIn("CHECK-JDBC-REACTOR-002", self.ids())
        shutil.copy2(SOURCE / "pom.xml", self.root / "pom.xml")
        policy = self.root / FILES[2]
        payload = json.loads(policy.read_text(encoding="utf-8"))
        payload["required_manifest_paths"].remove("components/adapters/jdbc")
        policy.write_text(json.dumps(payload), encoding="utf-8")
        self.assertIn("CHECK-JDBC-POLICY-002", self.ids())
        policy.write_text("{", encoding="utf-8")
        self.assertIn("CHECK-JDBC-POLICY-001", self.ids())

    def test_persistence_check_preflights_the_checkout_before_mutation_tests(self) -> None:
        self.mutate(
            "Makefile",
            "persistence-test: source-integrity-check persistence-check",
            "persistence-test: source-integrity-check",
        )
        self.assertIn("CHECK-JDBC-GATE-002", self.ids())
        shutil.copy2(SOURCE / "Makefile", self.root / "Makefile")
        self.mutate(
            "Makefile",
            "persistence-test: source-integrity-check persistence-check",
            "persistence-test: persistence-check",
        )
        self.assertIn("CHECK-JDBC-GATE-002", self.ids())
        (self.root / "Makefile").unlink()
        self.assertIn("CHECK-JDBC-GATE-001", self.ids())

    def test_external_path_and_cli_are_covered(self) -> None:
        checker = PersistenceChecker(self.root)
        outside = self.root.parent / "outside"
        checker._add("TEST", outside, "outside")
        self.assertEqual(outside.resolve().as_posix(), checker.violations[0].path)

        report = self.root / "reports/persistence.json"
        with patch.object(sys, "argv", ["persistence", "--root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, cli_main())
        self.assertEqual(0, json.loads(report.read_text(encoding="utf-8"))["violation_count"])

        (self.root / FILES[4]).unlink()
        with patch.object(sys, "argv", ["persistence", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, cli_main())
        with patch.object(sys, "argv", ["persistence", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()), warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_module("validation.persistence.cli", run_name="__main__")
        self.assertEqual(1, raised.exception.code)


if __name__ == "__main__":
    unittest.main()
