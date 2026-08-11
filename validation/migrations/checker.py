from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

_REQUIRED_FILES = (
    "postgresql.sql", "oracle.sql", "logical-model.json", "verify.sql.yaml",
    "rollback/postgresql.sql", "rollback/oracle.sql",
)
_ID = re.compile(r"^[0-9]{4}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")

@dataclass(frozen=True, order=True)
class MigrationViolation:
    check_id: str
    path: str
    message: str

class MigrationChecker:
    """Validates paired migration metadata without executing a database engine."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.violations: list[MigrationViolation] = []

    def run(self) -> tuple[MigrationViolation, ...]:
        catalogue_path = self.root / "catalogue.yaml"
        catalogue = self._load_yaml(catalogue_path, "CHECK-MIG-CATALOGUE-001")
        if not isinstance(catalogue, dict):
            return tuple(sorted(set(self.violations)))
        entries = catalogue.get("entries")
        if not isinstance(entries, list):
            self._add("CHECK-MIG-CATALOGUE-002", catalogue_path, "entries must be an array")
            return tuple(sorted(set(self.violations)))

        seen: set[str] = set()
        previous = "-1"
        known_ids = {entry.get("id") for entry in entries if isinstance(entry, dict)}
        for item in entries:
            if not isinstance(item, dict) or not isinstance(item.get("id"), str) or not isinstance(item.get("path"), str):
                self._add("CHECK-MIG-CATALOGUE-003", catalogue_path, "every entry requires string id and path")
                continue
            migration_id = item["id"]
            if not _ID.fullmatch(migration_id):
                self._add("CHECK-MIG-ID-001", catalogue_path, f"invalid migration id {migration_id!r}")
            if migration_id in seen:
                self._add("CHECK-MIG-ID-002", catalogue_path, f"duplicate migration id {migration_id!r}")
            if migration_id <= previous:
                self._add("CHECK-MIG-ID-003", catalogue_path, "migration ids must be strictly increasing")
            seen.add(migration_id)
            previous = migration_id
            self._check_entry(migration_id, item["path"], known_ids)
        return tuple(sorted(set(self.violations)))

    def _check_entry(self, catalogue_id: str, relative: str, known_ids: set[Any]) -> None:
        descriptor_path = (self.root / relative).resolve()
        if not descriptor_path.is_relative_to(self.root):
            self._add("CHECK-MIG-PATH-001", descriptor_path, "migration path escapes catalogue root")
            return
        payload = self._load_yaml(descriptor_path, "CHECK-MIG-DESCRIPTOR-001")
        if not isinstance(payload, dict):
            return
        if payload.get("id") != catalogue_id:
            self._add("CHECK-MIG-ID-004", descriptor_path, "descriptor id must match catalogue id")
        required = {"schema", "id", "name", "owner_context", "dependencies", "transactional", "lock",
                    "estimated_duration_seconds", "preconditions", "postconditions", "recovery", "rollback", "checksums"}
        missing = sorted(required - payload.keys())
        if missing:
            self._add("CHECK-MIG-DESCRIPTOR-002", descriptor_path, f"missing fields: {missing}")
        dependencies = payload.get("dependencies")
        if not isinstance(dependencies, list) or not all(isinstance(value, str) for value in dependencies):
            self._add("CHECK-MIG-DEP-001", descriptor_path, "dependencies must be a string array")
        else:
            for dependency in dependencies:
                if dependency not in known_ids:
                    self._add("CHECK-MIG-DEP-002", descriptor_path, f"unknown dependency {dependency!r}")
                elif dependency >= catalogue_id:
                    self._add("CHECK-MIG-DEP-003", descriptor_path, "dependencies must precede the migration")
        migration_dir = descriptor_path.parent
        checksums = payload.get("checksums")
        if not isinstance(checksums, dict):
            self._add("CHECK-MIG-CHECKSUM-001", descriptor_path, "checksums must be an object")
            return
        for relative_file in _REQUIRED_FILES:
            file_path = migration_dir / relative_file
            if not file_path.is_file():
                self._add("CHECK-MIG-PAIR-001", file_path, f"required paired artifact {relative_file!r} is missing")
                continue
            expected = checksums.get(relative_file)
            if not isinstance(expected, str) or not _SHA256.fullmatch(expected):
                self._add("CHECK-MIG-CHECKSUM-002", descriptor_path, f"missing or invalid checksum for {relative_file}")
                continue
            actual = hashlib.sha256(file_path.read_bytes()).hexdigest()
            if actual != expected:
                self._add("CHECK-MIG-CHECKSUM-003", file_path, "declared checksum does not match file content")
        self._check_logical_model(migration_dir / "logical-model.json")
        self._check_verification(migration_dir / "verify.sql.yaml")

    def _check_logical_model(self, path: Path) -> None:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add("CHECK-MIG-MODEL-001", path, f"invalid logical model: {error}")
            return
        if not isinstance(payload.get("objects"), list) or not payload["objects"]:
            self._add("CHECK-MIG-MODEL-002", path, "logical model must define at least one object")

    def _check_verification(self, path: Path) -> None:
        payload = self._load_yaml(path, "CHECK-MIG-VERIFY-001")
        if not isinstance(payload, dict):
            return
        checks = payload.get("checks")
        if not isinstance(checks, list) or not checks:
            self._add("CHECK-MIG-VERIFY-002", path, "verification must define checks")
            return
        for check in checks:
            if not isinstance(check, dict) or not all(isinstance(check.get(key), str) and check[key].strip() for key in ("id", "postgresql", "oracle")):
                self._add("CHECK-MIG-VERIFY-003", path, "each verification check requires id, postgresql and oracle queries")

    def _load_yaml(self, path: Path, check_id: str) -> Any:
        try:
            return yaml.safe_load(path.read_text(encoding="utf-8"))
        except (OSError, yaml.YAMLError) as error:
            self._add(check_id, path, f"invalid YAML: {error}")
            return None

    def _add(self, check_id: str, path: Path, message: str) -> None:
        try:
            rendered = path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            rendered = path.resolve().as_posix()
        self.violations.append(MigrationViolation(check_id, rendered, message))
