
from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True, order=True)
class Violation:
    """A deterministic Architecture-as-Code failure."""

    check_id: str
    path: str
    message: str

    def to_dict(self) -> dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class Manifest:
    """Validated component manifest used for dependency checks."""

    path: Path
    component_id: str
    kind: str
    owner: str
    lifecycle: str
    languages: tuple[str, ...]
    dependencies: tuple[str, ...]


@dataclass(frozen=True)
class CheckReport:
    """Machine-readable validation report."""

    root: str
    policy: str
    violations: tuple[Violation, ...]

    @property
    def ok(self) -> bool:
        return not self.violations

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema": "infranexum.architecture-report/v1",
            "root": self.root,
            "policy": self.policy,
            "status": "PASS" if self.ok else "FAIL",
            "violation_count": len(self.violations),
            "violations": [violation.to_dict() for violation in self.violations],
        }
