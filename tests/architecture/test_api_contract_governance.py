from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]


class ApiContractGovernanceArchitectureTests(unittest.TestCase):
    """Pin PGM-05-E01 phase-1 governance into source, CI and release metadata."""

    def test_makefile_exposes_blocking_api_contract_targets(self) -> None:
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        self.assertIn("api-contract-test: source-integrity-check", makefile)
        self.assertIn("api-contract-check:", makefile)
        verify_line = next(line for line in makefile.splitlines() if line.startswith("verify-foundation:"))
        self.assertIn("api-contract-test", verify_line)
        self.assertIn("api-contract-check", verify_line)

    def test_foundation_workflow_executes_api_contract_gates(self) -> None:
        workflow = (ROOT / ".github/workflows/foundation.yml").read_text(encoding="utf-8")
        architecture_command = next(
            line.strip()
            for line in workflow.splitlines()
            if "make api-contract-test api-contract-check architecture-test" in line
        )
        self.assertTrue(architecture_command.startswith("run: make api-contract-test api-contract-check"))

    def test_catalogue_and_debt_baseline_are_versioned_sources(self) -> None:
        catalogue = yaml.safe_load(
            (ROOT / "src/applications/server/resources/openapi/catalogue.yaml").read_text(encoding="utf-8")
        )
        baseline = json.loads((ROOT / "validation/api_contracts/baseline.json").read_text(encoding="utf-8"))
        version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
        self.assertEqual(version, catalogue["version"])
        self.assertEqual("infranexum.openapi-catalogue/v1", catalogue["schema"])
        self.assertEqual("infranexum.api-contract-debt/v1", baseline["schema"])
        self.assertEqual(13, len(catalogue["fragments"]))

    def test_generated_product_contract_is_not_a_canonical_source(self) -> None:
        self.assertFalse((ROOT / "src/applications/server/resources/openapi/openapi-product.yaml").exists())
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("artifacts/", gitignore)

    def test_governance_document_marks_epic_as_in_progress(self) -> None:
        document = (ROOT / "docs/api-platform-contract-governance.md").read_text(encoding="utf-8")
        self.assertIn("PGM-05-E01 remains **IN PROGRESS**", document)
        self.assertIn("idempotency=39", document.replace(" **39**", "=39"))
        self.assertIn("pagination=0", document.replace(" **0**", "=0"))
        self.assertIn("capability=56", document.replace(" **56**", "=56"))
        self.assertIn("permission=85", document.replace(" **85**", "=85"))


if __name__ == "__main__":
    unittest.main()
