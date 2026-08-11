from __future__ import annotations

import contextlib
import io
import json
import runpy
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from validation.audit.checker import AuditChecker
from validation.audit.cli import main as cli_main

SOURCE = Path(__file__).resolve().parents[2]

FILES = (
    "pom.xml",
    "Makefile",
    ".github/workflows/foundation.yml",
    "validation/architecture/policy.json",
    "src/components/core/audit/MANIFEST.json",
    "src/components/core/audit/pom.xml",
    "src/components/core/audit/audit-contract-pack.json",
    "src/components/core/audit/main/io/infranexum/core/audit/AuditEntry.java",
    "src/components/core/audit/main/io/infranexum/core/audit/AuditJournal.java",
    "src/components/core/audit/main/io/infranexum/core/audit/AuditCanonicalizer.java",
    "src/components/core/audit/main/io/infranexum/core/audit/AuditExportService.java",
    "src/components/core/audit/main/io/infranexum/core/audit/AuditExportVerifier.java",
    "src/components/core/audit/main/io/infranexum/core/audit/AuditPurgeTombstone.java",
    "src/components/adapters/jdbc/MANIFEST.json",
    "src/components/adapters/jdbc/pom.xml",
    "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcAuditJournal.java",
    "src/distribution/migrations/0005-core-audit/migration.yaml",
    "src/distribution/migrations/0005-core-audit/postgresql.sql",
    "src/distribution/migrations/0005-core-audit/oracle.sql",
    "src/distribution/migrations/0005-core-audit/verify.sql.yaml",
    "src/distribution/migrations/0005-core-audit/rollback/postgresql.sql",
    "src/distribution/migrations/0005-core-audit/rollback/oracle.sql",
)


class AuditCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for relative in FILES:
            source = SOURCE / relative
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def ids(self) -> set[str]:
        return {item.check_id for item in AuditChecker(self.root).run()}

    def reset(self, relative: str) -> None:
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SOURCE / relative, target)

    def mutate(self, relative: str, old: str, new: str = "") -> None:
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1), encoding="utf-8")

    def mutate_all(self, relative: str, old: str, new: str = "") -> None:
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new), encoding="utf-8")

    def test_reference_contract_is_valid(self) -> None:
        self.assertEqual(set(), self.ids())

    def test_missing_artifacts_are_reported(self) -> None:
        for relative in (FILES[4], FILES[8], FILES[12], FILES[15], FILES[18], FILES[21]):
            path = self.root / relative
            saved = path.read_bytes()
            path.unlink()
            self.assertIn("CHECK-AUD-FILES-001", self.ids())
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(saved)

    def test_contract_pack_is_strict(self) -> None:
        path = self.root / FILES[6]
        payload = json.loads(path.read_text())
        payload["append_only"] = False
        payload["chain_digest"] = "MD5"
        payload["entry_fields"].remove("origin")
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assertTrue({"CHECK-AUD-PACK-002", "CHECK-AUD-PACK-003"} <= self.ids())
        path.write_text("[]", encoding="utf-8")
        self.assertIn("CHECK-AUD-PACK-001", self.ids())
        path.write_text("{", encoding="utf-8")
        self.assertIn("CHECK-AUD-PACK-001", self.ids())

    def test_java_invariants_are_enforced(self) -> None:
        cases = (
            (FILES[7], "SENSITIVE_KEY"),
            (FILES[9], 'MessageDigest.getInstance("SHA-256")'),
            (FILES[10], 'Signature.getInstance("Ed25519")'),
            (FILES[11], "MessageDigest.isEqual"),
            (FILES[12], "two distinct approvers"),
        )
        for relative, token in cases:
            self.mutate_all(relative, token)
            self.assertIn("CHECK-AUD-JAVA-006", self.ids())
            self.reset(relative)

    def test_jdbc_append_only_contract_is_enforced(self) -> None:
        self.mutate(FILES[15], "TRANSACTION_READ_COMMITTED")
        self.assertIn("CHECK-AUD-JDBC-002", self.ids())
        self.reset(FILES[15])
        with (self.root / FILES[15]).open("a", encoding="utf-8") as stream:
            stream.write('\n// DELETE FROM infranexum_core.audit_entry\n')
        self.assertIn("CHECK-AUD-JDBC-003", self.ids())

    def test_migration_requires_hashes_triggers_and_safe_rollback(self) -> None:
        self.mutate_all(FILES[17], "previous_hash")
        self.assertIn("CHECK-AUD-MIG-002", self.ids())
        self.reset(FILES[17])
        self.mutate(FILES[18], "RAISE_APPLICATION_ERROR(-20005, 'InfraNexum Core Audit is append-only')", "NULL")
        self.assertIn("CHECK-AUD-MIG-003", self.ids())
        self.reset(FILES[18])
        path = self.root / FILES[20]
        path.write_text(path.read_text(encoding="utf-8").replace("EXISTS (", "("), encoding="utf-8")
        self.assertIn("CHECK-AUD-MIG-005", self.ids())
        self.reset(FILES[20])
        path = self.root / FILES[21]
        text = path.read_text(encoding="utf-8")
        path.write_text("\n".join(line for line in text.splitlines() if "SELECT COUNT(" not in line.upper()) + "\n", encoding="utf-8")
        self.assertIn("CHECK-AUD-MIG-005", self.ids())
        self.reset(FILES[21])

    def test_reactor_ci_and_manifest_wiring_are_enforced(self) -> None:
        cases = (
            (FILES[0], "<module>src/components/core/audit</module>"),
            (FILES[1], "audit-test"),
            (FILES[1], "java-audit-smoke"),
            (FILES[2], "audit-test"),
            (FILES[2], "make postgresql-test-schema"),
            (FILES[3], "components/core/audit"),
            (FILES[13], "components.core.audit"),
            (FILES[14], "infranexum-core-audit"),
        )
        for relative, token in cases:
            self.mutate_all(relative, token)
            self.assertIn("CHECK-AUD-WIRE-002", self.ids())
            self.reset(relative)

    def test_unreadable_inputs_external_paths_and_cli_are_covered(self) -> None:
        (self.root / FILES[10]).unlink()
        self.assertTrue({"CHECK-AUD-FILES-001", "CHECK-AUD-JAVA-003"} <= self.ids())
        self.reset(FILES[10])

        checker = AuditChecker(self.root)
        outside = self.root.parent / "outside-audit"
        checker._add("TEST", outside, "outside")
        self.assertEqual(outside.resolve().as_posix(), checker.violations[0].path)

        with patch.object(Path, "read_text", side_effect=OSError("denied")):
            self.assertIn("CHECK-AUD-PACK-001", self.ids())

        report = self.root / "reports/audit.json"
        with patch.object(sys, "argv", ["audit", "--root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, cli_main())
        self.assertEqual(0, json.loads(report.read_text())["violation_count"])

        self.mutate(FILES[6], '"append_only": true', '"append_only": false')
        with patch.object(sys, "argv", ["audit", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, cli_main())
        self.reset(FILES[6])
        with patch.object(sys, "argv", ["audit", "--root", str(SOURCE)]):
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as caught:
                    runpy.run_path(str(SOURCE / "validation/audit/cli.py"), run_name="__main__")
        self.assertEqual(0, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
