from __future__ import annotations

import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "src/applications/server/main/io/infranexum/server"
OPENAPI = ROOT / "src/applications/server/resources/openapi"

CANONICAL = {
    "type", "title", "status", "detail", "instance", "code", "message", "details", "metadata",
    "occurred_at", "timestamp", "correlation_id", "trace_id",
}


class ApiProblemRuntimeContractTest(unittest.TestCase):
    def test_every_exception_handler_uses_shared_api_problem_boundary(self) -> None:
        handlers = sorted(SERVER.rglob("*ExceptionHandler.java"))
        self.assertGreaterEqual(len(handlers), 10)
        for path in handlers:
            text = path.read_text(encoding="utf-8")
            with self.subTest(handler=path.name):
                self.assertIn("ApiProblem", text)
                self.assertIn("ApiProblemSupport", text)
                self.assertNotIn("ProblemDetail", text)
                self.assertNotIn("ResponseEntity<Map", text)
                self.assertNotRegex(text, r"record\s+(?:Problem|EntitlementProblem)\b")
                self.assertNotIn("MediaType.APPLICATION_JSON", text)

    def test_terminal_security_filters_use_the_same_problem_writer(self) -> None:
        filters = (
            SERVER / "http/CorrelationIdFilter.java",
            SERVER / "identity/LocalAuthenticationFilter.java",
            SERVER / "identityaccess/RbacAuthorizationFilter.java",
            SERVER / "identityaccess/AdvancedAuthorizationFilter.java",
        )
        for path in filters:
            text = path.read_text(encoding="utf-8")
            with self.subTest(filter=path.name):
                self.assertIn("ApiProblemSupport", text)
                self.assertIn("problems.write", text)
                self.assertNotRegex(text, r'String\s+(?:body|problem)\s*=\s*"\\{')

    def test_problem_support_preserves_legacy_aliases_and_redacts_public_text(self) -> None:
        model = (SERVER / "http/ApiProblem.java").read_text(encoding="utf-8")
        support = (SERVER / "http/ApiProblemSupport.java").read_text(encoding="utf-8")
        for field in CANONICAL:
            self.assertRegex(model, rf"\b{re.escape(field)}\b")
        self.assertIn('redactor.redact("problem", source)', support)
        self.assertIn("MediaType.APPLICATION_PROBLEM_JSON", support)
        self.assertIn("CorrelationContext.HEADER_NAME", support)
        self.assertIn("response.flushBuffer()", support)

    def test_all_openapi_fragments_expose_one_canonical_problem_and_correlation_header(self) -> None:
        fragments = sorted(p for p in OPENAPI.glob("*.yaml") if p.name != "catalogue.yaml")
        self.assertEqual(15, len(fragments))
        for path in fragments:
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            components = document["components"]
            problem = components["schemas"]["Problem"]
            with self.subTest(fragment=path.name):
                self.assertEqual(CANONICAL, set(problem["properties"]))
                self.assertEqual(CANONICAL, set(problem["required"]))
                self.assertFalse(problem["additionalProperties"])
                self.assertIn("CorrelationId", components["headers"])

    def test_legacy_local_auth_and_organization_error_media_types_are_removed(self) -> None:
        for name in ("local-auth.yaml", "organization-foundation.yaml"):
            document = yaml.safe_load((OPENAPI / name).read_text(encoding="utf-8"))
            for response_name, response in document["components"]["responses"].items():
                with self.subTest(fragment=name, response=response_name):
                    self.assertEqual({"application/problem+json"}, set(response["content"]))
                    self.assertIn("X-Correlation-ID", response["headers"])


if __name__ == "__main__":
    unittest.main()
