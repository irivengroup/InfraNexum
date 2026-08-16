"""Architecture regressions for PGM-06-E03 Core Schema Registry."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml


class RsotSchemaRegistryArchitectureTest(unittest.TestCase):
    """Keep schema governance declarative, capability-gated and aligned across surfaces."""

    ROOT = Path(__file__).resolve().parents[2]
    COMPAT = ROOT / "src/components/core/compatibility"
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web"

    def test_core_compatibility_is_first_class_and_dependencies_are_declared(self) -> None:
        root_pom = (self.ROOT / "pom.xml").read_text(encoding="utf-8")
        manifest = json.loads((self.COMPAT / "MANIFEST.json").read_text(encoding="utf-8"))
        server_manifest = json.loads((self.SERVER / "MANIFEST.json").read_text(encoding="utf-8"))
        jdbc_manifest = json.loads((self.ROOT / "src/components/adapters/jdbc/MANIFEST.json").read_text(encoding="utf-8"))
        policy = json.loads((self.ROOT / "validation/architecture/policy.json").read_text(encoding="utf-8"))
        self.assertIn("<module>src/components/core/compatibility</module>", root_pom)
        self.assertEqual("components.core.compatibility", manifest["id"])
        self.assertIn("PGM-06-E03", manifest["source_baseline"])
        self.assertIn("components.core.compatibility", server_manifest["dependencies"])
        self.assertIn("components.core.compatibility", jdbc_manifest["dependencies"])
        self.assertIn("components/core/compatibility", json.dumps(policy))

    def test_registry_service_enforces_capability_lifecycle_events_and_audit(self) -> None:
        source = (self.COMPAT / "main/io/infranexum/core/compatibility/SchemaRegistryService.java").read_text(encoding="utf-8")
        public_methods = (
            "createSchema", "updateDraft", "previewCompatibility", "publish", "deprecate",
            "getSchema", "listSchemas", "createProfile", "publishProfile", "deprecateProfile",
            "getProfile", "listProfiles",
        )
        self.assertGreaterEqual(source.count("features.requireAvailable();"), len(public_methods))
        for event in (
            "rsot.schema.created.v1", "rsot.schema.updated.v1", "rsot.schema.published.v1",
            "rsot.schema.deprecated.v1", "rsot.schema.profile.created.v1",
            "rsot.schema.profile.published.v1", "rsot.schema.profile.deprecated.v1",
        ):
            self.assertIn(event, source)
        self.assertIn("AuditScope.platform()", source)
        self.assertIn("SCHEMA_REVISION_CONFLICT", source)
        self.assertIn("SCHEMA_PROFILE_MEMBER_NOT_PUBLISHED", source)

    def test_json_schema_guard_is_fail_closed_and_extensions_cannot_execute_or_escape(self) -> None:
        source = (self.SERVER / "main/io/infranexum/server/rsot/JacksonSchemaDefinitionInspector.java").read_text(encoding="utf-8")
        for token in ('"script"', '"shell"', '"python"', '"javascript"', '"network"', '"file"', '"process"'):
            self.assertIn(token, source)
        self.assertIn('"$ref".equals(key)', source)
        self.assertIn('value.textValue().startsWith("#")', source)
        self.assertIn("CompatibilityVerdict.BREAKING", source)
        self.assertIn("CompatibilityVerdict.INDETERMINATE", source)
        self.assertIn("optional property became required", source)
        self.assertIn("property removed", source)
        self.assertIn("enum value removed", source)

    def test_rbac_permissions_and_route_requirements_are_exact(self) -> None:
        permission_source = (self.ROOT / "src/components/domains/identity-access/main/io/infranexum/identity/access/domain/PermissionCodes.java").read_text(encoding="utf-8")
        requirements = (self.SERVER / "main/io/infranexum/server/identityaccess/AuthorizationRequirement.java").read_text(encoding="utf-8")
        for permission in (
            "rsot.schema.create", "rsot.schema.read", "rsot.schema.update",
            "rsot.schema.deprecate", "rsot.schema.publish", "rsot.audit",
        ):
            self.assertIn(permission, permission_source)
        self.assertIn('/api/v1/rsot/schemas', requirements)
        self.assertIn('/api/v1/rsot/schema-profiles', requirements)
        self.assertIn("PermissionCodes.RSOT_SCHEMA_PUBLISH", requirements)
        self.assertIn("PermissionCodes.RSOT_SCHEMA_DEPRECATE", requirements)

    def test_openapi_covers_controller_operations_with_capability_and_permission_metadata(self) -> None:
        spec = yaml.safe_load((self.SERVER / "resources/openapi/rsot-schema-registry.yaml").read_text(encoding="utf-8"))
        operations = []
        for path_item in spec["paths"].values():
            for method, operation in path_item.items():
                if method.lower() in {"get", "post", "patch", "put", "delete"}:
                    operations.append(operation)
        self.assertEqual(12, len(operations))
        self.assertEqual(12, len({operation["operationId"] for operation in operations}))
        for operation in operations:
            self.assertEqual("rsot.core", operation["x-infranexum-capability"])
            authorization = operation["x-infranexum-permission"]
            self.assertEqual("permission", authorization["mode"])
            self.assertTrue(authorization["code"].startswith("rsot."))
        self.assertIn("rsot.schema.published.v1", spec["x-infranexum-published-events"])

    def test_cli_web_and_runtime_configuration_share_the_same_capability_boundary(self) -> None:
        cli = (self.SERVER / "main/io/infranexum/server/rsot/cli/RsotSchemaCli.java").read_text(encoding="utf-8")
        web = (self.WEB / "public/assets/rsot-schema-registry.mjs").read_text(encoding="utf-8")
        bootstrap = (self.WEB / "public/assets/bootstrap.mjs").read_text(encoding="utf-8")
        config = (self.WEB / "runtime/config.mjs").read_text(encoding="utf-8")
        self.assertIn('"password-file"', cli)
        self.assertIn('"definition-file"', cli)
        self.assertIn('args.flag("dry-run")', cli)
        self.assertIn("CapabilityUnavailableException", cli)
        self.assertIn("configuration.rsotCoreEnabled !== true", web)
        self.assertIn("If-Match", web)
        self.assertIn("rsotCoreEnabled is invalid", bootstrap)
        self.assertIn("INFRANEXUM_WEB_RSOT_CORE_ENABLED", config)

    def test_rsot_core_capability_is_lite_baseline_and_installed_by_default(self) -> None:
        rows = (self.ROOT / "src/components/core/capabilities/resources/io/infranexum/core/capabilities/capability-catalog.csv").read_text(encoding="utf-8")
        app = yaml.safe_load((self.SERVER / "resources/application.yaml").read_text(encoding="utf-8"))
        self.assertIn("rsot.core,lite;pro;enterprise,server", rows)
        self.assertIn("rsot.core", json.dumps(app))
        checker = (self.ROOT / "validation/capabilities/checker.py").read_text(encoding="utf-8")
        self.assertIn('"rsot.core"', checker)


if __name__ == "__main__":
    unittest.main()
