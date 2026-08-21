from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PUBLIC = ROOT / "src/applications/web/public"
ASSETS = PUBLIC / "assets"


class WebEnterpriseCrudContractTests(unittest.TestCase):
    """Pin the alpha.0.99 enterprise list/action/editor interaction model."""

    def test_topbar_is_not_duplicating_environment_and_docs_are_first_class_navigation(self) -> None:
        html = (PUBLIC / "index.html").read_text(encoding="utf-8")
        topbar = html.split('<header class="navbar sticky-top', 1)[1].split("</header>", 1)[0]
        self.assertNotIn("topbar-environment", topbar)
        self.assertNotIn("Environment local", topbar)
        self.assertIn('data-i18n="nav.documentation"', html)
        self.assertIn('data-route="swagger"', html)
        self.assertIn('data-route="redoc"', html)
        self.assertIn('id="swagger-workspace"', html)
        self.assertIn('id="redoc-workspace"', html)


    def test_route_state_cannot_be_bound_as_navigation_and_steal_form_focus(self) -> None:
        html = (PUBLIC / "index.html").read_text(encoding="utf-8")
        shell = (ASSETS / "admin-shell.mjs").read_text(encoding="utf-8")
        bootstrap = (ASSETS / "bootstrap.mjs").read_text(encoding="utf-8")
        self.assertIn('data-current-route="overview"', html)
        self.assertNotIn('<html lang="en" data-bs-theme="light" data-route=', html)
        self.assertIn("const ROUTE_CONTROL_SELECTOR = 'a[data-route]'", shell)
        self.assertIn("const ROUTE_STATE_ATTRIBUTE = 'data-current-route'", shell)
        self.assertNotIn("business-form-focus.mjs", bootstrap)
        self.assertFalse((ASSETS / "business-form-focus.mjs").exists())

    def test_identity_access_tabs_share_product_header_treatment(self) -> None:
        css = (ASSETS / "infranexum-theme.css").read_text(encoding="utf-8")
        self.assertIn('#identity-access-workspace .nav-underline[role="tablist"]', css)
        self.assertIn("background: var(--inx-tab-spectrum) !important", css)
        self.assertIn('#identity-access-workspace .nav-underline[role="tablist"] .nav-link.active', css)

    def test_table_header_surface_is_continuous_and_restrained(self) -> None:
        css = (ASSETS / "infranexum-theme.css").read_text(encoding="utf-8")
        self.assertIn("--inx-table-head-surface", css)
        self.assertIn(".table > thead > tr {", css)
        self.assertIn("background: var(--inx-table-head-surface) !important", css)
        self.assertIn(".table > thead > tr > th", css)
        self.assertIn("background: transparent !important", css)
        alpha99 = css.split("alpha.0.99", 1)[1]
        self.assertNotIn("thead > tr > th:first-child { background:", alpha99)
        self.assertNotIn("thead > tr > th:last-child { background:", alpha99)

    def test_crud_workspaces_are_list_first_with_explicit_editor_actions(self) -> None:
        for name in ["identity-access.mjs", "rsot-workspace.mjs", "itam-workspace.mjs", "dcim-workspace.mjs", "ddi-ipam-workspace.mjs"]:
            text = (ASSETS / name).read_text(encoding="utf-8")
            self.assertIn("enterprise-crud.mjs", text, name)
        controller = (ASSETS / "enterprise-crud.mjs").read_text(encoding="utf-8")
        self.assertIn("data-inx-crud-list", controller)
        self.assertIn("data-inx-crud-editor", controller)
        self.assertIn("infranexum:form-success", controller)
        self.assertIn("aria-sort", controller)
        self.assertIn("data-inx-actions-column", (ASSETS / "web-workspace-utils.mjs").read_text(encoding="utf-8"))

    def test_user_initiated_deletions_require_confirmation(self) -> None:
        iam = (ASSETS / "identity-access.mjs").read_text(encoding="utf-8")
        dcim = (ASSETS / "dcim-workspace.mjs").read_text(encoding="utf-8")
        self.assertIn("iam.confirmDelete", iam)
        self.assertIn("common.confirmDelete", iam)
        self.assertIn("targetStatus==='deleted'", dcim)
        self.assertIn("common.confirmDelete", dcim)

    def test_raw_json_list_inspectors_are_not_stacked_below_crud_tables(self) -> None:
        for name in ["rsot-workspace.mjs", "dcim-workspace.mjs", "ddi-ipam-workspace.mjs"]:
            text = (ASSETS / name).read_text(encoding="utf-8")
            self.assertNotIn('<pre id="rsot-schema-detail"', text)
            self.assertNotIn('<pre id="dcim-${resource}-detail"', text)
            self.assertNotIn('id="ddi-${name}-detail" class="p-3 rounded border', text)

    def test_datatables_use_bounded_pagination_without_nested_scroll_regions(self) -> None:
        controller = (ASSETS / "enterprise-crud.mjs").read_text(encoding="utf-8")
        css = (ASSETS / "infranexum-theme.css").read_text(encoding="utf-8")
        self.assertIn("Object.freeze([20, 50, 100, 200])", controller)
        self.assertIn("inx-datatable-pagination", controller)
        self.assertIn("overflow: visible !important", css)
        self.assertNotIn("overscroll-behavior-inline: contain", css)
        self.assertIn("max-width: 100%", css)
        self.assertIn("min-width: 0 !important", css)
        self.assertIn("table-layout: fixed", css)
        self.assertNotIn('width: 1%;\n  white-space: nowrap', css)
        self.assertIn("--inx-column-width", css)
        self.assertIn("DATA_TABLE_COLUMN_WEIGHTS", controller)
        self.assertIn("data-inx-density", controller)
        self.assertIn("inx-datatable-frame", controller)
        self.assertIn("ensureDataTableEmptyState(table, tbody)", controller)
        self.assertIn(".inx-datatable-page-button.btn-primary", css)

    def test_dcim_location_navigation_exposes_context_and_current_rubric(self) -> None:
        dcim = (ASSETS / "dcim-workspace.mjs").read_text(encoding="utf-8")
        physical = (ASSETS / "dcim-physical-workspace.mjs").read_text(encoding="utf-8")
        self.assertIn("TITLE_BY_RESOURCE", dcim)
        self.assertIn('data-dcim-context="location"', dcim)
        self.assertIn('data-i18n="dcim.nav.location"', dcim)
        self.assertIn('data-i18n="${titleKey}"', dcim)
        self.assertIn('data-i18n="dcim.physical.eyebrow"', physical)


if __name__ == "__main__":
    unittest.main()
