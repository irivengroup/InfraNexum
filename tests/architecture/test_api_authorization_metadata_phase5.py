from __future__ import annotations

import csv
import json
import re
import unittest
from collections import Counter
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "src/applications/server/resources/openapi"
HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options"}


class ApiAuthorizationMetadataPhase5Tests(unittest.TestCase):
    """Pin PGM-05-E01 phase-5 capability and authorization governance to real runtime registries."""

    @staticmethod
    def operations() -> list[tuple[str, str, dict]]:
        catalogue = yaml.safe_load((OPENAPI / "catalogue.yaml").read_text(encoding="utf-8"))
        result: list[tuple[str, str, dict]] = []
        for fragment in catalogue["fragments"]:
            document = yaml.safe_load((OPENAPI / fragment["file"]).read_text(encoding="utf-8"))
            for path, item in document.get("paths", {}).items():
                for method, operation in item.items():
                    if method in HTTP_METHODS and isinstance(operation, dict):
                        result.append((method, path, operation))
        return result

    def test_all_179_operations_have_registry_backed_capability_and_structured_authorization(self) -> None:
        operations = self.operations()
        self.assertEqual(179, len(operations))
        with (ROOT / "src/components/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv").open(
            encoding="utf-8", newline=""
        ) as stream:
            capabilities = {row["capability_code"] for row in csv.DictReader(stream)}
        permission_source = (
            ROOT / "src/components/domains/identity-access/main/io/infranexum/identity/access/domain/PermissionCodes.java"
        ).read_text(encoding="utf-8")
        permission_constants = {
            value: name for name, value in re.findall(r'(\w+)\s*=\s*"([a-z][a-z0-9_.]+)"', permission_source)
        }
        server_source = "\n".join(
            path.read_text(encoding="utf-8") for path in (ROOT / "src/applications/server/main").rglob("*.java")
        )

        modes: Counter[str] = Counter()
        for _method, _path, operation in operations:
            capability = operation.get("x-infranexum-capability")
            self.assertIn(capability, capabilities, operation["operationId"])
            authorization = operation.get("x-infranexum-permission")
            self.assertIsInstance(authorization, dict, operation["operationId"])
            mode = authorization.get("mode")
            modes[mode] += 1
            codes = [authorization["code"]] if mode == "permission" else authorization.get("codes", [])
            for code in codes:
                constant = permission_constants.get(code)
                self.assertIsNotNone(constant, f"{operation['operationId']} unknown permission {code}")
                self.assertIn(
                    f"PermissionCodes.{constant}",
                    server_source,
                    f"{operation['operationId']} permission {code} is not referenced by Server enforcement",
                )
        self.assertEqual(
            Counter(
                {
                    "permission": 157,
                    "platform-admin": 9,
                    "conditional": 4,
                    "authenticated-self": 3,
                    "organization-visibility": 2,
                    "anonymous": 3,
                    "connector-signature": 1,
                }
            ),
            modes,
        )

    def test_debt_baseline_is_fully_closed(self) -> None:
        baseline = json.loads((ROOT / "validation/api_contracts/baseline.json").read_text(encoding="utf-8"))
        self.assertEqual(
            {"idempotency": [], "pagination": [], "capability": [], "permission": []},
            baseline["debt"],
        )

    def test_runtime_capability_gate_is_ordered_after_correlation_and_before_authentication(self) -> None:
        capability_filter = (
            ROOT / "src/applications/server/main/io/infranexum/server/platform/ApiCapabilityFilter.java"
        ).read_text(encoding="utf-8")
        correlation = (
            ROOT / "src/applications/server/main/io/infranexum/server/observability/CorrelationIdFilter.java"
        ).read_text(encoding="utf-8")
        local_auth_candidates = list(
            (ROOT / "src/applications/server/main/io/infranexum/server").rglob("LocalAuthenticationFilter.java")
        )
        self.assertEqual(1, len(local_auth_candidates))
        local_auth = local_auth_candidates[0].read_text(encoding="utf-8")
        self.assertIn("Ordered.HIGHEST_PRECEDENCE + 15", capability_filter)
        self.assertIn("Ordered.HIGHEST_PRECEDENCE + 10", correlation)
        self.assertIn("Ordered.HIGHEST_PRECEDENCE + 20", local_auth)
        self.assertIn("HttpStatus.NOT_FOUND", capability_filter)
        self.assertIn("INFRANEXUM_API_CAPABILITY_UNAVAILABLE", capability_filter)
        self.assertIn("INFRANEXUM_CAPABILITY_RUNTIME_UNAVAILABLE", capability_filter)
        configuration = (
            ROOT / "src/applications/server/main/io/infranexum/server/platform/PlatformCapabilityConfiguration.java"
        ).read_text(encoding="utf-8")
        self.assertIn("ApiCapabilityFilter apiCapabilityFilter", configuration)

    def test_runtime_route_capability_smoke_is_blocking_in_make_and_foundation_ci(self) -> None:
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        self.assertIn("java-api-capability-smoke:", makefile)
        verify_line = next(line for line in makefile.splitlines() if line.startswith("verify-foundation:"))
        self.assertIn("java-api-capability-smoke", verify_line)
        workflow = (ROOT / ".github/workflows/foundation.yml").read_text(encoding="utf-8")
        self.assertIn("java-api-capability-smoke", workflow)
        generator = (ROOT / "tests/java-api-capability-smoke/generate_cases.py").read_text(encoding="utf-8")
        smoke = (
            ROOT
            / "tests/java-api-capability-smoke/io/infranexum/server/platform/ApiCapabilityRequirementSmoke.java"
        ).read_text(encoding="utf-8")
        self.assertIn('operation["x-infranexum-capability"]', generator)
        self.assertIn("rows.size() == 179", smoke)

    def test_special_authorization_modes_match_runtime_boundary_semantics(self) -> None:
        local_auth = yaml.safe_load((OPENAPI / "local-auth.yaml").read_text(encoding="utf-8"))
        self.assertEqual(
            "anonymous",
            local_auth["paths"]["/api/v1/iam/local-auth/session"]["post"]["x-infranexum-permission"]["mode"],
        )
        self.assertEqual(
            "authenticated-self",
            local_auth["paths"]["/api/v1/iam/local-auth/session"]["get"]["x-infranexum-permission"]["mode"],
        )
        authorization = (
            ROOT / "src/applications/server/main/io/infranexum/server/identityaccess/AuthorizationRequirement.java"
        ).read_text(encoding="utf-8")
        self.assertIn("PLATFORM_ADMINISTRATOR", authorization)
        self.assertIn("ORGANIZATION_VISIBILITY", authorization)
        self.assertIn("CONTROLLER_SCOPED", authorization)
        integrations = yaml.safe_load((OPENAPI / "integrations-connectors.yaml").read_text(encoding="utf-8"))
        webhook = integrations["paths"]["/api/v1/integrations/webhooks/{connectorKey}"]["post"]
        self.assertEqual("connector-signature", webhook["x-infranexum-permission"]["mode"])
        self.assertEqual("connector-delivery", webhook["x-infranexum-idempotency"])

    def test_effective_contract_filter_is_available_for_installation_specific_publication(self) -> None:
        checker = (ROOT / "validation/api_contracts/checker.py").read_text(encoding="utf-8")
        cli = (ROOT / "validation/api_contracts/cli.py").read_text(encoding="utf-8")
        self.assertIn("def build_effective_spec", checker)
        self.assertIn('product["x-infranexum-contract"] = "installation-effective"', checker)
        self.assertIn("--effective-spec", cli)
        self.assertIn("--effective-capability", cli)


if __name__ == "__main__":
    unittest.main()
