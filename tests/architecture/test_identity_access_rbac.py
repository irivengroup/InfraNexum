"""Architecture and public-contract regressions for PGM-03-E03 RBAC."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

import yaml


class IdentityAccessRbacArchitectureTest(unittest.TestCase):
    """Keep the IAM RBAC surface deny-by-default, scoped and OpenAPI-aligned."""

    ROOT = Path(__file__).resolve().parents[2]
    SERVER = ROOT / "src/applications/server/main/io/infranexum/server/identityaccess"
    SPEC = ROOT / "src/applications/server/resources/openapi/identity-access-rbac.yaml"

    @classmethod
    def setUpClass(cls) -> None:
        cls.openapi = yaml.safe_load(cls.SPEC.read_text(encoding="utf-8"))
        cls.controller = (cls.SERVER / "IdentityAccessController.java").read_text(encoding="utf-8")
        cls.requirements = (cls.SERVER / "AuthorizationRequirement.java").read_text(encoding="utf-8")

    def test_identity_access_is_declared_as_a_domain_module(self) -> None:
        pom = (self.ROOT / "pom.xml").read_text(encoding="utf-8")
        manifest = (
            self.ROOT / "src/components/domains/identity-access/MANIFEST.json"
        ).read_text(encoding="utf-8")
        self.assertIn("src/components/domains/identity-access", pom)
        self.assertIn('"status": "implemented"', manifest)

    def test_activation_manifest_codec_uses_current_jackson_string_accessor(self) -> None:
        """Prevent Jackson 3 deprecation warnings from the strict activation-manifest codec."""
        codec = (
            self.ROOT
            / "src/applications/server/main/io/infranexum/server/platform/entitlements/ActivationManifestJsonCodec.java"
        ).read_text(encoding="utf-8")
        self.assertIn(".asString()", codec)
        self.assertNotIn(".asText()", codec)

    def test_server_boot_repackage_has_an_explicit_canonical_main_class(self) -> None:
        """Prevent Spring Boot repackage from becoming ambiguous when CLI mains coexist."""
        pom = (self.ROOT / "src/applications/server/pom.xml").read_text(encoding="utf-8")
        self.assertIn(
            "<mainClass>io.infranexum.server.InfraNexumServerApplication</mainClass>",
            pom,
        )
        self.assertIn("class IdentityAccessCliApplication", (
            self.SERVER / "cli/IdentityAccessCliApplication.java"
        ).read_text(encoding="utf-8"))

    def test_controller_imports_permission_catalogue_used_for_nested_group_authorization(self) -> None:
        """Prevent a Docker/JDK build-only failure from an unresolved PermissionCodes symbol."""
        self.assertIn(
            "import io.infranexum.identity.access.domain.PermissionCodes;",
            self.controller,
        )
        self.assertIn("PermissionCodes.GROUP_ADD_GROUP", self.controller)
        self.assertIn("PermissionCodes.GROUP_REMOVE_GROUP", self.controller)

    def test_openapi_operations_match_controller_routes_exactly(self) -> None:
        controller_routes = {
            (match.group(1).upper(), match.group(2))
            for match in re.finditer(
                r"@(Get|Post|Patch|Delete)Mapping\(\"([^\"]+)\"\)", self.controller
            )
        }
        openapi_routes = {
            (method.upper(), path)
            for path, item in self.openapi["paths"].items()
            for method in item
            if method.lower() in {"get", "post", "patch", "delete"}
        }
        self.assertEqual(controller_routes, openapi_routes)
        self.assertEqual(34, len(openapi_routes))

    def test_openapi_is_rbac_secured_tagged_and_operation_ids_are_unique(self) -> None:
        self.assertEqual("3.1.0", self.openapi["openapi"])
        self.assertEqual("2.0.0-alpha.0.107", self.openapi["info"]["version"])
        self.assertTrue(self.openapi["x-infranexum-rbac-deny-by-default"])
        self.assertEqual([{"LocalSessionCookie": []}], self.openapi["security"])
        self.assertEqual(
            "INX_SESSION",
            self.openapi["components"]["securitySchemes"]["LocalSessionCookie"]["name"],
        )
        forbidden = {"Default", "Misc", "Utils", "Helpers", "Common", "Divers", "Autres", "Général"}
        tags = {tag["name"] for tag in self.openapi["tags"]}
        self.assertFalse(tags & forbidden)
        self.assertTrue(all(tag.startswith("IAM / Access / ") for tag in tags))

        operation_ids: list[str] = []
        for item in self.openapi["paths"].values():
            for method, operation in item.items():
                if method.lower() not in {"get", "post", "patch", "delete"}:
                    continue
                self.assertEqual(1, len(operation["tags"]))
                self.assertIn(operation["tags"][0], tags)
                self.assertTrue(operation["summary"].strip())
                operation_ids.append(operation["operationId"])
        self.assertEqual(len(operation_ids), len(set(operation_ids)))

    def test_exception_handler_uses_current_spring_422_status_constant(self) -> None:
        """Keep Spring 7 compilation free from the deprecated 422 alias."""
        handler = (self.SERVER / "IdentityAccessExceptionHandler.java").read_text(encoding="utf-8")
        self.assertIn("HttpStatus.UNPROCESSABLE_CONTENT", handler)
        self.assertNotIn("HttpStatus.UNPROCESSABLE_ENTITY", handler)

    def test_every_mutation_documents_csrf_and_authz_failures(self) -> None:
        for path, item in self.openapi["paths"].items():
            for method, operation in item.items():
                if method.lower() not in {"post", "patch", "delete"}:
                    continue
                refs = {
                    parameter.get("$ref")
                    for parameter in operation.get("parameters", [])
                    if isinstance(parameter, dict)
                }
                self.assertIn("#/components/parameters/CsrfToken", refs, f"{method} {path}")
                self.assertIn("401", operation["responses"], f"{method} {path}")
                self.assertIn("403", operation["responses"], f"{method} {path}")

    def test_permission_contract_preserves_normative_catalogue_gap(self) -> None:
        rendered = self.SPEC.read_text(encoding="utf-8")
        self.assertNotIn("organization.read", rendered)
        self.assertIn("organization.subdivision.read", (
            self.ROOT / "src/distribution/migrations/0013-identity-access-rbac-foundation/postgresql.sql"
        ).read_text(encoding="utf-8"))
        self.assertIn("ORGANIZATION_VISIBILITY", self.requirements)
        self.assertIn("PLATFORM_ADMINISTRATOR", self.requirements)
        self.assertIn("UNREGISTERED", self.requirements)

    def test_local_auth_and_organization_contracts_no_longer_claim_pre_rbac_behavior(self) -> None:
        local_auth = yaml.safe_load(
            (self.ROOT / "src/applications/server/resources/openapi/local-auth.yaml").read_text(encoding="utf-8")
        )
        organization = yaml.safe_load(
            (self.ROOT / "src/applications/server/resources/openapi/organization-foundation.yaml").read_text(encoding="utf-8")
        )
        self.assertEqual("2.0.0-alpha.0.107", local_auth["info"]["version"])
        self.assertIn("RBAC PEP", local_auth["info"]["description"])
        self.assertTrue(organization["x-infranexum-rbac-enforced"])
        self.assertNotIn("x-infranexum-pre-iam-local-only", organization)
        self.assertEqual([{"LocalSessionCookie": []}], organization["security"])


if __name__ == "__main__":
    unittest.main()
