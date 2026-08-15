"""Regression gate for valid Java text-block opening syntax."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


class JavaTextBlockRegressionTest(unittest.TestCase):
    """Prevent malformed inline Java text-block openings from re-entering source."""

    ROOT = Path(__file__).resolve().parents[2]

    def test_returned_text_blocks_start_on_the_next_line(self) -> None:
        invalid: list[str] = []
        for path in (self.ROOT / "src").rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            if re.search(r'\breturn\s+"""[^\r\n]', text):
                invalid.append(str(path.relative_to(self.ROOT)))
        self.assertEqual(
            invalid,
            [],
            f"Java text blocks must start after a line terminator: {invalid}",
        )
