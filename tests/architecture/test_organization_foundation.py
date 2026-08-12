"""Architecture regressions for the Organization/Subdivision foundation."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

import yaml


class OrganizationFoundationArchitectureTest(unittest.TestCase):
    """Keep the first business bounded context isolated, fail-closed and same-origin."""

    ROOT = Path(__file__).resolve().parents[2]
    ORG = ROOT / "src/components/domains/organization"
    SERVER = ROOT / "src/applications/server/main/io/infranexum/server/organization"

    def test_domain_is_a_declared_owned_maven_module(self) -> None:
        root_pom = (self.ROOT / "pom.xml").read_text(encoding="utf-8")
        manifest = (self.ORG / "MANIFEST.json").read_text(encoding="utf-8")
        owners = (self.ROOT / "OWNERS.json").read_text(encoding="utf-8")
        self.assertIn("src/components/domains/organization", root_pom)
        self.assertIn('"lifecycle": "active"', manifest)
        self.assertIn("team.identity-organization", owners)

    def test_pre_iam_http_adapter_is_fail_closed_outside_local(self) -> None:
        properties = (self.SERVER / "OrganizationRuntimeProperties.java").read_text(encoding="utf-8")
        configuration = (self.SERVER / "OrganizationRuntimeConfiguration.java").read_text(encoding="utf-8")
        application = (self.ROOT / "src/applications/server/resources/application.yaml").read_text(encoding="utf-8")
        self.assertIn('ConfigurationProperties(prefix = "infranexum.organization")', properties)
        self.assertIn("localDevelopment()", properties)
        self.assertIn("if (!runtime.localDevelopment())", configuration)
        self.assertIn("may only be enabled in local development", configuration)
        self.assertIn("api-enabled: ${INFRANEXUM_ORGANIZATION_API_ENABLED:false}", application)
        self.assertIn("environment: ${INFRANEXUM_ENVIRONMENT:production}", application)

    def test_event_types_obey_core_contract_and_are_versioned(self) -> None:
        service = (
            self.ORG
            / "main/io/infranexum/organization/application/OrganizationApplicationService.java"
        ).read_text(encoding="utf-8")
        event_types = re.findall(r'"(organization\.[a-z0-9.-]+\.v\d+)"', service)
        self.assertGreaterEqual(len(event_types), 5)
        pattern = re.compile(r"[a-z][a-z0-9]*(?:\.[a-z][a-z0-9-]*){2,7}\.v[1-9][0-9]*$")
        for event_type in event_types:
            self.assertRegex(event_type, pattern)

    def test_openapi_and_web_ingress_preserve_correlation_idempotency_and_same_origin(self) -> None:
        openapi = yaml.safe_load(
            (self.ROOT / "src/applications/server/resources/openapi/organization-foundation.yaml")
            .read_text(encoding="utf-8")
        )
        self.assertEqual("3.1.0", openapi["openapi"])
        self.assertTrue(openapi["x-infranexum-pre-iam-local-only"])
        self.assertIn("CorrelationId", openapi["components"]["parameters"])
        self.assertIn("IdempotencyKey", openapi["components"]["parameters"])

        haproxy = (self.ROOT / "docker/haproxy-web.cfg").read_text(encoding="utf-8")
        compose = (self.ROOT / "docker/compose.yaml").read_text(encoding="utf-8")
        self.assertIn("acl api_request path_beg /api/", haproxy)
        self.assertIn("use_backend infranexum-server-router if api_request", haproxy)
        self.assertIn("INFRANEXUM_WEB_API_BASE_URL: /api", compose)

    def test_web_uses_bootstrap_and_adapted_theme_without_inline_business_html_injection(self) -> None:
        index = (self.ROOT / "src/applications/web/public/index.html").read_text(encoding="utf-8")
        browser = (
            self.ROOT / "src/applications/web/public/assets/bootstrap.mjs"
        ).read_text(encoding="utf-8")
        self.assertIn("bootstrap-5.3.6.min.css", index)
        self.assertIn("infranexum-theme.css", index)
        self.assertLess(index.index("bootstrap-5.3.6.min.css"), index.index("infranexum-theme.css"))
        self.assertIn("organization-rows", index)
        self.assertIn("subdivision-rows", index)
        self.assertNotIn("innerHTML", browser)
        self.assertIn("textContent", browser)


if __name__ == "__main__":
    unittest.main()
