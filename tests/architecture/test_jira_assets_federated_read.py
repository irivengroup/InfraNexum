"""Architecture regressions for PGM-10-E06 Jira Assets federated read."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml


class JiraAssetsFederatedReadArchitectureTest(unittest.TestCase):
    """Keep the Jira Assets slice read-only, secret-safe, bounded and capability-gated."""

    ROOT = Path(__file__).resolve().parents[2]
    ADAPTER = ROOT / "src/components/adapters/jira-assets"
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web"

    def test_adapter_is_first_class_without_rsot_or_itam_dependency(self) -> None:
        parent_pom = (self.ROOT / "pom.xml").read_text(encoding="utf-8")
        adapter_pom = (self.ADAPTER / "pom.xml").read_text(encoding="utf-8")
        manifest = json.loads((self.ADAPTER / "MANIFEST.json").read_text(encoding="utf-8"))
        server_manifest = json.loads((self.SERVER / "MANIFEST.json").read_text(encoding="utf-8"))

        self.assertIn("<module>src/components/adapters/jira-assets</module>", parent_pom)
        self.assertEqual("components.adapters.jira-assets", manifest["id"])
        self.assertIn("PGM-10-E06", manifest["source_baseline"])
        self.assertEqual(
            ["components.domains.integrations", "components.core.contracts"],
            manifest["dependencies"],
        )
        self.assertIn("components.adapters.jira-assets", server_manifest["dependencies"])
        self.assertNotIn("infranexum-domain-rsot", adapter_pom)
        self.assertNotIn("infranexum-domain-itam", adapter_pom)

    def test_provider_boundary_is_fixed_https_redirect_free_and_bounded(self) -> None:
        connector = (self.ADAPTER / "main/io/infranexum/adapters/jiraassets/JiraAssetsConnector.java").read_text(encoding="utf-8")
        transport = (self.ADAPTER / "main/io/infranexum/adapters/jiraassets/JdkJiraAssetsTransport.java").read_text(encoding="utf-8")
        request_contract = (self.ADAPTER / "main/io/infranexum/adapters/jiraassets/JiraAssetsTransport.java").read_text(encoding="utf-8")

        self.assertIn('"https://api.atlassian.com/ex/jira/"', connector)
        self.assertIn('"/object/aql?startAt="', connector)
        self.assertNotIn("navlist/aql", connector)
        self.assertIn("includeAttributes=false", connector)
        self.assertIn("HttpClient.Redirect.NEVER", transport)
        self.assertIn("DEFAULT_MAX_RESPONSE_BYTES = 2_097_152", transport)
        self.assertIn("maximumResponseBytes + 1", transport)
        self.assertIn('!"https".equalsIgnoreCase(uri.getScheme())', request_contract)
        self.assertIn('!"api.atlassian.com".equalsIgnoreCase(uri.getHost())', request_contract)

    def test_governance_is_external_authority_federated_read_only(self) -> None:
        settings = (self.ADAPTER / "main/io/infranexum/adapters/jiraassets/JiraAssetsSettings.java").read_text(encoding="utf-8")
        connector = (self.ADAPTER / "main/io/infranexum/adapters/jiraassets/JiraAssetsConnector.java").read_text(encoding="utf-8")

        self.assertIn('DIRECTION = "FEDERATED_READ"', settings)
        self.assertIn('AUTHORITY = "EXTERNAL"', settings)
        self.assertIn('startsWith("env:")', settings)
        self.assertIn('startsWith("file:")', settings)
        self.assertIn("Arrays.fill(credential, (byte) 0)", connector)
        self.assertIn("record RemoteObject(String id, String globalId, String objectKey, String label, String objectTypeId, String objectTypeName)", connector)
        self.assertNotIn("record RemoteObject(String id, String globalId, String objectKey, String label, String attributes", connector)

    def test_openapi_operations_are_read_permissioned_and_paged_where_needed(self) -> None:
        spec = yaml.safe_load((self.SERVER / "resources/openapi/integrations-connectors.yaml").read_text(encoding="utf-8"))
        paths = spec["paths"]
        jira_paths = {path: item for path, item in paths.items() if "/providers/jira-assets" in path}
        self.assertEqual(3, len(jira_paths))

        operations = []
        for path, item in jira_paths.items():
            for method, operation in item.items():
                if method.lower() not in {"get", "post"}:
                    continue
                operations.append((path, method.lower(), operation))
                self.assertEqual("integrations.connectors", operation["x-infranexum-capability"])
                self.assertEqual("integrations.connector.read", operation["x-infranexum-permission"]["code"])

        self.assertEqual(3, len(operations))
        self.assertEqual(3, len({operation["operationId"] for _, _, operation in operations}))
        list_operation = jira_paths["/api/v1/integrations/providers/jira-assets"]["get"]
        search_operation = jira_paths["/api/v1/integrations/providers/jira-assets/{connectorKey}/objects/search"]["post"]
        self.assertEqual("offset", list_operation["x-infranexum-pagination"])
        self.assertEqual("offset", search_operation["x-infranexum-pagination"])
        self.assertEqual("repeatable", search_operation["x-infranexum-idempotency"])

    def test_web_never_receives_provider_authorization_material(self) -> None:
        client = (self.WEB / "public/assets/jira-assets.mjs").read_text(encoding="utf-8")
        workspace = (self.WEB / "public/assets/integrations-workspace.mjs").read_text(encoding="utf-8")
        runtime = (self.WEB / "runtime/config.mjs").read_text(encoding="utf-8")

        self.assertIn("configuration.integrationsConnectorsEnabled !== true", client)
        self.assertIn("credentials: 'same-origin'", client)
        self.assertIn("X-CSRF-Token", client)
        self.assertNotIn("Authorization", client)
        self.assertNotIn("bearer", client.lower())
        self.assertIn("JiraAssetsClient", workspace)
        self.assertIn("INFRANEXUM_WEB_INTEGRATIONS_CONNECTORS_ENABLED", runtime)

    def test_default_product_configuration_has_no_jira_secret_or_tenant(self) -> None:
        application = yaml.safe_load((self.SERVER / "resources/application.yaml").read_text(encoding="utf-8"))
        jira = application["infranexum"]["integrations"]["jira-assets"]
        compose = (self.ROOT / "docker/compose.yaml").read_text(encoding="utf-8")
        web_example = (self.WEB / "configs/web.env.example").read_text(encoding="utf-8")

        self.assertEqual({}, jira["connectors"])
        self.assertIn('INFRANEXUM_INTEGRATIONS_ENABLED: "true"', compose)
        self.assertIn('INFRANEXUM_WEB_INTEGRATIONS_CONNECTORS_ENABLED: "true"', compose)
        for forbidden in ("JIRA_TOKEN", "JIRA_BEARER", "JIRA_CLOUD_ID", "JIRA_WORKSPACE_ID"):
            self.assertNotIn(forbidden, compose)
        self.assertIn("INFRANEXUM_WEB_INTEGRATIONS_CONNECTORS_ENABLED=false", web_example)


if __name__ == "__main__":
    unittest.main()
