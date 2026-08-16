import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { SUPPORTED_LOCALES, translate } from '../../src/applications/web/public/assets/i18n.mjs';
import { DcimFacilityClient } from '../../src/applications/web/public/assets/dcim-facilities.mjs';
import { dcimWorkspaceTemplate } from '../../src/applications/web/public/assets/dcim-workspace.mjs';
import { ItamComplianceClient } from '../../src/applications/web/public/assets/itam-compliance.mjs';
import { itamWorkspaceTemplate } from '../../src/applications/web/public/assets/itam-workspace.mjs';
import { RsotCanonicalObjectClient } from '../../src/applications/web/public/assets/rsot-canonical-objects.mjs';
import { rsotWorkspaceTemplate } from '../../src/applications/web/public/assets/rsot-workspace.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const PUBLIC = path.join(ROOT, 'src/applications/web/public');

function configuration(overrides = {}) {
  return {
    apiBaseUrl: '/api',
    rsotCoreEnabled: true,
    itamPartnersEnabled: true,
    itamAssetsEnabled: true,
    itamComplianceEnabled: true,
    dcimFacilitiesEnabled: true,
    dcimPhysicalEnabled: true,
    ddiIpamEnabled: true,
    ...overrides,
  };
}

function response(payload, { status = 200, headers = {} } = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name) => headers[String(name).toLowerCase()] ?? null },
    json: async () => payload,
  };
}

test('RSOT, ITAM and DCIM are real first-level administration routes with concrete workspaces', async () => {
  const html = await readFile(path.join(PUBLIC, 'index.html'), 'utf8');
  const shell = await readFile(path.join(PUBLIC, 'assets/admin-shell.mjs'), 'utf8');
  const bootstrap = await readFile(path.join(PUBLIC, 'assets/bootstrap.mjs'), 'utf8');

  for (const route of ['rsot', 'itam', 'dcim', 'ddi']) {
    assert.match(html, new RegExp(`id="nav-${route}"[^>]+data-route="${route}"`));
    assert.match(html, new RegExp(`id="${route}-workspace"[^>]+data-view="${route}"`));
    assert.match(shell, new RegExp(`${route}: Object\\.freeze`));
  }
  assert.match(bootstrap, /initializeRsotWorkspace\(document, configuration, fetch\)/);
  assert.match(bootstrap, /initializeItamWorkspace\(document, configuration, fetch\)/);
  assert.match(bootstrap, /initializeDcimWorkspace\(document, configuration, fetch\)/);
  assert.match(bootstrap, /setRsotAvailability\(documentObject, configuration\.rsotCoreEnabled\)/);
  assert.match(bootstrap, /setItamAvailability\(documentObject, configuration\.itamPartnersEnabled \|\| configuration\.itamAssetsEnabled \|\| configuration\.itamComplianceEnabled\)/);
  assert.match(bootstrap, /setDcimAvailability\(documentObject, configuration\.dcimFacilitiesEnabled\)/);
  assert.match(bootstrap, /initializeDdiIpamWorkspace\(document, configuration, fetch\)/);
  assert.match(bootstrap, /setDdiAvailability\(documentObject, configuration\.ddiIpamEnabled\)/);
});

test('functional workspaces expose lists, create workflows and governed lifecycle actions', () => {
  const rsot = rsotWorkspaceTemplate();
  const itam = itamWorkspaceTemplate(configuration());
  const dcim = dcimWorkspaceTemplate();
  for (const id of [
    'rsot-object-table-body', 'rsot-schema-table-body', 'rsot-profile-table-body', 'rsot-schema-form', 'rsot-schema-lifecycle', 'rsot-profile-form', 'rsot-profile-lifecycle',
  ]) assert.match(rsot, new RegExp(`id="${id}"`));
  for (const id of [
    'itam-partner-table-body', 'itam-partner-create', 'itam-partner-lifecycle',
    'itam-asset-table-body', 'itam-asset-create', 'itam-asset-lifecycle', 'itam-custody-table-body',
    'itam-warranty-form', 'itam-license-form', 'itam-coverage-form', 'itam-authorization-create', 'itam-warranty-type-create', 'itam-alert-table-body', 'itam-history-table-body', 'itam-history-filter',
  ]) assert.match(itam, new RegExp(`id="${id}"`));
  for (const resource of ['sites', 'buildings', 'floors', 'rooms', 'zones']) {
    for (const suffix of ['rows', 'form', 'status-form']) assert.match(dcim, new RegExp(`id=\"dcim-${resource}-${suffix}\"`));
    assert.match(dcim, new RegExp(`data-inx-crud-panel=\"dcim-${resource}\"`));
  }
});

test('entity identifiers are selected from governed catalogues instead of raw UUID text inputs', () => {
  const html = itamWorkspaceTemplate(configuration());
  const requiredSelectNames = [
    'rsotObjectId', 'acquiredFromPartnerId', 'producerPartnerId', 'custodianId',
    'manufacturerPartnerId', 'warrantyTypeId', 'publisherPartnerId', 'providerPartnerId',
    'authorizationId', 'supportedManufacturerIds', 'subdivisionScopes',
  ];
  for (const name of requiredSelectNames) {
    assert.match(html, new RegExp(`<select[^>]+name="${name}"`), `${name} must be a select`);
    assert.doesNotMatch(html, new RegExp(`<input[^>]+name="${name}"`), `${name} must not be a free-form input`);
  }
  assert.match(html, /id="itam-organization"[^>]*class="form-select"/);
  assert.match(html, /id="itam-subdivision"[^>]*class="form-select"/);
});

test('DCIM hierarchy uses governed cascading selectors and never exposes parent UUID text inputs', () => {
  const html = dcimWorkspaceTemplate();
  for (const id of ['dcim-organization', 'dcim-subdivision', 'dcim-site-context', 'dcim-building-context', 'dcim-floor-context', 'dcim-room-context', 'dcim-zone-parent-kind', 'dcim-zone-parent']) {
    assert.match(html, new RegExp(`<select[^>]+id=\"${id}\"`), `${id} must be a select`);
  }
  assert.doesNotMatch(html, /<input[^>]+name="(?:organizationId|subdivisionId|parentId)"/);
  for (const field of ['addressLine1', 'postalCode', 'city', 'countryCode', 'timezone']) assert.match(html, new RegExp(`name="${field}"[^>]+required`));
  assert.match(html, /id="dcim-sites-country-filter"/);
});

test('date and datetime workflows use InfraNexum temporal controls', () => {
  const html = rsotWorkspaceTemplate() + itamWorkspaceTemplate(configuration());
  for (const id of ['rsot-schema-effective', 'rsot-schema-sunset', 'itam-partner-valid-from', 'itam-asset-date', 'itam-warranty-start', 'itam-license-start', 'itam-coverage-start', 'itam-auth-from']) {
    assert.match(html, new RegExp(`id="${id}"[^>]+data-inx-temporal="(?:date|datetime)"`));
  }
});

test('new administration vocabulary is translated in every supported locale', () => {
  const keys = [
    'nav.rsot', 'nav.itam', 'nav.dcim', 'topbar.rsot', 'topbar.itam', 'topbar.dcim', 'command.rsot.title', 'command.itam.title', 'command.dcim.title',
    'workspace.ready', 'workspace.restricted', 'rsot.objects', 'rsot.schemas', 'rsot.profiles',
    'itam.partners', 'itam.assets', 'itam.compliance', 'itam.role.manufacturer', 'itam.role.software_publisher',
    'itam.warranties', 'itam.licenses', 'itam.supportCoverage', 'itam.licenseSecretNotice',
    'dcim.sites', 'dcim.buildings', 'dcim.floors', 'dcim.rooms', 'dcim.zones', 'dcim.addressLine1', 'dcim.postalCode', 'dcim.city', 'dcim.countryFilter', 'dcim.status.draft', 'dcim.status.active', 'dcim.status.archived',
  ];
  assert.deepEqual(SUPPORTED_LOCALES, ['de', 'en', 'es', 'fr', 'it']);
  for (const locale of SUPPORTED_LOCALES) {
    for (const key of keys) assert.notEqual(translate(locale, key), key, `${locale}/${key} must not fall back to the key`);
  }
});

test('canonical RSOT browser client is capability gated and supports organization-filtered selectors', async () => {
  assert.throws(() => new RsotCanonicalObjectClient(configuration({ rsotCoreEnabled: false })), /disabled/);
  const calls = [];
  const client = new RsotCanonicalObjectClient(configuration(), { fetchFunction: async (url, options) => { calls.push({ url, options }); return response([]); } });
  const organizationId = '019ffbda-1001-7000-8000-000000000001';
  await client.list({ organizationId, offset: 0, limit: 200 });
  assert.match(calls[0].url, /\/v1\/rsot\/canonical-objects\?/);
  assert.match(calls[0].url, new RegExp(`organization_id=${organizationId}`));
  assert.match(calls[0].url, /limit=200/);
  assert.equal(calls[0].options.credentials, 'same-origin');
});

test('compliance client exposes selectors and detail reads required by editable Web forms', () => {
  const client = new ItamComplianceClient(configuration(), { fetchFunction: async () => response({}), cookieProvider: () => 'INFRANEXUM_CSRF=x' });
  for (const method of ['getWarranty', 'getLicense', 'getSupportCoverage', 'listSupportAuthorizations', 'warrantyTypes']) {
    assert.equal(typeof client[method], 'function', `${method} must be available`);
  }
});

test('DCIM client is capability gated and publishes the five governed facility collections', () => {
  assert.throws(() => new DcimFacilityClient(configuration({ dcimFacilitiesEnabled: false })), /disabled/);
  const client = new DcimFacilityClient(configuration(), { fetchFunction: async () => response({}), cookieProvider: () => 'INX_XSRF=x' });
  for (const method of ['list', 'get', 'create', 'update', 'changeStatus']) assert.equal(typeof client[method], 'function');
});

test('browser source tree contains no NUL bytes', async () => {
  const assets = path.join(PUBLIC, 'assets');
  for (const name of await readdir(assets)) {
    if (!/\.(?:mjs|css)$/.test(name)) continue;
    const buffer = await readFile(path.join(assets, name));
    assert.equal(buffer.includes(0), false, `${name} contains a NUL byte`);
  }
});
