from __future__ import annotations

import csv
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, order=True)
class CapabilityViolation:
    check_id: str
    path: str
    message: str


class CapabilityChecker:
    """Block drift between capability code, catalogues, Server wiring and profile invariants."""

    RESOURCE_ROOT = Path(
        "src/components/core/capabilities/resources/io/infranexum/core/capabilities"
    )
    JAVA_ROOT = Path("src/components/core/capabilities/main/io/infranexum/core/capabilities")

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.violations: list[CapabilityViolation] = []

    def run(self) -> tuple[CapabilityViolation, ...]:
        self._check_required_files()
        self._check_contract_pack()
        self._check_quota_catalogue()
        self._check_capability_catalogue()
        self._check_java_contracts()
        self._check_reactor_and_server()
        self._check_domain_profile_branching()
        return tuple(sorted(self.violations))

    def _check_required_files(self) -> None:
        required = (
            "src/components/core/capabilities/MANIFEST.json",
            "src/components/core/capabilities/pom.xml",
            self.RESOURCE_ROOT / "capability-contract-pack.json",
            self.RESOURCE_ROOT / "capability-catalog.csv",
            self.RESOURCE_ROOT / "quota-catalog.csv",
            self.RESOURCE_ROOT / "quota-policy.json",
            self.JAVA_ROOT / "CapabilityRegistry.java",
            self.JAVA_ROOT / "CapabilityEnvironment.java",
            self.JAVA_ROOT / "QuotaCatalog.java",
            self.JAVA_ROOT / "QuotaDefinition.java",
            self.JAVA_ROOT / "QuotaPolicy.java",
            "src/applications/server/main/io/infranexum/server/platform/PlatformCapabilityController.java",
            "src/applications/server/main/io/infranexum/server/platform/PlatformCapabilityConfiguration.java",
        )
        for relative in required:
            path = self.root / relative
            if not path.is_file():
                self._add("CHECK-CAP-FILES-001", path, "required capability file is missing")

    def _check_contract_pack(self) -> None:
        path = self.root / self.RESOURCE_ROOT / "capability-contract-pack.json"
        payload = self._read_json(path, "CHECK-CAP-PACK-001")
        if payload is None:
            return
        expected = {
            "schema": "infranexum.capability-contract-pack/v1",
            "catalog_version": "2.0.0-draft.20",
            "capability_count": 28,
            "quota_count": 119,
            "quota_thresholds_percent": [80, 90, 100],
            "functional_surface_rule": "allocation-tiers-do-not-change-capabilities",
        }
        for key, value in expected.items():
            if payload.get(key) != value:
                self._add("CHECK-CAP-PACK-002", path, f"invalid {key}: {payload.get(key)!r}")
        files = payload.get("files")
        if not isinstance(files, dict):
            self._add("CHECK-CAP-PACK-003", path, "files checksum map is missing")
            return
        for name in ("capability-catalog.csv", "quota-catalog.csv", "quota-policy.json"):
            target = self.root / self.RESOURCE_ROOT / name
            expected_hash = files.get(name)
            if not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
                self._add("CHECK-CAP-PACK-004", path, f"invalid checksum entry for {name}")
            elif target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() != expected_hash:
                self._add("CHECK-CAP-PACK-005", target, "file checksum differs from capability contract pack")

    def _check_quota_catalogue(self) -> None:
        csv_path = self.root / self.RESOURCE_ROOT / "quota-catalog.csv"
        policy_path = self.root / self.RESOURCE_ROOT / "quota-policy.json"
        rows = self._read_csv(csv_path, "CHECK-CAP-QUOTA-001")
        policy = self._read_json(policy_path, "CHECK-CAP-QUOTA-002")
        if rows is None or policy is None:
            return
        expected_columns = {
            "component",
            "quota_key",
            "unit",
            "quota_class",
            "generator_adjustable",
            "lite_fixed",
            "pro_standard",
            "pro_advanced_ceiling",
            "enterprise_standard",
            "enterprise_ultimate_ceiling",
            "scope",
            "enforcement",
        }
        if len(rows) != 119:
            self._add("CHECK-CAP-QUOTA-003", csv_path, f"expected 119 quotas, found {len(rows)}")
        keys: set[str] = set()
        classes = {"commercial_scalable": 0, "architectural_fixed": 0}
        for index, row in enumerate(rows, start=2):
            if set(row) != expected_columns:
                self._add("CHECK-CAP-QUOTA-004", csv_path, f"row {index} has invalid columns")
                continue
            key = row["quota_key"]
            if key in keys:
                self._add("CHECK-CAP-QUOTA-005", csv_path, f"duplicate quota key: {key}")
            keys.add(key)
            if not re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+", key):
                self._add("CHECK-CAP-QUOTA-006", csv_path, f"invalid quota key: {key}")
            if not key.startswith(row["component"] + "."):
                self._add("CHECK-CAP-QUOTA-007", csv_path, f"quota key/component mismatch: {key}")
            quota_class = row["quota_class"]
            if quota_class not in classes:
                self._add("CHECK-CAP-QUOTA-008", csv_path, f"invalid quota class: {quota_class}")
                continue
            classes[quota_class] += 1
            adjustable = row["generator_adjustable"]
            if (quota_class == "commercial_scalable") != (adjustable == "true"):
                self._add("CHECK-CAP-QUOTA-009", csv_path, f"class/adjustable mismatch: {key}")
            try:
                values = {
                    name: int(row[name])
                    for name in (
                        "lite_fixed",
                        "pro_standard",
                        "pro_advanced_ceiling",
                        "enterprise_standard",
                        "enterprise_ultimate_ceiling",
                    )
                }
            except (TypeError, ValueError):
                self._add("CHECK-CAP-QUOTA-010", csv_path, f"non-integer quota value: {key}")
                continue
            if any(value < 0 for value in values.values()):
                self._add("CHECK-CAP-QUOTA-011", csv_path, f"negative quota value: {key}")
            if values["pro_advanced_ceiling"] < values["pro_standard"]:
                self._add("CHECK-CAP-QUOTA-012", csv_path, f"Pro ceiling below standard: {key}")
            if values["enterprise_ultimate_ceiling"] < values["enterprise_standard"]:
                self._add("CHECK-CAP-QUOTA-013", csv_path, f"Enterprise ceiling below standard: {key}")
            if quota_class == "commercial_scalable" and (
                2 * values["pro_advanced_ceiling"] >= values["enterprise_standard"]
            ):
                self._add("CHECK-CAP-QUOTA-014", csv_path, f"Pro Advanced ratio is not strict: {key}")
            if quota_class == "architectural_fixed" and not key.startswith("deployment."):
                self._add("CHECK-CAP-QUOTA-015", csv_path, f"fixed quota is outside deployment: {key}")
        if classes != {"commercial_scalable": 108, "architectural_fixed": 11}:
            self._add("CHECK-CAP-QUOTA-016", csv_path, f"unexpected quota class counts: {classes}")
        if set(policy.get("quotas", {})) != keys:
            self._add("CHECK-CAP-QUOTA-017", policy_path, "policy quota keys differ from CSV catalogue")
        if policy.get("catalog_version") != "2.0.0-draft.20":
            self._add("CHECK-CAP-QUOTA-018", policy_path, "unexpected embedded catalog version")
        rules = policy.get("rules", {})
        if rules.get("tiers_do_not_unlock_capabilities") is not True:
            self._add("CHECK-CAP-QUOTA-019", policy_path, "tier functional-surface invariant is missing")
        if rules.get("architectural_quotas_are_not_generator_adjustable") is not True:
            self._add("CHECK-CAP-QUOTA-020", policy_path, "architectural quota invariant is missing")

    def _check_capability_catalogue(self) -> None:
        csv_path = self.root / self.RESOURCE_ROOT / "capability-catalog.csv"
        policy_path = self.root / self.RESOURCE_ROOT / "quota-policy.json"
        rows = self._read_csv(csv_path, "CHECK-CAP-CATALOG-001")
        policy = self._read_json(policy_path, "CHECK-CAP-CATALOG-002")
        if rows is None or policy is None:
            return
        if len(rows) != 28:
            self._add("CHECK-CAP-CATALOG-003", csv_path, f"expected 28 capabilities, found {len(rows)}")
        by_code = {row.get("capability_code", ""): row for row in rows}
        if len(by_code) != len(rows):
            self._add("CHECK-CAP-CATALOG-004", csv_path, "duplicate capability codes")
        restrictions = policy.get("capability_restrictions", {})
        for code in restrictions.get("enterprise_only", []):
            if by_code.get(code, {}).get("allowed_profiles") != "enterprise":
                self._add("CHECK-CAP-CATALOG-005", csv_path, f"enterprise-only mismatch: {code}")
        for code in restrictions.get("pro_or_enterprise", []):
            if by_code.get(code, {}).get("allowed_profiles") != "pro;enterprise":
                self._add("CHECK-CAP-CATALOG-006", csv_path, f"Pro/Enterprise mismatch: {code}")
        for code in ("iam.local-auth", "database.postgresql", "discovery.agentless", "rsot.core", "itam.partners"):
            if by_code.get(code, {}).get("allowed_profiles") != "lite;pro;enterprise":
                self._add("CHECK-CAP-CATALOG-007", csv_path, f"baseline capability mismatch: {code}")
        for code, row in by_code.items():
            if not re.fullmatch(r"[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+", code):
                self._add("CHECK-CAP-CATALOG-008", csv_path, f"invalid capability code: {code}")
            if row.get("activation_protected") not in {"true", "false"}:
                self._add("CHECK-CAP-CATALOG-009", csv_path, f"invalid activation flag: {code}")

    def _check_java_contracts(self) -> None:
        registry_path = self.root / self.JAVA_ROOT / "CapabilityRegistry.java"
        quota_path = self.root / self.JAVA_ROOT / "QuotaCatalog.java"
        definition_path = self.root / self.JAVA_ROOT / "QuotaDefinition.java"
        registry = self._read_text(registry_path, "CHECK-CAP-JAVA-001")
        quota = self._read_text(quota_path, "CHECK-CAP-JAVA-002")
        definition = self._read_text(definition_path, "CHECK-CAP-JAVA-002")
        if registry is not None:
            required = (
                "PROFILE_CAPABILITY_NOT_INSTALLED",
                "ROLE_NOT_DEPLOYED",
                "TOPOLOGY_UNSUPPORTED",
                "TRAIT_REQUIRED",
                "DEPENDENCY_UNAVAILABLE",
                "ACTIVATION_REQUIRED",
                "ENTITLEMENT_NOT_GRANTED",
                "MessageDigest.getInstance(\"SHA-256\")",
            )
            for token in required:
                if token not in registry:
                    self._add("CHECK-CAP-JAVA-003", registry_path, f"missing registry invariant: {token}")
            if "environment.allocationTier()" in registry:
                self._add("CHECK-CAP-JAVA-004", registry_path, "allocation tier changes capability hash/surface")
        if quota is not None:
            for token in (
                "architectural quota cannot be overridden",
                "Lite quotas are fixed",
                "quota catalogue version mismatch",
            ):
                if token not in quota:
                    self._add("CHECK-CAP-JAVA-005", quota_path, f"missing quota invariant: {token}")
        if definition is not None and "Math.multiplyExact(proAdvancedCeiling, 2L) >= enterpriseStandard" not in definition:
            self._add(
                "CHECK-CAP-JAVA-005",
                definition_path,
                "quota definition must certify the strict Pro Advanced / Enterprise Standard ratio",
            )

    def _check_reactor_and_server(self) -> None:
        pom_path = self.root / "pom.xml"
        server_pom_path = self.root / "src/applications/server/pom.xml"
        manifest_path = self.root / "src/applications/server/MANIFEST.json"
        policy_path = self.root / "validation/architecture/policy.json"
        pom = self._read_text(pom_path, "CHECK-CAP-WIRING-001")
        server_pom = self._read_text(server_pom_path, "CHECK-CAP-WIRING-002")
        manifest = self._read_json(manifest_path, "CHECK-CAP-WIRING-003")
        policy = self._read_json(policy_path, "CHECK-CAP-WIRING-004")
        if pom is not None and "<module>src/components/core/capabilities</module>" not in pom:
            self._add("CHECK-CAP-WIRING-005", pom_path, "capability module missing from Maven reactor")
        if server_pom is not None and "infranexum-core-capabilities" not in server_pom:
            self._add("CHECK-CAP-WIRING-006", server_pom_path, "Server does not depend on capability module")
        if manifest is not None and "components.core.capabilities" not in manifest.get("dependencies", []):
            self._add("CHECK-CAP-WIRING-007", manifest_path, "Server manifest misses capability dependency")
        if policy is not None and "components/core/capabilities" not in policy.get("required_manifest_paths", []):
            self._add("CHECK-CAP-WIRING-008", policy_path, "architecture policy misses capability manifest")

    def _check_domain_profile_branching(self) -> None:
        domain_root = self.root / "src/components/domains"
        if not domain_root.exists():
            return
        forbidden = re.compile(
            r"InstallationProfile|AllocationTier|\bLITE\b|\bPRO\b|\bENTERPRISE\b|\"lite\"|\"pro\"|\"enterprise\""
        )
        for path in sorted(domain_root.rglob("*.java")):
            try:
                text = path.read_text(encoding="utf-8")
            except OSError as error:
                self._add("CHECK-CAP-DOMAIN-001", path, f"cannot read domain source: {error}")
                continue
            if forbidden.search(text):
                self._add("CHECK-CAP-DOMAIN-002", path, "domain source branches on profile/tier instead of decisions")

    def _read_csv(self, path: Path, check_id: str) -> list[dict[str, str]] | None:
        try:
            with path.open(encoding="utf-8-sig", newline="") as stream:
                return list(csv.DictReader(stream, strict=True))
        except (OSError, csv.Error) as error:
            self._add(check_id, path, f"cannot read CSV: {error}")
            return None

    def _read_json(self, path: Path, check_id: str) -> dict[str, object] | None:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add(check_id, path, f"cannot read JSON: {error}")
            return None
        if not isinstance(payload, dict):
            self._add(check_id, path, "JSON root must be an object")
            return None
        return payload

    def _read_text(self, path: Path, check_id: str) -> str | None:
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
        self.violations.append(CapabilityViolation(check_id, rendered, message))
