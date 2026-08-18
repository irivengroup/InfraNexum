"""Regression gates for immutable Spring Boot integration configuration binding."""

from __future__ import annotations

import unittest
from pathlib import Path


class IntegrationConfigurationBindingArchitectureTest(unittest.TestCase):
    """Keeps the multi-constructor record explicitly constructor-bound."""

    ROOT = Path(__file__).resolve().parents[2]
    PROPERTIES = ROOT / "src/applications/server/main/io/infranexum/server/integrations/IntegrationRuntimeProperties.java"
    JAVA_TEST = ROOT / "tests/java/server/io/infranexum/server/integrations/IntegrationRuntimeConfigurationTest.java"

    def test_canonical_constructor_is_explicitly_selected_for_spring_binding(self) -> None:
        source = self.PROPERTIES.read_text(encoding="utf-8")

        self.assertIn(
            "import org.springframework.boot.context.properties.bind.ConstructorBinding;",
            source,
        )
        self.assertIn(
            "@ConstructorBinding\n    public IntegrationRuntimeProperties {",
            source,
        )
        self.assertIn(
            "/** Compatibility constructor for callers created before outbound notifications became configurable. */",
            source,
        )
        self.assertNotIn("public IntegrationRuntimeProperties()", source)

    def test_runtime_regression_test_loads_real_application_yaml(self) -> None:
        test_source = self.JAVA_TEST.read_text(encoding="utf-8")

        self.assertIn("ConfigDataApplicationContextInitializer", test_source)
        self.assertIn("applicationYamlBindsWithNoIntegrationEndpointsConfigured", test_source)
        self.assertIn("assertNull(context.getStartupFailure())", test_source)
        self.assertIn("assertTrue(properties.endpoints().isEmpty())", test_source)
        self.assertIn("assertTrue(properties.jiraAssets().connectors().isEmpty())", test_source)
        self.assertIn("assertTrue(properties.serviceNow().connectors().isEmpty())", test_source)
        self.assertIn("assertTrue(properties.notifications().endpoints().isEmpty())", test_source)
        self.assertIn("assertTrue(properties.governance().isEmpty())", test_source)


if __name__ == "__main__":
    unittest.main()
