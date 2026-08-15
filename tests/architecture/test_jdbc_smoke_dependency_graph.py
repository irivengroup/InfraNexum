"""Regression tests for offline JDBC smoke compilation dependencies."""

from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MAKEFILE = (ROOT / "Makefile").read_text(encoding="utf-8")


def _target_body(target: str) -> str:
    """Return one Make target body without depending on recipe command ordering."""
    marker = f"{target}:\n"
    start = MAKEFILE.index(marker) + len(marker)
    end = MAKEFILE.find("\n\n", start)
    return MAKEFILE[start:] if end == -1 else MAKEFILE[start:end]


class JdbcSmokeDependencyGraphTest(unittest.TestCase):
    """Keep javac smoke targets aligned with the JDBC adapter's Maven graph."""

    def test_shared_jdbc_domain_sources_cover_every_owned_port_family(self) -> None:
        for required_path in (
            "domains/identity-local/main/io/infranexum/identity/local/domain/*.java",
            "domains/identity-local/main/io/infranexum/identity/local/ports/*.java",
            "domains/identity-access/main/io/infranexum/identity/access/domain/*.java",
            "domains/identity-access/main/io/infranexum/identity/access/ports/*.java",
            "domains/organization/main/io/infranexum/organization/domain/*.java",
            "domains/organization/main/io/infranexum/organization/ports/*.java",
            "domains/rsot/main/io/infranexum/rsot/domain/*.java",
            "domains/rsot/main/io/infranexum/rsot/ports/*.java",
            "domains/dcim/main/io/infranexum/dcim/physical/domain/*.java",
            "domains/dcim/main/io/infranexum/dcim/physical/ports/*.java",
            "domains/ddi/main/io/infranexum/ddi/ipam/domain/*.java",
            "domains/ddi/main/io/infranexum/ddi/ipam/ports/*.java",
        ):
            self.assertIn(required_path, MAKEFILE)

    def test_every_wildcard_jdbc_smoke_compiles_the_domain_sources_first(self) -> None:
        for target in (
            "java-jdbc-smoke",
            "java-jdbc-workers-smoke",
            "java-entitlement-runtime-smoke",
        ):
            body = _target_body(target)
            domain_position = body.index("$(JDBC_DOMAIN_SOURCES)")
            jdbc_position = body.index(
                "$(COMPONENT_ROOT)/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/*.java"
            )
            self.assertLess(domain_position, jdbc_position, target)


if __name__ == "__main__":
    unittest.main()
