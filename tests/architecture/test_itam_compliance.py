"""Architecture and contract regressions for PGM-07-E03 warranty, support and licensing."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml


class ItamComplianceArchitectureTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    ITAM = ROOT / "src/components/domains/itam/main/io/infranexum/itam"
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web"
    MIGRATIONS = ROOT / "src/distribution/migrations"

    def test_domain_keeps_contractual_authority_in_itam_and_never_stores_raw_license_keys(self) -> None:
        compliance = self.ITAM / "compliance"
        license_source = (compliance / "domain/SoftwareLicenseContract.java").read_text(encoding="utf-8")
        service = (compliance / "application/ComplianceApplicationService.java").read_text(encoding="utf-8")
        for name in ("Warranty.java", "SoftwareLicenseContract.java", "SupportProviderAuthorization.java", "SupportCoverage.java", "ComplianceRevision.java"):
            self.assertTrue((compliance / "domain" / name).is_file())
        for forbidden in ("licenseKey", "license_key", "productKey", "privateKey"):
            self.assertNotIn(forbidden, license_source)
        self.assertIn("Raw software", service)
        self.assertIn("ITAM_COMPLIANCE_PRODUCER_MISMATCH", service)
        self.assertIn("ITAM_CONTRACT_QUOTA_EXCEEDED", service)

    def test_readiness_is_real_fail_closed_and_support_never_rewrites_manufacturer_dates(self) -> None:
        service = (self.ITAM / "compliance/application/ComplianceApplicationService.java").read_text(encoding="utf-8")
        policy = (self.SERVER / "main/io/infranexum/server/itam/ItamComplianceReadinessPolicy.java").read_text(encoding="utf-8")
        scheduler = (self.SERVER / "main/io/infranexum/server/itam/ItamComplianceAlertScheduler.java").read_text(encoding="utf-8")
        self.assertIn("if(!features.complianceEnabled()", service)
        self.assertIn("warranties.isEmpty()", service)
        self.assertIn("supportCoveragesForAsset", service)
        self.assertIn("compliance.hardwareReady", policy)
        self.assertIn("compliance.softwareReady", policy)
        self.assertIn("if(compliance.enabled())", scheduler)
        coverage = (self.ITAM / "compliance/domain/SupportCoverage.java").read_text(encoding="utf-8")
        self.assertNotIn("manufacturerSupportEndDate", coverage)
        self.assertNotIn("warrantyEndDate", coverage)

    def test_support_authorization_suspension_forces_active_coverages_to_review_required_transactionally(self) -> None:
        service = (self.ITAM / "compliance/application/ComplianceApplicationService.java").read_text(encoding="utf-8")
        segment = service[service.index("public SupportProviderAuthorization suspendSupportAuthorization"):service.index("public SupportCoverage createSupportCoverage")]
        self.assertIn("supportCoveragesForAuthorization", segment)
        self.assertIn("requireReview", segment)
        self.assertIn("repository.updateSupportCoverage", segment)
        self.assertIn("itam.support_coverage.review_required.v1", segment)
        self.assertIn("execute(tx->", segment)

    def test_migration_is_pg_oracle_symmetric_weakly_coupled_and_versioned(self) -> None:
        migration = self.MIGRATIONS / "0023-itam-warranty-support-license"
        pg = (migration / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (migration / "oracle.sql").read_text(encoding="utf-8").lower()
        logical = json.loads((migration / "logical-model.json").read_text(encoding="utf-8"))
        for token in ("warranty", "software_license_contract", "support_provider_authorization", "support_coverage", "compliance_revision", "compliance_alert_dedup"):
            self.assertIn(token, pg)
        for forbidden in ("references infranexum_org", "references infranexum_iam", "references infranexum_rsot", "references infranexum_itam.partner"):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        self.assertFalse(logical["invariants"]["cross_context_foreign_keys"])
        self.assertTrue(logical["invariants"]["versioned_evidence_history"])
        self.assertFalse(logical["invariants"]["raw_license_key_storage"])
        self.assertTrue(logical["invariants"]["deadline_alerts_independent_of_updated_at"])

    def test_openapi_is_native_unique_capability_gated_and_does_not_expose_secret_license_fields(self) -> None:
        path = self.SERVER / "resources/openapi/itam-compliance.yaml"
        raw = path.read_text(encoding="utf-8")
        spec = yaml.safe_load(raw)
        self.assertEqual("3.1.0", spec["openapi"])
        operations = []
        for item in spec["paths"].values():
            operations.extend(value for key, value in item.items() if key.lower() in {"get", "post", "put", "patch", "delete"})
        self.assertEqual(23, len(operations))
        self.assertEqual(23, len({operation["operationId"] for operation in operations}))
        for operation in operations:
            self.assertEqual("itam.compliance", operation["x-infranexum-capability"])
            self.assertTrue(operation["x-infranexum-permission"].startswith("itam."))
        self.assertNotIn("licenseKey", raw)
        self.assertNotIn("license_key", raw)
        self.assertNotIn("#/components/pathItems/", raw)
        self.assertNotIn("placeholder", raw.lower())

    def test_http_cli_web_share_scoped_rbac_and_fail_closed_capability_chain(self) -> None:
        controller = (self.SERVER / "main/io/infranexum/server/itam/ItamComplianceController.java").read_text(encoding="utf-8")
        cli = (self.SERVER / "main/io/infranexum/server/itam/cli/ItamComplianceCli.java").read_text(encoding="utf-8")
        runtime = (self.SERVER / "main/io/infranexum/server/itam/ItamComplianceRuntimeConfiguration.java").read_text(encoding="utf-8")
        web_config = (self.WEB / "runtime/config.mjs").read_text(encoding="utf-8")
        web_client = (self.WEB / "public/assets/itam-compliance.mjs").read_text(encoding="utf-8")
        self.assertIn("AuthorizationScope.organization", controller)
        self.assertNotIn("AuthorizationScope.platform()", controller)
        self.assertIn('"password-file"', cli)
        self.assertIn('flag("dry-run")', cli)
        for capability in ('"itam.partners"', '"itam.assets"', '"itam.compliance"'):
            self.assertIn(capability, runtime)
        self.assertIn("INFRANEXUM_WEB_ITAM_COMPLIANCE_ENABLED", web_config)
        self.assertIn("ITAM Compliance UI requires Partner catalogue and Asset lifecycle capabilities", web_config)
        self.assertIn("configuration.itamComplianceEnabled !== true", web_client)
        self.assertIn("raw software license keys are not accepted", web_client)
        self.assertIn("If-Match", web_client)
        self.assertIn("Idempotency-Key", web_client)

    def test_alert_policy_is_externalized_validated_and_defaults_to_cdc_thresholds(self) -> None:
        service = (self.ITAM / "compliance/application/ComplianceApplicationService.java").read_text(encoding="utf-8")
        runtime = (self.SERVER / "main/io/infranexum/server/itam/ItamComplianceRuntimeConfiguration.java").read_text(encoding="utf-8")
        application = (self.SERVER / "resources/application.yaml").read_text(encoding="utf-8")
        self.assertIn("fail-on-unknown-properties: true", application)
        self.assertIn("validatedThresholds", service)
        self.assertIn("unique, descending", service)
        self.assertIn("180,120,90,60,30,15,7,1", runtime)
        self.assertIn("INFRANEXUM_ITAM_COMPLIANCE_ALERT_THRESHOLDS", application)


if __name__ == "__main__":
    unittest.main()
