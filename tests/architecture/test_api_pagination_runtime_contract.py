from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "src/applications/server/main/io/infranexum/server"
OPENAPI = ROOT / "src/applications/server/resources/openapi"


class ApiPaginationRuntimeContractTests(unittest.TestCase):
    """Pin PGM-05-E01 phase-3 pagination semantics into HTTP, domain and persistence boundaries."""

    def test_pagination_debt_is_zero_and_contract_modes_are_explicit(self) -> None:
        baseline = json.loads((ROOT / "validation/api_contracts/baseline.json").read_text(encoding="utf-8"))
        self.assertEqual([], baseline["debt"]["pagination"])
        modes: dict[str, str] = {}
        for path in OPENAPI.glob("*.yaml"):
            if path.name == "catalogue.yaml":
                continue
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            for item in (document.get("paths") or {}).values():
                if not isinstance(item, dict):
                    continue
                for operation in item.values():
                    if isinstance(operation, dict) and operation.get("x-infranexum-pagination"):
                        modes[operation["operationId"]] = operation["x-infranexum-pagination"]
        self.assertEqual(24, len(modes))
        self.assertEqual(8, sum(mode == "cursor" for mode in modes.values()))
        self.assertEqual(16, sum(mode == "offset" for mode in modes.values()))

    def test_cursor_collections_use_keyset_queries_and_next_cursor_headers(self) -> None:
        dcim_controller = (SERVER / "dcim/DcimPhysicalController.java").read_text(encoding="utf-8")
        ipam_controller = (SERVER / "ddi/IpamController.java").read_text(encoding="utf-8")
        dcim_repository = (ROOT / "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcDcimPhysicalRepository.java").read_text(encoding="utf-8")
        ipam_repository = (ROOT / "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcIpamRepository.java").read_text(encoding="utf-8")
        self.assertGreaterEqual(dcim_controller.count("ApiPagination.cursor("), 3)
        self.assertGreaterEqual(ipam_controller.count("ApiPagination.cursor("), 5)
        self.assertIn('afterId==null?"":" AND id>?"', dcim_repository)
        self.assertIn('after==null?"":" AND id>?"', ipam_repository)
        helper = (SERVER / "http/ApiPagination.java").read_text(encoding="utf-8")
        self.assertIn('NEXT_CURSOR = "X-Next-Cursor"', helper)
        self.assertIn('PAGE_LIMIT = "X-Page-Limit"', helper)

    def test_offset_collections_are_bounded_and_expose_next_offset(self) -> None:
        helper = (SERVER / "http/ApiPagination.java").read_text(encoding="utf-8")
        self.assertIn('NEXT_OFFSET = "X-Next-Offset"', helper)
        dcim = (SERVER / "dcim/DcimPhysicalController.java").read_text(encoding="utf-8")
        iam = (SERVER / "identityaccess/IdentityAccessController.java").read_text(encoding="utf-8")
        itam = (SERVER / "itam/ItamComplianceController.java").read_text(encoding="utf-8")
        self.assertGreaterEqual(dcim.count("ApiPagination.offset("), 2)
        self.assertGreaterEqual(iam.count("ApiPagination.offset("), 2)
        self.assertGreaterEqual(itam.count("ApiPagination.offset("), 3)
        service = (ROOT / "src/components/domains/identity-access/main/io/infranexum/identity/access/application/IdentityAccessAdminService.java").read_text(encoding="utf-8")
        self.assertIn("page(offset,limit)", service)
        compliance = (ROOT / "src/components/domains/itam/main/io/infranexum/itam/compliance/application/ComplianceApplicationService.java").read_text(encoding="utf-8")
        constraints = (ROOT / "src/components/core/contracts/main/io/infranexum/core/contracts/PaginationConstraints.java").read_text(encoding="utf-8")
        self.assertIn('PaginationConstraints.requireOffset(offset)', compliance)
        self.assertIn('MAX_OFFSET = 1_000_000', constraints)
        self.assertIn('offset > MAX_OFFSET', constraints)
        self.assertIn('limit<1||limit>max', compliance)
        for path in OPENAPI.glob("*.yaml"):
            if path.name == "catalogue.yaml":
                continue
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            for item in (document.get("paths") or {}).values():
                if not isinstance(item, dict):
                    continue
                for operation in item.values():
                    if not isinstance(operation, dict) or operation.get("x-infranexum-pagination") != "offset":
                        continue
                    parameters = operation.get("parameters", [])
                    resolved = []
                    for parameter in parameters:
                        reference = parameter.get("$ref") if isinstance(parameter, dict) else None
                        if reference and reference.startswith("#/components/parameters/"):
                            resolved.append(document["components"]["parameters"][reference.rsplit("/", 1)[-1]])
                        else:
                            resolved.append(parameter)
                    offset = next(parameter for parameter in resolved if parameter.get("name") == "offset")
                    self.assertEqual(1_000_000, offset["schema"]["maximum"])

    def test_legacy_array_bodies_and_cli_overloads_are_preserved(self) -> None:
        helper = (SERVER / "http/ApiPagination.java").read_text(encoding="utf-8")
        self.assertIn("ResponseEntity<List<T>>", helper)
        self.assertIn("body(List.copyOf(items))", helper)
        dcim_service = (ROOT / "src/components/domains/dcim/main/io/infranexum/dcim/physical/application/DcimPhysicalApplicationService.java").read_text(encoding="utf-8")
        ipam_service = (ROOT / "src/components/domains/ddi/main/io/infranexum/ddi/ipam/application/IpamApplicationService.java").read_text(encoding="utf-8")
        self.assertIn("public List<EquipmentModel> models(DomainIdentifier org,int limit)", dcim_service)
        self.assertIn("public List<IpamVrf> vrfs(DomainIdentifier o,int l)", ipam_service)

    def test_cursor_pages_fetch_one_extra_row_before_emitting_continuation(self) -> None:
        dcim_service = (ROOT / "src/components/domains/dcim/main/io/infranexum/dcim/physical/application/DcimPhysicalApplicationService.java").read_text(encoding="utf-8")
        ipam_service = (ROOT / "src/components/domains/ddi/main/io/infranexum/ddi/ipam/application/IpamApplicationService.java").read_text(encoding="utf-8")
        self.assertGreaterEqual(dcim_service.count("size+1"), 5)
        self.assertGreaterEqual(ipam_service.count("size+1"), 5)
        self.assertIn("rows.size()>limit", dcim_service)
        self.assertIn("rows.size()>limit", ipam_service)


if __name__ == "__main__":
    unittest.main()
