"""Regression guards for Jackson 3 JSON file parsing at Server CLI boundaries."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


class CliJsonInputBoundaryArchitectureTest(unittest.TestCase):
    """Keep file I/O and Jackson parsing failures distinct and fail-closed."""

    ROOT = Path(__file__).resolve().parents[2]
    SOURCES = (
        "src/applications/server/main/io/infranexum/server/itam/cli/ItamAssetCli.java",
        "src/applications/server/main/io/infranexum/server/itam/cli/ItamPartnerCli.java",
        "src/applications/server/main/io/infranexum/server/itam/cli/ItamComplianceCli.java",
        "src/applications/server/main/io/infranexum/server/dcim/cli/DcimFacilityCli.java",
        "src/applications/server/main/io/infranexum/server/dcim/cli/DcimPhysicalCli.java",
        "src/applications/server/main/io/infranexum/server/ddi/cli/IpamCli.java",
    )

    @classmethod
    def setUpClass(cls) -> None:
        cls.contents = {
            relative: (cls.ROOT / relative).read_text(encoding="utf-8")
            for relative in cls.SOURCES
        }

    def test_every_json_file_boundary_catches_jackson_runtime_parse_failures(self) -> None:
        """Jackson 3 parse failures must become CLI usage errors, never EXIT_INTERNAL."""
        for relative, source in self.contents.items():
            with self.subTest(source=relative):
                self.assertIn("import tools.jackson.core.JacksonException;", source)
                self.assertRegex(source, r"catch\s*\(JacksonException\s+\w+\)")
                self.assertIn("invalid JSON", source)

    def test_file_io_is_completed_before_json_parsing(self) -> None:
        """Prevent a single IOException catch from incorrectly owning Jackson parsing."""
        forbidden = re.compile(r"json\.readTree\s*\(\s*Files\.readString\s*\(")
        for relative, source in self.contents.items():
            with self.subTest(source=relative):
                self.assertIsNone(forbidden.search(source))
                self.assertIn("Files.readString", source)
                self.assertIn("json.readTree(payload)", source)

    def test_unreadable_files_and_invalid_json_have_distinct_diagnostics(self) -> None:
        """Operators must be able to distinguish filesystem faults from invalid input."""
        for relative, source in self.contents.items():
            with self.subTest(source=relative):
                self.assertRegex(source, r"catch\s*\(IOException\s+\w+\)")
                self.assertRegex(source, r"unreadable")
                self.assertNotIn("unreadable or invalid JSON", source)


if __name__ == "__main__":
    unittest.main()
