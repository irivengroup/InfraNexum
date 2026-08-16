"""Regression gates for the usable and reliable Identity & Access Web workflow."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


class IdentityAccessWebWorkflowTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]

    @classmethod
    def setUpClass(cls) -> None:
        cls.html = (cls.ROOT / "src/applications/web/public/index.html").read_text(encoding="utf-8")
        cls.iam = (cls.ROOT / "src/applications/web/public/assets/identity-access.mjs").read_text(encoding="utf-8")
        cls.policy = (cls.ROOT / "src/applications/web/public/assets/policy-authorization.mjs").read_text(encoding="utf-8")
        cls.forms = (cls.ROOT / "src/applications/web/public/assets/form-controller.mjs").read_text(encoding="utf-8")
        cls.css = (cls.ROOT / "src/applications/web/public/assets/infranexum-theme.css").read_text(encoding="utf-8")
        cls.entity_selects = (cls.ROOT / "src/applications/web/public/assets/entity-selects.mjs").read_text(encoding="utf-8")
        cls.temporal_picker = (cls.ROOT / "src/applications/web/public/assets/temporal-picker.mjs").read_text(encoding="utf-8")

    def test_all_iam_mutation_surfaces_use_reliable_pointer_and_keyboard_form_controller(self) -> None:
        self.assertIn("wireAsyncForm", self.iam)
        self.assertIn("wireAsyncForm", self.policy)
        self.assertIn("button.addEventListener?.('click'", self.forms)
        self.assertIn("form.addEventListener('submit'", self.forms)
        self.assertIn("if (busy || !validate()) return false", self.forms)
        self.assertIn("form.reportValidity()", self.forms)

    def test_identity_workspace_has_one_accessible_subarea_navigation_instead_of_one_long_accordion(self) -> None:
        for area in ("users", "groups", "roles", "permissions", "policies"):
            self.assertIn(f'data-iam-section="{area}"', self.html)
        for area in ("users", "groups", "roles", "permissions", "policies"):
            self.assertIn(f'data-iam-panel="{area}"', self.html)
        self.assertIn('role="tablist"', self.html)
        self.assertIn("initializeIamSectionNavigation", self.iam)
        self.assertIn("ArrowRight", self.iam)
        self.assertIn('class="nav nav-pills flex-column gap-1"', self.html)
        self.assertIn(".inx-sidebar", self.css)

    def test_identity_workspace_uses_freeipa_inspired_functional_navigation_and_list_first_facets(self) -> None:
        for group in ("identity", "access-control", "policy"):
            self.assertIn(f'data-iam-nav-group="{group}"', self.html)
        for section in ("users", "groups", "roles", "permissions"):
            self.assertIn(f'data-iam-filter="{section}"', self.html)
            self.assertIn(f'data-iam-refresh="{section}"', self.html)
            self.assertIn(f'data-iam-open-workflow="{section}:create"', self.html)
        self.assertNotIn('<details id="iam-users-section"', self.html)
        self.assertIn("initializeIamWorkflowNavigation", self.iam)
        self.assertIn("applyTableFilter", self.iam)
        self.assertIn('class="nav nav-pills flex-column gap-1"', self.html)
        self.assertIn('class="table table-hover align-middle mb-0"', self.html)
        self.assertIn('class="nav nav-underline gap-3 mb-4 border-bottom"', self.html)

    def test_identity_workspace_preserves_all_existing_mutation_form_contracts(self) -> None:
        form_ids = (
            "iam-user-create-form", "iam-user-update-form", "iam-membership-form", "iam-user-role-form",
            "iam-group-create-form", "iam-group-update-form", "iam-group-member-form", "iam-group-member-remove-form", "iam-group-role-form",
            "iam-role-create-form", "iam-role-update-form", "iam-role-assignment-form", "iam-role-revoke-form",
            "iam-permission-create-form", "iam-permission-update-form", "iam-permission-evaluate-form",
            "iam-policy-create-form", "iam-policy-lifecycle-form", "iam-policy-decision-form",
        )
        for form_id in form_ids:
            self.assertEqual(self.html.count(f'id="{form_id}"'), 1, form_id)

    def test_iam_lists_expose_identifiers_and_selection_prefills_related_forms(self) -> None:
        self.assertGreaterEqual(self.html.count('data-i18n="iam.id"'), 4)
        for action in ("select-user", "select-group", "select-role", "select-permission"):
            self.assertIn(action, self.iam)
        self.assertIn("populateSelection", self.iam)
        self.assertIn("iam.selected", self.iam)

    def test_system_managed_groups_roles_and_permissions_do_not_offer_impossible_delete_actions(self) -> None:
        self.assertIn("if (!item.systemGroup)", self.iam)
        self.assertIn("if (!item.systemRole)", self.iam)
        self.assertIn("if (!item.systemDefined)", self.iam)

    def test_iam_errors_are_visible_with_safe_detail_instead_of_generic_silence(self) -> None:
        self.assertIn('id="iam-feedback"', self.html)
        self.assertIn("setFeedbackText", self.iam)
        self.assertIn("safeErrorDetail", self.iam)
        self.assertIn("error.message", self.policy)

    def test_search_authorization_failures_are_scoped_to_each_list_instead_of_failing_the_workspace(self) -> None:
        self.assertIn("refreshIamSectionSafely", self.iam)
        self.assertIn("Promise.all(['users', 'groups', 'roles', 'permissions'].map", self.iam)
        self.assertIn("iam.role.search", self.iam)
        self.assertIn("isAuthorizationDenied", self.iam)
        self.assertIn("iam.listRestricted", self.iam)
        self.assertNotIn("await Promise.all(['users', 'groups', 'roles', 'permissions'].map((section) => refreshSection", self.iam)

    def test_all_bootstrap_selects_keep_native_values_behind_stable_infranexum_comboboxes(self) -> None:
        stable = (self.ROOT / "src/applications/web/public/assets/stable-select.mjs").read_text(encoding="utf-8")
        bootstrap = (self.ROOT / "src/applications/web/public/assets/bootstrap.mjs").read_text(encoding="utf-8")
        self.assertIn("initializeStableSelects", bootstrap)
        self.assertIn("select.form-select", stable)
        self.assertIn("normalizeNativeSelect", stable)
        self.assertIn("role', 'combobox", stable)
        self.assertIn("role', 'listbox", stable)
        self.assertIn("select.value = option.value", stable)
        self.assertIn("dispatchNative('change')", stable)
        self.assertIn(".inx-select", self.css)

    def test_administration_canvas_and_data_tables_use_bootstrap_grid_and_table_contracts(self) -> None:
        self.assertIn('id="app-shell" class="container-fluid px-0 inx-app-shell"', self.html)
        self.assertIn('class="row g-0 min-vh-100"', self.html)
        self.assertIn('class="col-12 col-lg-10 inx-app-stage"', self.html)
        self.assertIn('class="table table-hover align-middle mb-0"', self.html)
        self.assertIn('.table > thead', self.css)
        self.assertIn(".inx-workspace", self.css)


    def test_structured_iam_identifiers_are_entity_selectors_with_hierarchical_dependencies(self) -> None:
        self.assertGreaterEqual(self.html.count('data-inx-entity='), 30)
        for entity in ("organization", "subdivision", "user", "group", "role", "permission", "policy", "actor", "assignment"):
            self.assertIn(f'data-inx-entity="{entity}"', self.html)
        self.assertNotIn('placeholder="Organization UUID"', self.html)
        self.assertNotIn('placeholder="Subdivision UUID"', self.html)
        self.assertNotIn('placeholder="Actor UUID"', self.html)
        self.assertIn('data-inx-organization-source="iam-policy-organization"', self.html)
        self.assertIn('data-inx-type-source="iam-role-assignment-actor-type"', self.html)
        self.assertIn('data-inx-role-source="iam-role-revoke-role"', self.html)
        self.assertIn("ensureSubdivisions", self.entity_selects)
        self.assertIn("ensureAssignments", self.entity_selects)
        self.assertIn("actorKindFor", self.entity_selects)
        self.assertIn("infranexum:entity-sync", self.entity_selects)

    def test_calendar_inputs_use_deterministic_infranexum_picker_with_fast_year_navigation(self) -> None:
        bootstrap = (self.ROOT / "src/applications/web/public/assets/bootstrap.mjs").read_text(encoding="utf-8")
        self.assertIn("initializeTemporalPickers", bootstrap)
        self.assertIn("input[data-inx-temporal]", self.temporal_picker)
        self.assertIn("inx-temporal-popover", self.temporal_picker)
        self.assertIn("inx-temporal-years", self.temporal_picker)
        self.assertIn("view.year - 12", self.temporal_picker)
        self.assertIn("view.year + 12", self.temporal_picker)
        self.assertIn("initializeTemporalRanges", self.temporal_picker)
        self.assertIn("end.min = startValue", self.temporal_picker)
        self.assertIn("start.max = endValue", self.temporal_picker)
        self.assertIn("setCustomValidity", self.temporal_picker)
        self.assertIn(".inx-temporal-popover", self.css)

    def test_visual_palette_is_applied_through_infranexum_tokens_and_bootstrap_components(self) -> None:
        self.assertIn("--inx-midnight: #001b41", self.css)
        self.assertIn("--inx-blue: #003d8f", self.css)
        self.assertIn("--inx-orange: #ffaa00", self.css)
        self.assertIn("--inx-turquoise: #11c7e6", self.css)
        self.assertIn("--bs-primary: var(--inx-blue)", self.css)
        self.assertIn("--bs-dark: var(--inx-midnight)", self.css)
        self.assertIn(".btn-primary", self.css)
        self.assertIn(".table > thead", self.css)
        self.assertIn(".alert", self.css)
        self.assertIn(".inx-sidebar", self.css)


    def test_temporal_fields_use_calendar_controls_and_server_side_timezone_resolution(self) -> None:
        self.assertEqual(self.html.count('name="effectiveFrom"'), len(re.findall(r'<input[^>]*name="effectiveFrom"[^>]*type="datetime-local"|<input[^>]*type="datetime-local"[^>]*name="effectiveFrom"', self.html)))
        self.assertEqual(self.html.count('name="effectiveTo"'), len(re.findall(r'<input[^>]*name="effectiveTo"[^>]*type="datetime-local"|<input[^>]*type="datetime-local"[^>]*name="effectiveTo"', self.html)))
        self.assertGreaterEqual(self.html.count('data-inx-temporal="datetime"'), 9)
        self.assertIn('temporal.serverTimezoneHint', self.html)
        self.assertNotIn('Effective from (ISO-8601)', self.html)
        self.assertNotIn('Effective to (ISO-8601)', self.html)

        parser = (self.ROOT / "src/applications/server/main/io/infranexum/server/configuration/ServerTemporalInputParser.java").read_text(encoding="utf-8")
        models = (self.ROOT / "src/applications/server/main/io/infranexum/server/identityaccess/IdentityAccessApiModels.java").read_text(encoding="utf-8")
        policy_models = (self.ROOT / "src/applications/server/main/io/infranexum/server/identityaccess/PolicyApiModels.java").read_text(encoding="utf-8")
        clock_configuration = (self.ROOT / "src/applications/server/main/io/infranexum/server/configuration/PlatformClockConfiguration.java").read_text(encoding="utf-8")
        self.assertIn('ZoneId.systemDefault()', clock_configuration)
        self.assertIn('getValidOffsets(local)', parser)
        self.assertIn('offsets.isEmpty()', parser)
        self.assertIn('offsets.size() != 1', parser)
        self.assertIn('@Size(max=80) String effectiveFrom', models)
        self.assertIn('@Size(max = 80) String effectiveFrom', policy_models)

        rbac_openapi = (self.ROOT / "src/applications/server/resources/openapi/identity-access-rbac.yaml").read_text(encoding="utf-8")
        policy_openapi = (self.ROOT / "src/applications/server/resources/openapi/identity-access-policy.yaml").read_text(encoding="utf-8")
        self.assertIn('x-infranexum-timezone-default: server', rbac_openapi)
        self.assertIn('x-infranexum-timezone-default: server', policy_openapi)


if __name__ == "__main__":
    unittest.main()
