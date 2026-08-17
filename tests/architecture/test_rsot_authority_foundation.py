"""Architecture regressions for PGM-04-E02 remediation and PGM-06-E01 RSOT foundation."""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


class RsotAuthorityFoundationArchitectureTest(unittest.TestCase):
    """Keep RSOT isolated, authoritative only where approved, and ready for the later IAM PDP."""

    ROOT = Path(__file__).resolve().parents[2]
    RSOT = ROOT / "src/components/domains/rsot"
    IAM = ROOT / "src/components/domains/identity-access"
    SERVER_IAM = ROOT / "src/applications/server/main/io/infranexum/server/identityaccess"

    def test_rsot_is_a_first_class_domain_module_with_only_core_contract_dependency(self) -> None:
        pom = (self.ROOT / "pom.xml").read_text(encoding="utf-8")
        module_pom = (self.RSOT / "pom.xml").read_text(encoding="utf-8")
        manifest = json.loads((self.RSOT / "MANIFEST.json").read_text(encoding="utf-8"))
        self.assertIn("<module>src/components/domains/rsot</module>", pom)
        self.assertEqual("components.domains.rsot", manifest["id"])
        self.assertEqual("team.data-rsot", manifest["owner"])
        self.assertIn("PGM-06-E01", manifest["source_baseline"])
        self.assertIn("infranexum-core-contracts", module_pom)
        for forbidden in ("domain-organization", "domain-identity-access", "adapters-jdbc", "spring"):
            self.assertNotIn(forbidden, module_pom.lower())

    def test_rsot_domain_has_no_import_or_storage_dependency_on_other_bounded_contexts(self) -> None:
        java = "\n".join(path.read_text(encoding="utf-8") for path in self.RSOT.rglob("*.java"))
        for forbidden in (
            "io.infranexum.organization.",
            "io.infranexum.identity.",
            "io.infranexum.adapters.",
            "java.sql.",
            "javax.sql.",
            "org.springframework.",
        ):
            self.assertNotIn(forbidden, java)

    def test_initial_authority_matrix_and_context_map_are_exact_and_no_direct_write_is_possible(self) -> None:
        governance = (self.RSOT / "main/io/infranexum/rsot/domain/InitialRsotGovernance.java").read_text(encoding="utf-8")
        self.assertEqual(9, len(re.findall(r"\brow\(\d+,", governance)))
        self.assertEqual(10, len(re.findall(r"\bcontext\(\d+,", governance)))
        self.assertIn('"Organisation, subdivision", "Organisation"', governance)
        self.assertIn('"Politique de qualité", "Governance/RSOT"', governance)
        self.assertIn('"Core Contracts/Compatibility", "registre de schémas"', governance)
        self.assertIn("new ContextRelationship(position, provider, contribution, false)", governance)

    def test_attribute_policy_has_all_normative_fields_and_bounded_wildcard_guard(self) -> None:
        source = (self.RSOT / "main/io/infranexum/rsot/domain/AttributeAuthorityPolicy.java").read_text(encoding="utf-8")
        for field in (
            "String objectType",
            "String attributePath",
            "AuthorityContext authorityContext",
            "List<AuthorityContext> sourcePriority",
            "Instant effectiveFrom",
            "Instant effectiveUntil",
            "String policyVersion",
            "String approvalRef",
        ):
            self.assertIn(field, source)
        self.assertIn('wildcards > 1', source)
        self.assertIn('!normalized.endsWith(".*")', source)
        self.assertIn('normalized.length() <= 2', source)

    def test_authority_resolution_fails_closed_on_missing_or_ambiguous_policy(self) -> None:
        source = (self.RSOT / "main/io/infranexum/rsot/application/RsotAuthorityService.java").read_text(encoding="utf-8")
        self.assertIn('"RSOT_AUTHORITY_NOT_CONFIGURED"', source)
        self.assertIn('"RSOT_AUTHORITY_AMBIGUOUS"', source)
        self.assertIn("matches.size() != 1", source)

    def test_canonical_consumer_reads_are_lifecycle_gated(self) -> None:
        status = (self.RSOT / "main/io/infranexum/rsot/domain/CanonicalObjectStatus.java").read_text(encoding="utf-8")
        query = (self.RSOT / "main/io/infranexum/rsot/application/RsotQueryService.java").read_text(encoding="utf-8")
        self.assertIn("this == VALIDATED || this == RECONCILED", status)
        self.assertIn("consumerReadable()", query)
        self.assertIn('"RSOT_CANONICAL_OBJECT_NOT_READABLE"', query)

    def test_iam_owns_a_public_weak_reference_port_instead_of_importing_organization(self) -> None:
        port = (self.IAM / "main/io/infranexum/identity/access/ports/OrganizationScopeReferencePort.java").read_text(encoding="utf-8")
        service = (self.IAM / "main/io/infranexum/identity/access/application/IdentityAccessAdminService.java").read_text(encoding="utf-8")
        all_iam_java = "\n".join(path.read_text(encoding="utf-8") for path in self.IAM.rglob("*.java"))
        self.assertIn("organizationExists", port)
        self.assertIn("subdivisionExists", port)
        self.assertIn("requireOrganizationScope", service)
        self.assertIn('"IAM_ORGANIZATION_NOT_FOUND"', service)
        self.assertIn('"IAM_SUBDIVISION_NOT_FOUND"', service)
        self.assertNotIn("io.infranexum.organization.", all_iam_java)

    def test_organization_reference_adapter_exists_only_at_server_composition_boundary(self) -> None:
        adapter = (self.SERVER_IAM / "OrganizationScopeReferenceAdapter.java").read_text(encoding="utf-8")
        runtime = (self.SERVER_IAM / "IdentityAccessRuntimeConfiguration.java").read_text(encoding="utf-8")
        self.assertIn("implements OrganizationScopeReferencePort", adapter)
        self.assertIn("OrganizationRepository", adapter)
        self.assertIn("SubdivisionRepository", adapter)
        self.assertIn("OrganizationScopeReferencePort identityAccessOrganizationScopeReferences", runtime)

    def test_rsot_jdbc_adapter_reads_only_rsot_owned_tables(self) -> None:
        source = (
            self.ROOT
            / "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcRsotRepository.java"
        ).read_text(encoding="utf-8")
        self.assertIn('"infranexum_rsot." + logicalName', source)
        self.assertIn('"INFRANEXUM_RSOT_" + logicalName', source)
        self.assertNotIn("infranexum_org", source.lower())
        self.assertNotIn("infranexum_iam", source.lower())

    def test_architecture_policy_registers_rsot_manifest(self) -> None:
        policy = json.loads((self.ROOT / "validation/architecture/policy.json").read_text(encoding="utf-8"))
        self.assertIn("components/domains/rsot", policy["required_manifest_paths"])


if __name__ == "__main__":
    unittest.main()
