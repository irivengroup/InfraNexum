from __future__ import annotations

import re
import stat
import subprocess
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

MAX_PROJECT_RELATIVE_LENGTH = 120
MAX_ARCHIVE_ROOT_LENGTH = 32
MAX_ARCHIVE_MEMBER_LENGTH = 160
MAX_SEGMENT_LENGTH = 80
_WINDOWS_RESERVED = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{index}" for index in range(1, 10)),
    *(f"LPT{index}" for index in range(1, 10)),
}
_WINDOWS_INVALID = re.compile(r'[<>:"\\|?*]|[\x00-\x1f]')


@dataclass(frozen=True, order=True)
class ArchiveViolation:
    check_id: str
    path: str
    message: str


class ArchiveCompatibilityChecker:
    """Checks that a source ZIP is complete and safely extractable on Windows."""

    def __init__(self, archive: Path, repository_root: Path | None = None) -> None:
        self.archive = archive.resolve()
        self.repository_root = repository_root.resolve() if repository_root is not None else None
        self.violations: list[ArchiveViolation] = []

    def run(self) -> tuple[ArchiveViolation, ...]:
        try:
            with zipfile.ZipFile(self.archive) as source:
                bad_member = source.testzip()
                if bad_member is not None:
                    self._add("CHECK-ARCHIVE-001", bad_member, "ZIP member checksum is invalid")
                infos = source.infolist()
                self._check_members(infos)
                if self.repository_root is not None:
                    self._check_git_parity(infos)
        except (OSError, zipfile.BadZipFile) as error:
            self._add("CHECK-ARCHIVE-001", self.archive.name, f"archive cannot be read: {error}")
        return tuple(sorted(set(self.violations)))

    def _check_members(self, infos: list[zipfile.ZipInfo]) -> None:
        if not infos:
            self._add("CHECK-ARCHIVE-002", self.archive.name, "archive is empty")
            return

        roots: set[str] = set()
        windows_keys: dict[str, str] = {}
        for info in infos:
            name = info.filename
            if "\\" in name:
                self._add("CHECK-ARCHIVE-003", name, "archive member must use POSIX separators")
                continue
            pure = PurePosixPath(name)
            parts = tuple(part for part in pure.parts if part != "/")
            canonical = pure.as_posix() + ("/" if info.is_dir() and not pure.as_posix().endswith("/") else "")
            if (
                pure.is_absolute()
                or not parts
                or any(part in {"", ".", ".."} for part in parts)
                or name != canonical
            ):
                self._add("CHECK-ARCHIVE-003", name, "archive member must be a safe canonical relative path")
                continue
            root = parts[0]
            roots.add(root)
            if len(root) > MAX_ARCHIVE_ROOT_LENGTH:
                self._add(
                    "CHECK-ARCHIVE-004",
                    name,
                    f"archive root exceeds {MAX_ARCHIVE_ROOT_LENGTH} characters",
                )
            if len(name) > MAX_ARCHIVE_MEMBER_LENGTH:
                self._add(
                    "CHECK-ARCHIVE-005",
                    name,
                    f"archive member exceeds {MAX_ARCHIVE_MEMBER_LENGTH} characters",
                )
            relative = "/".join(parts[1:])
            if relative and len(relative) > MAX_PROJECT_RELATIVE_LENGTH:
                self._add(
                    "CHECK-ARCHIVE-006",
                    name,
                    f"project-relative path exceeds {MAX_PROJECT_RELATIVE_LENGTH} characters",
                )
            for segment in parts:
                self._check_windows_segment(name, segment)

            normalized = "/".join(segment.rstrip(" .").casefold() for segment in parts)
            previous = windows_keys.get(normalized)
            if previous is not None and previous != name:
                self._add(
                    "CHECK-ARCHIVE-008",
                    name,
                    f"Windows-normalized path collides with {previous}",
                )
            else:
                windows_keys[normalized] = name

            mode = (info.external_attr >> 16) & 0xFFFF
            if mode and stat.S_ISLNK(mode):
                self._add("CHECK-ARCHIVE-009", name, "symbolic links are forbidden in published source ZIPs")

        if len(roots) != 1:
            self._add(
                "CHECK-ARCHIVE-010",
                self.archive.name,
                f"archive must contain exactly one root directory; found={sorted(roots)}",
            )

    def _check_windows_segment(self, member: str, segment: str) -> None:
        if len(segment) > MAX_SEGMENT_LENGTH:
            self._add(
                "CHECK-ARCHIVE-007",
                member,
                f"path segment exceeds {MAX_SEGMENT_LENGTH} characters: {segment}",
            )
        if segment.endswith((" ", ".")):
            self._add("CHECK-ARCHIVE-007", member, "Windows path segment cannot end with a dot or space")
        if _WINDOWS_INVALID.search(segment):
            self._add("CHECK-ARCHIVE-007", member, "Windows-invalid character in path segment")
        stem = segment.split(".", 1)[0].upper()
        if stem in _WINDOWS_RESERVED:
            self._add("CHECK-ARCHIVE-007", member, f"Windows-reserved path segment: {segment}")

    def _check_git_parity(self, infos: list[zipfile.ZipInfo]) -> None:
        assert self.repository_root is not None
        try:
            tracked = subprocess.check_output(
                ["git", "-C", str(self.repository_root), "ls-files", "-z"],
                text=False,
                stderr=subprocess.DEVNULL,
            ).decode("utf-8").split("\0")
        except (OSError, subprocess.CalledProcessError, UnicodeDecodeError) as error:
            self._add("CHECK-ARCHIVE-011", self.archive.name, f"cannot read Git index: {error}")
            return
        expected = {path for path in tracked if path}
        roots = {PurePosixPath(info.filename).parts[0] for info in infos if PurePosixPath(info.filename).parts}
        if len(roots) != 1:
            return
        root = next(iter(roots))
        actual = {
            "/".join(PurePosixPath(info.filename).parts[1:])
            for info in infos
            if not info.is_dir() and len(PurePosixPath(info.filename).parts) > 1
        }
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        if missing or extra:
            detail = f"Git/archive parity mismatch; missing={missing[:10]}, extra={extra[:10]}"
            self._add("CHECK-ARCHIVE-012", root, detail)

    def _add(self, check_id: str, path: str, message: str) -> None:
        self.violations.append(ArchiveViolation(check_id, path, message))
