from __future__ import annotations

import contextlib
import io
import json
import runpy
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from validation.entitlements.checker import EntitlementChecker
from validation.entitlements.cli import main as cli_main

SOURCE = Path(__file__).resolve().parents[2]
FILES = (
    "pom.xml",
    "Makefile",
    ".github/workflows/foundation.yml",
    "validation/architecture/policy.json",
    "src/components/core/capabilities/main/io/infranexum/core/capabilities/ActivationState.java",
    "src/components/core/entitlements/MANIFEST.json",
    "src/components/core/entitlements/pom.xml",
    "src/components/core/entitlements/resources/io/infranexum/core/entitlements/activation-manifest.schema.json",
    "src/components/core/entitlements/resources/io/infranexum/core/entitlements/entitlement-contract-pack.json",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/ActivationManifestPayload.java",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/ActivationManifestVerifier.java",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/LiteEvaluationPolicy.java",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/TrustedTimeGuard.java",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/IntegrityProof.java",
    "src/distribution/migrations/0004-core-entitlements/migration.yaml",
    "src/distribution/migrations/0004-core-entitlements/postgresql.sql",
    "src/distribution/migrations/0004-core-entitlements/oracle.sql",
    "src/distribution/migrations/0004-core-entitlements/logical-model.json",
    "src/distribution/migrations/0004-core-entitlements/verify.sql.yaml",
    "src/applications/server/pom.xml",
    "src/applications/server/MANIFEST.json",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/EntitlementGuard.java",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/EntitlementAccessException.java",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/EntitlementRuntimeAuthority.java",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/EntitlementRuntimeStatus.java",
    "src/components/core/entitlements/main/io/infranexum/core/entitlements/EntitlementRuntimeRepository.java",
    "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcActivationOperationalRepository.java",
    "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/FileIntegrityProofStore.java",
    "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcRevocationRegistry.java",
    "src/applications/server/main/io/infranexum/server/platform/entitlements/ActivationRuntimeConfiguration.java",
    "src/applications/server/main/io/infranexum/server/platform/entitlements/ActivationRuntimeProperties.java",
    "src/applications/server/main/io/infranexum/server/platform/entitlements/EntitlementWebServerStartupGuard.java",
    "src/applications/server/main/io/infranexum/server/platform/entitlements/EntitlementMutationInterceptor.java",
    "src/applications/server/main/io/infranexum/server/platform/entitlements/EvaluationStatusController.java",
    "src/applications/server/main/io/infranexum/server/platform/entitlements/EntitlementExceptionHandler.java",
    "src/applications/server/resources/application.yaml",
    "src/applications/server/resources/contracts/activation-trust-store.schema.json",
    "src/applications/server/resources/openapi/platform-entitlements.yaml",
)


class EntitlementCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repository"
        self.root.mkdir()
        for relative in FILES:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def ids(self) -> set[str]:
        return {item.check_id for item in EntitlementChecker(self.root).run()}

    def reset(self, relative: str) -> None:
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SOURCE / relative, target)

    def mutate(self, relative: str, old: str, new: str = "") -> None:
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1), encoding="utf-8")

    def test_reference_contract_is_valid(self) -> None:
        self.assertEqual(set(), self.ids())

    def test_missing_files_are_reported(self) -> None:
        for relative in (FILES[5], FILES[7], FILES[9], FILES[10], FILES[11], FILES[12], FILES[14], FILES[15], FILES[21], FILES[22]):
            path = self.root / relative
            saved = path.read_bytes()
            path.unlink()
            self.assertIn("CHECK-ENT-FILES-001", self.ids())
            path.write_bytes(saved)

    def test_schema_enforces_paid_profiles_grace_and_required_fields(self) -> None:
        path = self.root / FILES[7]
        payload = json.loads(path.read_text())
        payload["properties"]["profile"]["enum"] = ["lite", "pro"]
        payload["properties"]["grace_period_days"]["const"] = 29
        payload["required"].remove("signature")
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assertTrue({"CHECK-ENT-SCHEMA-002", "CHECK-ENT-SCHEMA-003", "CHECK-ENT-SCHEMA-004",
                         "CHECK-ENT-SCHEMA-005"} <= self.ids())
        path.write_text("[]", encoding="utf-8")
        self.assertIn("CHECK-ENT-SCHEMA-001", self.ids())
        path.write_text("{", encoding="utf-8")
        self.assertIn("CHECK-ENT-SCHEMA-001", self.ids())

    def test_contract_pack_invariants_are_enforced(self) -> None:
        path = self.root / FILES[8]
        payload = json.loads(path.read_text())
        payload["signature_algorithm"] = "RSA"
        payload["lite_full_days"] = 179
        payload["files"]["activation-manifest.schema.json"] = "0" * 64
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.assertTrue({"CHECK-ENT-PACK-002", "CHECK-ENT-PACK-003"} <= self.ids())
        path.write_text("[]", encoding="utf-8")
        self.assertIn("CHECK-ENT-PACK-001", self.ids())

    def test_java_invariants_and_explicit_clock_are_enforced(self) -> None:
        self.mutate(FILES[9], "Lite activation manifests are forbidden")
        self.assertIn("CHECK-ENT-JAVA-006", self.ids())
        self.reset(FILES[9])
        self.mutate(FILES[10], 'Signature.getInstance("Ed25519")')
        self.assertIn("CHECK-ENT-JAVA-006", self.ids())
        self.reset(FILES[10])
        self.mutate(FILES[11], "EVALUATION_DAYS = 180")
        self.assertIn("CHECK-ENT-JAVA-006", self.ids())
        self.reset(FILES[11])
        self.mutate(FILES[12], 'Mac.getInstance("HmacSHA256")')
        self.assertIn("CHECK-ENT-JAVA-006", self.ids())
        self.reset(FILES[12])
        self.mutate(FILES[4], "this == NOT_REQUIRED || ")
        self.assertIn("CHECK-ENT-JAVA-006", self.ids())
        self.reset(FILES[4])
        self.mutate(FILES[21], "requireMutation(LiteEvaluation")
        self.assertIn("CHECK-ENT-JAVA-006", self.ids())
        self.reset(FILES[21])
        with (self.root / FILES[13]).open("a", encoding="utf-8") as stream:
            stream.write("\n// Instant.now()\n")
        self.assertIn("CHECK-ENT-JAVA-008", self.ids())

    def test_migration_objects_constraints_and_model_are_enforced(self) -> None:
        path = self.root / FILES[15]
        path.write_text(path.read_text(encoding="utf-8").replace("core_activation_revocation", "removed_revocation"), encoding="utf-8")
        self.assertIn("CHECK-ENT-MIG-004", self.ids())
        self.reset(FILES[15])
        path = self.root / FILES[16]
        path.write_text(path.read_text(encoding="utf-8").replace("core_activation_manifest", "removed_manifest"), encoding="utf-8")
        self.assertIn("CHECK-ENT-MIG-004", self.ids())
        self.reset(FILES[16])
        self.mutate(FILES[15], "'LITE','PRO','ENTERPRISE'")
        self.assertIn("CHECK-ENT-MIG-005", self.ids())
        self.reset(FILES[15])
        self.mutate(FILES[16], "grace_period_days = 30")
        self.assertIn("CHECK-ENT-MIG-006", self.ids())
        self.reset(FILES[16])
        model = self.root / FILES[17]
        payload = json.loads(model.read_text())
        payload["objects"].pop()
        model.write_text(json.dumps(payload), encoding="utf-8")
        self.assertIn("CHECK-ENT-MIG-007", self.ids())
        model.write_text("[]", encoding="utf-8")
        self.assertIn("CHECK-ENT-MIG-003", self.ids())

    def test_authoritative_runtime_wiring_is_enforced(self) -> None:
        mutations = (
            (FILES[23], "initializeAndRequireStartup"),
            (FILES[24], "Set<String> entitledCapabilities,"),
            (FILES[26], "implements EntitlementRuntimeRepository"),
            (FILES[27], "StandardCopyOption.ATOMIC_MOVE"),
            (FILES[29], "authoritative entitlements require PostgreSQL or Oracle persistence"),
            (FILES[31], "WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>"),
            (FILES[32], "authority.requireMutation()"),
            (FILES[33], '@RequestMapping("/api/v1/platform/evaluation")'),
            (FILES[34], "problems.response"),
        )
        for relative, token in mutations:
            self.mutate(relative, token)
            self.assertIn("CHECK-ENT-RUNTIME-002", self.ids())
            self.reset(relative)
        self.mutate(FILES[35], "INFRANEXUM_ENTITLEMENTS_ENABLED:true")
        self.assertIn("CHECK-ENT-RUNTIME-004", self.ids())
        self.reset(FILES[35])
        self.mutate(FILES[19], "spring-boot-starter-jdbc")
        self.assertIn("CHECK-ENT-RUNTIME-006", self.ids())
        self.reset(FILES[19])

    def test_reactor_server_ci_and_policy_wiring_are_enforced(self) -> None:
        mutations = (
            (FILES[0], "<module>src/components/core/entitlements</module>"),
            (FILES[19], "infranexum-core-entitlements"),
            (FILES[20], "components.core.entitlements"),
            (FILES[3], "components/core/entitlements"),
            (FILES[1], "entitlements-test"),
            (FILES[1], "java-entitlements-smoke"),
            (FILES[1], "java-entitlement-runtime-smoke"),
            (FILES[2], "entitlements-test"),
            (FILES[2], "java-entitlement-runtime-smoke"),
            (FILES[2], "make postgresql-test-schema"),
        )
        for relative, token in mutations:
            path = self.root / relative
            text = path.read_text(encoding="utf-8")
            self.assertIn(token, text)
            path.write_text(text.replace(token, ""), encoding="utf-8")
            self.assertIn("CHECK-ENT-WIRE-002", self.ids())
            self.reset(relative)

    def test_key_material_is_forbidden(self) -> None:
        key = self.root / "src/components/core/entitlements/private.key"
        key.write_text("not-a-real-key", encoding="utf-8")
        self.assertIn("CHECK-ENT-KEY-001", self.ids())

    def test_unreadable_inputs_external_paths_and_cli_are_covered(self) -> None:
        (self.root / FILES[10]).unlink()
        self.assertTrue({"CHECK-ENT-FILES-001", "CHECK-ENT-JAVA-002"} <= self.ids())
        self.reset(FILES[10])
        checker = EntitlementChecker(self.root)
        outside = self.root.parent / "outside"
        checker._add("TEST", outside, "outside")
        self.assertEqual(outside.resolve().as_posix(), checker.violations[0].path)
        with patch.object(Path, "read_text", side_effect=OSError("denied")):
            self.assertIn("CHECK-ENT-SCHEMA-001", self.ids())

        report = self.root / "reports/entitlements.json"
        with patch.object(sys, "argv", ["entitlements", "--root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, cli_main())
        self.assertEqual(0, json.loads(report.read_text())["violation_count"])

        self.mutate(FILES[7], '"pro", "enterprise"', '"lite", "pro"')
        with patch.object(sys, "argv", ["entitlements", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, cli_main())
        with patch.object(sys, "argv", ["entitlements", "--root", str(SOURCE)]):
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as caught:
                    runpy.run_path(str(SOURCE / "validation/entitlements/cli.py"), run_name="__main__")
        self.assertEqual(0, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
