"""Regression gates for JDBC repository constructor contracts used by the Server."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


class ServerRepositoryCompositionContractsTest(unittest.TestCase):
    """Keeps composition-root constructor calls aligned with authoritative adapters."""

    ROOT = Path(__file__).resolve().parents[2]
    JDBC = ROOT / "src/components/adapters/jdbc/main"
    SERVER = ROOT / "src/applications/server/main"

    @staticmethod
    def _parenthesized(text: str, start: int) -> str:
        """Return content until the matching ')' while respecting Java strings."""
        depth = 1
        in_string = False
        escaped = False
        index = start
        while index < len(text) and depth:
            char = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
            else:
                if char == '"':
                    in_string = True
                elif char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
            index += 1
        if depth != 0:
            raise AssertionError("unterminated Java parenthesized expression")
        return text[start : index - 1]

    @staticmethod
    def _argument_count(arguments: str) -> int:
        """Count top-level Java arguments without being confused by nested expressions."""
        if not arguments.strip():
            return 0
        depth = 0
        in_string = False
        escaped = False
        count = 1
        for char in arguments:
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char in "([{<":
                depth += 1
            elif char in ")]}>":
                depth = max(0, depth - 1)
            elif char == "," and depth == 0:
                count += 1
        return count

    def test_server_jdbc_repository_constructor_arities_match_adapter_contracts(self) -> None:
        constructor_arities: dict[str, set[int]] = {}
        for path in self.JDBC.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            class_match = re.search(r"public\s+(?:final\s+)?class\s+(Jdbc\w+Repository)\b", text)
            if class_match is None:
                continue
            class_name = class_match.group(1)
            arities: set[int] = set()
            for match in re.finditer(rf"public\s+{re.escape(class_name)}\s*\(", text):
                arguments = self._parenthesized(text, match.end())
                arities.add(self._argument_count(arguments))
            self.assertTrue(arities, f"{class_name} must expose an explicit public constructor")
            constructor_arities[class_name] = arities

        mismatches: list[str] = []
        for path in self.SERVER.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            for match in re.finditer(r"new\s+(Jdbc\w+Repository)\s*\(", text):
                class_name = match.group(1)
                if class_name not in constructor_arities:
                    continue
                arguments = self._parenthesized(text, match.end())
                actual = self._argument_count(arguments)
                expected = constructor_arities[class_name]
                if actual not in expected:
                    mismatches.append(
                        f"{path.relative_to(self.ROOT)}: {class_name} uses {actual} argument(s), "
                        f"expected one of {sorted(expected)}"
                    )

        self.assertEqual([], mismatches, "\n".join(mismatches))

    def test_memory_runtime_does_not_compose_durable_rsot_boundaries(self) -> None:
        object_controller = (self.SERVER / "io/infranexum/server/rsot/RsotObjectController.java").read_text(encoding="utf-8")
        schema_controller = (self.SERVER / "io/infranexum/server/rsot/RsotSchemaController.java").read_text(encoding="utf-8")
        runtime = (self.SERVER / "io/infranexum/server/rsot/RsotRuntimeConfiguration.java").read_text(encoding="utf-8")
        condition = "${infranexum.persistence.mode:MEMORY}"
        for text in (object_controller, schema_controller, runtime):
            self.assertIn("ConditionalOnExpression", text)
            self.assertIn(condition, text)
            self.assertIn("POSTGRESQL", text)
            self.assertIn("ORACLE", text)

        application_test = (self.ROOT / "tests/java/server/io/infranexum/server/InfraNexumServerApplicationTest.java").read_text(encoding="utf-8")
        self.assertIn('.run(', application_test)
        self.assertIn('"--infranexum.entitlements.enabled=false"', application_test)
        self.assertIn('"--infranexum.persistence.mode=MEMORY"', application_test)
        self.assertNotIn('.properties(\n                        "spring.main.banner-mode=off"', application_test)


if __name__ == "__main__":
    unittest.main()
