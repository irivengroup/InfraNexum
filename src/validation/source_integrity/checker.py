"""Validate that InfraNexum's canonical source inventory is complete and tracked.

This gate runs before language-specific builds. It prevents a release archive from
looking complete while the Git checkout silently omits newly introduced source
files, which would otherwise surface later as compiler or fixture failures.
"""

from __future__ import annotations

import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree


INVENTORY_PATH = Path("src/distribution/source-inventory.json")
SCHEMA = "infranexum.source-inventory/v1"

_ROOT_FILES = {
    ".editorconfig",
    ".gitattributes",
    ".gitignore",
    ".node-version",
    ".python-version",
    ".tool-versions",
    "BASELINE.json",
    "Makefile",
    "OWNERS.json",
    "README.md",
    "VERSION",
    "mvnw",
    "mvnw.cmd",
    "pom.xml",
    "pyproject.toml",
    "toolchains.lock.json",
}
_SCAN_PREFIXES = (".github", ".mvn", "docs", "requirements", "src")
_EXCLUDED_RELATIVE = {
    INVENTORY_PATH.as_posix(),
    "src/distribution/source-files.sha256",
}
_EXCLUDED_PARTS = {
    "__pycache__",
    ".pytest_cache",
    "node_modules",
    "target",
    "dist",
    "bin",
}
_PROJECT_IMPORT_RE = re.compile(r"^\s*import\s+(?!static)(io\.infranexum\.[A-Za-z0-9_.]+);", re.MULTILINE)
_PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*);", re.MULTILINE)
_TOP_LEVEL_TYPE_RE = re.compile(
    r"^(?:public\s+)?(?:(?:final|sealed|non-sealed|abstract)\s+)*(?:class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)


@dataclass(frozen=True, slots=True)
class SourceIntegrityViolation:
    """A deterministic source-integrity violation."""

    check_id: str
    path: str
    message: str


class SourceIntegrityChecker:
    """Validate inventory, Java source graph, Maven modules and Git tracking."""

    def __init__(self, root: Path, *, require_git_tracking: bool | None = None) -> None:
        self.root = root.resolve()
        self.require_git_tracking = require_git_tracking
        self.violations: list[SourceIntegrityViolation] = []

    def run(self) -> list[SourceIntegrityViolation]:
        """Execute all checks and return violations in deterministic order."""
        self.violations.clear()
        inventory = self._load_inventory()
        if inventory is None:
            return self._sorted()

        actual = self.canonical_files()
        declared = set(inventory)
        casefolded: dict[str, str] = {}
        for path in inventory:
            key = path.casefold()
            previous = casefolded.get(key)
            if previous is not None and previous != path:
                self._add("CHECK-SOURCE-INVENTORY-004", Path(path), f"case-insensitive path collision with {previous}")
            else:
                casefolded[key] = path
        for missing in sorted(declared - actual):
            self._add("CHECK-SOURCE-INVENTORY-002", Path(missing), "inventory entry is missing from the checkout")
        for undeclared in sorted(actual - declared):
            self._add("CHECK-SOURCE-INVENTORY-003", Path(undeclared), "canonical file is not declared in the source inventory")

        self._check_java_graph()
        self._check_maven_modules()
        self._check_makefile_preflight()
        self._check_git_tracking(inventory)
        return self._sorted()

    def _load_inventory(self) -> tuple[str, ...] | None:
        path = self.root / INVENTORY_PATH
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            self._add("CHECK-SOURCE-INVENTORY-001", INVENTORY_PATH, f"cannot load inventory: {error}")
            return None
        if payload.get("schema") != SCHEMA or not isinstance(payload.get("paths"), list):
            self._add("CHECK-SOURCE-INVENTORY-001", INVENTORY_PATH, "inventory schema or paths are invalid")
            return None

        paths = payload["paths"]
        if any(not isinstance(item, str) or not self._safe_relative(item) for item in paths):
            self._add("CHECK-SOURCE-INVENTORY-001", INVENTORY_PATH, "inventory contains an unsafe or non-string path")
            return None
        if paths != sorted(paths) or len(paths) != len(set(paths)):
            self._add("CHECK-SOURCE-INVENTORY-001", INVENTORY_PATH, "inventory paths must be sorted and unique")
            return None
        return tuple(paths)

    def canonical_files(self) -> set[str]:
        result = {name for name in _ROOT_FILES if (self.root / name).is_file()}
        for prefix in _SCAN_PREFIXES:
            base = self.root / prefix
            if not base.exists():
                continue
            for path in base.rglob("*"):
                if not path.is_file():
                    continue
                relative = path.relative_to(self.root)
                rendered = relative.as_posix()
                if rendered in _EXCLUDED_RELATIVE or any(part in _EXCLUDED_PARTS for part in relative.parts):
                    continue
                result.add(rendered)
        return result

    def _check_java_graph(self) -> None:
        main_sources = sorted(self.root.glob("src/**/src/main/java/**/*.java"))
        fqcn_to_path: dict[str, Path] = {}
        source_texts: list[tuple[Path, str]] = []
        for path in main_sources:
            text = self._read(path, "CHECK-SOURCE-JAVA-001")
            if text is None:
                continue
            source_texts.append((path, text))
            package = _PACKAGE_RE.search(text)
            public_type = _TOP_LEVEL_TYPE_RE.search(text)
            if package is None or public_type is None:
                self._add("CHECK-SOURCE-JAVA-001", path, "Java source must declare a package and one top-level type")
                continue
            type_name = public_type.group(1)
            if path.stem != type_name:
                self._add("CHECK-SOURCE-JAVA-004", path, f"Java filename must match top-level type {type_name}")
            fqcn = f"{package.group(1)}.{type_name}"
            previous = fqcn_to_path.get(fqcn)
            if previous is not None:
                self._add("CHECK-SOURCE-JAVA-002", path, f"duplicate project type {fqcn}; first declared in {previous.relative_to(self.root).as_posix()}")
            else:
                fqcn_to_path[fqcn] = path

        for path, text in source_texts:
            for imported in _PROJECT_IMPORT_RE.findall(text):
                if imported not in fqcn_to_path:
                    self._add("CHECK-SOURCE-JAVA-003", path, f"project import has no main-source definition: {imported}")

    def _check_maven_modules(self) -> None:
        pom = self.root / "pom.xml"
        try:
            tree = ElementTree.parse(pom)
        except (OSError, ElementTree.ParseError) as error:
            self._add("CHECK-SOURCE-MAVEN-001", pom, f"cannot parse root pom.xml: {error}")
            return
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        modules = [node.text.strip() for node in tree.findall("./m:modules/m:module", namespace) if node.text and node.text.strip()]
        declared_modules = set(modules)
        for module in modules:
            module_path = Path(module)
            if not self._safe_relative(module):
                self._add("CHECK-SOURCE-MAVEN-001", pom, f"unsafe Maven module path: {module}")
                continue
            if not (self.root / module_path / "pom.xml").is_file():
                self._add("CHECK-SOURCE-MAVEN-002", module_path, "Maven reactor module has no pom.xml")

        discovered_modules = {
            path.parent.relative_to(self.root).as_posix()
            for path in self.root.glob("src/**/pom.xml")
            if path.is_file()
        }
        for orphan in sorted(discovered_modules - declared_modules):
            self._add("CHECK-SOURCE-MAVEN-003", Path(orphan), "Maven module pom.xml exists but is absent from the root reactor")

    def _check_makefile_preflight(self) -> None:
        path = self.root / "Makefile"
        text = self._read(path, "CHECK-SOURCE-MAKE-001")
        if text is None:
            return
        targets = (
            "architecture-test",
            "toolchain-test",
            "migration-test",
            "eventing-test",
            "persistence-test",
            "capabilities-test",
            "entitlements-test",
            "audit-test",
        )
        for target in targets:
            match = re.search(rf"(?m)^{re.escape(target)}:(?P<deps>[^\n]*)$", text)
            if match is None or "source-integrity-check" not in match.group("deps").split():
                self._add(
                    "CHECK-SOURCE-MAKE-002",
                    path,
                    f"{target} must depend on source-integrity-check",
                )

    def _check_git_tracking(self, inventory: tuple[str, ...]) -> None:
        inside = self._is_git_checkout()
        required = self.require_git_tracking if self.require_git_tracking is not None else inside
        if not required:
            return
        if not inside:
            self._add("CHECK-SOURCE-GIT-001", Path("."), "Git tracking was required but repository metadata is unavailable")
            return
        try:
            completed = subprocess.run(
                ["git", "-C", str(self.root), "ls-files", "-z"],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            self._add("CHECK-SOURCE-GIT-001", Path("."), f"cannot inspect Git index: {error}")
            return
        tracked = {item.decode("utf-8") for item in completed.stdout.split(b"\0") if item}
        for path in inventory:
            candidate = self.root / path
            if path not in tracked and candidate.is_file():
                self._add("CHECK-SOURCE-GIT-002", Path(path), "inventory file exists but is not tracked by Git")

    def _is_git_checkout(self) -> bool:
        try:
            result = subprocess.run(
                ["git", "-C", str(self.root), "rev-parse", "--is-inside-work-tree"],
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
            )
        except OSError:
            return False
        return result.returncode == 0 and result.stdout.strip() == "true"

    @staticmethod
    def _safe_relative(value: str) -> bool:
        path = Path(value)
        return bool(value) and not path.is_absolute() and ".." not in path.parts and value == path.as_posix()

    def _read(self, path: Path, check_id: str) -> str | None:
        try:
            return path.read_text(encoding="utf-8")
        except OSError as error:
            self._add(check_id, path, f"cannot read source: {error}")
            return None

    def _add(self, check_id: str, path: Path, message: str) -> None:
        candidate = path if path.is_absolute() else self.root / path
        try:
            rendered = candidate.resolve().relative_to(self.root).as_posix()
        except ValueError:
            rendered = candidate.resolve().as_posix()
        self.violations.append(SourceIntegrityViolation(check_id, rendered, message))

    def _sorted(self) -> list[SourceIntegrityViolation]:
        return sorted(self.violations, key=lambda item: (item.check_id, item.path, item.message))
