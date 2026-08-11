"""Validate that InfraNexum's canonical source inventory is complete and tracked.

This gate runs before language-specific builds. It prevents a release archive from
looking complete while the Git checkout silently omits newly introduced source
files, which would otherwise surface later as compiler or fixture failures.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree


INVENTORY_PATH = Path("src/distribution/source-inventory.json")
CHECKSUM_PATH = Path("src/distribution/source-files.sha256")
SCHEMA = "infranexum.source-inventory/v1"
MAX_RELATIVE_PATH_LENGTH = 120
MAX_PATH_COMPONENT_LENGTH = 80
MAX_ARCHIVE_PREFIX_LENGTH = 32

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
_SCAN_PREFIXES = (
    ".github",
    ".githooks",
    ".mvn",
    "src",
    "docs",
    "requirements",
    "tests",
    "tools",
    "validation",
)
_EXCLUDED_RELATIVE = {
    INVENTORY_PATH.as_posix(),
    "src/distribution/source-files.sha256",
}
_PRODUCT_SPACES = (
    "applications",
    "components",
    "deployment",
    "distribution",
    "engines",
    "installer",
    "provisioning",
    "sdk",
)

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

    def __init__(
        self,
        root: Path,
        *,
        require_git_tracking: bool | None = None,
        require_staged_snapshot: bool = False,
        require_git_checksums: bool = False,
    ) -> None:
        self.root = root.resolve()
        self.require_git_tracking = require_git_tracking
        self.require_staged_snapshot = require_staged_snapshot
        self.require_git_checksums = require_git_checksums
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

        self._check_path_budget(declared | actual)
        self._check_product_layout()
        self._check_release_archive_prefix()
        self._check_java_graph()
        self._check_maven_modules()
        self._check_makefile_preflight()
        self._check_git_tracking(inventory)
        self._check_git_checksums()
        self._check_staged_snapshot()
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

    def _check_path_budget(self, paths: set[str]) -> None:
        """Keep checkout/archive paths safely below legacy Windows extraction limits.

        The project-relative budget leaves substantial room for a checkout root,
        temporary extraction directory and the short release archive prefix.
        """
        for rendered in sorted(paths):
            if len(rendered) > MAX_RELATIVE_PATH_LENGTH:
                self._add(
                    "CHECK-SOURCE-PATH-001",
                    Path(rendered),
                    f"repository-relative path is {len(rendered)} characters; maximum is {MAX_RELATIVE_PATH_LENGTH}",
                )
            for part in Path(rendered).parts:
                if len(part) > MAX_PATH_COMPONENT_LENGTH:
                    self._add(
                        "CHECK-SOURCE-PATH-002",
                        Path(rendered),
                        f"path component {part!r} is {len(part)} characters; maximum is {MAX_PATH_COMPONENT_LENGTH}",
                    )
                    break

    def _check_product_layout(self) -> None:
        """Keep product implementation below src/ and all tests outside it."""
        product_root = self.root / "src"
        if not product_root.is_dir():
            self._add("CHECK-SOURCE-LAYOUT-001", Path("src"), "product source root is missing")
            return

        for space in _PRODUCT_SPACES:
            legacy = self.root / space
            if legacy.exists():
                self._add(
                    "CHECK-SOURCE-LAYOUT-002",
                    Path(space),
                    f"product space {space!r} must live below src/",
                )

        for path in product_root.rglob("*"):
            if not path.is_file():
                continue
            relative = path.relative_to(product_root)
            name = path.name
            if (
                "test" in relative.parts
                or "tests" in relative.parts
                or name.endswith("_test.go")
                or ".test." in name
                or ".spec." in name
            ):
                self._add(
                    "CHECK-SOURCE-LAYOUT-003",
                    path.relative_to(self.root),
                    "test source must live below repository-level tests/, not src/",
                )

    def _check_release_archive_prefix(self) -> None:
        """Require a short, deterministic source-archive root prefix."""
        path = self.root / "src/distribution/release-manifest.json"
        if not path.is_file():
            return
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            prefix = payload["source_archive"]["prefix"]
            version = (self.root / "VERSION").read_text(encoding="utf-8").strip()
        except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
            self._add("CHECK-SOURCE-PATH-003", path, f"cannot validate release archive prefix: {error}")
            return
        expected = f"infranexum-{version}"
        if not isinstance(prefix, str) or prefix != expected or len(prefix) > MAX_ARCHIVE_PREFIX_LENGTH:
            self._add(
                "CHECK-SOURCE-PATH-003",
                path,
                f"source archive prefix must be {expected!r} and at most {MAX_ARCHIVE_PREFIX_LENGTH} characters",
            )

        # release-manifest.json moved one directory deeper with the product source
        # tree. Keep its repository-support references explicit so a layout move
        # cannot silently redirect validation evidence below src/.
        if payload.get("baseline") != "../../BASELINE.json":
            self._add(
                "CHECK-SOURCE-LAYOUT-004",
                path,
                "release baseline reference must resolve to repository-level BASELINE.json",
            )

        reports = payload.get("validation_reports")
        if reports is not None and (
            not isinstance(reports, list)
            or any(
                not isinstance(report, str)
                or not report.startswith("../../artifacts/validation/")
                or not self._manifest_reference_is_within(path.parent, report, self.root / "artifacts" / "validation")
                for report in reports
            )
        ):
            self._add(
                "CHECK-SOURCE-LAYOUT-005",
                path,
                "validation reports must resolve below repository-level artifacts/validation/",
            )

        source_archive = payload.get("source_archive")
        if isinstance(source_archive, dict) and source_archive.get("release_checksum_manifest") not in (
            None,
            "../../artifacts/validation/release-files.sha256",
        ):
            self._add(
                "CHECK-SOURCE-LAYOUT-006",
                path,
                "release checksum manifest must resolve to repository-level artifacts/validation/release-files.sha256",
            )

    def _check_java_graph(self) -> None:
        main_sources = sorted(
            [
                path
                for prefix in ("applications", "components")
                for path in self.root.glob(f"src/{prefix}/**/main/**/*.java")
            ]
        )
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
            module_pom = self.root / module_path / "pom.xml"
            if not module_pom.is_file():
                self._add("CHECK-SOURCE-MAVEN-002", module_path, "Maven reactor module has no pom.xml")
                continue
            self._check_maven_test_source(module_path, module_pom)

        discovered_modules = {
            path.parent.relative_to(self.root).as_posix()
            for prefix in ("applications", "components")
            for path in (self.root / "src" / prefix).rglob("pom.xml")
            if path.is_file() and "target" not in path.parts
        }
        for orphan in sorted(discovered_modules - declared_modules):
            self._add("CHECK-SOURCE-MAVEN-003", Path(orphan), "Maven module pom.xml exists but is absent from the root reactor")

    def _check_maven_test_source(self, module_path: Path, module_pom: Path) -> None:
        """Require every active Maven module to consume tests from repository-level tests/."""
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        try:
            tree = ElementTree.parse(module_pom)
        except (OSError, ElementTree.ParseError) as error:
            self._add("CHECK-SOURCE-MAVEN-004", module_pom, f"cannot parse module pom.xml: {error}")
            return
        node = tree.find("./m:build/m:testSourceDirectory", namespace)
        value = node.text.strip() if node is not None and node.text else ""
        prefix = "${maven.multiModuleProjectDirectory}/"
        if not value.startswith(prefix):
            self._add(
                "CHECK-SOURCE-MAVEN-004",
                module_pom,
                "module testSourceDirectory must use ${maven.multiModuleProjectDirectory}/tests/...",
            )
            return
        relative = value[len(prefix):]
        if not self._safe_relative(relative) or not relative.startswith("tests/"):
            self._add(
                "CHECK-SOURCE-MAVEN-004",
                module_pom,
                "module tests must resolve below repository-level tests/",
            )
            return
        test_root = self.root / relative
        if not test_root.is_dir() or not any(test_root.rglob("*.java")):
            self._add(
                "CHECK-SOURCE-MAVEN-005",
                Path(relative),
                f"Maven module {module_path.as_posix()} has no external Java tests",
            )

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


    def _check_git_checksums(self) -> None:
        """Verify SHA-256 digests against Git index blobs, not checkout bytes.

        Git attributes may transform line endings on checkout. Hashing index blobs
        keeps the source manifest stable across Windows and Linux while the
        separate release manifest verifies the packaged filesystem bytes.
        """
        if not self.require_git_checksums:
            return
        if not self._is_git_checkout():
            self._add(
                "CHECK-SOURCE-GIT-003",
                CHECKSUM_PATH,
                "Git source checksum validation requires repository metadata",
            )
            return
        try:
            index = self._git_index_entries()
            manifest_oid = index.get(CHECKSUM_PATH.as_posix())
            if manifest_oid is None:
                raise RuntimeError("checksum manifest is not present in the Git index")
            manifest_bytes = self._git_blob_by_oid(manifest_oid)
        except (OSError, RuntimeError, UnicodeError, subprocess.CalledProcessError) as error:
            self._add(
                "CHECK-SOURCE-GIT-003",
                CHECKSUM_PATH,
                f"cannot inspect Git source checksum manifest: {error}",
            )
            return

        try:
            text = manifest_bytes.decode("utf-8")
        except UnicodeDecodeError as error:
            self._add(
                "CHECK-SOURCE-GIT-003",
                CHECKSUM_PATH,
                f"checksum manifest must be UTF-8: {error}",
            )
            return

        entries: list[tuple[str, str]] = []
        seen: set[str] = set()
        for line_number, line in enumerate(text.splitlines(), start=1):
            match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
            if match is None:
                self._add(
                    "CHECK-SOURCE-GIT-003",
                    CHECKSUM_PATH,
                    f"invalid checksum manifest line {line_number}",
                )
                return
            digest, path = match.groups()
            if not self._safe_relative(path) or "\n" in path or "\r" in path or path in seen:
                self._add(
                    "CHECK-SOURCE-GIT-003",
                    CHECKSUM_PATH,
                    f"unsafe or duplicate checksum path on line {line_number}",
                )
                return
            seen.add(path)
            entries.append((path, digest))

        paths = [path for path, _ in entries]
        expected = sorted(path for path in index if path != CHECKSUM_PATH.as_posix())
        if paths != sorted(paths) or paths != expected:
            self._add(
                "CHECK-SOURCE-GIT-004",
                CHECKSUM_PATH,
                "checksum manifest paths must exactly match the sorted Git index excluding the manifest itself",
            )
            return

        for path, expected_digest in entries:
            try:
                actual_digest = hashlib.sha256(self._git_blob_by_oid(index[path])).hexdigest()
            except (KeyError, OSError, subprocess.CalledProcessError) as error:
                self._add(
                    "CHECK-SOURCE-GIT-003",
                    Path(path),
                    f"cannot read Git index blob for checksum validation: {error}",
                )
                continue
            if actual_digest != expected_digest:
                self._add(
                    "CHECK-SOURCE-GIT-005",
                    Path(path),
                    "Git index blob SHA-256 does not match source checksum manifest",
                )

    def update_git_checksum_manifest(self) -> int:
        """Rewrite the source checksum manifest from the exact Git index blobs."""
        if not self._is_git_checkout():
            raise RuntimeError("Git repository metadata is required to update source checksums")
        index = self._git_index_entries()
        paths = sorted(path for path in index if path != CHECKSUM_PATH.as_posix())
        lines: list[str] = []
        for path in paths:
            if "\n" in path or "\r" in path or not self._safe_relative(path):
                raise ValueError(f"unsafe Git path cannot be represented in checksum manifest: {path!r}")
            digest = hashlib.sha256(self._git_blob_by_oid(index[path])).hexdigest()
            lines.append(f"{digest}  {path}\n")
        target = self.root / CHECKSUM_PATH
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("".join(lines), encoding="utf-8")
        return len(paths)

    def _git_index_entries(self) -> dict[str, str]:
        """Return stage-zero index paths mapped to immutable Git object IDs.

        Reading object IDs from ``git ls-files --stage`` avoids path-based Git
        revision parsing and makes checksum verification independent from
        checkout filters such as ``eol=crlf``. Unmerged index stages are rejected
        because they cannot represent a deterministic commit candidate.
        """
        completed = subprocess.run(
            ["git", "-C", str(self.root), "ls-files", "--stage", "-z"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        entries: dict[str, str] = {}
        for raw_entry in completed.stdout.split(b"\0"):
            if not raw_entry:
                continue
            try:
                metadata, raw_path = raw_entry.split(b"\t", 1)
                _mode, raw_oid, raw_stage = metadata.split(b" ", 2)
                path = raw_path.decode("utf-8")
                oid = raw_oid.decode("ascii")
                stage = raw_stage.decode("ascii")
            except (UnicodeError, ValueError) as error:
                raise RuntimeError(f"cannot parse Git index entry: {error}") from error
            if stage != "0":
                raise RuntimeError(f"unmerged Git index entry is not allowed: {path} (stage {stage})")
            if path in entries:
                raise RuntimeError(f"duplicate Git index entry is not allowed: {path}")
            entries[path] = oid
        return entries

    def _git_blob_by_oid(self, oid: str) -> bytes:
        """Read an indexed blob by object ID without checkout transformations."""
        completed = subprocess.run(
            ["git", "-C", str(self.root), "cat-file", "blob", oid],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        return completed.stdout

    def _check_staged_snapshot(self) -> None:
        """Validate the exact Git index snapshot that would be committed.

        Working-tree validation alone cannot prove that every canonical source is
        staged. This check materializes the index into an isolated directory and
        runs the full source-integrity graph against that candidate commit.
        """
        if not self.require_staged_snapshot:
            return
        if not self._is_git_checkout():
            self._add(
                "CHECK-SOURCE-STAGED-001",
                Path("."),
                "staged snapshot validation was required but repository metadata is unavailable",
            )
            return

        import tempfile

        try:
            with tempfile.TemporaryDirectory(prefix="infranexum-staged-") as temporary:
                staged_root = Path(temporary) / "snapshot"
                staged_root.mkdir()
                prefix = str(staged_root) + "/"
                subprocess.run(
                    [
                        "git",
                        "-C",
                        str(self.root),
                        "checkout-index",
                        "--all",
                        "--force",
                        f"--prefix={prefix}",
                    ],
                    check=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )
                nested = SourceIntegrityChecker(
                    staged_root,
                    require_git_tracking=False,
                    require_staged_snapshot=False,
                ).run()
        except (OSError, subprocess.CalledProcessError) as error:
            self._add(
                "CHECK-SOURCE-STAGED-001",
                Path("."),
                f"cannot materialize Git index snapshot: {error}",
            )
            return

        for violation in nested:
            self._add(
                "CHECK-SOURCE-STAGED-002",
                Path(violation.path),
                f"staged snapshot violates {violation.check_id}: {violation.message}",
            )

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
    def _manifest_reference_is_within(manifest_dir: Path, reference: str, expected_root: Path) -> bool:
        """Return whether a manifest-relative reference stays within the expected repository support root."""
        try:
            (manifest_dir / reference).resolve().relative_to(expected_root.resolve())
        except (OSError, ValueError):
            return False
        return True

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
