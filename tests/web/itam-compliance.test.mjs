import assert from 'node:assert/strict';
import test from 'node:test';

import { ItamComplianceApiError, ItamComplianceClient } from '../../src/applications/web/public/assets/itam-compliance.mjs';

const assetId = '019ffbda-2300-7001-8001-000000000001';
const recordId = '019ffbda-2301-7002-8002-000000000002';
const organizationId = '019ffbda-2302-7003-8003-000000000003';
const configuration = Object.freeze({ apiBaseUrl: '/api', itamComplianceEnabled: true });
const cookieProvider = () => 'INX_XSRF=csrf-token';
function response(payload, { status = 200, etag = '"ver-2"' } = {}) { return { ok: status >= 200 && status < 300, status, headers: { get: (name) => name === 'etag' ? etag : null }, async json() { return payload; } }; }

test('client fails closed and paginates contractual collections', async () => {
  assert.throws(() => new ItamComplianceClient({ apiBaseUrl: '/api', itamComplianceEnabled: false }), /disabled/);
  let url; const client = new ItamComplianceClient(configuration, { fetchFunction: async (value) => { url = value; return response({ items: [] }); }, cookieProvider });
  await client.warranties(assetId, { limit: 25 }); assert.equal(url, `/api/v1/itam/assets/${assetId}/warranties?limit=25`);
  await assert.rejects(async () => client.warranties(assetId, { limit: 201 }), /between 1 and 200/);
});

test('warranty mutation carries CSRF, idempotency and optimistic version', async () => {
  let call; const client = new ItamComplianceClient(configuration, { fetchFunction: async (url, options) => { call = { url, options }; return response({ id: recordId }); }, cookieProvider });
  await client.reviseWarranty(recordId, 2, { reason: 'Correct contractual dates', proofReference: 'evidence:1' }, 'warranty-key-0001');
  assert.equal(call.url, `/api/v1/itam/warranties/${recordId}`); assert.equal(call.options.headers['X-CSRF-Token'], 'csrf-token');
  assert.equal(call.options.headers['Idempotency-Key'], 'warranty-key-0001'); assert.equal(call.options.headers['If-Match'], '"ver-2"');
  await client.expireWarranty(recordId, 3, 'Contractual end reached', 'warranty-key-0002');
  assert.equal(call.url, `/api/v1/itam/warranties/${recordId}/expire`);
});

test('software license client refuses raw secret key material', async () => {
  const client = new ItamComplianceClient(configuration, { fetchFunction: async () => response({}), cookieProvider });
  await assert.rejects(async () => client.createLicense(assetId, { reason: 'Import contract metadata', licenseKey: 'SECRET' }, 'license-key-0001'), /raw software license keys/);
  await assert.rejects(async () => client.reviseLicense(recordId, 1, { reason: 'Update contract', product_key: 'SECRET' }, 'license-key-0002'), /raw software license keys/);
});

test('support authorization and catalog operations retain organization scope', async () => {
  let calls = []; const client = new ItamComplianceClient(configuration, { fetchFunction: async (url, options) => { calls.push({ url, options }); return response([]); }, cookieProvider });
  await client.warrantyTypes(organizationId); assert.equal(calls.at(-1).url, `/api/v1/itam/warranty-types?organization_id=${organizationId}`);
  await client.createWarrantyType(organizationId, 'standard', 'Standard warranty', 'Govern catalog', 'catalog-key-0001');
  assert.deepEqual(JSON.parse(calls.at(-1).options.body), { organizationId, code: 'standard', displayName: 'Standard warranty', reason: 'Govern catalog' });
  await client.suspendSupportAuthorization(recordId, 2, 'Authorization suspended', 'support-key-0001');
  assert.equal(calls.at(-1).url, `/api/v1/itam/support-authorizations/${recordId}/suspend`);
});

test('alerts and history use bounded deterministic query parameters', async () => {
  let url; const client = new ItamComplianceClient(configuration, { fetchFunction: async (value) => { url = value; return response([]); }, cookieProvider });
  await client.alerts(assetId, { asOf: '2026-08-15', horizonDays: 180 }); assert.equal(url, `/api/v1/itam/assets/${assetId}/compliance-alerts?horizon_days=180&as_of=2026-08-15`);
  await client.history('warranties', recordId, { afterVersion: 2, limit: 50 }); assert.equal(url, `/api/v1/itam/warranties/${recordId}/history?after_version=2&limit=50`);
  await assert.rejects(async () => client.history('unknown', recordId), /unsupported/);
});

test('problem+json and missing CSRF are translated safely', async () => {
  const failure = new ItamComplianceClient(configuration, { fetchFunction: async () => response({ code: 'ITAM_COMPLIANCE_PRODUCER_MISMATCH', detail: 'blocked' }, { status: 422, etag: null }), cookieProvider });
  await assert.rejects(async () => failure.warranties(assetId), (error) => { assert.ok(error instanceof ItamComplianceApiError); assert.equal(error.status, 422); assert.equal(error.code, 'ITAM_COMPLIANCE_PRODUCER_MISMATCH'); return true; });
  const noCsrf = new ItamComplianceClient(configuration, { fetchFunction: async () => response({}), cookieProvider: () => '' });
  await assert.rejects(async () => noCsrf.activateWarranty(recordId, 1, 'Activate warranty', 'warranty-key-0003'), /CSRF/);
});
