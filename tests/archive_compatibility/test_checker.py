from __future__ import annotations

import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import MagicMock, patch

from validation.archive_compatibility.checker import ArchiveCompatibilityChecker
from validation.archive_compatibility.cli import main as cli_main


class ArchiveCompatibilityCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.email", "ci@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.name", "CI"], check=True)
        (self.root / "README.md").write_text("ok\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.root), "add", "README.md"], check=True)
        subprocess.run(["git", "-C", str(self.root), "commit", "-qm", "fixture"], check=True)
        self.archive = Path(self.temp.name) / "source.zip"
        self.write_zip([("infranexum-2.0.0-alpha.0.38/README.md", b"ok\n")])

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_zip(self, members: list[tuple[str, bytes]]) -> None:
        with zipfile.ZipFile(self.archive, "w", compression=zipfile.ZIP_DEFLATED) as target:
            for name, content in members:
                target.writestr(name, content)

    def ids(self, parity: bool = False) -> set[str]:
        root = self.root if parity else None
        return {item.check_id for item in ArchiveCompatibilityChecker(self.archive, root).run()}

    def test_reference_archive_is_valid_and_git_complete(self) -> None:
        self.assertEqual(set(), self.ids(parity=True))

    def test_bad_or_empty_archive_is_blocked(self) -> None:
        self.archive.write_bytes(b"not-a-zip")
        self.assertEqual({"CHECK-ARCHIVE-001"}, self.ids())
        self.write_zip([])
        self.assertIn("CHECK-ARCHIVE-002", self.ids())

    def test_paths_must_be_relative_single_root_and_within_budgets(self) -> None:
        long_relative = "a" * 130
        long_root = "r" * 33
        self.write_zip([
            ("/absolute.txt", b"x"),
            ("one/a.txt", b"x"),
            ("two/b.txt", b"x"),
            (f"{long_root}/{long_relative}", b"x"),
        ])
        ids = self.ids()
        self.assertTrue({"CHECK-ARCHIVE-003", "CHECK-ARCHIVE-004", "CHECK-ARCHIVE-005", "CHECK-ARCHIVE-006", "CHECK-ARCHIVE-007", "CHECK-ARCHIVE-010"} <= ids)

    def test_windows_invalid_reserved_and_colliding_names_are_blocked(self) -> None:
        self.write_zip([
            ("root/CON.txt", b"x"),
            ("root/name .txt", b"x"),
            ("root/bad:name.txt", b"x"),
            ("root/A.txt", b"x"),
            ("root/a.txt", b"y"),
        ])
        self.assertTrue({"CHECK-ARCHIVE-007", "CHECK-ARCHIVE-008"} <= self.ids())

    def test_symbolic_links_are_rejected(self) -> None:
        with zipfile.ZipFile(self.archive, "w") as target:
            info = zipfile.ZipInfo("root/link")
            info.create_system = 3
            info.external_attr = (0o120777 << 16)
            target.writestr(info, "target")
        self.assertIn("CHECK-ARCHIVE-009", self.ids())


    def test_corrupt_member_reported_by_zip_self_test(self) -> None:
        source = MagicMock()
        source.__enter__.return_value = source
        source.__exit__.return_value = False
        source.testzip.return_value = "root/bad.txt"
        source.infolist.return_value = []
        with patch("validation.archive_compatibility.checker.zipfile.ZipFile", return_value=source):
            self.assertTrue({"CHECK-ARCHIVE-001", "CHECK-ARCHIVE-002"} <= self.ids())

    def test_backslash_trailing_space_and_root_directory_are_rejected_portably(self) -> None:
        self.write_zip([
            ("root/", b""),
            ("root\\bad.txt", b"x"),
            ("root/bad. ", b"x"),
            ("root//duplicate-separator.txt", b"x"),
            ("./root/dot-prefix.txt", b"x"),
        ])
        self.assertTrue({"CHECK-ARCHIVE-003", "CHECK-ARCHIVE-007"} <= self.ids())

    def test_git_parity_returns_after_multi_root_validation(self) -> None:
        self.write_zip([("one/README.md", b"ok\n"), ("two/other.txt", b"x")])
        ids = self.ids(parity=True)
        self.assertIn("CHECK-ARCHIVE-010", ids)
        self.assertNotIn("CHECK-ARCHIVE-012", ids)

    def test_git_parity_detects_missing_and_extra_members(self) -> None:
        self.write_zip([("root/extra.txt", b"x")])
        self.assertIn("CHECK-ARCHIVE-012", self.ids(parity=True))

    def test_git_failure_is_reported(self) -> None:
        outside = Path(self.temp.name) / "not-git"
        outside.mkdir()
        self.assertIn("CHECK-ARCHIVE-011", {item.check_id for item in ArchiveCompatibilityChecker(self.archive, outside).run()})

    def test_cli_writes_report_and_status(self) -> None:
        report = Path(self.temp.name) / "report.json"
        with patch.object(sys, "argv", ["archive-compat", "--archive", str(self.archive), "--repository-root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, cli_main())
        self.assertEqual(0, json.loads(report.read_text(encoding="utf-8"))["violation_count"])
        self.write_zip([("root/CON", b"x")])
        with patch.object(sys, "argv", ["archive-compat", "--archive", str(self.archive)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, cli_main())

    def test_module_entrypoint_returns_success(self) -> None:
        cli_path = Path(__file__).resolve().parents[2] / "validation/archive_compatibility/cli.py"
        namespace = {
            "__name__": "__main__",
            "__package__": "validation.archive_compatibility",
            "__file__": str(cli_path),
        }
        with patch.object(
            sys,
            "argv",
            ["archive-compat", "--archive", str(self.archive), "--repository-root", str(self.root)],
        ):
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as raised:
                    exec(compile(cli_path.read_text(encoding="utf-8"), str(cli_path), "exec"), namespace)
        self.assertEqual(0, raised.exception.code)



if __name__ == "__main__":
    unittest.main()
