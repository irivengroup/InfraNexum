"""Architecture regressions for PGM-07-E01 governed ITAM Partner catalogues."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml


class ItamPartnerCatalogueArchitectureTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    ITAM = ROOT / "src/components/domains/itam"
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web"

    def test_itam_is_first_class_bounded_context_with_declared_runtime_dependencies(self) -> None:
        root_pom = (self.ROOT / "pom.xml").read_text(encoding="utf-8")
        manifest = json.loads((self.ITAM / "MANIFEST.json").read_text(encoding="utf-8"))
        server = json.loads((self.SERVER / "MANIFEST.json").read_text(encoding="utf-8"))
        jdbc = json.loads((self.ROOT / "src/components/adapters/jdbc/MANIFEST.json").read_text(encoding="utf-8"))
        policy = json.loads((self.ROOT / "validation/architecture/policy.json").read_text(encoding="utf-8"))
        self.assertIn("<module>src/components/domains/itam</module>", root_pom)
        self.assertEqual("components.domains.itam", manifest["id"])
        self.assertIn("PGM-07-E01", manifest["source_baseline"])
        self.assertIn("components.domains.itam", server["dependencies"])
        self.assertIn("components.domains.itam", jdbc["dependencies"])
        self.assertIn("components/domains/itam", policy["required_manifest_paths"])

    def test_single_partner_aggregate_has_exact_roles_and_governed_lifecycle(self) -> None:
        roles = (self.ITAM / "main/io/infranexum/itam/partner/domain/PartnerRole.java").read_text(encoding="utf-8")
        status = (self.ITAM / "main/io/infranexum/itam/partner/domain/PartnerAuthorizationStatus.java").read_text(encoding="utf-8")
        for role in (
            "manufacturer", "software_publisher", "supplier", "third_party_support_provider", "integrator", "recycler",
        ):
            self.assertIn(f'"{role}"', roles)
        for state in ("draft", "pending_approval", "active", "suspended", "retired"):
            self.assertIn(f'"{state}"', status)
        self.assertIn("DRAFT -> target == PENDING_APPROVAL", status)
        self.assertIn("PENDING_APPROVAL -> target == ACTIVE || target == DRAFT", status)
        self.assertIn("RETIRED -> false", status)

    def test_application_service_enforces_capability_quota_dedup_idempotency_and_events(self) -> None:
        source = (self.ITAM / "main/io/infranexum/itam/partner/application/PartnerApplicationService.java").read_text(encoding="utf-8")
        for token in (
            "partnerCatalogueEnabled", "partnerLimit", "hasIdentityTokenCollision", "existsByCode",
            "IDEMPOTENCY_CONFLICT", "VERSION_CONFLICT", "itam.partner.created.v1",
            "itam.partner.authorized.v1", "itam.partner.suspended.v1",
        ):
            self.assertIn(token, source)
        self.assertNotIn("itam.partner.submit", source)

    def test_http_routes_are_controller_scoped_and_enforce_real_organization_scope(self) -> None:
        requirement = (self.SERVER / "main/io/infranexum/server/identityaccess/AuthorizationRequirement.java").read_text(encoding="utf-8")
        rbac_filter = (self.SERVER / "main/io/infranexum/server/identityaccess/RbacAuthorizationFilter.java").read_text(encoding="utf-8")
        advanced = (self.SERVER / "main/io/infranexum/server/identityaccess/AdvancedAuthorizationFilter.java").read_text(encoding="utf-8")
        controller = (self.SERVER / "main/io/infranexum/server/itam/ItamPartnerController.java").read_text(encoding="utf-8")
        guard = (self.SERVER / "main/io/infranexum/server/identityaccess/ScopedAuthorizationGuard.java").read_text(encoding="utf-8")
        self.assertIn("CONTROLLER_SCOPED", requirement)
        self.assertIn('/api/v1/itam/partners', requirement)
        self.assertIn("DEFERRED_TO_CONTROLLER", rbac_filter)
        self.assertIn("CONTROLLER_SCOPED", advanced)
        self.assertGreaterEqual(controller.count("authorization.require("), 2)
        self.assertIn("AuthorizationScope.organization", controller)
        self.assertIn("PolicyDecisionService", guard)
        self.assertIn("REQUIRE_JUSTIFICATION", guard)

    def test_openapi_exposes_only_source_supported_surface_with_governance_metadata(self) -> None:
        spec = yaml.safe_load((self.SERVER / "resources/openapi/itam-partners.yaml").read_text(encoding="utf-8"))
        operations = []
        for item in spec["paths"].values():
            operations.extend(value for key, value in item.items() if key.lower() in {"get", "post", "put", "patch", "delete"})
        self.assertEqual(5, len(operations))
        self.assertEqual(5, len({operation["operationId"] for operation in operations}))
        for operation in operations:
            self.assertEqual("itam.partners", operation["x-infranexum-capability"])
            self.assertTrue(operation["x-infranexum-permission"].startswith("itam.partner."))
        self.assertEqual(
            {"itam.partner.created.v1", "itam.partner.authorized.v1", "itam.partner.suspended.v1"},
            set(spec["x-infranexum-published-events"]),
        )
        self.assertNotIn("/api/v1/itam/manufacturers", json.dumps(spec))

    def test_rbac_cli_web_and_capability_share_the_same_boundary(self) -> None:
        permissions = (self.ROOT / "src/components/domains/identity-access/main/io/infranexum/identity/access/domain/PermissionCodes.java").read_text(encoding="utf-8")
        cli = (self.SERVER / "main/io/infranexum/server/itam/cli/ItamPartnerCli.java").read_text(encoding="utf-8")
        web = (self.WEB / "public/assets/itam-partners.mjs").read_text(encoding="utf-8")
        config = (self.WEB / "runtime/config.mjs").read_text(encoding="utf-8")
        catalog = (self.ROOT / "src/components/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv").read_text(encoding="utf-8")
        for permission in (
            "itam.partner.read", "itam.partner.create", "itam.partner.update", "itam.partner.approve",
            "itam.partner.suspend", "itam.audit.read",
        ):
            self.assertIn(permission, permissions)
        self.assertIn('"password-file"', cli)
        self.assertIn('args.flag("dry-run")', cli)
        self.assertIn("configuration.itamPartnersEnabled !== true", web)
        self.assertIn("Idempotency-Key", web)
        self.assertIn("If-Match", web)
        self.assertIn("INFRANEXUM_WEB_ITAM_PARTNERS_ENABLED", config)
        self.assertIn("itam.partners,lite;pro;enterprise,server", catalog)

    def test_web_partner_form_uses_structured_contacts_and_iso_alpha2_country_catalogue(self) -> None:
        workspace = (self.WEB / "public/assets/itam-workspace.mjs").read_text(encoding="utf-8")
        countries = (self.WEB / "public/assets/country-catalog.mjs").read_text(encoding="utf-8")
        stable = (self.WEB / "public/assets/stable-select.mjs").read_text(encoding="utf-8")
        self.assertIn('name="countryCode" class="form-select" data-inx-country-select', workspace)
        self.assertNotIn("jsonField('itam-partner-contacts'", workspace)
        for field in ("contactName", "contactEmail", "contactPhone", "contactUri"):
            self.assertIn(f'name="{field}"', workspace)
        self.assertIn("serializePartnerContacts", workspace)
        self.assertIn("COUNTRY_CATALOG", countries)
        self.assertIn("Object.freeze", countries)
        self.assertIn("OPTGROUP", stable)
        self.assertIn("inx-select-group", stable)

    def test_partner_validity_is_a_calendar_period_with_fail_closed_ordering(self) -> None:
        workspace = (self.WEB / "public/assets/itam-workspace.mjs").read_text(encoding="utf-8")
        temporal = (self.WEB / "public/assets/temporal-picker.mjs").read_text(encoding="utf-8")
        partner = (self.ITAM / "main/io/infranexum/itam/partner/domain/Partner.java").read_text(encoding="utf-8")
        self.assertIn('name="validFrom" type="date" data-inx-temporal="date"', workspace)
        self.assertIn('name="validUntil" type="date" data-inx-temporal="date"', workspace)
        self.assertIn("['validFrom', 'validUntil']", temporal)
        self.assertIn("end.min = startValue", temporal)
        self.assertIn("start.max = endValue", temporal)
        self.assertIn("validUntil.isBefore(validFrom)", partner)



if __name__ == "__main__":
    unittest.main()
