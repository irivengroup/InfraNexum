import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { SUPPORTED_LOCALES, translate } from '../../src/applications/web/public/assets/i18n.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const PUBLIC = path.join(ROOT, 'src/applications/web/public');
const read = (name) => readFile(path.join(PUBLIC, name), 'utf8');

function functionBody(source, name, next = 'function ') {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `${name} must exist`);
  const end = source.indexOf(`\n${next}`, start + 10);
  return source.slice(start, end === -1 ? source.length : end);
}

test('DataTables fit the complete workspace width without nested scrolling or clipped actions', async () => {
  const [theme, crud] = await Promise.all([read('assets/infranexum-theme.css'), read('assets/enterprise-crud.mjs')]);
  assert.match(theme, /\.inx-workspace \.table-responsive,[\s\S]*max-width:\s*100%[\s\S]*overflow:\s*visible\s*!important/);
  assert.doesNotMatch(theme, /\.inx-workspace \.table-responsive,[\s\S]{0,260}overflow-x:\s*auto\s*!important/);
  assert.match(theme, /\.inx-datatable-frame\s*\{[\s\S]*overflow:\s*visible\s*!important/);
  assert.match(theme, /\.inx-data-table\s*\{[\s\S]*width:\s*100%\s*!important[\s\S]*min-width:\s*0\s*!important[\s\S]*max-width:\s*100%\s*!important[\s\S]*table-layout:\s*auto/);
  assert.match(theme, /\.inx-data-table > :not\(caption\) > \* > \*\s*\{[\s\S]*overflow-wrap:\s*anywhere[\s\S]*white-space:\s*normal/);
  assert.match(theme, /\.inx-data-table \.inx-crud-actions\s*\{[\s\S]*flex-wrap:\s*wrap/);
  assert.match(theme, /data-inx-column-size="compact"/);
  assert.match(theme, /data-inx-column-size="flex"/);
  assert.match(theme, /data-inx-column-size="actions"/);
  assert.match(crud, /responsiveContainer\?\.parentElement\?\.classList\?\.add\?\.\('inx-datatable-frame'\)/);
  assert.match(crud, /classifyDataTableColumns\(table\)/);
  assert.match(crud, /longest <= 12 \? 'compact' : longest <= 32 \? 'content' : 'flex'/);
});

test('DataTables render an explicit localized empty row for every empty tbody', async () => {
  const [crud, utils] = await Promise.all([read('assets/enterprise-crud.mjs'), read('assets/web-workspace-utils.mjs')]);
  assert.match(crud, /ensureDataTableEmptyState\(table, tbody\)/);
  assert.match(crud, /data-inx-empty-state/);
  assert.match(crud, /translate\(localeFromDocument\(doc\), 'common\.emptyList'\)/);
  assert.match(utils, /data-inx-empty-state/);
  assert.equal(translate('en', 'common.emptyList'), 'No records are available.');
});

test('DCIM Location panels expose both the context and the active rubric like Infrastructure panels', async () => {
  const [dcim, physical] = await Promise.all([read('assets/dcim-workspace.mjs'), read('assets/dcim-physical-workspace.mjs')]);
  assert.match(dcim, /TITLE_BY_RESOURCE/);
  assert.match(dcim, /data-dcim-context="location"/);
  assert.match(dcim, /data-i18n="dcim\.nav\.location"[\s\S]*data-i18n="\$\{titleKey\}"/);
  assert.match(dcim, /inx-crud-editor-header[\s\S]*data-i18n="dcim\.nav\.location"[\s\S]*data-i18n="\$\{titleKey\}"/);
  assert.match(physical, /data-i18n="dcim\.physical\.eyebrow"[\s\S]*data-i18n="\$\{titleKey\}"/);
});

test('technical ID and UUID columns are removed from list presentation and surfaced read-only in detail editors', async () => {
  const [crud, html] = await Promise.all([read('assets/enterprise-crud.mjs'), read('index.html')]);
  assert.match(crud, /concealTechnicalIdentifierColumns\(table\)/);
  assert.match(crud, /text === 'id' \|\| text === 'uuid'/);
  assert.match(crud, /data-inx-technical-id-column/);
  assert.match(crud, /ensureTechnicalIdentifierField/);
  assert.match(crud, /input\.readOnly = true/);
  assert.match(html, /id="iam-user-update-form"[\s\S]*data-inx-technical-id=""[\s\S]*name="userId"[\s\S]*readonly/);
  assert.match(html, /id="iam-group-update-form"[\s\S]*data-inx-technical-id=""[\s\S]*name="groupId"[\s\S]*readonly/);
  assert.match(html, /id="iam-role-update-form"[\s\S]*data-inx-technical-id=""[\s\S]*name="roleId"[\s\S]*readonly/);
});

test('+ New remains functional for controls injected after CRUD initialization', async () => {
  const crud = await read('assets/enterprise-crud.mjs');
  assert.match(crud, /panel\.addEventListener\?\.\('click'/);
  assert.match(crud, /event\?\.target\?\.closest\?\.\('\[data-inx-crud-new\], \[data-inx-crud-open\]'/);
  assert.match(crud, /CRUD_HANDLED_EVENTS = new WeakSet\(\)/);
});

test('IAM list actions collapse related operations into contextual edit facets', async () => {
  const iam = await read('assets/identity-access.mjs');
  const users = functionBody(iam, 'userCells');
  const groups = functionBody(iam, 'groupCells');
  const roles = functionBody(iam, 'roleCells');
  const permissions = functionBody(iam, 'permissionCells');

  assert.match(users, /selectAction\('user', item, 'users:settings'\)/);
  assert.doesNotMatch(users, /users:memberships|users:roles|activate-user|suspend-user/);
  assert.match(groups, /groups:settings/);
  assert.match(groups, /groups:direct-members[\s\S]*'iam\.members'[\s\S]*'members'/);
  assert.doesNotMatch(groups, /groups:remove-member|groups:roles|groups:effective/);
  assert.match(roles, /roles:settings/);
  assert.doesNotMatch(roles, /roles:assignments|roles:revoke/);
  assert.match(permissions, /permissions:settings/);
  assert.doesNotMatch(permissions, /permissions:effective/);

  assert.match(iam, /'users:settings': 'edit',[\s\S]*'users:memberships': 'edit',[\s\S]*'users:roles': 'edit'/);
  assert.match(iam, /'groups:settings': 'edit',[\s\S]*'groups:roles': 'edit'/);
  assert.match(iam, /'groups:direct-members': 'members',[\s\S]*'groups:members': 'members',[\s\S]*'groups:effective': 'members'/);
  assert.match(iam, /'roles:settings': 'edit',[\s\S]*'roles:assignments': 'edit',[\s\S]*'roles:revoke': 'edit'/);
});

test('user edit includes audited ACTIVE/SUSPENDED lifecycle management', async () => {
  const [html, iam] = await Promise.all([read('index.html'), read('assets/identity-access.mjs')]);
  const formStart = html.indexOf('id="iam-user-update-form"');
  const formEnd = html.indexOf('</form>', formStart);
  const form = html.slice(formStart, formEnd);
  assert.match(form, /name="status"/);
  assert.match(form, /value="active"/);
  assert.match(form, /value="suspended"/);
  assert.match(form, /value="pending" disabled/);
  assert.match(iam, /data-inx-original-status/);
  assert.match(iam, /requestedStatus === 'active'[\s\S]*\/activate/);
  assert.match(iam, /requestedStatus === 'suspended'[\s\S]*\/suspend/);
});

test('group Members detail exposes direct CRUD list, add and effective-resolution facets without visible technical IDs', async () => {
  const [html, iam] = await Promise.all([read('index.html'), read('assets/identity-access.mjs')]);
  assert.match(html, /data-iam-workflow="groups:direct-members"/);
  assert.match(html, /data-iam-workflow="groups:members"/);
  assert.doesNotMatch(html, /data-iam-workflow="groups:remove-member"/);
  assert.match(html, /data-iam-workflow="groups:effective"/);
  assert.match(html, /id="iam-direct-group-members"[\s\S]*<\/tbody>/);
  assert.match(html, /id="iam-effective-group-members"[\s\S]*<\/tbody>/);
  assert.match(iam, /\/groups\/\$\{group\}\/members\?offset=0&limit=200/);
  assert.match(iam, /data-iam-action', 'remove-group-member'/);
});

test('avatar dropdown uses the InfraNexum visual system and remains theme-aware', async () => {
  const theme = await read('assets/infranexum-theme.css');
  assert.match(theme, /\.inx-session-trigger[\s\S]*color-mix\(in srgb, var\(--inx-blue\)/);
  assert.match(theme, /\.inx-session-avatar[\s\S]*linear-gradient\(145deg, var\(--inx-midnight\), var\(--inx-blue\)/);
  assert.match(theme, /\.inx-session-dropdown[\s\S]*var\(--inx-tab-spectrum\)/);
  assert.match(theme, /\[data-bs-theme="dark"\] \.inx-session-dropdown/);
});

test('new detail vocabulary is translated in all five supported locales', () => {
  for (const locale of SUPPORTED_LOCALES) {
    for (const key of ['common.identifier', 'iam.members', 'iam.userStatusHint']) {
      const value = translate(locale, key);
      assert.notEqual(value, key, `${locale}:${key} must be translated`);
      assert.ok(value.length >= 3);
    }
  }
});
