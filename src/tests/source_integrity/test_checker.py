from __future__ import annotations

import contextlib
import io
import json
import runpy
import subprocess
import sys
import tempfile
import unittest
import warnings
from pathlib import Path
from unittest.mock import patch

from validation.source_integrity.checker import INVENTORY_PATH, SCHEMA, SourceIntegrityChecker
from validation.source_integrity.cli import main as cli_main


POM = """<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>io.infranexum</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>src/module</module></modules></project>"""
MODULE_POM = """<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>io.infranexum</groupId><artifactId>module</artifactId><version>1</version></project>"""
A_JAVA = """package io.infranexum.a;\nimport io.infranexum.b.B;\npublic final class A { private final B b = new B(); }\n"""
B_JAVA = """package io.infranexum.b;\npublic final class B {}\n"""


class SourceIntegrityCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repository"
        self.root.mkdir()
        self.write("pom.xml", POM)
        self.write("VERSION", "test\n")
        self.write(
            "Makefile",
            "\n".join(
                f"{target}: source-integrity-check"
                for target in (
                    "architecture-test", "toolchain-test", "migration-test", "eventing-test",
                    "persistence-test", "capabilities-test", "entitlements-test", "audit-test"
                )
            ) + "\n",
        )
        self.write("src/module/pom.xml", MODULE_POM)
        self.write("src/module/src/main/java/io/infranexum/a/A.java", A_JAVA)
        self.write("src/module/src/main/java/io/infranexum/b/B.java", B_JAVA)
        self.write(".github/workflows/test.yml", "name: test\n")
        self.write_inventory()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def canonical(self) -> list[str]:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        return sorted(checker.canonical_files())

    def write_inventory(self, paths: list[str] | None = None) -> None:
        target = self.root / INVENTORY_PATH
        target.parent.mkdir(parents=True, exist_ok=True)
        if paths is None:
            paths = self.canonical()
        target.write_text(json.dumps({"schema": SCHEMA, "paths": sorted(paths)}, indent=2) + "\n", encoding="utf-8")

    def ids(self, *, require_git_tracking: bool | None = False) -> set[str]:
        return {item.check_id for item in SourceIntegrityChecker(self.root, require_git_tracking=require_git_tracking).run()}

    def test_reference_repository_is_valid(self) -> None:
        self.assertEqual(set(), self.ids())

    def test_inventory_missing_invalid_unsafe_duplicate_and_unsorted_are_rejected(self) -> None:
        (self.root / INVENTORY_PATH).unlink()
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())
        self.write(str(INVENTORY_PATH), "{")
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())
        self.write(str(INVENTORY_PATH), json.dumps({"schema": "wrong", "paths": []}))
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())
        self.write(str(INVENTORY_PATH), json.dumps({"schema": SCHEMA, "paths": ["../escape"]}))
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())
        self.write(str(INVENTORY_PATH), json.dumps({"schema": SCHEMA, "paths": ["b", "a", "a"]}))
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())

    def test_inventory_rejects_case_insensitive_collisions(self) -> None:
        paths = self.canonical()
        paths.extend(["src/Case.txt", "src/case.txt"])
        self.write("src/Case.txt", "a")
        self.write("src/case.txt", "b")
        self.write_inventory(paths)
        self.assertIn("CHECK-SOURCE-INVENTORY-004", self.ids())

    def test_inventory_detects_missing_and_undeclared_files(self) -> None:
        paths = self.canonical()
        declared = paths + ["src/expected-but-missing.txt"]
        self.write_inventory(declared)
        self.assertIn("CHECK-SOURCE-INVENTORY-002", self.ids())
        self.write_inventory(paths)
        self.write("src/module/extra.txt", "extra")
        self.assertIn("CHECK-SOURCE-INVENTORY-003", self.ids())

    def test_java_graph_rejects_filename_type_mismatch(self) -> None:
        self.write(
            "src/module/src/main/java/io/infranexum/a/WrongName.java",
            "package io.infranexum.a;\npublic final class RightName {}\n",
        )
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-JAVA-004", self.ids())

    def test_java_graph_accepts_package_private_top_level_type(self) -> None:
        self.write(
            "src/module/src/main/java/io/infranexum/internal/Internal.java",
            "package io.infranexum.internal;\nfinal class Internal {}\n",
        )
        self.write_inventory()
        self.assertEqual(set(), self.ids())

    def test_java_graph_detects_missing_import_duplicate_and_malformed_source(self) -> None:
        (self.root / "src/module/src/main/java/io/infranexum/b/B.java").unlink()
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-JAVA-003", self.ids())

        self.write("src/module/src/main/java/io/infranexum/b/B.java", B_JAVA)
        self.write("src/module/src/main/java/io/infranexum/duplicate/B.java", B_JAVA)
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-JAVA-002", self.ids())

        self.write("src/module/src/main/java/io/infranexum/invalid/Invalid.java", "final class Invalid {}\n")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-JAVA-001", self.ids())

    def test_java_read_failure_is_reported(self) -> None:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        missing = self.root / "src/missing.java"
        self.assertIsNone(checker._read(missing, "CHECK-SOURCE-JAVA-001"))
        self.assertEqual("CHECK-SOURCE-JAVA-001", checker.violations[0].check_id)

    def test_java_graph_skips_unreadable_source_after_reporting(self) -> None:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        original = checker._read

        def unreadable(path: Path, check_id: str) -> str | None:
            if path.name == "A.java":
                checker._add(check_id, path, "simulated read failure")
                return None
            return original(path, check_id)

        with patch.object(checker, "_read", side_effect=unreadable):
            checker._check_java_graph()
        self.assertIn("CHECK-SOURCE-JAVA-001", {item.check_id for item in checker.violations})

    def test_maven_detects_orphan_module_pom(self) -> None:
        self.write("src/orphan/pom.xml", MODULE_POM.replace("<artifactId>module</artifactId>", "<artifactId>orphan</artifactId>"))
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-003", self.ids())

    def test_makefile_requires_source_integrity_preflight_for_validation_suites(self) -> None:
        makefile = self.root / "Makefile"
        text = makefile.read_text(encoding="utf-8")
        makefile.write_text(text.replace("capabilities-test: source-integrity-check", "capabilities-test:"), encoding="utf-8")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAKE-002", self.ids())
        makefile.unlink()
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAKE-001", self.ids())

    def test_maven_invalid_root_unsafe_and_missing_module_are_rejected(self) -> None:
        self.write("pom.xml", "<")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-001", self.ids())
        self.write("pom.xml", POM.replace("src/module", "../module"))
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-001", self.ids())
        self.write("pom.xml", POM)
        (self.root / "src/module/pom.xml").unlink()
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-002", self.ids())

    def test_git_tracking_detects_untracked_inventory_file(self) -> None:
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        self.assertEqual(set(), self.ids(require_git_tracking=True))
        tracked = self.root / "src/module/src/main/java/io/infranexum/b/B.java"
        subprocess.run(["git", "-C", str(self.root), "rm", "--cached", "-q", str(tracked.relative_to(self.root))], check=True)
        self.assertIn("CHECK-SOURCE-GIT-002", self.ids(require_git_tracking=True))

    def test_git_required_without_repository_and_git_command_error_are_reported(self) -> None:
        self.assertIn("CHECK-SOURCE-GIT-001", self.ids(require_git_tracking=True))
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        with patch("validation.source_integrity.checker.subprocess.run", side_effect=OSError("git missing")):
            self.assertFalse(checker._is_git_checkout())
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        checker = SourceIntegrityChecker(self.root, require_git_tracking=True)
        with patch.object(checker, "_is_git_checkout", return_value=True), patch(
            "validation.source_integrity.checker.subprocess.run", side_effect=OSError("git missing")
        ):
            checker._check_git_tracking(tuple(self.canonical()))
        self.assertIn("CHECK-SOURCE-GIT-001", {item.check_id for item in checker.violations})

    def test_external_path_and_cli_paths_are_covered(self) -> None:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        outside = self.root.parent / "outside"
        checker._add("TEST", outside, "outside")
        self.assertEqual(outside.resolve().as_posix(), checker.violations[0].path)

        report = self.root / "reports/source-integrity.json"
        with patch.object(sys, "argv", ["source-integrity", "--root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, cli_main())
        self.assertEqual(0, json.loads(report.read_text(encoding="utf-8"))["violation_count"])

        (self.root / "src/module/src/main/java/io/infranexum/b/B.java").unlink()
        with patch.object(sys, "argv", ["source-integrity", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, cli_main())
        with patch.object(sys, "argv", ["source-integrity", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()), warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_module("validation.source_integrity.cli", run_name="__main__")
        self.assertEqual(1, raised.exception.code)

    def test_cli_can_regenerate_inventory(self) -> None:
        inventory = self.root / INVENTORY_PATH
        inventory.unlink()
        with patch.object(sys, "argv", ["source-integrity", "--root", str(self.root), "--update-inventory"]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, cli_main())
        payload = json.loads(inventory.read_text(encoding="utf-8"))
        self.assertEqual(SCHEMA, payload["schema"])
        self.assertEqual(sorted(payload["paths"]), payload["paths"])
        self.assertIn("src/module/src/main/java/io/infranexum/a/A.java", payload["paths"])

    def test_safe_relative_and_git_autodetection(self) -> None:
        self.assertTrue(SourceIntegrityChecker._safe_relative("src/a.txt"))
        self.assertFalse(SourceIntegrityChecker._safe_relative(""))
        self.assertFalse(SourceIntegrityChecker._safe_relative("../a"))
        self.assertFalse(SourceIntegrityChecker._safe_relative("/tmp/a"))
        checker = SourceIntegrityChecker(self.root)
        self.assertEqual(set(), {item.check_id for item in checker.run()})


if __name__ == "__main__":
    unittest.main()
