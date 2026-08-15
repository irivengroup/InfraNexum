"""Architecture and full-stack parity regressions for PGM-07-E04 DCIM facilities."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml


class DcimFacilityHierarchyArchitectureTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    DOMAIN = ROOT / "src/components/domains/dcim"
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web"
    MIGRATION = ROOT / "src/distribution/migrations/0026-dcim-facility-hierarchy"

    def test_manifests_wire_dcim_domain_only_through_declared_dependencies(self) -> None:
        domain_manifest = json.loads((self.DOMAIN / "MANIFEST.json").read_text(encoding="utf-8"))
        self.assertEqual("components.domains.dcim", domain_manifest["id"])
        self.assertIn("components.core.contracts", domain_manifest["dependencies"])
        self.assertIn("components.core.events", domain_manifest["dependencies"])
        for relative in ("src/components/adapters/jdbc/MANIFEST.json", "src/applications/server/MANIFEST.json"):
            manifest = json.loads((self.ROOT / relative).read_text(encoding="utf-8"))
            self.assertIn("components.domains.dcim", manifest["dependencies"])
        policy = json.loads((self.ROOT / "validation/architecture/policy.json").read_text(encoding="utf-8"))
        self.assertIn("components/domains/dcim", policy["required_manifest_paths"])

    def test_domain_enforces_hierarchy_site_address_and_kind_specific_coherence(self) -> None:
        node = (self.DOMAIN / "main/io/infranexum/dcim/facility/domain/FacilityNode.java").read_text(encoding="utf-8")
        service = (self.DOMAIN / "main/io/infranexum/dcim/facility/application/FacilityApplicationService.java").read_text(encoding="utf-8")
        for field in ("addressLine1", "addressLine2", "postalCode", "city", "countryCode", "timezone"):
            self.assertIn(field, node)
        self.assertIn('siteText(kind, addressLine1', node)
        self.assertIn('kind == FacilityKind.BUILDING ? positive(floorCount', node)
        self.assertIn('kind == FacilityKind.FLOOR ? Objects.requireNonNull(levelNumber', node)
        self.assertIn('kind == FacilityKind.ROOM', node)
        self.assertIn('zoneType is only valid for zone', node)
        self.assertIn('activeBuildingsForSite', service)
        self.assertIn('DCIM_SITE_ARCHIVE_BLOCKED', service)
        self.assertIn('dcim.site.archived.v1', service)
        self.assertIn('dcim.site.deleted.v1', service)
        self.assertIn('dcim.room.locked.v1', service)

    def test_storage_uses_only_internal_dcim_fk_and_persists_structured_address(self) -> None:
        pg = (self.MIGRATION / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.MIGRATION / "oracle.sql").read_text(encoding="utf-8").lower()
        logical = json.loads((self.MIGRATION / "logical-model.json").read_text(encoding="utf-8"))
        for field in ("address_line_1", "address_line_2", "postal_code", "city"):
            self.assertIn(field, pg)
            self.assertIn(field, oracle)
        self.assertIn("numeric(10,7)", pg)
        self.assertIn("number(10,7)", oracle)
        for forbidden in ("references infranexum_org", "references infranexum_iam", "references infranexum_rsot", "references infranexum_itam"):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        self.assertEqual(["organization_id", "subdivision_id"], logical["objects"][0]["weak_references"])
        self.assertEqual(["parent_id"], logical["objects"][0]["internal_foreign_keys"])
        self.assertEqual(["address_line_1", "postal_code", "city", "country_code", "timezone"], logical["objects"][0]["required_site_address"])

    def test_openapi_has_five_native_resources_exact_request_schemas_and_unique_operations(self) -> None:
        spec = yaml.safe_load((self.SERVER / "resources/openapi/dcim-facilities.yaml").read_text(encoding="utf-8"))
        operations = []
        for item in spec["paths"].values():
            operations.extend(value for key, value in item.items() if key in {"get", "post", "patch", "put", "delete"})
        self.assertEqual("3.1.0", spec["openapi"])
        self.assertEqual(25, len(operations))
        self.assertEqual(25, len({operation["operationId"] for operation in operations}))
        self.assertTrue(all(operation["x-infranexum-capability"] == "dcim.facilities" for operation in operations))
        for resource, singular in (("sites", "Site"), ("buildings", "Building"), ("floors", "Floor"), ("rooms", "Room"), ("zones", "Zone")):
            create_ref = spec["paths"][f"/api/v1/dcim/{resource}"]["post"]["requestBody"]["content"]["application/json"]["schema"]["$ref"]
            update_ref = spec["paths"][f"/api/v1/dcim/{resource}/{{facilityId}}"]["patch"]["requestBody"]["content"]["application/json"]["schema"]["$ref"]
            self.assertEqual(f"#/components/schemas/Create{singular}Facility", create_ref)
            self.assertEqual(f"#/components/schemas/Update{singular}Facility", update_ref)
        site = spec["components"]["schemas"]["CreateSiteFacility"]
        self.assertTrue({"addressLine1", "postalCode", "city", "countryCode", "timezone"}.issubset(site["required"]))
        params = spec["paths"]["/api/v1/dcim/sites"]["get"]["parameters"]
        self.assertIn("country_code", {parameter.get("name") for parameter in params})

    def test_http_is_controller_scoped_and_web_is_not_client_only(self) -> None:
        controller = (self.SERVER / "main/io/infranexum/server/dcim/DcimFacilityController.java").read_text(encoding="utf-8")
        requirements = (self.SERVER / "main/io/infranexum/server/identityaccess/AuthorizationRequirement.java").read_text(encoding="utf-8")
        workspace = (self.WEB / "public/assets/dcim-workspace.mjs").read_text(encoding="utf-8")
        client = (self.WEB / "public/assets/dcim-facilities.mjs").read_text(encoding="utf-8")
        shell = (self.WEB / "public/assets/admin-shell.mjs").read_text(encoding="utf-8")
        self.assertIn("AuthorizationScope.organization", controller)
        self.assertIn("CONTROLLER_SCOPED", requirements)
        self.assertIn("dcim: Object.freeze", shell)
        for select_id in ("dcim-organization", "dcim-subdivision", "dcim-site-context", "dcim-building-context", "dcim-floor-context", "dcim-room-context"):
            self.assertIn(select_id, workspace)
        for field in ("addressLine1", "postalCode", "city"):
            self.assertIn(field, workspace)
        self.assertIn("dcim-sites-country-filter", workspace)
        self.assertIn("country_code", client)
        self.assertNotIn('name="parentId" class="form-control"', workspace)

    def test_same_tranche_web_gate_is_integrated_in_foundation_verification(self) -> None:
        makefile = (self.ROOT / "Makefile").read_text(encoding="utf-8")
        compose = (self.ROOT / "docker/compose.yaml").read_text(encoding="utf-8")
        web_config = (self.WEB / "runtime/config.mjs").read_text(encoding="utf-8")
        self.assertIn("java-dcim-facility-smoke", makefile)
        self.assertIn("java-dcim-facility-smoke", makefile[makefile.index("verify-foundation"):])
        self.assertIn("INFRANEXUM_DCIM_FACILITY_API_ENABLED", compose)
        self.assertIn("INFRANEXUM_WEB_DCIM_FACILITIES_ENABLED", compose)
        self.assertIn("dcimFacilitiesEnabled", web_config)


if __name__ == "__main__":
    unittest.main()
