from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, order=True)
class EntitlementViolation:
    check_id: str
    path: str
    message: str


class EntitlementChecker:
    """Block drift in activation, Lite lifecycle, trusted-time and persistence contracts."""

    MODULE = Path("src/components/core/entitlements")
    JAVA = MODULE / "main/io/infranexum/core/entitlements"
    RESOURCES = MODULE / "resources/io/infranexum/core/entitlements"
    MIGRATION = Path("src/distribution/migrations/0004-core-entitlements")
    SERVER = Path("src/applications/server/main/io/infranexum/server/platform/entitlements")
    JDBC = Path("src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc")

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.violations: list[EntitlementViolation] = []

    def run(self) -> tuple[EntitlementViolation, ...]:
        self._check_required_files()
        self._check_schema()
        self._check_contract_pack()
        self._check_java_invariants()
        self._check_migration()
        self._check_runtime_integration()
        self._check_wiring()
        self._check_key_material()
        return tuple(sorted(self.violations))

    def _check_required_files(self) -> None:
        required = (
            self.MODULE / "MANIFEST.json",
            self.MODULE / "pom.xml",
            self.RESOURCES / "activation-manifest.schema.json",
            self.RESOURCES / "entitlement-contract-pack.json",
            self.JAVA / "ActivationManifestPayload.java",
            self.JAVA / "ActivationManifestVerifier.java",
            self.JAVA / "LiteEvaluationPolicy.java",
            self.JAVA / "TrustedTimeGuard.java",
            self.JAVA / "IntegrityProof.java",
            self.JAVA / "EntitlementGuard.java",
            self.JAVA / "EntitlementAccessException.java",
            self.JAVA / "EntitlementRuntimeAuthority.java",
            self.JAVA / "EntitlementRuntimeStatus.java",
            self.JAVA / "EntitlementRuntimeRepository.java",
            self.JDBC / "JdbcActivationOperationalRepository.java",
            self.JDBC / "FileIntegrityProofStore.java",
            self.JDBC / "JdbcRevocationRegistry.java",
            self.SERVER / "ActivationRuntimeConfiguration.java",
            self.SERVER / "ActivationRuntimeProperties.java",
            self.SERVER / "EntitlementWebServerStartupGuard.java",
            self.SERVER / "EntitlementMutationInterceptor.java",
            self.SERVER / "EvaluationStatusController.java",
            self.SERVER / "EntitlementExceptionHandler.java",
            Path("src/applications/server/resources/contracts/activation-trust-store.schema.json"),
            Path("src/applications/server/resources/openapi/platform-entitlements.yaml"),
            self.MIGRATION / "migration.yaml",
            self.MIGRATION / "postgresql.sql",
            self.MIGRATION / "oracle.sql",
            self.MIGRATION / "logical-model.json",
            self.MIGRATION / "verify.sql.yaml",
        )
        for relative in required:
            if not (self.root / relative).is_file():
                self._add("CHECK-ENT-FILES-001", self.root / relative, "required entitlement file is missing")

    def _check_schema(self) -> None:
        path = self.root / self.RESOURCES / "activation-manifest.schema.json"
        payload = self._json(path, "CHECK-ENT-SCHEMA-001")
        if payload is None:
            return
        props = payload.get("properties", {})
        profile = props.get("profile", {}) if isinstance(props, dict) else {}
        grace = props.get("grace_period_days", {}) if isinstance(props, dict) else {}
        required = payload.get("required", [])
        if profile.get("enum") != ["pro", "enterprise"]:
            self._add("CHECK-ENT-SCHEMA-002", path, "activation schema must exclude Lite")
        if grace.get("const") != 30:
            self._add("CHECK-ENT-SCHEMA-003", path, "grace period must be fixed to 30 days")
        mandatory = {"activation_id", "installation", "profile", "allocation_tier", "catalog_version",
                     "host_limit", "capabilities", "quotas", "valid_from", "valid_until", "sequence",
                     "key_id", "signature"}
        if not isinstance(required, list) or not mandatory.issubset(set(required)):
            self._add("CHECK-ENT-SCHEMA-004", path, "required activation fields are incomplete")
        text = json.dumps(payload, sort_keys=True)
        if '"lite"' in text.lower():
            self._add("CHECK-ENT-SCHEMA-005", path, "Lite appears in the activation schema")

    def _check_contract_pack(self) -> None:
        path = self.root / self.RESOURCES / "entitlement-contract-pack.json"
        payload = self._json(path, "CHECK-ENT-PACK-001")
        if payload is None:
            return
        expected = {
            "schema": "infranexum.entitlement-contract-pack/v1",
            "activation_manifest_schema": "infranexum.activation-manifest/v2",
            "signature_algorithm": "Ed25519",
            "lite_full_days": 180,
            "lite_conversion_days": 30,
            "paid_grace_days": 30,
            "profiles_with_manifests": ["pro", "enterprise"],
            "lite_manifest_forbidden": True,
        }
        for key, value in expected.items():
            if payload.get(key) != value:
                self._add("CHECK-ENT-PACK-002", path, f"invalid contract-pack field {key}")
        files = payload.get("files")
        schema_path = self.root / self.RESOURCES / "activation-manifest.schema.json"
        expected_hash = hashlib.sha256(schema_path.read_bytes()).hexdigest() if schema_path.is_file() else None
        if not isinstance(files, dict) or files.get("activation-manifest.schema.json") != expected_hash:
            self._add("CHECK-ENT-PACK-003", path, "activation schema checksum is missing or inconsistent")

    def _check_java_invariants(self) -> None:
        files = {
            "payload": self._text(self.root / self.JAVA / "ActivationManifestPayload.java", "CHECK-ENT-JAVA-001"),
            "verifier": self._text(self.root / self.JAVA / "ActivationManifestVerifier.java", "CHECK-ENT-JAVA-002"),
            "lite": self._text(self.root / self.JAVA / "LiteEvaluationPolicy.java", "CHECK-ENT-JAVA-003"),
            "time": self._text(self.root / self.JAVA / "TrustedTimeGuard.java", "CHECK-ENT-JAVA-004"),
            "activation": self._text(self.root / "src/components/core/capabilities/main/io/infranexum/core/capabilities/ActivationState.java", "CHECK-ENT-JAVA-005"),
            "guard": self._text(self.root / self.JAVA / "EntitlementGuard.java", "CHECK-ENT-JAVA-009"),
        }
        required = {
            "payload": ("Lite activation manifests are forbidden", "gracePeriodDays != 30", "canonicalBytes()"),
            "verifier": ("Signature.getInstance(\"Ed25519\")", "host_limit must equal rsot.managed_hosts.max",
                         "acceptedSequence().accepts", "isActivationRevoked", "HARD_STOPPED"),
            "lite": ("EVALUATION_DAYS = 180", "CONVERSION_DAYS = 30", "now.isBefore(hardStopAt)"),
            "time": ("Mac.getInstance(\"HmacSHA256\")", "MessageDigest.isEqual", "database and independent temporal evidence diverge",
                     "current time is before the last reliable observation"),
            "activation": ("this == NOT_REQUIRED || this == ACTIVE || this == GRACE",),
            "guard": ("requireServiceStartup(LiteEvaluation", "requireMutation(LiteEvaluation",
                      "requireServiceStartup(ActivationVerificationResult",
                      "requireMutation(ActivationVerificationResult",
                      "LITE_CONVERSION_REQUIRED", "ACTIVATION_EXPIRED"),
        }
        for name, tokens in required.items():
            text = files[name]
            if text is None:
                continue
            for token in tokens:
                if token not in text:
                    self._add("CHECK-ENT-JAVA-006", self.root / self.JAVA, f"missing entitlement invariant: {token}")
        for path in sorted((self.root / self.JAVA).glob("*.java")):
            text = self._text(path, "CHECK-ENT-JAVA-007")
            if text is not None and ("System.currentTimeMillis(" in text or "Instant.now(" in text):
                self._add("CHECK-ENT-JAVA-008", path, "core entitlements must receive trusted time explicitly")

    def _check_migration(self) -> None:
        pg = self._text(self.root / self.MIGRATION / "postgresql.sql", "CHECK-ENT-MIG-001")
        ora = self._text(self.root / self.MIGRATION / "oracle.sql", "CHECK-ENT-MIG-002")
        model = self._json(self.root / self.MIGRATION / "logical-model.json", "CHECK-ENT-MIG-003")
        for text, path in ((pg, self.root / self.MIGRATION / "postgresql.sql"),
                           (ora, self.root / self.MIGRATION / "oracle.sql")):
            if text is None:
                continue
            for token in ("core_installation_identity", "core_entitlement_state", "core_entitlement_integrity_proof",
                          "core_activation_manifest", "core_activation_revocation", "grace_period_days",
                          "max_activation_sequence"):
                if token.lower() not in text.lower():
                    self._add("CHECK-ENT-MIG-004", path, f"missing entitlement persistence object: {token}")
            normalized = text.replace(" ", "")
            if ("'LITE','PRO','ENTERPRISE'" not in normalized
                    and "''LITE'',''PRO'',''ENTERPRISE''" not in normalized):
                self._add("CHECK-ENT-MIG-005", path, "profile constraint is missing")
            if "grace_period_days = 30" not in text:
                self._add("CHECK-ENT-MIG-006", path, "fixed 30-day grace constraint is missing")
        if model is not None:
            names = {obj.get("logical_name") for obj in model.get("objects", []) if isinstance(obj, dict)}
            expected = {"core.installation_identity", "core.entitlement_state", "core.entitlement_integrity_proof",
                        "core.activation_manifest", "core.activation_revocation"}
            if names != expected:
                self._add("CHECK-ENT-MIG-007", self.root / self.MIGRATION / "logical-model.json",
                          f"logical model differs from expected objects: {names}")

    def _check_runtime_integration(self) -> None:
        checks = {
            self.JAVA / "EntitlementRuntimeAuthority.java": (
                "initializeAndRequireStartup", "currentStatus", "requireMutation",
                "independent temporal evidence is missing", "acceptedManifestDocument"),
            self.JAVA / "EntitlementRuntimeStatus.java": (
                "Set<String> entitledCapabilities,", "Map<String, Long> quotaOverrides,",
                "serviceStartupPermitted", "mutationPermitted"),
            self.JDBC / "JdbcActivationOperationalRepository.java": (
                "implements EntitlementRuntimeRepository", "initializeLite", "updateRuntimeState",
                "acceptedManifestDocument"),
            self.JDBC / "FileIntegrityProofStore.java": (
                "StandardCopyOption.ATOMIC_MOVE", "channel.force(true)", "OWNER_READ", "OWNER_WRITE"),
            self.SERVER / "ActivationRuntimeConfiguration.java": (
                "authoritative entitlements require PostgreSQL or Oracle persistence",
                "ActivationImportCoordinator", "EntitlementRuntimeAuthority", "JdbcRevocationRegistry"),
            self.SERVER / "EntitlementWebServerStartupGuard.java": (
                "WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>",
                "initializeAndRequireStartup", "applyEntitlementStatus"),
            self.SERVER / "EntitlementMutationInterceptor.java": (
                'Set.of("POST", "PUT", "PATCH", "DELETE")', "authority.requireMutation()"),
            self.SERVER / "EvaluationStatusController.java": (
                '@RequestMapping("/api/v1/platform/evaluation")', '@GetMapping("/status")',
                "CacheControl.noStore()"),
            self.SERVER / "EntitlementExceptionHandler.java": (
                "MediaType.APPLICATION_PROBLEM_JSON",
                "INFRANEXUM_ENTITLEMENT_RUNTIME_UNAVAILABLE",
                "urn:infranexum:problem:entitlement-access-denied"),
        }
        for relative, tokens in checks.items():
            path = self.root / relative
            text = self._text(path, "CHECK-ENT-RUNTIME-001")
            if text is None:
                continue
            for token in tokens:
                if token not in text:
                    self._add("CHECK-ENT-RUNTIME-002", path, f"missing runtime entitlement invariant: {token}")
        application = self._text(
            self.root / "src/applications/server/resources/application.yaml",
            "CHECK-ENT-RUNTIME-003")
        if application is not None:
            for token in ("INFRANEXUM_ENTITLEMENTS_ENABLED:true", "INFRANEXUM_INTEGRITY_KEY_FILE",
                          "INFRANEXUM_INTEGRITY_PROOF_DIRECTORY", "INFRANEXUM_PERSISTENCE_MODE:POSTGRESQL"):
                if token not in application:
                    self._add("CHECK-ENT-RUNTIME-004", self.root / "src/applications/server/resources/application.yaml",
                              f"missing fail-closed runtime configuration: {token}")
        server_pom = self._text(self.root / "src/applications/server/pom.xml", "CHECK-ENT-RUNTIME-005")
        if server_pom is not None:
            for token in ("spring-boot-starter-jdbc", "org.postgresql", "postgresql"):
                if token not in server_pom:
                    self._add("CHECK-ENT-RUNTIME-006", self.root / "src/applications/server/pom.xml",
                              f"missing runtime persistence dependency: {token}")

    def _check_wiring(self) -> None:
        checks = (
            ("pom.xml", "<module>src/components/core/entitlements</module>"),
            ("src/applications/server/pom.xml", "infranexum-core-entitlements"),
            ("src/applications/server/MANIFEST.json", "components.core.entitlements"),
            ("validation/architecture/policy.json", "components/core/entitlements"),
            ("Makefile", "entitlements-test"),
            ("Makefile", "java-entitlements-smoke"),
            ("Makefile", "java-entitlement-runtime-smoke"),
            (".github/workflows/foundation.yml", "entitlements-test"),
            (".github/workflows/foundation.yml", "java-entitlement-runtime-smoke"),
            (".github/workflows/foundation.yml", "make postgresql-test-schema"),
        )
        for relative, token in checks:
            path = self.root / relative
            text = self._text(path, "CHECK-ENT-WIRE-001")
            if text is not None and token not in text:
                self._add("CHECK-ENT-WIRE-002", path, f"missing entitlement wiring token: {token}")

    def _check_key_material(self) -> None:
        for path in sorted(self.root.rglob("*")):
            if not path.is_file():
                continue
            lowered = path.name.lower()
            if lowered.endswith((".key", ".pem", ".p12", ".pfx")):
                self._add("CHECK-ENT-KEY-001", path, "private or ambiguous key material is forbidden")

    def _json(self, path: Path, check_id: str) -> dict[str, object] | None:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add(check_id, path, f"cannot read JSON: {error}")
            return None
        if not isinstance(value, dict):
            self._add(check_id, path, "JSON root must be an object")
            return None
        return value

    def _text(self, path: Path, check_id: str) -> str | None:
        try:
            return path.read_text(encoding="utf-8")
        except OSError as error:
            self._add(check_id, path, f"cannot read required file: {error}")
            return None

    def _add(self, check_id: str, path: Path, message: str) -> None:
        try:
            rendered = path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            rendered = path.resolve().as_posix()
        self.violations.append(EntitlementViolation(check_id, rendered, message))
