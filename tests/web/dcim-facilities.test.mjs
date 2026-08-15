import assert from 'node:assert/strict';
import test from 'node:test';

import { DcimFacilityApiError, DcimFacilityClient } from '../../src/applications/web/public/assets/dcim-facilities.mjs';

const facilityId = '019ffbda-3300-7001-8001-000000000001';
const organizationId = '019ffbda-3301-7002-8002-000000000002';
const subdivisionId = '019ffbda-3302-7003-8003-000000000003';
const configuration = Object.freeze({ apiBaseUrl: '/api', dcimFacilitiesEnabled: true });
const cookieProvider = () => 'INX_XSRF=csrf-token';

function response(payload, { status = 200, etag = '"ver-2"' } = {}) {
  return { ok: status >= 200 && status < 300, status, headers: { get: (name) => String(name).toLowerCase() === 'etag' ? etag : null }, async json() { return payload; } };
}

test('client is fail-closed and collection filters are allow-listed', async () => {
  assert.throws(() => new DcimFacilityClient({ apiBaseUrl: '/api', dcimFacilitiesEnabled: false }), /disabled/);
  let call;
  const client = new DcimFacilityClient(configuration, { fetchFunction: async (url, options) => { call = { url, options }; return response({ items: [] }); }, cookieProvider });
  await client.list('sites', { organization_id: organizationId, subdivision_id: subdivisionId, country_code: 'FR', limit: 200, ignored: 'x' });
  assert.equal(call.url, `/api/v1/dcim/sites?organization_id=${organizationId}&subdivision_id=${subdivisionId}&limit=200&country_code=FR`);
  await client.list('rooms', { organization_id: organizationId, country_code: 'FR', limit: 50 });
  assert.equal(call.url, `/api/v1/dcim/rooms?organization_id=${organizationId}&limit=50`);
  assert.equal(call.options.credentials, 'same-origin');
  assert.equal(call.options.cache, 'no-store');
  await assert.rejects(async () => client.list('racks'), /unsupported/);
});

test('create carries CSRF, idempotency and audit justification without an optimistic header', async () => {
  let call;
  const client = new DcimFacilityClient(configuration, { fetchFunction: async (url, options) => { call = { url, options }; return response({ id: facilityId }, { status: 201, etag: '"ver-1"' }); }, cookieProvider });
  const result = await client.create('sites', { organizationId, subdivisionId, code: 'PAR01', displayName: 'Paris', addressLine1: '10 Rue de Rivoli', postalCode: '75001', city: 'Paris', countryCode: 'FR', timezone: 'Europe/Paris', reason: 'Create governed site' }, 'dcim-key-0001');
  assert.equal(call.url, '/api/v1/dcim/sites');
  assert.equal(call.options.headers['X-CSRF-Token'], 'csrf-token');
  assert.equal(call.options.headers['Idempotency-Key'], 'dcim-key-0001');
  assert.equal(call.options.headers['X-InfraNexum-Justification'], 'Create governed site');
  assert.equal(call.options.headers['If-Match'], undefined);
  assert.equal(result.etag, '"ver-1"');
});

test('update and status transitions enforce optimistic concurrency', async () => {
  const calls = [];
  const client = new DcimFacilityClient(configuration, { fetchFunction: async (url, options) => { calls.push({ url, options }); return response({ id: facilityId }); }, cookieProvider });
  await client.update('rooms', facilityId, 3, { displayName: 'Room A', reason: 'Rename room' }, 'dcim-key-0002');
  await client.changeStatus('rooms', facilityId, 4, 'locked', 'Secure maintenance', 'dcim-key-0003');
  assert.equal(calls[0].url, `/api/v1/dcim/rooms/${facilityId}`);
  assert.equal(calls[0].options.method, 'PATCH');
  assert.equal(calls[0].options.headers['If-Match'], '"ver-3"');
  assert.equal(calls[1].url, `/api/v1/dcim/rooms/${facilityId}/status`);
  assert.equal(calls[1].options.headers['If-Match'], '"ver-4"');
  assert.deepEqual(JSON.parse(calls[1].options.body), { targetStatus: 'locked', reason: 'Secure maintenance' });
});

test('client rejects malformed resources, identifiers, versions, reasons and idempotency keys', async () => {
  const client = new DcimFacilityClient(configuration, { fetchFunction: async () => response({}), cookieProvider });
  await assert.rejects(async () => client.get('unknown', facilityId), /unsupported/);
  await assert.rejects(async () => client.get('sites', 'not-a-uuid'), /facilityId/);
  await assert.rejects(async () => client.update('sites', facilityId, 0, { reason: 'Update site' }, 'dcim-key-0004'), /positive integer/);
  await assert.rejects(async () => client.create('sites', { reason: 'x' }, 'dcim-key-0005'), /2 to 1024/);
  await assert.rejects(async () => client.create('sites', { reason: 'Create site' }, 'short'), /8 to 200/);
});

test('mutations fail closed without CSRF and problem+json is translated safely', async () => {
  const noCsrf = new DcimFacilityClient(configuration, { fetchFunction: async () => response({}), cookieProvider: () => '' });
  await assert.rejects(async () => noCsrf.changeStatus('sites', facilityId, 1, 'active', 'Activate site', 'dcim-key-0006'), /CSRF/);
  const client = new DcimFacilityClient(configuration, {
    fetchFunction: async () => response({ code: 'DCIM_FACILITY_CONFLICT', detail: 'facility changed' }, { status: 409, etag: null }), cookieProvider,
  });
  await assert.rejects(async () => client.get('sites', facilityId), (error) => {
    assert.ok(error instanceof DcimFacilityApiError);
    assert.equal(error.status, 409);
    assert.equal(error.code, 'DCIM_FACILITY_CONFLICT');
    assert.equal(error.message, 'facility changed');
    return true;
  });
});
