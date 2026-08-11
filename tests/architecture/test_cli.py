
from __future__ import annotations

import json
import io
import runpy
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from validation.architecture.cli import build_parser, main
from validation.architecture.model import CheckReport, Violation


class ArchitectureCliTest(unittest.TestCase):
    def test_parser_requires_policy(self) -> None:
        parser = build_parser()
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            parser.parse_args([])

    def test_main_writes_pass_report(self) -> None:
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            with redirect_stdout(io.StringIO()):
                code = main(["--root", str(root), "--policy", str(root / "validation/architecture/policy.json"), "--json-report", str(report)])
            self.assertEqual(0, code)
            self.assertEqual("PASS", json.loads(report.read_text(encoding="utf-8"))["status"])

    @patch("validation.architecture.cli.ArchitectureChecker")
    def test_main_returns_failure(self, checker_type) -> None:
        checker_type.return_value.run.return_value = CheckReport("root", "policy", (Violation("X", "p", "m"),))
        with redirect_stderr(io.StringIO()):
            self.assertEqual(1, main(["--policy", "policy.json"]))

    def test_module_entry_point_exits_with_success(self) -> None:
        root = Path(__file__).resolve().parents[2]
        arguments = [
            "validation.architecture.cli",
            "--root",
            str(root),
            "--policy",
            str(root / "validation/architecture/policy.json"),
        ]
        loaded_module = sys.modules.pop("validation.architecture.cli", None)
        try:
            with patch.object(sys, "argv", arguments), redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as raised:
                runpy.run_module("validation.architecture.cli", run_name="__main__")
            self.assertEqual(0, raised.exception.code)
        finally:
            if loaded_module is not None:
                sys.modules["validation.architecture.cli"] = loaded_module
