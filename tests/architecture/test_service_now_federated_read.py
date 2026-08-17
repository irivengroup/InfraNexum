"""Architecture regressions for PGM-10-E06 ServiceNow CMDB federated read."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml


class ServiceNowFederatedReadArchitectureTest(unittest.TestCase):
    """Keep the ServiceNow slice read-only, secret-safe, bounded and capability-gated."""

    ROOT = Path(__file__).resolve().parents[2]
    ADAPTER = ROOT / "src/components/adapters/service-now"
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web"

    def test_adapter_is_first_class_without_rsot_or_itam_dependency(self) -> None:
        parent_pom = (self.ROOT / "pom.xml").read_text(encoding="utf-8")
        adapter_pom = (self.ADAPTER / "pom.xml").read_text(encoding="utf-8")
        manifest = json.loads((self.ADAPTER / "MANIFEST.json").read_text(encoding="utf-8"))
        server_manifest = json.loads((self.SERVER / "MANIFEST.json").read_text(encoding="utf-8"))

        self.assertIn("<module>src/components/adapters/service-now</module>", parent_pom)
        self.assertEqual("components.adapters.service-now", manifest["id"])
        self.assertIn("PGM-10-E06", manifest["source_baseline"])
        self.assertEqual(
            ["components.domains.integrations", "components.core.contracts"],
            manifest["dependencies"],
        )
        self.assertIn("components.adapters.service-now", server_manifest["dependencies"])
        self.assertNotIn("infranexum-domain-rsot", adapter_pom)
        self.assertNotIn("infranexum-domain-itam", adapter_pom)

    def test_provider_boundary_is_fixed_https_redirect_free_and_bounded(self) -> None:
        connector = (self.ADAPTER / "main/io/infranexum/adapters/servicenow/ServiceNowConnector.java").read_text(encoding="utf-8")
        transport = (self.ADAPTER / "main/io/infranexum/adapters/servicenow/JdkServiceNowTransport.java").read_text(encoding="utf-8")
        request_contract = (self.ADAPTER / "main/io/infranexum/adapters/servicenow/ServiceNowTransport.java").read_text(encoding="utf-8")
        settings = (self.ADAPTER / "main/io/infranexum/adapters/servicenow/ServiceNowSettings.java").read_text(encoding="utf-8")

        self.assertIn('"https://" + settings.instanceHost() + "/api/now/table/"', connector)
        self.assertIn('"&sysparm_limit="', connector)
        self.assertIn('"&sysparm_offset="', connector)
        self.assertIn('"&sysparm_fields="', connector)
        self.assertIn('"&sysparm_no_count=true"', connector)
        self.assertIn("HttpClient.Redirect.NEVER", transport)
        self.assertIn("DEFAULT_MAX_RESPONSE_BYTES = 2_097_152", transport)
        self.assertIn("maximumResponseBytes + 1", transport)
        self.assertIn('!"https".equalsIgnoreCase(uri.getScheme())', request_contract)
        self.assertIn('host.endsWith(".service-now.com")', request_contract)
        self.assertIn('service-now\\\\.com', settings)

    def test_governance_is_external_authority_federated_read_only(self) -> None:
        settings = (self.ADAPTER / "main/io/infranexum/adapters/servicenow/ServiceNowSettings.java").read_text(encoding="utf-8")
        connector = (self.ADAPTER / "main/io/infranexum/adapters/servicenow/ServiceNowConnector.java").read_text(encoding="utf-8")

        self.assertIn('DIRECTION = "FEDERATED_READ"', settings)
        self.assertIn('AUTHORITY = "EXTERNAL"', settings)
        self.assertIn('startsWith("env:")', settings)
        self.assertIn('startsWith("file:")', settings)
        self.assertIn("Arrays.fill(credential, (byte) 0)", connector)
        self.assertIn('FIELDS = "sys_id,name,sys_class_name,sys_updated_on"', connector)
        self.assertIn('"nameLIKE" + normalized + "^ORDERBYsys_id"', connector)
        self.assertIn('Pattern.compile("[A-Za-z0-9 _./:-]{1,256}")', connector)
        self.assertNotIn("sysparm_query=" + '" + term', connector)
        self.assertNotIn("rsot", connector.lower())
        self.assertNotIn("itam", connector.lower())

    def test_openapi_operations_are_read_permissioned_and_paged_where_needed(self) -> None:
        spec = yaml.safe_load((self.SERVER / "resources/openapi/integrations-connectors.yaml").read_text(encoding="utf-8"))
        paths = spec["paths"]
        service_now_paths = {path: item for path, item in paths.items() if "/providers/service-now" in path}
        self.assertEqual(3, len(service_now_paths))

        operations = []
        for path, item in service_now_paths.items():
            for method, operation in item.items():
                if method.lower() not in {"get", "post"}:
                    continue
                operations.append((path, method.lower(), operation))
                self.assertEqual("integrations.connectors", operation["x-infranexum-capability"])
                self.assertEqual("integrations.connector.read", operation["x-infranexum-permission"]["code"])

        self.assertEqual(3, len(operations))
        self.assertEqual(3, len({operation["operationId"] for _, _, operation in operations}))
        list_operation = service_now_paths["/api/v1/integrations/providers/service-now"]["get"]
        search_operation = service_now_paths[
            "/api/v1/integrations/providers/service-now/{connectorKey}/configuration-items/search"
        ]["post"]
        self.assertEqual("offset", list_operation["x-infranexum-pagination"])
        self.assertEqual("offset", search_operation["x-infranexum-pagination"])
        self.assertEqual("repeatable", search_operation["x-infranexum-idempotency"])
        schema = spec["components"]["schemas"]["ServiceNowSearchRequest"]["properties"]["query"]
        self.assertEqual("^[A-Za-z0-9 _./:-]+$", schema["pattern"])

    def test_web_never_receives_provider_authorization_material(self) -> None:
        client = (self.WEB / "public/assets/service-now.mjs").read_text(encoding="utf-8")
        workspace = (self.WEB / "public/assets/integrations-workspace.mjs").read_text(encoding="utf-8")
        runtime = (self.WEB / "runtime/config.mjs").read_text(encoding="utf-8")

        self.assertIn("configuration.integrationsConnectorsEnabled !== true", client)
        self.assertIn("credentials: 'same-origin'", client)
        self.assertIn("X-CSRF-Token", client)
        self.assertNotIn("Authorization", client)
        self.assertNotIn("bearer", client.lower())
        self.assertIn("ServiceNowClient", workspace)
        self.assertIn("INFRANEXUM_WEB_INTEGRATIONS_CONNECTORS_ENABLED", runtime)

    def test_default_product_configuration_has_no_service_now_secret_or_tenant(self) -> None:
        application = yaml.safe_load((self.SERVER / "resources/application.yaml").read_text(encoding="utf-8"))
        service_now = application["infranexum"]["integrations"]["service-now"]
        compose = (self.ROOT / "docker/compose.yaml").read_text(encoding="utf-8")

        self.assertEqual({}, service_now["connectors"])
        self.assertIn('INFRANEXUM_INTEGRATIONS_ENABLED: "true"', compose)
        self.assertIn('INFRANEXUM_WEB_INTEGRATIONS_CONNECTORS_ENABLED: "true"', compose)
        for forbidden in (
            "SERVICENOW_TOKEN",
            "SERVICE_NOW_TOKEN",
            "SERVICENOW_CLIENT_SECRET",
            "SERVICE_NOW_CLIENT_SECRET",
            "SERVICENOW_INSTANCE",
            "SERVICE_NOW_INSTANCE",
        ):
            self.assertNotIn(forbidden, compose)


if __name__ == "__main__":
    unittest.main()
