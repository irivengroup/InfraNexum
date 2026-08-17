import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class OutboundNotificationArchitectureTest(unittest.TestCase):
    """Protects the PGM-10-E06 durable signed-webhook notification boundary."""

    def test_adapter_is_registered_and_server_depends_on_it(self):
        parent = (ROOT / "pom.xml").read_text(encoding="utf-8")
        manifest = json.loads((ROOT / "src/components/adapters/outbound-webhook/MANIFEST.json").read_text(encoding="utf-8"))
        server = json.loads((ROOT / "src/applications/server/MANIFEST.json").read_text(encoding="utf-8"))
        self.assertIn("<module>src/components/adapters/outbound-webhook</module>", parent)
        self.assertEqual("components.adapters.outbound-webhook", manifest["id"])
        self.assertEqual(["components.domains.integrations", "components.core.contracts"], manifest["dependencies"])
        self.assertIn("components.adapters.outbound-webhook", server["dependencies"])

    def test_transport_is_https_signed_redirect_safe_and_secret_external(self):
        adapter = (ROOT / "src/components/adapters/outbound-webhook/main/io/infranexum/adapters/outboundwebhook/JdkSignedWebhookTransport.java").read_text(encoding="utf-8")
        endpoint = (ROOT / "src/components/domains/integrations/main/io/infranexum/integrations/OutboundNotificationEndpoint.java").read_text(encoding="utf-8")
        self.assertIn('HttpClient.Redirect.NEVER', adapter)
        self.assertIn('X-InfraNexum-Signature', adapter)
        self.assertIn('X-InfraNexum-Timestamp', adapter)
        self.assertIn('X-InfraNexum-Delivery-ID', adapter)
        self.assertIn('HmacSHA256', adapter)
        self.assertIn('Arrays.fill(secret, (byte) 0)', adapter)
        self.assertIn('"https"', endpoint)
        self.assertIn('normalized.startsWith("env:") || normalized.startsWith("file:")', endpoint)

    def test_notifications_are_durable_bounded_and_do_not_write_rsot_or_itam(self):
        domain = ROOT / "src/components/domains/integrations/main/io/infranexum/integrations"
        text = "\n".join(path.read_text(encoding="utf-8") for path in domain.glob("OutboundNotification*.java"))
        self.assertIn("MAX_ENDPOINTS_PER_EVENT = 64", text)
        self.assertIn("claimBatch", text)
        self.assertIn("DEAD_LETTER", text)
        self.assertIn("suspendAfterDeadLetters", text)
        lowered = text.lower()
        self.assertNotIn("io.infranexum.rsot", lowered)
        self.assertNotIn("io.infranexum.itam", lowered)

    def test_database_contract_has_outbox_idempotency_lease_dlq_and_endpoint_state(self):
        pg = (ROOT / "src/distribution/migrations/0036-integrations-outbound-notifications/postgresql.sql").read_text(encoding="utf-8").lower()
        self.assertIn("notification_outbox", pg)
        self.assertIn("unique(endpoint_key,event_id)", pg.replace(" ", ""))
        self.assertIn("lease_owner", pg)
        self.assertIn("lease_until", pg)
        self.assertIn("dead_letter", pg)
        self.assertIn("notification_endpoint_state", pg)

    def test_api_models_and_web_boundary_never_expose_destination_or_secret_reference(self):
        models = (ROOT / "src/applications/server/main/io/infranexum/server/integrations/NotificationApiModels.java").read_text(encoding="utf-8")
        self.assertIn("record EndpointResponse", models)
        endpoint_response = models[models.index("record EndpointResponse"):models.index("record PublishRequest")]
        self.assertNotIn("destination", endpoint_response)
        self.assertNotIn("secretReference", endpoint_response)
        self.assertNotIn("bearer", endpoint_response.lower())

    def test_permissions_are_distinct_for_read_publish_replay_and_resume(self):
        sql = (ROOT / "src/distribution/migrations/0037-identity-access-notification-permissions/postgresql.sql").read_text(encoding="utf-8")
        for permission in (
            "integrations.notification.read",
            "integrations.notification.publish",
            "integrations.notification.replay",
            "integrations.notification.resume",
        ):
            self.assertIn(permission, sql)
        self.assertIn("019ffbda-1001-7e80-9ec8-7580467e9a85", sql)


if __name__ == "__main__":
    unittest.main()
