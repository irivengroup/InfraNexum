from __future__ import annotations

import contextlib
import io
import json
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
from pathlib import Path
from unittest.mock import patch

from validation.source_integrity.checker import (
    CHECKSUM_PATH,
    INVENTORY_PATH,
    MAX_ARCHIVE_PREFIX_LENGTH,
    MAX_PATH_COMPONENT_LENGTH,
    MAX_RELATIVE_PATH_LENGTH,
    SCHEMA,
    SourceIntegrityChecker,
)
from validation.source_integrity.cli import main as cli_main


POM = """<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>io.infranexum</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>src/components/module</module></modules></project>"""
MODULE_POM = """<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>io.infranexum</groupId><artifactId>module</artifactId><version>1</version><build><testSourceDirectory>${maven.multiModuleProjectDirectory}/tests/java/module</testSourceDirectory></build></project>"""
A_JAVA = """package io.infranexum.a;\nimport io.infranexum.b.B;\npublic final class A { private final B b = new B(); }\n"""
B_JAVA = """package io.infranexum.b;\npublic final class B {}\n"""


class SourceIntegrityCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repository"
        self.root.mkdir()
        self.write("pom.xml", POM)
        self.write("VERSION", "test\n")
        self.write(
            "Makefile",
            "\n".join(
                f"{target}: source-integrity-check"
                for target in (
                    "architecture-test", "toolchain-test", "migration-test", "eventing-test",
                    "persistence-test", "capabilities-test", "entitlements-test", "audit-test"
                )
            ) + "\n",
        )
        self.write("src/components/module/pom.xml", MODULE_POM)
        self.write("src/components/module/main/io/infranexum/a/A.java", A_JAVA)
        self.write("src/components/module/main/io/infranexum/b/B.java", B_JAVA)
        self.write(
            "tests/java/module/io/infranexum/a/ATest.java",
            "package io.infranexum.a; final class ATest {}\n",
        )
        self.write(".github/workflows/test.yml", "name: test\n")
        self.write_inventory()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def canonical(self) -> list[str]:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        return sorted(checker.canonical_files())

    def write_inventory(self, paths: list[str] | None = None) -> None:
        target = self.root / INVENTORY_PATH
        target.parent.mkdir(parents=True, exist_ok=True)
        if paths is None:
            paths = self.canonical()
        target.write_text(json.dumps({"schema": SCHEMA, "paths": sorted(paths)}, indent=2) + "\n", encoding="utf-8")

    def ids(self, *, require_git_tracking: bool | None = False) -> set[str]:
        return {item.check_id for item in SourceIntegrityChecker(self.root, require_git_tracking=require_git_tracking).run()}

    def test_reference_repository_is_valid(self) -> None:
        self.assertEqual(set(), self.ids())

    def test_path_budget_rejects_long_relative_path(self) -> None:
        stem = "x" * 40
        relative = f"src/components/module/test/io/infranexum/{stem}/{stem}/TooLong.java"
        self.assertGreater(len(relative), MAX_RELATIVE_PATH_LENGTH)
        self.write(relative, "package io.infranexum.longpath;\nfinal class TooLong {}\n")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-PATH-001", self.ids())

    def test_path_budget_rejects_long_component(self) -> None:
        component = "x" * (MAX_PATH_COMPONENT_LENGTH + 1)
        relative = f"src/components/module/{component}/file.txt"
        self.write(relative, "x\n")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-PATH-002", self.ids())

    def test_product_layout_rejects_test_sources_under_src(self) -> None:
        self.write(
            "src/components/module/test/io/infranexum/a/ATest.java",
            "package io.infranexum.a; final class ATest {}\n",
        )
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-LAYOUT-003", self.ids())

    def test_product_layout_rejects_legacy_top_level_product_spaces(self) -> None:
        self.write("applications/server/runtime.txt", "legacy layout\n")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-LAYOUT-002", self.ids())

    def test_product_layout_rejects_container_deployment_assets(self) -> None:
        self.write("src/deployment/docker/compose.yaml", "services: {}\n")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-LAYOUT-007", self.ids())

        (self.root / "src/deployment/docker/compose.yaml").unlink()
        self.write("src/applications/server/server.Dockerfile", "FROM scratch\n")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-LAYOUT-007", self.ids())

        (self.root / "src/applications/server/server.Dockerfile").unlink()
        self.write("docker/server.Dockerfile", "FROM scratch\n")
        self.write(".dockerignore", "target\n")
        self.write_inventory()
        self.assertNotIn("CHECK-SOURCE-LAYOUT-007", self.ids())

    def test_product_layout_reports_missing_source_root(self) -> None:
        shutil.rmtree(self.root / "src")
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        checker._check_product_layout()
        self.assertEqual("CHECK-SOURCE-LAYOUT-001", checker.violations[0].check_id)

    def test_release_archive_prefix_is_short_and_version_derived(self) -> None:
        self.write(
            "src/distribution/release-manifest.json",
            json.dumps({
                "baseline": "../../BASELINE.json",
                "validation_reports": ["../../artifacts/validation/source-integrity.json"],
                "source_archive": {
                    "prefix": "infranexum-test",
                    "release_checksum_manifest": "../../artifacts/validation/release-files.sha256",
                },
            }),
        )
        self.write_inventory()
        self.assertEqual(set(), self.ids())

        self.write(
            "src/distribution/release-manifest.json",
            json.dumps({"source_archive": {"prefix": "x" * (MAX_ARCHIVE_PREFIX_LENGTH + 1)}}),
        )
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-PATH-003", self.ids())

        self.write("src/distribution/release-manifest.json", "{")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-PATH-003", self.ids())

    def test_release_manifest_references_follow_repository_support_layout(self) -> None:
        manifest = {
            "baseline": "../BASELINE.json",
            "validation_reports": ["../artifacts/validation/source-integrity.json"],
            "source_archive": {
                "prefix": "infranexum-test",
                "release_checksum_manifest": "../artifacts/validation/release-files.sha256",
            },
        }
        self.write("src/distribution/release-manifest.json", json.dumps(manifest))
        self.write_inventory()
        ids = self.ids()
        self.assertIn("CHECK-SOURCE-LAYOUT-004", ids)
        self.assertIn("CHECK-SOURCE-LAYOUT-005", ids)
        self.assertIn("CHECK-SOURCE-LAYOUT-006", ids)

    def test_release_manifest_rejects_validation_reference_escape(self) -> None:
        manifest = {
            "baseline": "../../BASELINE.json",
            "validation_reports": ["../../artifacts/validation/../../secrets.txt"],
            "source_archive": {
                "prefix": "infranexum-test",
                "release_checksum_manifest": "../../artifacts/validation/release-files.sha256",
            },
        }
        self.write("src/distribution/release-manifest.json", json.dumps(manifest))
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-LAYOUT-005", self.ids())

    def test_inventory_missing_invalid_unsafe_duplicate_and_unsorted_are_rejected(self) -> None:
        (self.root / INVENTORY_PATH).unlink()
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())
        self.write(str(INVENTORY_PATH), "{")
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())
        self.write(str(INVENTORY_PATH), json.dumps({"schema": "wrong", "paths": []}))
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())
        self.write(str(INVENTORY_PATH), json.dumps({"schema": SCHEMA, "paths": ["../escape"]}))
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())
        self.write(str(INVENTORY_PATH), json.dumps({"schema": SCHEMA, "paths": ["b", "a", "a"]}))
        self.assertIn("CHECK-SOURCE-INVENTORY-001", self.ids())

    def test_inventory_rejects_case_insensitive_collisions(self) -> None:
        paths = self.canonical()
        paths.extend(["src/components/Case.txt", "src/components/case.txt"])
        self.write("src/components/Case.txt", "a")
        self.write("src/components/case.txt", "b")
        self.write_inventory(paths)
        self.assertIn("CHECK-SOURCE-INVENTORY-004", self.ids())

    def test_inventory_detects_missing_and_undeclared_files(self) -> None:
        paths = self.canonical()
        declared = paths + ["src/components/expected-but-missing.txt"]
        self.write_inventory(declared)
        self.assertIn("CHECK-SOURCE-INVENTORY-002", self.ids())
        self.write_inventory(paths)
        self.write("src/components/module/extra.txt", "extra")
        self.assertIn("CHECK-SOURCE-INVENTORY-003", self.ids())

    def test_java_graph_rejects_filename_type_mismatch(self) -> None:
        self.write(
            "src/components/module/main/io/infranexum/a/WrongName.java",
            "package io.infranexum.a;\npublic final class RightName {}\n",
        )
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-JAVA-004", self.ids())

    def test_java_graph_accepts_package_private_top_level_type(self) -> None:
        self.write(
            "src/components/module/main/io/infranexum/internal/Internal.java",
            "package io.infranexum.internal;\nfinal class Internal {}\n",
        )
        self.write_inventory()
        self.assertEqual(set(), self.ids())

    def test_java_graph_detects_missing_import_duplicate_and_malformed_source(self) -> None:
        (self.root / "src/components/module/main/io/infranexum/b/B.java").unlink()
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-JAVA-003", self.ids())

        self.write("src/components/module/main/io/infranexum/b/B.java", B_JAVA)
        self.write("src/components/module/main/io/infranexum/duplicate/B.java", B_JAVA)
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-JAVA-002", self.ids())

        self.write("src/components/module/main/io/infranexum/invalid/Invalid.java", "final class Invalid {}\n")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-JAVA-001", self.ids())

    def test_java_read_failure_is_reported(self) -> None:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        missing = self.root / "src/components/missing.java"
        self.assertIsNone(checker._read(missing, "CHECK-SOURCE-JAVA-001"))
        self.assertEqual("CHECK-SOURCE-JAVA-001", checker.violations[0].check_id)

    def test_java_graph_skips_unreadable_source_after_reporting(self) -> None:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        original = checker._read

        def unreadable(path: Path, check_id: str) -> str | None:
            if path.name == "A.java":
                checker._add(check_id, path, "simulated read failure")
                return None
            return original(path, check_id)

        with patch.object(checker, "_read", side_effect=unreadable):
            checker._check_java_graph()
        self.assertIn("CHECK-SOURCE-JAVA-001", {item.check_id for item in checker.violations})

    def test_maven_requires_external_test_source(self) -> None:
        path = self.root / "src/components/module/pom.xml"
        path.write_text(MODULE_POM.replace(
            "${maven.multiModuleProjectDirectory}/tests/java/module", "test"
        ), encoding="utf-8")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-004", self.ids())

    def test_maven_requires_existing_external_tests(self) -> None:
        shutil.rmtree(self.root / "tests/java/module")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-005", self.ids())

    def test_maven_rejects_invalid_module_pom(self) -> None:
        self.write("src/components/module/pom.xml", "<")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-004", self.ids())

    def test_maven_rejects_unsafe_or_non_test_external_root(self) -> None:
        path = self.root / "src/components/module/pom.xml"
        for value in (
            "${maven.multiModuleProjectDirectory}/../escape",
            "${maven.multiModuleProjectDirectory}/support/java/module",
        ):
            path.write_text(
                MODULE_POM.replace(
                    "${maven.multiModuleProjectDirectory}/tests/java/module", value
                ),
                encoding="utf-8",
            )
            self.write_inventory()
            self.assertIn("CHECK-SOURCE-MAVEN-004", self.ids())

    def test_maven_rejects_missing_test_source_declaration(self) -> None:
        path = self.root / "src/components/module/pom.xml"
        path.write_text(
            MODULE_POM.replace(
                "<build><testSourceDirectory>${maven.multiModuleProjectDirectory}/tests/java/module</testSourceDirectory></build>",
                "",
            ),
            encoding="utf-8",
        )
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-004", self.ids())

    def test_maven_detects_orphan_module_pom(self) -> None:
        self.write("src/components/orphan/pom.xml", MODULE_POM.replace("<artifactId>module</artifactId>", "<artifactId>orphan</artifactId>"))
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-003", self.ids())

    def test_makefile_requires_source_integrity_preflight_for_validation_suites(self) -> None:
        makefile = self.root / "Makefile"
        text = makefile.read_text(encoding="utf-8")
        makefile.write_text(text.replace("capabilities-test: source-integrity-check", "capabilities-test:"), encoding="utf-8")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAKE-002", self.ids())
        makefile.unlink()
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAKE-001", self.ids())

    def test_maven_invalid_root_unsafe_and_missing_module_are_rejected(self) -> None:
        self.write("pom.xml", "<")
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-001", self.ids())
        self.write("pom.xml", POM.replace("src/components/module", "../module"))
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-001", self.ids())
        self.write("pom.xml", POM)
        (self.root / "src/components/module/pom.xml").unlink()
        self.write_inventory()
        self.assertIn("CHECK-SOURCE-MAVEN-002", self.ids())

    def test_git_tracking_detects_untracked_inventory_file(self) -> None:
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        self.assertEqual(set(), self.ids(require_git_tracking=True))
        tracked = self.root / "src/components/module/main/io/infranexum/b/B.java"
        subprocess.run(["git", "-C", str(self.root), "rm", "--cached", "-q", str(tracked.relative_to(self.root))], check=True)
        self.assertIn("CHECK-SOURCE-GIT-002", self.ids(require_git_tracking=True))

    def test_git_tracking_does_not_duplicate_missing_checkout_violation(self) -> None:
        """A missing checkout entry is reported once by the inventory check.

        This reproduces the hosted checkout failure where inventory entries were
        both absent from disk and absent from the Git index. CHECK-SOURCE-GIT-002
        is reserved for files that actually exist locally but were not staged.
        """
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        tracked = self.root / "src/components/module/main/io/infranexum/b/B.java"
        subprocess.run(
            ["git", "-C", str(self.root), "rm", "--cached", "-q", str(tracked.relative_to(self.root))],
            check=True,
        )
        tracked.unlink()

        ids = self.ids(require_git_tracking=True)

        self.assertIn("CHECK-SOURCE-INVENTORY-002", ids)
        self.assertNotIn("CHECK-SOURCE-GIT-002", ids)

    def test_git_required_without_repository_and_git_command_error_are_reported(self) -> None:
        self.assertIn("CHECK-SOURCE-GIT-001", self.ids(require_git_tracking=True))
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        with patch("validation.source_integrity.checker.subprocess.run", side_effect=OSError("git missing")):
            self.assertFalse(checker._is_git_checkout())
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        checker = SourceIntegrityChecker(self.root, require_git_tracking=True)
        with patch.object(checker, "_is_git_checkout", return_value=True), patch(
            "validation.source_integrity.checker.subprocess.run", side_effect=OSError("git missing")
        ):
            checker._check_git_tracking(tuple(self.canonical()))
        self.assertIn("CHECK-SOURCE-GIT-001", {item.check_id for item in checker.violations})

    def test_staged_snapshot_accepts_complete_index(self) -> None:
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)

        violations = SourceIntegrityChecker(
            self.root,
            require_git_tracking=True,
            require_staged_snapshot=True,
        ).run()

        self.assertEqual([], violations)

    def test_staged_snapshot_rejects_commit_candidate_missing_inventory_source(self) -> None:
        """The candidate commit fails even while the working tree still has the source."""
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        relative = "src/components/module/main/io/infranexum/b/B.java"
        subprocess.run(
            ["git", "-C", str(self.root), "rm", "--cached", "-q", relative],
            check=True,
        )

        violations = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_staged_snapshot=True,
        ).run()
        staged = [item for item in violations if item.check_id == "CHECK-SOURCE-STAGED-002"]

        self.assertTrue(staged)
        self.assertTrue(any(item.path == relative for item in staged))
        self.assertTrue(any("CHECK-SOURCE-INVENTORY-002" in item.message for item in staged))

    def test_git_checksum_manifest_accepts_exact_index_blobs(self) -> None:
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        recorded = checker.update_git_checksum_manifest()
        subprocess.run(["git", "-C", str(self.root), "add", CHECKSUM_PATH.as_posix()], check=True)

        violations = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_git_checksums=True,
        ).run()

        self.assertGreater(recorded, 0)
        self.assertEqual([], violations)
        manifest = (self.root / CHECKSUM_PATH).read_text(encoding="utf-8").splitlines()
        self.assertEqual(recorded, len(manifest))
        self.assertNotIn(CHECKSUM_PATH.as_posix(), "\n".join(manifest))

    def test_git_checksum_manifest_is_independent_from_checkout_eol_filters(self) -> None:
        self.write(".gitattributes", "* text=auto eol=lf\n*.cmd text eol=crlf\n")
        self.write("script.cmd", "@echo off\necho InfraNexum\n")
        self.write_inventory()
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        checker.update_git_checksum_manifest()
        subprocess.run(["git", "-C", str(self.root), "add", CHECKSUM_PATH.as_posix()], check=True)

        # Simulate a Windows-style checkout transformation without staging it.
        (self.root / "script.cmd").write_bytes(b"@echo off\r\necho InfraNexum\r\n")
        violations = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_git_checksums=True,
        ).run()

        self.assertNotIn("CHECK-SOURCE-GIT-005", {item.check_id for item in violations})
        self.assertEqual([], violations)

    def test_git_checksum_manifest_detects_staged_blob_tampering(self) -> None:
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        checker.update_git_checksum_manifest()
        subprocess.run(["git", "-C", str(self.root), "add", CHECKSUM_PATH.as_posix()], check=True)

        self.write("src/components/module/main/io/infranexum/a/A.java", A_JAVA + "// staged change\n")
        subprocess.run(
            ["git", "-C", str(self.root), "add", "src/components/module/main/io/infranexum/a/A.java"],
            check=True,
        )
        violations = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_git_checksums=True,
        ).run()

        mismatches = [item for item in violations if item.check_id == "CHECK-SOURCE-GIT-005"]
        self.assertEqual(1, len(mismatches))
        self.assertEqual("src/components/module/main/io/infranexum/a/A.java", mismatches[0].path)

    def test_git_checksum_manifest_rejects_malformed_and_incomplete_manifests(self) -> None:
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        checker.update_git_checksum_manifest()
        manifest_path = self.root / CHECKSUM_PATH
        subprocess.run(["git", "-C", str(self.root), "add", CHECKSUM_PATH.as_posix()], check=True)

        manifest_path.write_text("not-a-checksum\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.root), "add", CHECKSUM_PATH.as_posix()], check=True)
        malformed = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_git_checksums=True,
        ).run()
        self.assertIn("CHECK-SOURCE-GIT-003", {item.check_id for item in malformed})

        checker.update_git_checksum_manifest()
        lines = manifest_path.read_text(encoding="utf-8").splitlines(keepends=True)
        manifest_path.write_text("".join(lines[:-1]), encoding="utf-8")
        subprocess.run(["git", "-C", str(self.root), "add", CHECKSUM_PATH.as_posix()], check=True)
        incomplete = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_git_checksums=True,
        ).run()
        self.assertIn("CHECK-SOURCE-GIT-004", {item.check_id for item in incomplete})

    def test_git_checksum_validation_requires_git_metadata_and_indexed_manifest(self) -> None:
        violations = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_git_checksums=True,
        ).run()
        self.assertIn("CHECK-SOURCE-GIT-003", {item.check_id for item in violations})
        with self.assertRaises(RuntimeError):
            SourceIntegrityChecker(self.root, require_git_tracking=False).update_git_checksum_manifest()

        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        violations = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_git_checksums=True,
        ).run()
        self.assertIn("CHECK-SOURCE-GIT-003", {item.check_id for item in violations})

    def test_git_checksum_manifest_rejects_invalid_utf8_unsafe_path_and_blob_failure(self) -> None:
        checker = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_git_checksums=True,
        )
        index = {CHECKSUM_PATH.as_posix(): "manifest", "src/a.txt": "blob"}
        with patch.object(checker, "_is_git_checkout", return_value=True), patch.object(
            checker, "_git_index_entries", return_value=index
        ), patch.object(checker, "_git_blob_by_oid", return_value=b"\xff"):
            checker._check_git_checksums()
        self.assertIn("checksum manifest must be UTF-8", checker.violations[-1].message)

        checker.violations.clear()
        unsafe_manifest = ("0" * 64 + "  ../escape\n").encode("utf-8")
        with patch.object(checker, "_is_git_checkout", return_value=True), patch.object(
            checker, "_git_index_entries", return_value=index
        ), patch.object(checker, "_git_blob_by_oid", return_value=unsafe_manifest):
            checker._check_git_checksums()
        self.assertIn("unsafe or duplicate checksum path", checker.violations[-1].message)

        checker.violations.clear()
        valid_manifest = ("0" * 64 + "  src/a.txt\n").encode("utf-8")
        calls = iter((valid_manifest, OSError("blob unavailable")))

        def blob_result(_oid: str) -> bytes:
            result = next(calls)
            if isinstance(result, OSError):
                raise result
            return result

        with patch.object(checker, "_is_git_checkout", return_value=True), patch.object(
            checker, "_git_index_entries", return_value=index
        ), patch.object(checker, "_git_blob_by_oid", side_effect=blob_result):
            checker._check_git_checksums()
        self.assertIn("cannot read Git index blob", checker.violations[-1].message)

    def test_git_checksum_update_rejects_unsafe_index_path(self) -> None:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        with patch.object(checker, "_is_git_checkout", return_value=True), patch.object(
            checker, "_git_index_entries", return_value={"../escape": "a" * 40}
        ):
            with self.assertRaisesRegex(ValueError, "unsafe Git path"):
                checker.update_git_checksum_manifest()

    def test_git_index_parser_rejects_unmerged_and_malformed_entries(self) -> None:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        unmerged = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=b"100644 " + b"a" * 40 + b" 2\tsrc/a.txt\0", stderr=b""
        )
        with patch("validation.source_integrity.checker.subprocess.run", return_value=unmerged):
            with self.assertRaisesRegex(RuntimeError, "unmerged Git index entry"):
                checker._git_index_entries()

        malformed = subprocess.CompletedProcess(args=[], returncode=0, stdout=b"broken\0", stderr=b"")
        with patch("validation.source_integrity.checker.subprocess.run", return_value=malformed):
            with self.assertRaisesRegex(RuntimeError, "cannot parse Git index entry"):
                checker._git_index_entries()

        duplicate_stdout = (
            b"100644 " + b"a" * 40 + b" 0\tsrc/a.txt\0"
            + b"100644 " + b"b" * 40 + b" 0\tsrc/a.txt\0"
        )
        duplicate = subprocess.CompletedProcess(args=[], returncode=0, stdout=duplicate_stdout, stderr=b"")
        with patch("validation.source_integrity.checker.subprocess.run", return_value=duplicate):
            with self.assertRaisesRegex(RuntimeError, "duplicate Git index entry"):
                checker._git_index_entries()

    def test_cli_can_regenerate_git_checksum_manifest(self) -> None:
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        stdout = io.StringIO()
        with patch.object(
            sys,
            "argv",
            ["source-integrity", "--root", str(self.root), "--update-git-checksums"],
        ), contextlib.redirect_stdout(stdout):
            self.assertEqual(0, cli_main())
        self.assertIn("Git blob checksum(s)", stdout.getvalue())
        self.assertTrue((self.root / CHECKSUM_PATH).is_file())

    def test_staged_snapshot_requires_repository_metadata(self) -> None:
        violations = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_staged_snapshot=True,
        ).run()

        self.assertIn("CHECK-SOURCE-STAGED-001", {item.check_id for item in violations})

    def test_staged_snapshot_reports_materialization_failure(self) -> None:
        checker = SourceIntegrityChecker(
            self.root,
            require_git_tracking=False,
            require_staged_snapshot=True,
        )
        with patch.object(checker, "_is_git_checkout", return_value=True), patch(
            "validation.source_integrity.checker.subprocess.run", side_effect=OSError("index unavailable")
        ):
            checker._check_staged_snapshot()

        self.assertIn("CHECK-SOURCE-STAGED-001", {item.check_id for item in checker.violations})

    def test_external_path_and_cli_paths_are_covered(self) -> None:
        checker = SourceIntegrityChecker(self.root, require_git_tracking=False)
        outside = self.root.parent / "outside"
        checker._add("TEST", outside, "outside")
        self.assertEqual(outside.resolve().as_posix(), checker.violations[0].path)

        report = self.root / "reports/source-integrity.json"
        with patch.object(sys, "argv", ["source-integrity", "--root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, cli_main())
        self.assertEqual(0, json.loads(report.read_text(encoding="utf-8"))["violation_count"])

        (self.root / "src/components/module/main/io/infranexum/b/B.java").unlink()
        with patch.object(sys, "argv", ["source-integrity", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, cli_main())
        with patch.object(sys, "argv", ["source-integrity", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()), warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_module("validation.source_integrity.cli", run_name="__main__")
        self.assertEqual(1, raised.exception.code)

    def test_cli_can_regenerate_inventory(self) -> None:
        inventory = self.root / INVENTORY_PATH
        inventory.unlink()
        with patch.object(sys, "argv", ["source-integrity", "--root", str(self.root), "--update-inventory"]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, cli_main())
        payload = json.loads(inventory.read_text(encoding="utf-8"))
        self.assertEqual(SCHEMA, payload["schema"])
        self.assertEqual(sorted(payload["paths"]), payload["paths"])
        self.assertIn("src/components/module/main/io/infranexum/a/A.java", payload["paths"])

    def test_safe_relative_and_git_autodetection(self) -> None:
        self.assertTrue(SourceIntegrityChecker._safe_relative("src/a.txt"))
        self.assertFalse(SourceIntegrityChecker._safe_relative(""))
        self.assertFalse(SourceIntegrityChecker._safe_relative("../a"))
        self.assertFalse(SourceIntegrityChecker._safe_relative("/tmp/a"))
        self.assertFalse(SourceIntegrityChecker._safe_relative(r"C:\\temp\\a"))
        self.assertFalse(SourceIntegrityChecker._safe_relative("C:/temp/a"))
        self.assertFalse(SourceIntegrityChecker._safe_relative(r"\\temp\\a"))
        self.assertFalse(SourceIntegrityChecker._safe_relative(r"\\\\server\\share\\a"))
        self.assertFalse(SourceIntegrityChecker._safe_relative("src\\a.txt"))
        self.assertFalse(SourceIntegrityChecker._safe_relative("src/./a.txt"))
        self.assertFalse(SourceIntegrityChecker._safe_relative("src/a/../b.txt"))
        self.assertFalse(SourceIntegrityChecker._safe_relative("src/a.txt\nother"))
        checker = SourceIntegrityChecker(self.root)
        self.assertEqual(set(), {item.check_id for item in checker.run()})


if __name__ == "__main__":
    unittest.main()
