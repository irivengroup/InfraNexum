from __future__ import annotations

import contextlib
import io
import json
import shutil
import tempfile
import unittest
from pathlib import Path

import yaml

from validation.api_contracts.checker import ApiContractChecker
from validation.api_contracts.cli import main as cli_main

SOURCE = Path(__file__).resolve().parents[2]


class ApiContractCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repository"
        (self.root / "src/applications/server/resources").mkdir(parents=True)
        openapi = self.root / "src/applications/server/resources/openapi"
        openapi.mkdir(parents=True)
        for name in ("platform-entitlements.yaml", "identity-access-rbac.yaml"):
            shutil.copy2(SOURCE / "src/applications/server/resources/openapi" / name, openapi / name)
        catalogue = {
            "schema": "infranexum.openapi-catalogue/v1",
            "product": "InfraNexum — Infrastructure Control & Governance Platform",
            "version": (SOURCE / "VERSION").read_text(encoding="utf-8").strip(),
            "fragments": [
                {"file": "identity-access-rbac.yaml", "component": "IAM", "context": "Access"},
                {"file": "platform-entitlements.yaml", "component": "Platform", "context": "Entitlements"},
            ],
        }
        (openapi / "catalogue.yaml").write_text(yaml.safe_dump(catalogue, sort_keys=False, allow_unicode=True), encoding="utf-8")
        (self.root / "validation/api_contracts").mkdir(parents=True)
        shutil.copy2(SOURCE / "validation/api_contracts/baseline.json", self.root / "validation/api_contracts/baseline.json")
        shutil.copy2(SOURCE / "VERSION", self.root / "VERSION")
        capability_target = self.root / "src/components/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv"
        capability_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SOURCE / "src/components/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv", capability_target)
        permission_target = self.root / "src/components/domains/identity-access/main/io/infranexum/identity/access/domain/PermissionCodes.java"
        permission_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SOURCE / "src/components/domains/identity-access/main/io/infranexum/identity/access/domain/PermissionCodes.java", permission_target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def violations(self) -> set[str]:
        return {item.check_id for item in ApiContractChecker(self.root).run()}

    def spec(self, name: str) -> Path:
        return self.root / "src/applications/server/resources/openapi" / name

    def load(self, name: str) -> dict:
        return yaml.safe_load(self.spec(name).read_text(encoding="utf-8"))

    def save(self, name: str, payload: dict) -> None:
        self.spec(name).write_text(yaml.safe_dump(payload, sort_keys=False, allow_unicode=True), encoding="utf-8")

    def test_reference_contract_catalogue_is_valid_and_debt_is_frozen(self) -> None:
        checker = ApiContractChecker(SOURCE)
        self.assertEqual((), checker.run())
        self.assertEqual(15, len(checker.documents))
        self.assertEqual(200, len(checker.operations))
        self.assertEqual(0, len(checker.current_debt.idempotency))
        self.assertEqual(0, len(checker.current_debt.pagination))
        self.assertEqual(0, len(checker.current_debt.capability))
        self.assertEqual(0, len(checker.current_debt.permission))

    def test_missing_or_invalid_version_is_rejected(self) -> None:
        (self.root / "VERSION").unlink()
        self.assertIn("CHECK-API-002", self.violations())
        (self.root / "VERSION").write_text("latest\n", encoding="utf-8")
        self.assertIn("CHECK-API-002", self.violations())

    def test_catalogue_schema_version_and_entries_are_validated(self) -> None:
        path = self.spec("catalogue.yaml")
        payload = self.load("catalogue.yaml")
        payload["schema"] = "unknown"
        payload["version"] = "0.0.0"
        payload["fragments"].append(dict(payload["fragments"][0]))
        payload["fragments"].append({"file": "broken.yaml", "component": "", "context": "X"})
        path.write_text(yaml.safe_dump(payload, sort_keys=False), encoding="utf-8")
        ids = self.violations()
        self.assertTrue({"CHECK-API-003", "CHECK-API-004", "CHECK-API-005", "CHECK-API-006", "CHECK-API-007"} <= ids)

    def test_catalogue_must_be_a_mapping_with_non_empty_fragments(self) -> None:
        path = self.spec("catalogue.yaml")
        path.write_text("[]\n", encoding="utf-8")
        self.assertIn("CHECK-API-001", self.violations())
        path.write_text("schema: infranexum.openapi-catalogue/v1\nversion: 2.0.0-alpha.0.116\nfragments: []\n", encoding="utf-8")
        self.assertIn("CHECK-API-005", self.violations())

    def test_duplicate_yaml_keys_are_rejected_but_merge_overrides_are_supported(self) -> None:
        path = self.spec("platform-entitlements.yaml")
        text = path.read_text(encoding="utf-8")
        path.write_text(text.replace("openapi: 3.1.0", "openapi: 3.1.0\nopenapi: 3.1.0", 1), encoding="utf-8")
        self.assertIn("CHECK-API-008", self.violations())
        shutil.copy2(SOURCE / "src/applications/server/resources/openapi/platform-entitlements.yaml", path)
        self.assertNotIn("CHECK-API-008", self.violations())

    def test_malformed_internal_reference_is_rejected_before_redoc_rendering(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        payload["paths"]["/api/v1/platform/evaluation/status"]["get"]["responses"]["200"]["content"]["application/json"]["schema"] = {
            "$ref": "#/src/components/schemas/EvaluationStatus"
        }
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-036", self.violations())

    def test_external_reference_is_rejected_to_keep_documentation_offline_deterministic(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        payload["components"]["schemas"]["ExternalContract"] = {"$ref": "https://example.invalid/schema.yaml"}
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-036", self.violations())

    def test_document_openapi_version_info_tags_and_paths_are_enforced(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        payload["openapi"] = "3.0.3"
        payload["info"]["version"] = "wrong"
        payload["tags"] = [{"name": "Default"}]
        payload["x-tagGroups"] = [{"name": "Platform", "tags": ["Default", "Default"]}]
        operation = payload["paths"].pop("/api/v1/platform/evaluation/status")
        payload["paths"]["/internal/status"] = operation
        self.save("platform-entitlements.yaml", payload)
        ids = self.violations()
        self.assertTrue({"CHECK-API-009", "CHECK-API-010", "CHECK-API-012", "CHECK-API-013", "CHECK-API-014", "CHECK-API-016"} <= ids)

    def test_tags_and_path_items_are_mandatory(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        payload.pop("tags")
        payload.pop("x-tagGroups")
        payload["paths"]["/api/v1/platform/broken"] = []
        self.save("platform-entitlements.yaml", payload)
        ids = self.violations()
        self.assertTrue({"CHECK-API-011", "CHECK-API-015"} <= ids)

    def test_operations_require_ids_summary_single_tag_security_and_responses(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        operation = payload["paths"]["/api/v1/platform/evaluation/status"]["get"]
        operation.pop("operationId")
        operation.pop("summary")
        operation["tags"] = []
        operation.pop("security", None)
        operation["responses"] = {}
        self.save("platform-entitlements.yaml", payload)
        ids = self.violations()
        self.assertIn("CHECK-API-017", ids)

        payload = self.load("platform-entitlements.yaml")
        operation = payload["paths"]["/api/v1/platform/evaluation/status"]["get"]
        operation["operationId"] = "getPlatformEvaluationStatus"
        operation["summary"] = ""
        operation["tags"] = []
        operation.pop("security", None)
        operation["responses"] = {}
        self.save("platform-entitlements.yaml", payload)
        ids = self.violations()
        self.assertTrue({"CHECK-API-020", "CHECK-API-021", "CHECK-API-022", "CHECK-API-023"} <= ids)

        operation["summary"] = "Get status"
        operation["tags"] = ["Unknown"]
        operation["security"] = [{"LocalSessionCookie": []}]
        operation["responses"] = {"200": {"description": "OK"}}
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-021", self.violations())

    def test_duplicate_operation_id_and_route_are_blocked(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        original = payload["paths"]["/api/v1/platform/evaluation/status"]["get"]
        payload["paths"]["/api/v1/platform/evaluation/other"] = {"get": dict(original)}
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-018", self.violations())

        shutil.copy2(SOURCE / "src/applications/server/resources/openapi/platform-entitlements.yaml", self.spec("platform-entitlements.yaml"))
        catalogue = self.load("catalogue.yaml")
        duplicate = self.load("platform-entitlements.yaml")
        duplicate["info"]["title"] = "Duplicate"
        self.save("duplicate.yaml", duplicate)
        catalogue["fragments"].append({"file": "duplicate.yaml", "component": "Platform", "context": "Duplicate"})
        self.save("catalogue.yaml", catalogue)
        self.assertIn("CHECK-API-019", self.violations())

    def test_error_responses_and_local_refs_are_contractual(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        operation = payload["paths"]["/api/v1/platform/evaluation/status"]["get"]
        operation["responses"]["400"] = {"description": "Bad", "content": {"application/json": {"schema": {"type": "object"}}}}
        operation["responses"]["401"] = {"$ref": "#/components/responses/MissingProblem"}
        self.save("platform-entitlements.yaml", payload)
        ids = self.violations()
        self.assertTrue({"CHECK-API-024", "CHECK-API-025", "CHECK-API-030"} <= ids)

    def test_connector_delivery_idempotency_and_signature_authorization_are_strict(self) -> None:
        shutil.copy2(
            SOURCE / "src/applications/server/resources/openapi/integrations-connectors.yaml",
            self.spec("integrations-connectors.yaml"),
        )
        catalogue = self.load("catalogue.yaml")
        catalogue["fragments"].append(
            {"file": "integrations-connectors.yaml", "component": "Integrations", "context": "Connectors"}
        )
        self.save("catalogue.yaml", catalogue)
        self.assertEqual(set(), self.violations())

        payload = self.load("integrations-connectors.yaml")
        webhook = payload["paths"]["/api/v1/integrations/webhooks/{connectorKey}"]["post"]
        webhook["parameters"] = [
            parameter for parameter in webhook["parameters"]
            if parameter.get("$ref") != "#/components/parameters/ConnectorDeliveryId"
        ]
        self.save("integrations-connectors.yaml", payload)
        self.assertIn("CHECK-API-032", self.violations())

        payload = self.load("integrations-connectors.yaml")
        webhook = payload["paths"]["/api/v1/integrations/webhooks/{connectorKey}"]["post"]
        webhook["parameters"].append({
            "name": "Idempotency-Key", "in": "header", "required": True,
            "schema": {"type": "string", "minLength": 8, "maxLength": 200, "pattern": "^[A-Za-z0-9._:-]+$"},
        })
        self.save("integrations-connectors.yaml", payload)
        self.assertIn("CHECK-API-032", self.violations())

        payload = self.load("integrations-connectors.yaml")
        webhook = payload["paths"]["/api/v1/integrations/webhooks/{connectorKey}"]["post"]
        webhook["x-infranexum-permission"] = {"mode": "connector-signature", "code": "integrations.dlq.read"}
        self.save("integrations-connectors.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

        payload = self.load("integrations-connectors.yaml")
        webhook = payload["paths"]["/api/v1/integrations/webhooks/{connectorKey}"]["post"]
        webhook["x-infranexum-permission"] = {"mode": "connector-signature"}
        webhook["security"] = []
        self.save("integrations-connectors.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

    def test_declared_pagination_contract_requires_bounded_position_and_response_headers(self) -> None:
        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users/{userId}/memberships"]["get"]
        operation["x-infranexum-pagination"] = "offset"
        operation["parameters"] = [{"name": "offset", "in": "query", "schema": {"type": "integer", "minimum": 0}}]
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-031", self.violations())

        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users/{userId}/memberships"]["get"]
        operation["x-infranexum-pagination"] = "cursor"
        operation["parameters"] = [
            {"name": "limit", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100}},
            {"name": "cursor", "in": "query", "schema": {"type": "string", "format": "uuid"}},
        ]
        operation["responses"]["200"].setdefault("headers", {})["X-Page-Limit"] = {"schema": {"type": "integer"}}
        operation["responses"]["200"]["headers"]["X-Next-Cursor"] = {"schema": {"type": "string", "format": "uuid"}}
        self.save("identity-access-rbac.yaml", payload)
        self.assertNotIn("CHECK-API-031", self.violations())

    def test_declared_pagination_contract_rejects_ambiguous_and_malformed_modes(self) -> None:
        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users/{userId}/memberships"]["get"]
        operation["x-infranexum-pagination"] = "page"
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-031", self.violations())

        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users/{userId}/memberships"]["get"]
        operation["x-infranexum-pagination"] = "offset"
        operation["parameters"] = [
            {"name": "limit", "in": "query", "schema": {"type": "string", "minimum": 1, "maximum": 100}},
            {"name": "offset", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 1_000_001}},
            {"name": "cursor", "in": "query", "schema": {"type": "string", "format": "uuid"}},
        ]
        operation["responses"]["200"].pop("headers", None)
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-031", self.violations())

        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users/{userId}/memberships"]["get"]
        operation["x-infranexum-pagination"] = "cursor"
        operation["parameters"] = [
            {"name": "limit", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100}},
            {"name": "cursor", "in": "header", "schema": {"type": "integer"}},
            {"name": "offset", "in": "query", "schema": {"type": "integer", "minimum": 0, "maximum": 100}},
        ]
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-031", self.violations())

    def test_canonical_problem_schema_is_mandatory_in_every_fragment(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        payload["components"]["schemas"]["Problem"]["properties"].pop("correlation_id")
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-029", self.violations())

    def test_reusable_error_response_must_resolve_to_problem_and_correlation_header(self) -> None:
        payload = self.load("identity-access-rbac.yaml")
        bad = payload["components"]["responses"]["BadRequest"]
        bad["content"] = {"application/json": {"schema": {"type": "object"}}}
        bad["headers"].pop("X-Correlation-ID")
        self.save("identity-access-rbac.yaml", payload)
        ids = self.violations()
        self.assertTrue({"CHECK-API-024", "CHECK-API-030"} <= ids)

    def test_contract_debt_is_a_ratchet_and_may_decrease(self) -> None:
        spec = self.load("identity-access-rbac.yaml")
        operation = spec["paths"]["/api/v1/iam/users"]["post"]
        operation.pop("x-infranexum-idempotency", None)
        operation["parameters"] = [p for p in operation.get("parameters", []) if p.get("$ref") != "#/components/parameters/IdempotencyKey"]
        self.save("identity-access-rbac.yaml", spec)
        self.assertIn("CHECK-API-028", self.violations())

        shutil.copy2(SOURCE / "src/applications/server/resources/openapi/identity-access-rbac.yaml", self.spec("identity-access-rbac.yaml"))
        self.assertNotIn("CHECK-API-028", self.violations())

    def test_invalid_debt_baseline_is_rejected(self) -> None:
        path = self.root / "validation/api_contracts/baseline.json"
        path.write_text("{", encoding="utf-8")
        self.assertIn("CHECK-API-026", self.violations())
        path.write_text(json.dumps({"schema": "wrong", "debt": {}}), encoding="utf-8")
        self.assertIn("CHECK-API-027", self.violations())
        path.write_text(json.dumps({"schema": "infranexum.api-contract-debt/v1", "debt": {"idempotency": "x"}}), encoding="utf-8")
        self.assertIn("CHECK-API-027", self.violations())

    def test_product_spec_is_deterministic_and_namespaces_components(self) -> None:
        checker = ApiContractChecker(self.root)
        product = checker.build_product_spec()
        self.assertEqual("3.1.0", product["openapi"])
        self.assertEqual("product-complete", product["x-infranexum-contract"])
        operation_count = sum(1 for item in product["paths"].values() for key in item if key in {"get","post","put","patch","delete","head","options"})
        self.assertGreater(operation_count, 10)
        schemas = product["components"].get("schemas", {})
        self.assertTrue(any(name.startswith("identity-access-rbac__") for name in schemas))
        self.assertTrue(any(name.startswith("platform-entitlements__") for name in schemas))
        text = yaml.safe_dump(product, sort_keys=False, allow_unicode=True)
        self.assertNotIn("#/components/schemas/Problem\n", text)

    def test_effective_spec_filters_unavailable_capabilities_without_mutating_product_contract(self) -> None:
        checker = ApiContractChecker(self.root)
        product = checker.build_product_spec()
        effective = checker.build_effective_spec(["iam.access"])
        self.assertEqual("installation-effective", effective["x-infranexum-contract"])
        self.assertEqual(["iam.access", "platform.bootstrap"], effective["x-infranexum-effective-capabilities"])
        operations = [
            operation
            for item in effective["paths"].values()
            for method, operation in item.items()
            if method in {"get", "post", "put", "patch", "delete", "head", "options"}
        ]
        self.assertTrue(operations)
        self.assertEqual({"iam.access"}, {op["x-infranexum-capability"] for op in operations})
        self.assertGreater(len(product["paths"]), len(effective["paths"]))
        self.assertEqual("product-complete", product["x-infranexum-contract"])
        used_tags = {tag for op in operations for tag in op.get("tags", [])}
        self.assertEqual(used_tags, {tag["name"] for tag in effective["tags"]})

    def test_reference_effective_spec_always_preserves_bootstrap_routes(self) -> None:
        effective = ApiContractChecker(SOURCE).build_effective_spec(["iam.access"])
        self.assertIn("platform.bootstrap", effective["x-infranexum-effective-capabilities"])
        self.assertIn("/api/v1/system/build", effective["paths"])
        self.assertIn("/api/v1/platform/capabilities", effective["paths"])
        self.assertNotIn("/api/v1/ddi/ipam/networks", effective["paths"])

    def test_effective_spec_rejects_empty_or_unknown_capability_sets(self) -> None:
        checker = ApiContractChecker(self.root)
        with self.assertRaises(ValueError):
            checker.build_effective_spec([])
        with self.assertRaises(ValueError):
            checker.build_effective_spec(["does.not.exist"])

    def test_product_spec_writer_and_report_are_machine_readable(self) -> None:
        checker = ApiContractChecker(self.root)
        destination = self.root / "build/openapi-product.yaml"
        checker.write_product_spec(destination)
        self.assertEqual("3.1.0", yaml.safe_load(destination.read_text(encoding="utf-8"))["openapi"])
        report = checker.report()
        self.assertGreater(report["operations"], 10)
        self.assertEqual([], report["violations"])

    def test_product_spec_refuses_generation_when_contract_is_invalid(self) -> None:
        self.spec("catalogue.yaml").write_text("[]\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            ApiContractChecker(self.root).build_product_spec()

    def test_cli_writes_report_and_product_contract_and_returns_failure_on_violation(self) -> None:
        report = self.root / "reports/api.json"
        product = self.root / "reports/product.yaml"
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = cli_main(["--root", str(self.root), "--json-report", str(report), "--product-spec", str(product)])
        self.assertEqual(0, status)
        self.assertIn("api-contracts: PASS", output.getvalue())
        self.assertTrue(report.is_file())
        self.assertTrue(product.is_file())

        effective = self.root / "reports/effective.yaml"
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = cli_main([
                "--root", str(self.root),
                "--effective-spec", str(effective),
                "--effective-capability", "iam.access",
            ])
        self.assertEqual(0, status)
        effective_payload = yaml.safe_load(effective.read_text(encoding="utf-8"))
        self.assertEqual("installation-effective", effective_payload["x-infranexum-contract"])

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, cli_main(["--root", str(self.root), "--effective-spec", str(effective)]))
        self.assertIn("CHECK-API-035", output.getvalue())

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, cli_main(["--root", str(self.root), "--effective-capability", "iam.access"]))
        self.assertIn("CHECK-API-035", output.getvalue())

        self.spec("catalogue.yaml").write_text("[]\n", encoding="utf-8")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = cli_main(["--root", str(self.root)])
        self.assertEqual(1, status)
        self.assertIn("CHECK-API-001", output.getvalue())


    def test_catalogue_entry_types_and_missing_fragment_are_rejected(self) -> None:
        payload = self.load("catalogue.yaml")
        payload["fragments"].append("not-an-object")
        self.save("catalogue.yaml", payload)
        self.assertIn("CHECK-API-005", self.violations())

        payload = self.load("catalogue.yaml")
        payload["fragments"][0]["file"] = "missing.yaml"
        self.save("catalogue.yaml", payload)
        ids = self.violations()
        self.assertTrue({"CHECK-API-007", "CHECK-API-008"} <= ids)

    def test_non_mapping_paths_and_operation_values_are_rejected_or_ignored_safely(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        payload["paths"] = []
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-015", self.violations())

        shutil.copy2(SOURCE / "src/applications/server/resources/openapi/platform-entitlements.yaml", self.spec("platform-entitlements.yaml"))
        payload = self.load("platform-entitlements.yaml")
        payload["paths"]["/api/v1/platform/broken"] = []
        payload["paths"]["/api/v1/platform/evaluation/status"]["post"] = []
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-015", self.violations())

    def test_tag_group_non_objects_do_not_hide_grouping_violation(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        payload["x-tagGroups"] = ["bad", {"name": "Platform", "tags": "bad"}]
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-012", self.violations())

    def test_debt_baseline_requires_debt_mapping(self) -> None:
        path = self.root / "validation/api_contracts/baseline.json"
        path.write_text(json.dumps({"schema": "infranexum.api-contract-debt/v1", "debt": []}), encoding="utf-8")
        self.assertIn("CHECK-API-027", self.violations())

    def test_parameter_resolution_tolerates_non_parameter_entries_and_non_parameter_refs(self) -> None:
        checker = ApiContractChecker(self.root)
        self.assertEqual((), checker.run())
        operation = next(item for item in checker.operations if item.operation_id == "getPlatformEvaluationStatus")
        path_item = dict(operation.path_item)
        op = dict(operation.operation)
        op["parameters"] = ["bad", {"$ref": "#/components/schemas/Problem"}, {"name": 3, "in": "query"}]
        from validation.api_contracts.checker import _Operation
        synthetic = _Operation(operation.source, operation.path, operation.method, operation.operation_id, op, path_item, operation.document)
        self.assertEqual(set(), checker._parameter_names(synthetic))

    def test_rewrite_helpers_cover_lists_root_security_and_non_component_refs(self) -> None:
        checker = ApiContractChecker(self.root)
        document = {
            "security": [{"cookie": []}],
            "tags": [{"name": "X / Y"}, "ignored"],
            "x-tagGroups": [{"name": "X", "tags": ["X / Y"]}, "ignored"],
            "components": {"schemas": {"Thing": {"type": "object"}}, "ignored": []},
            "paths": {
                "/api/v1/x": {
                    "parameters": [{"$ref": "#/components/schemas/Thing"}],
                    "get": {"operationId": "getX", "tags": ["X / Y"], "responses": {"200": {"description": "OK"}}},
                    "post": "ignored",
                },
                "/api/v1/ignored": [],
            },
            "extra": [{"$ref": "https://example.invalid/schema"}],
        }
        rewritten = checker._rewrite_fragment(document, "test")
        self.assertNotIn("security", rewritten)
        self.assertEqual([{"cookie": []}], rewritten["paths"]["/api/v1/x"]["get"]["security"])
        self.assertEqual("#/components/schemas/test__Thing", rewritten["paths"]["/api/v1/x"]["parameters"][0]["$ref"])
        self.assertEqual("https://example.invalid/schema", rewritten["extra"][0]["$ref"])

    def test_collect_refs_and_outside_root_violation_path_are_supported(self) -> None:
        checker = ApiContractChecker(self.root)
        refs = list(checker._collect_refs({"a": [{"$ref": "#/components/schemas/A"}], "b": "x"}))
        self.assertEqual(["#/components/schemas/A"], refs)
        checker._add("X", Path("/tmp/outside"), "message")
        self.assertEqual("/tmp/outside", checker.violations[-1].path)

    def test_cli_success_without_product_output_covers_optional_branch(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = cli_main(["--root", str(self.root)])
        self.assertEqual(0, status)
        self.assertIn("api-contracts: PASS", output.getvalue())


    def test_unreadable_catalogue_and_missing_component_parameter_cover_defensive_paths(self) -> None:
        self.spec("catalogue.yaml").write_text("[", encoding="utf-8")
        self.assertIn("CHECK-API-001", self.violations())

        checker = ApiContractChecker(self.root)
        shutil.copy2(SOURCE / "src/applications/server/resources/openapi/catalogue.yaml", self.spec("catalogue.yaml"))
        # Restore the two-entry fixture catalogue rather than the full source catalogue.
        catalogue = {
            "schema": "infranexum.openapi-catalogue/v1",
            "product": "InfraNexum",
            "version": (self.root / "VERSION").read_text(encoding="utf-8").strip(),
            "fragments": [
                {"file": "identity-access-rbac.yaml", "component": "IAM", "context": "Access"},
                {"file": "platform-entitlements.yaml", "component": "Platform", "context": "Entitlements"},
            ],
        }
        self.save("catalogue.yaml", catalogue)
        self.assertEqual((), checker.run())
        operation = next(item for item in checker.operations if item.operation_id == "getPlatformEvaluationStatus")
        from validation.api_contracts.checker import _Operation
        op = dict(operation.operation)
        op["parameters"] = [{"$ref": "#/components/parameters/DoesNotExist"}]
        synthetic = _Operation(operation.source, operation.path, operation.method, operation.operation_id, op, operation.path_item, operation.document)
        self.assertEqual(set(), checker._parameter_names(synthetic))

    def test_product_assembly_merges_shared_paths_and_deduplicates_tags(self) -> None:
        from validation.api_contracts.checker import ApiContractChecker as BaseChecker

        class SyntheticChecker(BaseChecker):
            def run(self):
                base = {
                    "openapi": "3.1.0",
                    "info": {"version": "2.0.0-alpha.0.116"},
                    "tags": [{"name": "X / Shared"}],
                    "x-tagGroups": [{"name": "X", "tags": ["X / Shared", "X / Shared"]}],
                    "components": {"schemas": {"Thing": {"type": "object"}}},
                }
                self.documents = {
                    "a.yaml": {**base, "paths": {"/api/v1/shared": {"parameters": [], "get": {"operationId": "getShared", "tags": ["X / Shared"], "summary": "Get", "responses": {"200": {"description": "OK"}}}}}},
                    "b.yaml": {**base, "paths": {"/api/v1/shared": {"parameters": [], "post": {"operationId": "postShared", "tags": ["X / Shared"], "summary": "Post", "responses": {"200": {"description": "OK"}}}}}},
                }
                return ()

        product = SyntheticChecker(self.root).build_product_spec()
        self.assertIn("get", product["paths"]["/api/v1/shared"])
        self.assertIn("post", product["paths"]["/api/v1/shared"])
        self.assertEqual(1, len(product["tags"]))
        self.assertEqual(["X / Shared"], product["x-tagGroups"][0]["tags"])

    def test_product_assembly_defensive_duplicate_route_and_component_errors(self) -> None:
        from validation.api_contracts.checker import ApiContractChecker as BaseChecker

        class DuplicateRouteChecker(BaseChecker):
            def run(self):
                op = {"operationId": "x", "tags": ["X / Y"], "summary": "X", "responses": {"200": {"description": "OK"}}}
                doc = {"openapi": "3.1.0", "info": {"version": "2.0.0-alpha.0.116"}, "tags": [{"name": "X / Y"}], "x-tagGroups": [{"name": "X", "tags": ["X / Y"]}], "paths": {"/api/v1/x": {"get": op}}, "components": {}}
                self.documents = {"one.yaml": doc, "two.yaml": doc}
                return ()
        with self.assertRaises(ValueError):
            DuplicateRouteChecker(self.root).build_product_spec()

        class DuplicateComponentChecker(BaseChecker):
            def run(self):
                self.documents = {
                    "a+b.yaml": {"tags": [], "x-tagGroups": [], "paths": {}, "components": {"schemas": {"Thing": {"type": "string"}}}},
                    "a b.yaml": {"tags": [], "x-tagGroups": [], "paths": {}, "components": {"schemas": {"Thing": {"type": "integer"}}}},
                }
                return ()
        with self.assertRaises(ValueError):
            DuplicateComponentChecker(self.root).build_product_spec()

    def test_rewrite_fragment_without_components_or_paths_is_safe(self) -> None:
        checker = ApiContractChecker(self.root)
        self.assertEqual({"tags": []}, checker._rewrite_fragment({"tags": []}, "x"))

    def test_capability_metadata_must_reference_authoritative_catalogue(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        operation = payload["paths"]["/api/v1/platform/evaluation/status"]["get"]
        operation["x-infranexum-capability"] = "platform.unknown"
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-033", self.violations())

    def test_permission_metadata_is_structured_and_registry_backed(self) -> None:
        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users"]["get"]
        operation["x-infranexum-permission"] = "iam.user.search"
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users"]["get"]
        operation["x-infranexum-permission"] = {"mode": "permission", "code": "iam.user.unknown"}
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

    def test_authorization_metadata_invalid_modes_conditionals_and_security_are_rejected(self) -> None:
        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users"]["get"]
        operation["x-infranexum-permission"] = {"mode": "unknown"}
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

        shutil.copy2(SOURCE / "src/applications/server/resources/openapi/identity-access-rbac.yaml", self.spec("identity-access-rbac.yaml"))
        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users"]["get"]
        operation["x-infranexum-permission"] = {
            "mode": "conditional",
            "codes": ["iam.user.read", "does.not.exist"],
        }
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

        shutil.copy2(SOURCE / "src/applications/server/resources/openapi/identity-access-rbac.yaml", self.spec("identity-access-rbac.yaml"))
        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users"]["get"]
        operation["security"] = []
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

    def test_authoritative_registry_failures_and_duplicate_values_are_rejected(self) -> None:
        capability = self.root / "src/components/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv"
        saved_capability = capability.read_text(encoding="utf-8")
        capability.unlink()
        self.assertIn("CHECK-API-033", self.violations())
        capability.write_text(saved_capability + saved_capability.splitlines()[1] + "\n", encoding="utf-8")
        self.assertIn("CHECK-API-033", self.violations())
        capability.write_text(saved_capability, encoding="utf-8")

        permissions = self.root / "src/components/domains/identity-access/main/io/infranexum/identity/access/domain/PermissionCodes.java"
        saved_permissions = permissions.read_text(encoding="utf-8")
        permissions.unlink()
        self.assertIn("CHECK-API-034", self.violations())
        permissions.write_text('final class PermissionCodes { static final String A="iam.user.read", B="iam.user.read"; }\n', encoding="utf-8")
        self.assertIn("CHECK-API-034", self.violations())
        permissions.write_text(saved_permissions, encoding="utf-8")

    def test_effective_contract_rejects_invalid_source_and_cli_reports_unknown_capability(self) -> None:
        self.spec("catalogue.yaml").write_text("[]\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            ApiContractChecker(self.root).build_effective_spec(["iam.access"])
        shutil.copy2(SOURCE / "src/applications/server/resources/openapi/identity-access-rbac.yaml", self.spec("identity-access-rbac.yaml"))
        catalogue = {
            "schema": "infranexum.openapi-catalogue/v1",
            "product": "InfraNexum — Infrastructure Control & Governance Platform",
            "version": (SOURCE / "VERSION").read_text(encoding="utf-8").strip(),
            "fragments": [
                {"file": "identity-access-rbac.yaml", "component": "IAM", "context": "Access"},
                {"file": "platform-entitlements.yaml", "component": "Platform", "context": "Entitlements"},
            ],
        }
        self.spec("catalogue.yaml").write_text(yaml.safe_dump(catalogue, sort_keys=False, allow_unicode=True), encoding="utf-8")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = cli_main([
                "--root", str(self.root), "--effective-spec", str(self.root / "effective.yaml"),
                "--effective-capability", "unknown.capability",
            ])
        self.assertEqual(1, status)
        self.assertIn("CHECK-API-035", output.getvalue())

    def test_repeatable_operation_cannot_expose_idempotency_key_and_missing_metadata_is_debt(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        operation = payload["paths"]["/api/v1/platform/evaluation/status"]["get"]
        operation["x-infranexum-idempotency"] = "repeatable"
        operation.setdefault("parameters", []).append({
            "name": "Idempotency-Key", "in": "header", "required": True,
            "schema": {"type": "string", "minLength": 8, "maxLength": 200, "pattern": "^[A-Za-z0-9._:-]+$"},
        })
        operation.pop("x-infranexum-capability", None)
        operation.pop("x-infranexum-permission", None)
        self.save("platform-entitlements.yaml", payload)
        checker = ApiContractChecker(self.root)
        ids = {item.check_id for item in checker.run()}
        self.assertIn("CHECK-API-032", ids)
        self.assertIn("CHECK-API-033", ids)
        self.assertIn("CHECK-API-034", ids)
        self.assertIn("getPlatformEvaluationStatus", checker.current_debt.capability)
        self.assertIn("getPlatformEvaluationStatus", checker.current_debt.permission)

    def test_special_authorization_modes_are_fail_closed(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        operation = payload["paths"]["/api/v1/platform/evaluation/status"]["get"]
        operation["x-infranexum-permission"] = {"mode": "anonymous", "code": "platform.profile.read"}
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

        payload = self.load("platform-entitlements.yaml")
        operation = payload["paths"]["/api/v1/platform/evaluation/status"]["get"]
        operation["x-infranexum-permission"] = {"mode": "conditional", "codes": ["platform.profile.read"]}
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

    def test_anonymous_mode_requires_explicit_empty_security(self) -> None:
        payload = self.load("platform-entitlements.yaml")
        operation = payload["paths"]["/api/v1/platform/evaluation/status"]["get"]
        operation["x-infranexum-permission"] = {"mode": "anonymous"}
        self.save("platform-entitlements.yaml", payload)
        self.assertIn("CHECK-API-034", self.violations())

    def test_idempotency_contract_supports_required_repeatable_and_security_exempt_modes(self) -> None:
        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/iam/users"]["post"]
        operation["x-infranexum-idempotency"] = "required"
        operation["parameters"] = [p for p in operation.get("parameters", []) if p.get("$ref") != "#/components/parameters/IdempotencyKey"]
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-032", self.violations())

        payload = self.load("identity-access-rbac.yaml")
        operation = payload["paths"]["/api/v1/organizations/{orgId}/permissions/validate"]["post"]
        operation["x-infranexum-idempotency"] = "invalid"
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-032", self.violations())

    def test_canonical_idempotency_header_rejects_weak_bounds_or_pattern(self) -> None:
        payload = self.load("identity-access-rbac.yaml")
        payload["components"]["parameters"]["IdempotencyKey"]["schema"]["minLength"] = 1
        self.save("identity-access-rbac.yaml", payload)
        self.assertIn("CHECK-API-032", self.violations())


if __name__ == "__main__":
    unittest.main()
