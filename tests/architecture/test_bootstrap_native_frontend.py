"""Regression gates for the Bootstrap-backed InfraNexum Web design system."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


class BootstrapNativeFrontendTest(unittest.TestCase):
    """Keep Bootstrap primitives authoritative while allowing the bounded .inx-* product layer."""

    ROOT = Path(__file__).resolve().parents[2]
    PUBLIC = ROOT / "src/applications/web/public"
    BOOTSTRAP = PUBLIC / "assets/vendor/bootstrap-5.3.6.min.css"
    THEME = PUBLIC / "assets/infranexum-theme.css"

    @classmethod
    def setUpClass(cls) -> None:
        cls.bootstrap_css = cls.BOOTSTRAP.read_text(encoding="utf-8")
        cls.theme_css = cls.THEME.read_text(encoding="utf-8")
        cls.bootstrap_classes = set(re.findall(r"\.([A-Za-z_][A-Za-z0-9_-]*)", cls.bootstrap_css))

    def test_theme_layers_bootstrap_tokens_and_a_bounded_infranexum_design_system(self) -> None:
        self.assertRegex(self.theme_css, r"\.inx-[A-Za-z0-9_-]+")
        self.assertRegex(self.theme_css, r"--inx-[A-Za-z0-9_-]+")
        custom_properties = set(re.findall(r"(--[A-Za-z0-9_-]+)\s*:", self.theme_css))
        self.assertTrue(custom_properties)
        unsupported = {name for name in custom_properties if not (name.startswith("--bs-") or name.startswith("--inx-"))}
        self.assertEqual(unsupported, set(), unsupported)
        for token in ("--inx-midnight", "--inx-blue", "--inx-turquoise", "--inx-orange"):
            self.assertIn(token, self.theme_css)
        for selector in (".btn-primary", ".btn-outline-primary", ".nav-pills", ".card", ".table", ".alert", ".form-control", ".form-select"):
            self.assertIn(selector, self.theme_css)

    def test_static_and_generated_classes_are_bootstrap_or_infranexum_namespaced(self) -> None:
        files = [self.PUBLIC / "index.html", *sorted((self.PUBLIC / "assets").glob("*.mjs"))]
        unknown: dict[str, list[str]] = {}
        for path in files:
            text = path.read_text(encoding="utf-8")
            for match in re.finditer(r"(?:class|className)\s*=\s*[\"'`]([^\"'`$]*)[\"'`]", text):
                for token in match.group(1).split():
                    if token and token not in self.bootstrap_classes and not token.startswith("inx-"):
                        unknown.setdefault(token, []).append(str(path.relative_to(self.ROOT)))
        self.assertEqual(unknown, {}, f"non-Bootstrap/non-InfraNexum presentation classes found: {unknown}")

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

    def test_native_form_values_remain_authoritative_behind_accessible_infranexum_selects(self) -> None:
        stable = (self.PUBLIC / "assets/stable-select.mjs").read_text(encoding="utf-8")
        temporal = (self.PUBLIC / "assets/temporal-picker.mjs").read_text(encoding="utf-8")
        self.assertIn("select.form-select", stable)
        self.assertIn("role', 'combobox", stable)
        self.assertIn("role', 'listbox", stable)
        self.assertIn("select.value = option.value", stable)
        self.assertIn("dispatchNative('input')", stable)
        self.assertIn("dispatchNative('change')", stable)
        self.assertIn("select.multiple === true", stable)
        self.assertNotIn("select.remove?.()", stable)
        self.assertIn("input.value = value", temporal)
        self.assertIn("inx-temporal-popover", temporal)
        self.assertIn("inx-temporal-years", temporal)
        self.assertIn("setCustomValidity", temporal)
        self.assertIn("end.min = startValue", temporal)

    @staticmethod
    def _relative_luminance(hex_color: str) -> float:
        channels = [int(hex_color[index:index + 2], 16) / 255 for index in (1, 3, 5)]
        linear = [value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4 for value in channels]
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]

    @classmethod
    def _contrast_ratio(cls, foreground: str, background: str) -> float:
        first = cls._relative_luminance(foreground)
        second = cls._relative_luminance(background)
        lighter, darker = max(first, second), min(first, second)
        return (lighter + 0.05) / (darker + 0.05)

    def test_ionos_visual_hierarchy_uses_product_surfaces_without_blue_on_blue_sidebar_text(self) -> None:
        index = (self.PUBLIC / "index.html").read_text(encoding="utf-8")
        self.assertIn('class="col-12 col-lg-2 bg-dark text-white', index)
        self.assertIn('inx-sidebar', index)
        self.assertIn('inx-hero', index)
        self.assertNotRegex(index, r'<article class="col\s+card')
        self.assertNotRegex(index, r'\sstyle="')
        self.assertRegex(self.theme_css, r'\.inx-sidebar\s*\{[\s\S]*linear-gradient')
        self.assertRegex(self.theme_css, r'\.inx-hero\s*\{[\s\S]*linear-gradient')
        self.assertRegex(self.theme_css, r'\.inx-sidebar-nav \.inx-nav-link[\s\S]*color:\s*rgba\(255,255,255')
        self.assertRegex(self.theme_css, r'\.inx-sidebar-nav \.inx-nav-link\.active[\s\S]*color:\s*#ffffff\s*!important')
        self.assertNotRegex(self.theme_css, r'\.inx-sidebar-nav \.inx-nav-link(?:\.active)?[^}]*color:\s*var\(--inx-blue\)')
        self.assertIn('[data-density="compact"]', self.theme_css)
        self.assertIn('[data-navigation="compact"]', self.theme_css)
        self.assertRegex(self.theme_css, r'#identity-access-workspace > \.row > aside \[role="tablist"\] \.nav-link[\s\S]*width:\s*100%')

    def test_primary_visual_contrasts_meet_wcag_aa(self) -> None:
        pairs = (
            ("#ffffff", "#003d8f"),
            ("#ffffff", "#001b41"),
            ("#001b41", "#11c7e6"),
            ("#14233a", "#ffffff"),
            ("#064f5d", "#e7fbfe"),
            ("#6c4600", "#fff6df"),
        )
        for foreground, background in pairs:
            with self.subTest(foreground=foreground, background=background):
                self.assertGreaterEqual(self._contrast_ratio(foreground, background), 4.5)


    def test_enterprise_filters_use_the_namespaced_dense_toolbar_contract(self) -> None:
        files = [
            self.PUBLIC / "index.html",
            self.PUBLIC / "assets/rsot-workspace.mjs",
            self.PUBLIC / "assets/itam-workspace.mjs",
            self.PUBLIC / "assets/dcim-workspace.mjs",
            self.PUBLIC / "assets/ddi-ipam-workspace.mjs",
        ]
        combined = "\n".join(path.read_text(encoding="utf-8") for path in files)
        for identifier in (
            "rsot-object-filter", "rsot-schema-filter", "itam-partner-filter",
            "itam-asset-filter", "itam-alert-filter", "itam-history-filter",
        ):
            self.assertRegex(combined, rf'id="{identifier}"[^>]*class="[^"]*inx-filter-bar')
        self.assertIn(".inx-filter-bar", self.theme_css)
        self.assertRegex(self.theme_css, r'@media \(min-width:\s*768px\)[\s\S]*\.inx-filter-bar\s*\{\s*flex-wrap:\s*nowrap')

    def test_table_and_tab_navigation_headers_keep_high_contrast_product_accents(self) -> None:
        self.assertRegex(self.theme_css, r'\.table > thead > tr > th\s*\{[\s\S]*color:\s*#f8fbff\s*!important')
        self.assertRegex(self.theme_css, r'\.table > thead > tr > th\s*\{[\s\S]*background:\s*var\(--inx-table-head\)')
        self.assertRegex(self.theme_css, r'\.inx-workspace \.nav-underline\[role="tablist"\][\s\S]*background:\s*var\(--inx-tab-spectrum\)')
        self.assertRegex(self.theme_css, r'\.inx-workspace \.nav-underline\[role="tablist"\] \.nav-link\.active[\s\S]*color:\s*#fff\s*!important')
        self.assertGreaterEqual(self._contrast_ratio("#f8fbff", "#003d8f"), 4.5)


if __name__ == "__main__":
    unittest.main()
