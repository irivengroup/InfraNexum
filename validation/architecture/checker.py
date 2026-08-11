from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any

from .model import CheckReport, Manifest, Violation


class ArchitectureChecker:
    """Validate repository layout, ownership, language boundaries, and hygiene."""

    _CODE_EXTENSIONS = frozenset(
        {
            ".java",
            ".go",
            ".ts",
            ".tsx",
            ".js",
            ".jsx",
            ".mjs",
            ".cjs",
            ".py",
            ".cc",
            ".cpp",
            ".cxx",
            ".h",
            ".hpp",
            ".sh",
            ".ps1",
        }
    )

    def __init__(self, root: Path, policy_path: Path) -> None:
        self.root = root.resolve()
        self.policy_path = policy_path.resolve()
        self.violations: list[Violation] = []
        self.manifests: dict[str, Manifest] = {}
        self.policy = self._load_json(self.policy_path)
        self.source_root = self._resolve_source_root()

    def run(self) -> CheckReport:
        self._check_source_layout()
        self._check_root()
        self._check_applications()
        self._load_and_validate_manifests()
        self._check_dependencies()
        self._check_language_boundaries()
        self._check_forbidden_names()
        self._check_secrets()
        self._check_artifacts()
        ordered = tuple(sorted(set(self.violations)))
        return CheckReport(".", self._render_path(self.policy_path), ordered)

    def _resolve_source_root(self) -> Path:
        """Resolve the single source root while refusing absolute or escaping paths."""
        configured = self.policy.get("source_root")
        fallback = self.root
        if not isinstance(configured, str) or not configured.strip():
            self._add(
                "CHECK-ARCH-SRC-001",
                self.policy_path,
                "source_root must be a non-empty relative path",
            )
            return fallback
        candidate = (self.root / configured).resolve()
        if not candidate.is_relative_to(self.root):
            self._add(
                "CHECK-ARCH-SRC-001",
                self.policy_path,
                "source_root must remain inside the repository",
            )
            return fallback
        return candidate

    def _check_source_layout(self) -> None:
        """Require code to remain in explicitly governed repository spaces."""
        if not self.source_root.is_dir():
            self._add(
                "CHECK-ARCH-SRC-003",
                self.source_root,
                "configured source root is missing",
            )
        allowed_roots = self.policy.get("allowed_code_roots")
        if not isinstance(allowed_roots, list) or not allowed_roots or not all(
            isinstance(item, str) and item and "/" not in item and "\\" not in item
            for item in allowed_roots
        ):
            self._add(
                "CHECK-ARCH-SRC-004",
                self.policy_path,
                "allowed_code_roots must be a non-empty array of top-level directory names",
            )
            allowed_roots = []
        support_roots = self.policy.get("allowed_support_roots", [])
        if not isinstance(support_roots, list) or not all(
            isinstance(item, str) and item and "/" not in item and "\\" not in item
            for item in support_roots
        ):
            self._add(
                "CHECK-ARCH-SRC-005",
                self.policy_path,
                "allowed_support_roots must be an array of top-level directory names",
            )
            support_roots = []

        product_bases = {self.source_root / item for item in allowed_roots}
        support_bases = {self.root / item for item in support_roots}
        allowed = product_bases | support_bases
        for path in self._repository_paths():
            if not path.is_file() or path.suffix not in self._CODE_EXTENSIONS:
                continue
            if not any(path.is_relative_to(base) for base in allowed):
                rendered = sorted(
                    [f"src/{item}" for item in allowed_roots] + list(support_roots)
                )
                self._add(
                    "CHECK-ARCH-SRC-002",
                    path,
                    f"implementation/support source files must be located below one of {rendered}",
                )

    def _check_root(self) -> None:
        for name in self.policy["required_structural_spaces"]:
            path = self.source_root / name
            if not path.is_dir():
                self._add(
                    "CHECK-ARCH-ROOT-001",
                    path,
                    f"required structural space {name!r} is missing",
                )

    def _check_applications(self) -> None:
        applications = self.source_root / "applications"
        if not applications.is_dir():
            return
        actual = sorted(path.name for path in applications.iterdir() if path.is_dir())
        expected = sorted(self.policy["allowed_applications"])
        if actual != expected:
            self._add(
                "CHECK-ARCH-APP-001",
                applications,
                f"applications must be exactly {expected}; found {actual}",
            )

    def _load_and_validate_manifests(self) -> None:
        owners = self._load_owner_ids()
        expected_schema = self.policy["manifest_schema"]
        for relative in self.policy["required_manifest_paths"]:
            manifest_path = self.source_root / relative / "MANIFEST.json"
            if not manifest_path.is_file():
                self._add(
                    "CHECK-ARCH-MANIFEST-001",
                    manifest_path,
                    "required component manifest is missing",
                )
                continue
            try:
                payload = self._load_json(manifest_path)
            except (OSError, ValueError) as error:
                self._add(
                    "CHECK-ARCH-MANIFEST-002",
                    manifest_path,
                    f"invalid JSON manifest: {error}",
                )
                continue
            required = {
                "schema",
                "id",
                "kind",
                "owner",
                "lifecycle",
                "languages",
                "dependencies",
                "source_baseline",
            }
            missing = sorted(required - payload.keys())
            if missing:
                self._add(
                    "CHECK-ARCH-MANIFEST-003",
                    manifest_path,
                    f"missing fields: {missing}",
                )
                continue
            if payload["schema"] != expected_schema:
                self._add(
                    "CHECK-ARCH-MANIFEST-004",
                    manifest_path,
                    f"schema must be {expected_schema!r}",
                )
            overrides = self.policy.get("manifest_id_overrides", {})
            if not isinstance(overrides, dict):
                overrides = {}
                self._add(
                    "CHECK-ARCH-MANIFEST-010",
                    self.policy_path,
                    "manifest_id_overrides must be an object",
                )
            expected_id = overrides.get(relative, relative.replace("/", "."))
            if payload["id"] != expected_id:
                self._add(
                    "CHECK-ARCH-MANIFEST-005",
                    manifest_path,
                    f"id must be {expected_id!r}",
                )
            if payload["owner"] not in owners:
                self._add(
                    "CHECK-ARCH-OWNER-001",
                    manifest_path,
                    f"unknown owner {payload['owner']!r}",
                )
            if payload["lifecycle"] not in {"active", "planned", "deprecated"}:
                self._add(
                    "CHECK-ARCH-MANIFEST-006",
                    manifest_path,
                    "lifecycle must be active, planned, or deprecated",
                )
            if not isinstance(payload["languages"], list) or not all(
                isinstance(item, str) for item in payload["languages"]
            ):
                self._add(
                    "CHECK-ARCH-MANIFEST-007",
                    manifest_path,
                    "languages must be a string array",
                )
                continue
            if not isinstance(payload["dependencies"], list) or not all(
                isinstance(item, str) for item in payload["dependencies"]
            ):
                self._add(
                    "CHECK-ARCH-MANIFEST-008",
                    manifest_path,
                    "dependencies must be a string array",
                )
                continue
            if not isinstance(payload["source_baseline"], list) or not payload[
                "source_baseline"
            ]:
                self._add(
                    "CHECK-ARCH-TRACE-001",
                    manifest_path,
                    "source_baseline must contain at least one traceability reference",
                )
            manifest = Manifest(
                path=manifest_path,
                component_id=payload["id"],
                kind=payload["kind"],
                owner=payload["owner"],
                lifecycle=payload["lifecycle"],
                languages=tuple(payload["languages"]),
                dependencies=tuple(payload["dependencies"]),
            )
            if manifest.component_id in self.manifests:
                self._add(
                    "CHECK-ARCH-MANIFEST-009",
                    manifest_path,
                    f"duplicate component id {manifest.component_id!r}",
                )
            self.manifests[manifest.component_id] = manifest

    def _load_owner_ids(self) -> set[str]:
        path = self.root / self.policy["owner_registry"]
        try:
            payload = self._load_json(path)
        except (OSError, ValueError) as error:
            self._add(
                "CHECK-ARCH-OWNER-002",
                path,
                f"invalid owner registry: {error}",
            )
            return set()
        owners = payload.get("owners")
        if not isinstance(owners, list):
            self._add("CHECK-ARCH-OWNER-003", path, "owners must be an array")
            return set()
        ids = {
            entry.get("id")
            for entry in owners
            if isinstance(entry, dict) and isinstance(entry.get("id"), str)
        }
        if len(ids) != len(owners):
            self._add(
                "CHECK-ARCH-OWNER-004",
                path,
                "every owner must have a unique string id",
            )
        return ids

    def _check_dependencies(self) -> None:
        known = set(self.manifests)
        for manifest in self.manifests.values():
            for dependency in manifest.dependencies:
                if dependency == manifest.component_id:
                    self._add(
                        "CHECK-ARCH-DEP-001",
                        manifest.path,
                        "component must not depend on itself",
                    )
                elif dependency not in known:
                    self._add(
                        "CHECK-ARCH-DEP-002",
                        manifest.path,
                        f"unknown dependency {dependency!r}",
                    )
            if len(set(manifest.dependencies)) != len(manifest.dependencies):
                self._add(
                    "CHECK-ARCH-DEP-003",
                    manifest.path,
                    "dependencies must not contain duplicates",
                )

    def _check_language_boundaries(self) -> None:
        extension_map: dict[str, list[str]] = self.policy["language_extensions"]
        ignored = set(self.policy.get("ignored_code_files", []))
        manifests = tuple(self.manifests.values())

        # A source file belongs to the deepest manifest directory that contains
        # it. Structural-space manifests therefore cannot override a narrower
        # application or component language contract.
        source_files = [
            path
            for path in self.source_root.rglob("*")
            if path.is_file()
            and path.name not in ignored
            and path.suffix in self._CODE_EXTENSIONS
        ]
        for path in source_files:
            candidates = [
                manifest
                for manifest in manifests
                if path.is_relative_to(manifest.path.parent)
            ]
            if not candidates:
                # Tests, validation and build-support sources are governed by
                # their dedicated quality gates rather than product manifests.
                continue
            manifest = max(candidates, key=lambda item: len(item.path.parent.parts))
            allowed_extensions = {
                extension
                for language in manifest.languages
                for extension in extension_map.get(language, [])
            }
            if path.suffix not in allowed_extensions:
                self._add(
                    "CHECK-ARCH-LANG-001",
                    path,
                    f"extension {path.suffix!r} is not allowed by manifest languages {list(manifest.languages)}",
                )
            self._check_namespace(path)

        for manifest in manifests:
            if manifest.lifecycle != "active" or manifest.kind not in {
                "application",
                "component",
            }:
                continue
            component_dir = manifest.path.parent
            owned_sources = [
                path
                for path in source_files
                if path.is_relative_to(component_dir)
                and max(
                    (
                        candidate
                        for candidate in manifests
                        if path.is_relative_to(candidate.path.parent)
                    ),
                    key=lambda item: len(item.path.parent.parts),
                )
                == manifest
            ]
            if not owned_sources:
                self._add(
                    "CHECK-ARCH-CODE-001",
                    component_dir,
                    "active application/component must contain executable source",
                )

    def _check_namespace(self, path: Path) -> None:
        relative = path.relative_to(self.source_root).as_posix()
        if path.suffix == ".java":
            text = path.read_text(encoding="utf-8")
            match = re.search(
                r"^package\s+([a-zA-Z0-9_.]+);",
                text,
                re.MULTILINE,
            )
            if not match or not match.group(1).startswith("io.infranexum"):
                self._add(
                    "CHECK-ARCH-NS-001",
                    path,
                    "Java package must use io.infranexum namespace",
                )
        elif path.suffix == ".go" and relative.startswith("applications/agent/"):
            text = path.read_text(encoding="utf-8")
            legacy_import_prefix = '"' + "open" + "infra/"
            if legacy_import_prefix in text:
                self._add(
                    "CHECK-ARCH-NS-002",
                    path,
                    "Go imports must use infranexum namespace",
                )

    def _check_forbidden_names(self) -> None:
        forbidden = [
            "".join(parts).lower() for parts in self.policy["forbidden_name_parts"]
        ]
        scan_extensions = self._CODE_EXTENSIONS | {
            ".md",
            ".json",
            ".yaml",
            ".yml",
            ".xml",
            ".properties",
        }
        for path in self._repository_paths():
            if not path.is_file() or path.suffix.lower() not in scan_extensions:
                continue
            relative = path.relative_to(self.root).as_posix().lower()
            text = path.read_text(encoding="utf-8", errors="replace").lower()
            for token in forbidden:
                if token in relative or token in text:
                    self._add(
                        "CHECK-BRAND-001",
                        path,
                        f"forbidden legacy namespace {token!r} detected",
                    )

    def _check_secrets(self) -> None:
        """Block high-confidence credential material without echoing the match."""
        extensions = {
            item.lower() for item in self.policy.get("secret_scan_extensions", [])
        }
        filenames = set(self.policy.get("secret_scan_filenames", []))
        compiled_patterns: list[tuple[str, re.Pattern[str]]] = []
        for entry in self.policy.get("secret_patterns", []):
            try:
                pattern_id = entry["id"]
                expression = entry["regex"]
                if not isinstance(pattern_id, str) or not isinstance(expression, str):
                    raise TypeError("id and regex must be strings")
                compiled_patterns.append((pattern_id, re.compile(expression)))
            except (KeyError, TypeError, re.error) as error:
                self._add(
                    "CHECK-SECRET-POLICY-001",
                    self.policy_path,
                    f"invalid secret pattern declaration: {error}",
                )

        if not compiled_patterns:
            return
        forbidden_artifacts = set(self.policy.get("forbidden_artifact_names", []))
        for path in self._repository_paths():
            if not path.is_file():
                continue
            if any(
                part in forbidden_artifacts or part == ".git" for part in path.parts
            ):
                continue
            if (
                path.suffix.lower() not in extensions
                and path.name not in filenames
            ):
                continue
            try:
                if path.stat().st_size > 1_048_576:
                    continue
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            for pattern_id, pattern in compiled_patterns:
                if pattern.search(text):
                    self._add(
                        "CHECK-SECRET-001",
                        path,
                        f"high-confidence secret pattern {pattern_id!r} detected",
                    )

    def _check_artifacts(self) -> None:
        forbidden = set(self.policy["forbidden_artifact_names"])
        for path in self._repository_paths():
            if path.name in forbidden:
                self._add(
                    "CHECK-REPO-CLEAN-001",
                    path,
                    f"generated or forbidden artifact {path.name!r} must not be committed",
                )

    def _repository_paths(self):
        """Yield repository entries while pruning Git metadata deterministically."""
        for directory, dirnames, filenames in os.walk(self.root):
            dirnames[:] = sorted(name for name in dirnames if name != ".git")
            base = Path(directory)
            for name in dirnames:
                yield base / name
            for name in sorted(filenames):
                yield base / name

    def _add(self, check_id: str, path: Path, message: str) -> None:
        self.violations.append(
            Violation(check_id, self._render_path(path), message)
        )

    def _render_path(self, path: Path) -> str:
        """Render repository paths portably while preserving external paths."""
        try:
            return path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            return str(path)

    @staticmethod
    def _load_json(path: Path) -> dict[str, Any]:
        with path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
        if not isinstance(payload, dict):
            raise ValueError("top-level JSON value must be an object")
        return payload
