"""Regression gates for the Bootstrap 5-native InfraNexum Web presentation contract."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


class BootstrapNativeFrontendTest(unittest.TestCase):
    """Reject proprietary presentation classes and CSS outside Bootstrap 5."""

    ROOT = Path(__file__).resolve().parents[2]
    PUBLIC = ROOT / "src/applications/web/public"
    BOOTSTRAP = PUBLIC / "assets/vendor/bootstrap-5.3.6.min.css"
    THEME = PUBLIC / "assets/infranexum-theme.css"

    @classmethod
    def setUpClass(cls) -> None:
        cls.bootstrap_css = cls.BOOTSTRAP.read_text(encoding="utf-8")
        cls.theme_css = cls.THEME.read_text(encoding="utf-8")
        cls.bootstrap_classes = set(re.findall(r"\.([A-Za-z_][A-Za-z0-9_-]*)", cls.bootstrap_css))

    def test_theme_only_overrides_bootstrap_tokens_and_components(self) -> None:
        self.assertNotRegex(self.theme_css, r"\.inx-[A-Za-z0-9_-]+")
        self.assertNotRegex(self.theme_css, r"--inx-[A-Za-z0-9_-]+")
        custom_properties = set(re.findall(r"(--[A-Za-z0-9_-]+)\s*:", self.theme_css))
        self.assertTrue(custom_properties)
        self.assertTrue(all(name.startswith("--bs-") for name in custom_properties), custom_properties)
        for selector in (".btn-primary", ".btn-outline-primary", ".nav-pills", ".card", ".table", ".alert", ".form-control", ".form-select"):
            self.assertIn(selector, self.theme_css)

    def test_static_html_and_template_literal_classes_are_bootstrap_native(self) -> None:
        files = [self.PUBLIC / "index.html", *sorted((self.PUBLIC / "assets").glob("*.mjs"))]
        unknown: dict[str, list[str]] = {}
        for path in files:
            text = path.read_text(encoding="utf-8")
            self.assertNotRegex(text, r"class(?:Name)?\s*=\s*[\"'`][^\"'`]*\binx-[A-Za-z0-9_-]+")
            for match in re.finditer(r"(?:class|className)\s*=\s*[\"'`]([^\"'`$]*)[\"'`]", text):
                for token in match.group(1).split():
                    if token and token not in self.bootstrap_classes:
                        unknown.setdefault(token, []).append(str(path.relative_to(self.ROOT)))
        self.assertEqual(unknown, {}, f"non-Bootstrap presentation classes found: {unknown}")

    def test_alert_surfaces_use_bootstrap_alert_component(self) -> None:
        index = (self.PUBLIC / "index.html").read_text(encoding="utf-8")
        notifications = (self.PUBLIC / "assets/notifications.mjs").read_text(encoding="utf-8")
        workspace = (self.PUBLIC / "assets/web-workspace-utils.mjs").read_text(encoding="utf-8")
        auth = (self.PUBLIC / "assets/auth.mjs").read_text(encoding="utf-8")
        self.assertIn('class="alert alert-info', index)
        self.assertRegex(index, r'class="[^"]*\balert\b[^"]*\balert-warning\b')
        self.assertIn("`alert alert-${bootstrapContext}", notifications)
        self.assertIn("`alert alert-${contextual[state] ?? 'info'}", workspace)
        self.assertIn("'alert-danger' : 'alert-info'", auth)

    def test_native_form_controls_are_authoritative(self) -> None:
        stable = (self.PUBLIC / "assets/stable-select.mjs").read_text(encoding="utf-8")
        temporal = (self.PUBLIC / "assets/temporal-picker.mjs").read_text(encoding="utf-8")
        self.assertIn("select.form-select", stable)
        self.assertNotIn("role', 'combobox", stable)
        self.assertNotIn("role', 'listbox", stable)
        self.assertNotIn("createElement('button')", stable)
        self.assertIn("datetime-local", temporal)
        self.assertIn("input.showPicker?.()", temporal)
        self.assertNotIn("createElement('div')", temporal)
        self.assertNotIn("createElement('button')", temporal)
