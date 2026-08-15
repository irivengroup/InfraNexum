import assert from 'node:assert/strict';
import test from 'node:test';
import { ItamPartnerApiError, ItamPartnerClient } from '../../src/applications/web/public/assets/itam-partners.mjs';

const ID = '01900000-0000-7000-8000-000000000001';
const configuration = Object.freeze({ apiBaseUrl: '/api', itamPartnersEnabled: true });
const response = (status, payload, etag = null) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: { get: (name) => name.toLowerCase() === 'etag' ? etag : null },
  async json() { return payload; },
});

test('ITAM Partner client is fail-closed and keeps catalogue filters bounded', async () => {
  assert.throws(() => new ItamPartnerClient({ apiBaseUrl: '/api', itamPartnersEnabled: false }), /disabled/);
  let observed;
  const client = new ItamPartnerClient(configuration, {
    fetchFunction: async (url, options) => { observed = { url, options }; return response(200, { items: [], nextCursor: null }); },
  });
  await client.list({ role: 'manufacturer', authorization_status: 'active', country_code: 'FR', ignored: 'secret', limit: 50 });
  assert.match(observed.url, /^\/api\/v1\/itam\/partners\?/);
  assert.match(observed.url, /role=manufacturer/);
  assert.match(observed.url, /authorization_status=active/);
  assert.doesNotMatch(observed.url, /ignored/);
  assert.equal(observed.options.credentials, 'same-origin');
});

test('ITAM Partner mutations require CSRF, idempotency and optimistic version headers', async () => {
  const calls = [];
  const client = new ItamPartnerClient(configuration, {
    cookieProvider: () => 'INX_XSRF=csrf-token',
    fetchFunction: async (url, options) => { calls.push({ url, options }); return response(200, { id: ID, version: 3 }, '"ver-3"'); },
  });
  await client.authorize(ID, 2, 'Approval reviewed by ITAM owner', 'partner-auth-0001');
  assert.equal(calls[0].url, `/api/v1/itam/partners/${ID}/authorize`);
  assert.equal(calls[0].options.headers['X-CSRF-Token'], 'csrf-token');
  assert.equal(calls[0].options.headers['Idempotency-Key'], 'partner-auth-0001');
  assert.equal(calls[0].options.headers['If-Match'], '"ver-2"');
  assert.deepEqual(JSON.parse(calls[0].options.body), { reason: 'Approval reviewed by ITAM owner' });
});

test('ITAM Partner client validates identifiers, versions, reasons and idempotency keys', async () => {
  const client = new ItamPartnerClient(configuration, {
    cookieProvider: () => 'INX_XSRF=csrf-token',
    fetchFunction: async () => response(200, {}),
  });
  assert.throws(() => client.authorize('bad', 1, 'Sufficient reason text', 'partner-auth-0001'), /UUID/);
  await assert.rejects(client.authorize(ID, 0, 'Sufficient reason text', 'partner-auth-0001'), /positive integer/);
  assert.throws(() => client.authorize(ID, 1, 'x', 'partner-auth-0001'), /2 to 1024/);
  await assert.rejects(client.create({}, 'bad'), /8 to 200/);
  const noCsrf = new ItamPartnerClient(configuration, { cookieProvider: () => '', fetchFunction: async () => response(200, {}) });
  await assert.rejects(noCsrf.create({}, 'partner-create-0001'), /CSRF token/);
});

test('ITAM Partner client maps problem+json without exposing transport internals', async () => {
  const client = new ItamPartnerClient(configuration, {
    fetchFunction: async () => response(409, { code: 'PARTNER_DUPLICATE', detail: 'matching governed identity already exists' }),
  });
  await assert.rejects(client.list(), (error) => error instanceof ItamPartnerApiError && error.code === 'PARTNER_DUPLICATE');
});
