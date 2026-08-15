import assert from 'node:assert/strict';
import test from 'node:test';
import { RsotSchemaRegistryClient, RsotSchemaRegistryApiError } from '../../src/applications/web/public/assets/rsot-schema-registry.mjs';

const ID = '01900000-0000-7000-8000-000000000001';
const configuration = Object.freeze({ apiBaseUrl: '/api', rsotCoreEnabled: true });
function response(status, payload, etag = null) {
  return { ok: status >= 200 && status < 300, status, headers: { get: (name) => name.toLowerCase() === 'etag' ? etag : null }, async json() { return payload; } };
}

test('RSOT schema client is capability gated and sends CSRF plus If-Match on mutation', async () => {
  assert.throws(() => new RsotSchemaRegistryClient({ apiBaseUrl: '/api', rsotCoreEnabled: false }), /disabled/);
  let observed;
  const client = new RsotSchemaRegistryClient(configuration, {
    cookieProvider: () => 'INX_XSRF=csrf-token',
    fetchFunction: async (url, options) => { observed = { url, options }; return response(200, { id: ID, revision: 3 }, '"rev-3"'); },
  });
  const result = await client.updateSchema(ID, 2, { type: 'object' });
  assert.equal(observed.url, `/api/v1/rsot/schemas/${ID}`);
  assert.equal(observed.options.headers['X-CSRF-Token'], 'csrf-token');
  assert.equal(observed.options.headers['If-Match'], '"rev-2"');
  assert.equal(result.etag, '"rev-3"');
});

test('RSOT schema client keeps query bounded and validates identifiers and revisions', async () => {
  let url;
  const client = new RsotSchemaRegistryClient(configuration, {
    fetchFunction: async (target) => { url = target; return response(200, []); },
  });
  await client.listSchemas({ schemaKey: 'rsot.server', status: 'PUBLISHED', ignored: 'x', limit: 50 });
  assert.match(url, /^\/api\/v1\/rsot\/schemas\?/);
  assert.match(url, /schemaKey=rsot.server/);
  assert.doesNotMatch(url, /ignored/);
  assert.throws(() => client.getSchema('bad'), /UUID/);
  await assert.rejects(client.publishSchema(ID, 0), /positive integer/);
});

test('RSOT schema client maps problem+json and requires CSRF for mutations', async () => {
  const client = new RsotSchemaRegistryClient(configuration, {
    cookieProvider: () => '',
    fetchFunction: async () => response(422, { code: 'SCHEMA_COMPATIBILITY_INDETERMINATE', detail: 'compatibility cannot be proven' }),
  });
  await assert.rejects(client.createSchema({}), /CSRF token/);
  const readClient = new RsotSchemaRegistryClient(configuration, {
    fetchFunction: async () => response(422, { code: 'SCHEMA_COMPATIBILITY_INDETERMINATE', detail: 'compatibility cannot be proven' }),
  });
  await assert.rejects(readClient.compatibility(ID), (error) => error instanceof RsotSchemaRegistryApiError && error.code === 'SCHEMA_COMPATIBILITY_INDETERMINATE');
});

test('RSOT profile lifecycle shares the same optimistic concurrency transport', async () => {
  const calls = [];
  const client = new RsotSchemaRegistryClient(configuration, {
    cookieProvider: () => 'INX_XSRF=csrf-token',
    fetchFunction: async (url, options) => { calls.push({ url, options }); return response(200, { id: ID }); },
  });
  await client.publishProfile(ID, 4);
  await client.deprecateProfile(ID, 5, '2027-01-01T00:00:00Z', 'Superseded');
  assert.equal(calls[0].options.headers['If-Match'], '"rev-4"');
  assert.equal(calls[1].options.headers['If-Match'], '"rev-5"');
  assert.deepEqual(JSON.parse(calls[1].options.body), { sunsetAt: '2027-01-01T00:00:00Z', reason: 'Superseded' });
});
