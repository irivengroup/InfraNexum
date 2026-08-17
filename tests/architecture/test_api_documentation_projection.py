from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEB_SPEC = ROOT / "src/applications/web/public/assets/generated/infranexum-openapi.yaml"
WEB_SPEC_JSON = ROOT / "src/applications/web/public/assets/generated/infranexum-openapi.json"


class ApiDocumentationProjectionArchitectureTests(unittest.TestCase):
    """Keep the Web documentation contract as a deterministic projection of canonical fragments."""

    def test_embedded_openapi_is_generated_from_the_certified_catalogue(self) -> None:
        self.assertTrue(WEB_SPEC.is_file(), "Web OpenAPI projection is missing")
        environment = os.environ.copy()
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        with tempfile.TemporaryDirectory(prefix="infranexum-openapi-") as directory:
            generated = Path(directory) / "infranexum-openapi.yaml"
            completed = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "validation.api_contracts.cli",
                    "--root",
                    str(ROOT),
                    "--product-spec",
                    str(generated),
                ],
                cwd=ROOT,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
                timeout=60,
            )
            self.assertEqual(0, completed.returncode, completed.stdout)
            self.assertEqual(
                generated.read_bytes(),
                WEB_SPEC.read_bytes(),
                "The Web documentation OpenAPI must be regenerated, never hand-edited",
            )
            generated_json = Path(directory) / "infranexum-openapi.json"
            completed_json = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "validation.api_contracts.cli",
                    "--root",
                    str(ROOT),
                    "--product-spec-json",
                    str(generated_json),
                ],
                cwd=ROOT,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
                timeout=60,
            )
            self.assertEqual(0, completed_json.returncode, completed_json.stdout)
            self.assertEqual(
                generated_json.read_bytes(),
                WEB_SPEC_JSON.read_bytes(),
                "The Web ReDoc JSON projection must be regenerated, never hand-edited",
            )

    def test_documentation_projection_tracks_release_version(self) -> None:
        version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
        document = WEB_SPEC.read_text(encoding="utf-8")
        document_json = WEB_SPEC_JSON.read_text(encoding="utf-8")
        self.assertIn(f"version: {version}", document)
        self.assertIn(f'"version":"{version}"', document_json)


if __name__ == "__main__":
    unittest.main()
