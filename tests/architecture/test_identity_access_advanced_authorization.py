"""Architecture and public-contract regressions for PGM-03-E04 advanced authorization."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

import yaml


class IdentityAccessAdvancedAuthorizationArchitectureTest(unittest.TestCase):
    """Keep PAP/PDP/PEP/PIP/PRP fail-closed, RBAC-bound and contract-aligned."""

    ROOT = Path(__file__).resolve().parents[2]
    SERVER = ROOT / "src/applications/server/main/io/infranexum/server/identityaccess"
    IAM = ROOT / "src/components/domains/identity-access/main/io/infranexum/identity/access"
    SPEC = ROOT / "src/applications/server/resources/openapi/identity-access-policy.yaml"

    @classmethod
    def setUpClass(cls) -> None:
        cls.controller = (cls.SERVER / "PolicyController.java").read_text(encoding="utf-8")
        cls.requirements = (cls.SERVER / "AuthorizationRequirement.java").read_text(encoding="utf-8")
        cls.filter = (cls.SERVER / "AdvancedAuthorizationFilter.java").read_text(encoding="utf-8")
        cls.rbac_filter = (cls.SERVER / "RbacAuthorizationFilter.java").read_text(encoding="utf-8")
        cls.pdp = (cls.IAM / "application/PolicyDecisionService.java").read_text(encoding="utf-8")
        cls.admin = (cls.IAM / "application/IdentityAccessAdminService.java").read_text(encoding="utf-8")
        cls.sod = (cls.IAM / "application/SeparationOfDutyService.java").read_text(encoding="utf-8")
        cls.cli = (cls.SERVER / "cli/IdentityAccessCli.java").read_text(encoding="utf-8")
        cls.openapi = yaml.safe_load(cls.SPEC.read_text(encoding="utf-8"))

    def test_controller_keeps_six_normative_mutations_and_adds_read_only_policy_catalog(self) -> None:
        routes = {
            (match.group(1).upper(), match.group(2))
            for match in re.finditer(r"@(Get|Post)Mapping\(\"([^\"]+)\"\)", self.controller)
        }
        self.assertEqual(
            {
                ("GET", "/api/v1/iam/policies"),
                ("POST", "/api/v1/iam/policies"),
                ("POST", "/api/v1/iam/policies/{policyId}/validate"),
                ("POST", "/api/v1/iam/policies/{policyId}/approve"),
                ("POST", "/api/v1/iam/policies/{policyId}/activate"),
                ("POST", "/api/v1/iam/authorization/decisions"),
                ("POST", "/api/v1/iam/authorization/explain"),
            },
            routes,
        )

    def test_openapi_matches_controller_and_requires_session_plus_csrf(self) -> None:
        openapi_routes = {
            (method.upper(), path)
            for path, item in self.openapi["paths"].items()
            for method in item
            if method.lower() in {"get", "post"}
        }
        controller_routes = {
            (match.group(1).upper(), match.group(2))
            for match in re.finditer(r"@(Get|Post)Mapping\(\"([^\"]+)\"\)", self.controller)
        }
        self.assertEqual(controller_routes, openapi_routes)
        self.assertEqual([{"LocalSessionCookie": []}], self.openapi["security"])
        self.assertEqual("INX_SESSION", self.openapi["components"]["securitySchemes"]["LocalSessionCookie"]["name"])
        operation_ids: list[str] = []
        for path, item in self.openapi["paths"].items():
            for method in ("get", "post"):
                if method not in item:
                    continue
                operation = item[method]
                refs = {
                    parameter.get("$ref")
                    for parameter in operation.get("parameters", [])
                    if isinstance(parameter, dict)
                }
                if method == "post":
                    self.assertIn("#/components/parameters/CsrfToken", refs, path)
                else:
                    self.assertNotIn("#/components/parameters/CsrfToken", refs, path)
                self.assertIn("401", operation["responses"], path)
                self.assertIn("403", operation["responses"], path)
                operation_ids.append(operation["operationId"])
        self.assertEqual(len(operation_ids), len(set(operation_ids)))

    def test_decision_contract_is_closed_and_explanation_never_exposes_pip_attributes(self) -> None:
        self.assertEqual(
            ["permit", "deny", "not_applicable", "indeterminate"],
            self.openapi["components"]["schemas"]["Decision"]["properties"]["decision"]["enum"],
        )
        explanation = self.openapi["components"]["schemas"]["Explanation"]
        rendered = yaml.safe_dump(explanation).lower()
        self.assertNotIn("attributes", rendered)
        self.assertNotIn("attributevalues", rendered)
        self.assertNotIn("authorityattributes", rendered)
        self.assertIn("matchedpolicies", rendered)
        self.assertNotIn("PolicyAttributeBag", self.controller)

    def test_rbac_is_a_non_overridable_baseline_and_abac_pep_runs_after_rbac(self) -> None:
        self.assertIn("if (!request.rbacPermitted())", self.pdp)
        self.assertIn("PolicyDecision.DENY", self.pdp)
        self.assertIn("IAM_RBAC_BASELINE_DENIED", self.pdp)
        self.assertIn("public static final int ORDER", self.rbac_filter)
        self.assertIn("RbacAuthorizationFilter.ORDER + 10", self.filter)
        self.assertIn("RbacAuthorizationFilter.REQUIREMENT_ATTRIBUTE", self.filter)
        self.assertIn("request.setAttribute(REQUIREMENT_ATTRIBUTE, requirement)", self.rbac_filter)

    def test_pep_fails_closed_for_non_permit_and_unenforceable_obligations(self) -> None:
        self.assertIn("if (!decision.permitted())", self.filter)
        self.assertIn("REQUIRE_JUSTIFICATION", self.filter)
        self.assertIn("for (PolicyObligation obligation : decision.obligations())", self.filter)
        self.assertIn("if (obligation == PolicyObligation.REQUIRE_JUSTIFICATION)", self.filter)
        self.assertIn("return false", self.filter)

    def test_cli_reuses_rbac_then_the_same_pdp_and_fails_closed_on_obligations(self) -> None:
        rbac_index = self.cli.index("authorization.decide")
        pdp_index = self.cli.index("policyDecisions.decide", rbac_index)
        self.assertLess(rbac_index, pdp_index)
        self.assertIn("supportsAdvancedAuthorization", self.cli)
        self.assertIn("REQUIRE_JUSTIFICATION", self.cli)
        self.assertIn("if (!advanced.permitted())", self.cli)

    def test_static_sod_guard_executes_before_role_assignment_persistence(self) -> None:
        guard_index = self.admin.index("assignmentGuard.check")
        insert_index = self.admin.index("repository.insertAssignment", guard_index)
        self.assertLess(guard_index, insert_index)
        self.assertIn('"IAM_SOD_CONFLICT"', self.sod)
        self.assertIn("effectiveGroupMembers", self.sod)

    def test_policy_language_contains_no_executable_expression_runtime(self) -> None:
        policy_sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (self.IAM / "domain").glob("Policy*.java")
        )
        for forbidden in (
            "ScriptEngine",
            "Runtime.getRuntime",
            "ProcessBuilder",
            "java.net.",
            "java.nio.file.",
            "Class.forName",
            "Method.invoke",
        ):
            self.assertNotIn(forbidden, policy_sources)

    def test_web_is_capability_gated_and_uses_exact_normative_endpoints(self) -> None:
        config = (self.ROOT / "src/applications/web/runtime/config.mjs").read_text(encoding="utf-8")
        module = (self.ROOT / "src/applications/web/public/assets/policy-authorization.mjs").read_text(encoding="utf-8")
        html = (self.ROOT / "src/applications/web/public/index.html").read_text(encoding="utf-8")
        self.assertIn("advancedAuthorizationEnabled", config)
        self.assertIn("Advanced-authorization UI requires identity-access capability", config)
        for path in (
            "/v1/iam/policies",
            "/v1/iam/authorization/",
        ):
            self.assertIn(path, module)
        self.assertIn('id="iam-policy-workspace"', html)
        self.assertIn("Closed declarative JSON", html)
        self.assertNotIn("eval(", module)
        self.assertNotIn("innerHTML", module)

    def test_migration_0016_stays_inside_iam_and_seeds_only_the_system_bridge(self) -> None:
        migration = self.ROOT / "src/distribution/migrations/0016-identity-access-abac-sod"
        pg = (migration / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (migration / "oracle.sql").read_text(encoding="utf-8").lower()
        for sql in (pg, oracle):
            self.assertIn("system.rbac-bridge", sql)
            self.assertIn("access_policy", sql)
            self.assertIn("sod_constraint", sql)
            self.assertNotIn("infranexum_org", sql)
            self.assertNotIn("infranexum_rsot", sql)
        self.assertIn("rbac", pg)
        self.assertIn("permitted", pg)

    def test_policy_temporal_input_uses_server_timezone_when_offset_is_absent(self) -> None:
        temporal = self.openapi["components"]["schemas"]["CreatePolicy"]["properties"]["effectiveFrom"]
        self.assertEqual("server", temporal["x-infranexum-timezone-default"])
        rendered = yaml.safe_dump(temporal)
        self.assertIn("format: date-time", rendered)
        self.assertIn("pattern:", rendered)

        handler = (self.SERVER / "IdentityAccessExceptionHandler.java").read_text(encoding="utf-8")
        self.assertIn("PolicyController.class", handler)
        self.assertIn("INFRANEXUM_IAM_INVALID_REQUEST", handler)


if __name__ == "__main__":
    unittest.main()
