from __future__ import annotations

import re
import unittest
from pathlib import Path


class ServerClockOwnershipTest(unittest.TestCase):
    """Prevent ambiguous Spring Clock injection across Server bounded contexts."""

    ROOT = Path(__file__).resolve().parents[2]
    SERVER = ROOT / "src/applications/server/main/io/infranexum/server"

    def test_every_injected_clock_is_explicitly_qualified(self) -> None:
        violations: list[str] = []
        for path in sorted(self.SERVER.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            # Inspect method/constructor parameter lists. Field declarations and local variables
            # are intentionally outside this rule; Spring ambiguity occurs at injection points.
            for signature in re.finditer(r"\(([^()]*)\)", text, re.DOTALL):
                parameters = signature.group(1)
                if "Clock" not in parameters:
                    continue
                for parameter in parameters.split(","):
                    if re.search(r"\bClock\s+\w+", parameter) and "@Qualifier(" not in parameter:
                        line = text.count("\n", 0, signature.start()) + 1
                        violations.append(f"{path.relative_to(self.ROOT)}:{line}: {parameter.strip()}")
        self.assertEqual([], violations, "Clock injection must be context-qualified:\n" + "\n".join(violations))

    def test_server_declares_primary_platform_clock_and_distinct_context_clocks(self) -> None:
        platform = (self.SERVER / "configuration/PlatformClockConfiguration.java").read_text(encoding="utf-8")
        activation = (self.SERVER / "platform/entitlements/ActivationRuntimeConfiguration.java").read_text(
            encoding="utf-8"
        )
        workers = (self.SERVER / "workers/WorkerRuntimeConfiguration.java").read_text(encoding="utf-8")

        self.assertIn('@Bean("platformClock")', platform)
        self.assertIn('@Primary', platform)
        self.assertIn('@Bean("entitlementClock")', activation)
        self.assertIn('@Bean("workerClock")', workers)
        self.assertNotIn('@Primary', activation)
        self.assertNotIn('@Primary', workers)

    def test_only_platform_clock_is_primary(self) -> None:
        primary_clock_owners: list[str] = []
        for path in sorted(self.SERVER.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            if "@Primary" in text and re.search(r"@Primary\s+Clock\s+\w+\s*\(", text):
                primary_clock_owners.append(str(path.relative_to(self.ROOT)))
        self.assertEqual(
            ["src/applications/server/main/io/infranexum/server/configuration/PlatformClockConfiguration.java"],
            primary_clock_owners,
        )


if __name__ == "__main__":
    unittest.main()
