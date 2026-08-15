"""Architecture and full-stack parity regressions for PGM-07-E05 physical infrastructure."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml


class DcimPhysicalInfrastructureArchitectureTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    DOMAIN = ROOT / "src/components/domains/dcim/main/io/infranexum/dcim/physical"
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web"
    MIGRATION = ROOT / "src/distribution/migrations/0028-dcim-rack-equipment-cabling"

    def test_domain_models_multivendor_footprints_racks_ports_and_cables(self) -> None:
        model = (self.DOMAIN / "domain/EquipmentModel.java").read_text(encoding="utf-8")
        service = (self.DOMAIN / "application/DcimPhysicalApplicationService.java").read_text(encoding="utf-8")
        for token in ("manufacturerPartnerId", "rackUnits", "widthMm", "depthMm", "portTemplates"):
            self.assertIn(token, model)
        for token in (
            "lockRackForOccupancy",
            "lockPortsForConnection",
            "DCIM_RACK_POSITION_OCCUPIED",
            "DCIM_PORT_ALREADY_CONNECTED",
            "DCIM_PORT_MEDIA_MISMATCH",
        ):
            self.assertIn(token, service)

    def test_storage_keeps_authority_boundaries_and_serializes_competing_allocations(self) -> None:
        pg = (self.MIGRATION / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.MIGRATION / "oracle.sql").read_text(encoding="utf-8").lower()
        jdbc = (self.ROOT / "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcDcimPhysicalRepository.java").read_text(encoding="utf-8")
        logical = json.loads((self.MIGRATION / "logical-model.json").read_text(encoding="utf-8"))
        for forbidden in (
            "references infranexum_org",
            "references infranexum_iam",
            "references infranexum_rsot",
            "references infranexum_itam",
        ):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        objects = {item["name"]: item for item in logical["objects"]}
        self.assertEqual(["organization_id", "manufacturer_partner_id"], objects["equipment_model"]["weak_references"])
        self.assertEqual(["rsot_object_id", "itam_asset_id"], objects["equipment"]["weak_references"])
        self.assertIn("FOR UPDATE", jdbc)
        self.assertIn("lockRackForOccupancy", jdbc)
        self.assertIn("lockPortsForConnection", jdbc)

    def test_openapi_exposes_four_functional_contexts_with_unique_native_operations(self) -> None:
        spec = yaml.safe_load((self.SERVER / "resources/openapi/dcim-physical.yaml").read_text(encoding="utf-8"))
        operations = []
        for item in spec["paths"].values():
            operations.extend(value for key, value in item.items() if key in {"get", "post", "patch", "put", "delete"})
        self.assertEqual("3.1.0", spec["openapi"])
        self.assertEqual(14, len(operations))
        self.assertEqual(14, len({op["operationId"] for op in operations}))
        self.assertTrue(all(op["x-infranexum-capability"] == "dcim.physical" for op in operations))
        self.assertIn("x-tagGroups", spec)

    def test_web_parity_includes_governed_references_lifecycle_move_and_cabling(self) -> None:
        workspace = (self.WEB / "public/assets/dcim-physical-workspace.mjs").read_text(encoding="utf-8")
        client = (self.WEB / "public/assets/dcim-physical.mjs").read_text(encoding="utf-8")
        config = (self.WEB / "runtime/config.mjs").read_text(encoding="utf-8")
        for governed in (
            "manufacturerPartnerId",
            "rackId",
            "modelId",
            "rsotObjectId",
            "itamAssetId",
            "portAId",
            "portBId",
            "destinationRackId",
        ):
            self.assertIn(f"sel('{governed}'", workspace)
        self.assertNotRegex(workspace, r'<input[^>]+name=["\'](?:manufacturerPartnerId|rackId|modelId|rsotObjectId|itamAssetId|portAId|portBId|destinationRackId)["\']')
        for token in ("lifecycleControls('model')", "lifecycleControls('rack')", "dcim-equipment-move-form", "c.move(", "c.connect(", "c.disconnect("):
            self.assertIn(token, workspace)
        self.assertIn("If-Match", client)
        self.assertIn("Idempotency-Key", client)
        self.assertIn("dcimPhysicalEnabled", config)

    def test_itam_asset_reference_uses_the_current_ownership_contract(self) -> None:
        adapter = (self.SERVER / "main/io/infranexum/server/dcim/DcimPhysicalReferencePolicyAdapter.java").read_text(encoding="utf-8")
        asset = (self.ROOT / "src/components/domains/itam/main/io/infranexum/itam/asset/domain/Asset.java").read_text(encoding="utf-8")
        self.assertIn("public DomainIdentifier owningOrganizationId()", asset)
        self.assertIn("asset.owningOrganizationId().equals(organizationId)", adapter)
        self.assertNotIn("asset.organizationId()", adapter)

    def test_same_tranche_is_wired_into_server_compose_and_foundation_gate(self) -> None:
        makefile = (self.ROOT / "Makefile").read_text(encoding="utf-8")
        compose = (self.ROOT / "docker/compose.yaml").read_text(encoding="utf-8")
        application = (self.SERVER / "resources/application.yaml").read_text(encoding="utf-8")
        self.assertIn("java-dcim-physical-smoke", makefile)
        self.assertIn("java-dcim-physical-smoke", makefile[makefile.index("verify-foundation"):])
        self.assertIn("INFRANEXUM_DCIM_PHYSICAL_API_ENABLED", compose)
        self.assertIn("INFRANEXUM_WEB_DCIM_PHYSICAL_ENABLED", compose)
        self.assertIn("physical-api-enabled", application)


if __name__ == "__main__":
    unittest.main()
