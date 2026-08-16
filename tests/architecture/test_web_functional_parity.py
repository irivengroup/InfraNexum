"""Cross-layer regressions for alpha.0.80 RSOT/ITAM Web functional parity."""

from __future__ import annotations

import unittest
from pathlib import Path

import yaml


class WebFunctionalParityArchitectureTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web/public"

    def test_rsot_canonical_read_contract_is_organization_scoped_and_capability_gated(self) -> None:
        controller = (self.SERVER / "main/io/infranexum/server/rsot/RsotObjectController.java").read_text(encoding="utf-8")
        requirements = (self.SERVER / "main/io/infranexum/server/identityaccess/AuthorizationRequirement.java").read_text(encoding="utf-8")
        permissions = (self.ROOT / "src/components/domains/identity-access/main/io/infranexum/identity/access/domain/PermissionCodes.java").read_text(encoding="utf-8")
        spec = yaml.safe_load((self.SERVER / "resources/openapi/rsot-canonical-objects.yaml").read_text(encoding="utf-8"))

        self.assertIn('RSOT_READ="rsot.read"', permissions)
        self.assertIn('controllerScoped("rsot-object"', requirements)
        self.assertIn("AuthorizationScope.organization", controller)
        self.assertIn("PermissionCodes.RSOT_READ", controller)
        self.assertIn('capabilities.explain("rsot.core")', controller)
        organization = next(
            parameter for parameter in spec["paths"]["/api/v1/rsot/canonical-objects"]["get"]["parameters"]
            if parameter.get("name") == "organization_id"
        )
        self.assertTrue(organization["required"])
        operations = [
            operation
            for item in spec["paths"].values()
            for method, operation in item.items()
            if method.lower() in {"get", "post", "put", "patch", "delete"}
        ]
        self.assertEqual(2, len(operations))
        self.assertEqual(2, len({operation["operationId"] for operation in operations}))
        for operation in operations:
            self.assertEqual({"mode": "permission", "code": "rsot.read"}, operation["x-infranexum-permission"])
            self.assertEqual("rsot.core", operation["x-infranexum-capability"])
        self.assertEqual([{"LocalSessionCookie": []}], spec["security"])

    def test_web_shell_mounts_real_capability_gated_rsot_and_itam_workspaces(self) -> None:
        index = (self.WEB / "index.html").read_text(encoding="utf-8")
        shell = (self.WEB / "assets/admin-shell.mjs").read_text(encoding="utf-8")
        bootstrap = (self.WEB / "assets/bootstrap.mjs").read_text(encoding="utf-8")
        for identifier in ("nav-rsot", "nav-itam", "rsot-workspace", "itam-workspace"):
            self.assertIn(f'id="{identifier}"', index)
        self.assertIn("setRsotAvailability", shell)
        self.assertIn("setItamAvailability", shell)
        self.assertIn("initializeRsotWorkspace", bootstrap)
        self.assertIn("initializeItamWorkspace", bootstrap)
        self.assertIn("configuration.rsotCoreEnabled", bootstrap)
        self.assertIn("configuration.itamPartnersEnabled", bootstrap)

    def test_governed_entity_references_are_selects_and_temporal_inputs_use_shared_picker(self) -> None:
        rsot = (self.WEB / "assets/rsot-workspace.mjs").read_text(encoding="utf-8")
        itam = (self.WEB / "assets/itam-workspace.mjs").read_text(encoding="utf-8")
        combined = rsot + "\n" + itam
        for identifier in (
            "rsot-object-organization", "itam-organization", "itam-subdivision",
            "itam-asset-rsot", "itam-asset-supplier", "itam-asset-producer", "itam-custodian-id",
            "itam-warranty-manufacturer", "itam-warranty-type", "itam-license-publisher",
            "itam-coverage-provider", "itam-coverage-authorization",
        ):
            self.assertIn(f'id="{identifier}"', combined)
            self.assertRegex(combined, rf'<select[^>]+id="{identifier}"|<select id="{identifier}"')
        self.assertGreaterEqual(combined.count('data-inx-temporal="date"'), 4)
        self.assertGreaterEqual(combined.count('data-inx-temporal="datetime"'), 3)

    def test_web_parity_has_interaction_tests_and_nul_regression_guard(self) -> None:
        test = (self.ROOT / "tests/web/web-functional-parity.test.mjs").read_text(encoding="utf-8")
        self.assertIn("contains no NUL bytes", test)
        self.assertIn("real first-level administration routes", test)
        self.assertIn("entity identifiers are selected from governed catalogues", test)
        self.assertIn("every supported locale", test)


if __name__ == "__main__":
    unittest.main()
