from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

_VERSION = re.compile(r"^[0-9]+(?:\.[0-9]+){1,2}(?:\+[0-9]+)?$")
_REQUIRED = {
    "java", "maven", "spring-boot", "spring-modulith", "go", "node",
    "pnpm", "typescript", "python", "cmake", "gcc",
}


@dataclass(frozen=True, order=True)
class ToolchainViolation:
    check_id: str
    path: str
    message: str


class ToolchainChecker:
    """Checks exact polyglot toolchain pins and their build-file wiring."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.violations: list[ToolchainViolation] = []

    def run(self) -> tuple[ToolchainViolation, ...]:
        lock_path = self.root / "toolchains.lock.json"
        payload = self._load_lock(lock_path)
        if payload is None:
            return tuple(sorted(set(self.violations)))
        tools = payload.get("toolchains") if isinstance(payload, dict) else None
        if not isinstance(tools, dict):
            self._add("CHECK-TOOLCHAIN-002", lock_path, "toolchains must be an object")
            return tuple(sorted(set(self.violations)))

        missing = sorted(_REQUIRED - tools.keys())
        extra = sorted(tools.keys() - _REQUIRED)
        if missing or extra:
            self._add(
                "CHECK-TOOLCHAIN-003",
                lock_path,
                f"toolchain set mismatch; missing={missing}, extra={extra}",
            )
        for name, item in tools.items():
            version = item.get("version") if isinstance(item, dict) else None
            if not isinstance(version, str) or not _VERSION.fullmatch(version):
                self._add("CHECK-TOOLCHAIN-004", lock_path, f"{name} must have an exact version")

        self._match_file(lock_path, ".node-version", tools, "node")
        self._match_file(lock_path, ".python-version", tools, "python")
        self._check_maven(tools)
        self._check_go(tools)
        return tuple(sorted(set(self.violations)))

    def _load_lock(self, path: Path) -> Any | None:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add("CHECK-TOOLCHAIN-001", path, f"invalid toolchain lock: {error}")
            return None

    def _match_file(self, lock_path: Path, relative: str, tools: dict[str, Any], key: str) -> None:
        file_path = self.root / relative
        text = self._read_text(file_path, "CHECK-TOOLCHAIN-005", "version file cannot be read")
        if text is None:
            return
        expected = self._version(tools, key)
        if text.strip() != expected:
            self._add("CHECK-TOOLCHAIN-006", file_path, f"version must match {lock_path.name}")

    def _check_maven(self, tools: dict[str, Any]) -> None:
        pom_path = self.root / "pom.xml"
        text = self._read_text(pom_path, "CHECK-TOOLCHAIN-010", "root Maven model cannot be read")
        if text is not None:
            java_major = self._version(tools, "java").split(".", 1)[0]
            if f"<java.version>{java_major}</java.version>" not in text:
                self._add("CHECK-TOOLCHAIN-007", pom_path, "Java major does not match toolchain lock")
            for key, property_name in (
                ("spring-boot", "spring-boot.version"),
                ("spring-modulith", "spring-modulith.version"),
            ):
                version = self._version(tools, key)
                if f"<{property_name}>{version}</{property_name}>" not in text:
                    self._add("CHECK-TOOLCHAIN-008", pom_path, f"{key} does not match toolchain lock")

        wrapper_path = self.root / ".mvn/wrapper/maven-wrapper.properties"
        wrapper = self._read_text(
            wrapper_path,
            "CHECK-TOOLCHAIN-011",
            "Maven Wrapper properties cannot be read",
        )
        if wrapper is not None:
            maven_version = self._version(tools, "maven")
            expected_fragment = f"/apache-maven-{maven_version}-bin.tar.gz"
            if expected_fragment not in wrapper or "distributionSha512Sum=" not in wrapper:
                self._add(
                    "CHECK-TOOLCHAIN-012",
                    wrapper_path,
                    "Maven distribution and SHA-512 pin must match the toolchain lock",
                )

    def _check_go(self, tools: dict[str, Any]) -> None:
        path = self.root / "applications/agent/go.mod"
        text = self._read_text(path, "CHECK-TOOLCHAIN-013", "Go module cannot be read")
        if text is None:
            return
        version = self._version(tools, "go")
        if f"toolchain go{version}" not in text:
            self._add("CHECK-TOOLCHAIN-009", path, "Go toolchain does not match lock")

    @staticmethod
    def _version(tools: dict[str, Any], key: str) -> str:
        item = tools.get(key)
        return item.get("version", "") if isinstance(item, dict) else ""

    def _read_text(self, path: Path, check_id: str, message: str) -> str | None:
        try:
            return path.read_text(encoding="utf-8")
        except OSError as error:
            self._add(check_id, path, f"{message}: {error}")
            return None

    def _add(self, check_id: str, path: Path, message: str) -> None:
        try:
            rendered = path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            rendered = path.resolve().as_posix()
        self.violations.append(ToolchainViolation(check_id, rendered, message))
