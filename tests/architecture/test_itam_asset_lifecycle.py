"""Architecture and contract regressions for PGM-07-E02 ITAM asset lifecycle."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml


class ItamAssetLifecycleArchitectureTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    ITAM = ROOT / "src/components/domains/itam"
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web"
    JDBC = ROOT / "src/components/adapters/jdbc"

    def test_domain_owns_patrimonial_lifecycle_but_keeps_cross_context_references_weak(self) -> None:
        asset = (self.ITAM / "main/io/infranexum/itam/asset/domain/Asset.java").read_text(encoding="utf-8")
        status = (self.ITAM / "main/io/infranexum/itam/asset/domain/AssetLifecycleStatus.java").read_text(encoding="utf-8")
        repository = (self.ITAM / "main/io/infranexum/itam/asset/ports/AssetRepository.java").read_text(encoding="utf-8")
        for value in ("ACQUIRED", "RECEIVED", "IN_STOCK", "ASSIGNED", "DEPLOYED", "MAINTENANCE", "RETURNED", "RETIRED", "DISPOSED"):
            self.assertIn(value, status)
        self.assertIn("rsotObjectId", asset)
        self.assertIn("owningOrganizationId", asset)
        self.assertIn("acquiredFromPartnerId", asset)
        self.assertIn("append-only custody history", repository)
        for forbidden in ("Warranty", "LicenseContract", "Dcim", "Rack", "Room"):
            self.assertNotIn(forbidden, asset)

    def test_operational_states_are_protected_by_explicit_e03_readiness_port_and_fail_closed_runtime(self) -> None:
        port = (self.ITAM / "main/io/infranexum/itam/asset/ports/AssetOperationalReadinessPolicy.java").read_text(encoding="utf-8")
        service = (self.ITAM / "main/io/infranexum/itam/asset/application/AssetApplicationService.java").read_text(encoding="utf-8")
        pending = (self.SERVER / "main/io/infranexum/server/itam/PendingAssetComplianceReadinessPolicy.java").read_text(encoding="utf-8")
        self.assertIn("PGM-07-E03", port)
        self.assertGreaterEqual(service.count("readiness.requireReady"), 1)
        for transition in ("stock", "assign", "deploy"):
            self.assertIn(f'"{transition}"', service)
        self.assertIn("ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE", pending)
        self.assertNotIn("return;", pending)

    def test_mutations_are_idempotent_versioned_transactional_and_emit_minimized_events(self) -> None:
        service = (self.ITAM / "main/io/infranexum/itam/asset/application/AssetApplicationService.java").read_text(encoding="utf-8")
        for token in (
            "IDEMPOTENCY_CONFLICT", "VERSION_CONFLICT", "ITAM_ASSET_RSOT_CONFLICT", "features.assetLimit()",
            "events.execute", "transaction.append", "itam.asset.acquired.v1", "itam.asset.disposed.v1",
        ):
            self.assertIn(token, service)
        self.assertNotIn("acquisitionValue", service[service.index("private EventEnvelope event"):])
        self.assertNotIn("currencyCode", service[service.index("private EventEnvelope event"):])
        retire = service[service.index("public Asset retire"):service.index("public Asset dispose")]
        self.assertIn("return execute(transaction ->", retire)
        self.assertIn("Asset current = requireAsset(id)", retire)

    def test_jdbc_schema_and_adapter_preserve_append_only_custody_and_weak_references(self) -> None:
        adapter = (self.JDBC / "main/io/infranexum/adapters/persistence/jdbc/JdbcAssetRepository.java").read_text(encoding="utf-8")
        migration = self.ROOT / "src/distribution/migrations/0021-itam-asset-lifecycle"
        pg = (migration / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (migration / "oracle.sql").read_text(encoding="utf-8").lower()
        logical = json.loads((migration / "logical-model.json").read_text(encoding="utf-8"))
        self.assertIn("asset_custody_event", pg)
        self.assertIn("infranexum_itam_asset_custody", oracle)
        self.assertIn("currentConnection", adapter)
        self.assertIn("expectedVersion", adapter)
        for forbidden in ("references infranexum_org", "references infranexum_iam", "references infranexum_rsot"):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        self.assertFalse(logical["invariants"]["cross_context_foreign_keys"])
        self.assertTrue(logical["invariants"]["custody_history_append_only"])
        self.assertTrue(logical["invariants"]["disposal_evidence_required"])

    def test_api_contract_is_native_openapi31_unique_and_controller_scoped(self) -> None:
        spec_path = self.SERVER / "resources/openapi/itam-assets.yaml"
        raw = spec_path.read_text(encoding="utf-8")
        spec = yaml.safe_load(raw)
        self.assertEqual("3.1.0", spec["openapi"])
        operations = []
        for path_item in spec["paths"].values():
            operations.extend(value for key, value in path_item.items() if key.lower() in {"get", "post", "put", "patch", "delete"})
        self.assertEqual(13, len(operations))
        self.assertEqual(13, len({operation["operationId"] for operation in operations}))
        for operation in operations:
            self.assertEqual("itam.assets", operation["x-infranexum-capability"])
            self.assertTrue(operation["x-infranexum-permission"].startswith("itam.asset."))
        self.assertNotIn("responses-map-placeholder", raw)
        self.assertNotIn("#/components/pathItems/", raw)
        requirement = (self.SERVER / "main/io/infranexum/server/identityaccess/AuthorizationRequirement.java").read_text(encoding="utf-8")
        controller = (self.SERVER / "main/io/infranexum/server/itam/ItamAssetController.java").read_text(encoding="utf-8")
        self.assertIn('/api/v1/itam/assets', requirement)
        self.assertIn("CONTROLLER_SCOPED", requirement)
        self.assertGreaterEqual(controller.count("authorization.require("), 3)
        self.assertIn("AuthorizationScope.organization", controller)

    def test_cli_web_capability_and_default_http_surface_share_fail_closed_boundary(self) -> None:
        permissions = (self.ROOT / "src/components/domains/identity-access/main/io/infranexum/identity/access/domain/PermissionCodes.java").read_text(encoding="utf-8")
        cli = (self.SERVER / "main/io/infranexum/server/itam/cli/ItamAssetCli.java").read_text(encoding="utf-8")
        web = (self.WEB / "public/assets/itam-assets.mjs").read_text(encoding="utf-8")
        runtime = (self.WEB / "runtime/config.mjs").read_text(encoding="utf-8")
        application = (self.SERVER / "resources/application.yaml").read_text(encoding="utf-8")
        catalogue = (self.ROOT / "src/components/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv").read_text(encoding="utf-8")
        for permission in ("itam.asset.read", "itam.asset.create", "itam.asset.update"):
            self.assertIn(permission, permissions)
        self.assertIn('"password-file"', cli)
        self.assertIn('args.flag("dry-run")', cli)
        self.assertIn("configuration.itamAssetsEnabled !== true", web)
        self.assertIn("Idempotency-Key", web)
        self.assertIn("If-Match", web)
        self.assertIn("INFRANEXUM_WEB_ITAM_ASSETS_ENABLED", runtime)
        self.assertIn("asset-api-enabled: ${INFRANEXUM_ITAM_ASSET_API_ENABLED:false}", application)
        self.assertIn("itam.assets,lite;pro;enterprise,server", catalogue)


if __name__ == "__main__":
    unittest.main()
