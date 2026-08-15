import assert from 'node:assert/strict';
import test from 'node:test';

import { ItamAssetApiError, ItamAssetClient } from '../../src/applications/web/public/assets/itam-assets.mjs';

const assetId = '019ffbda-2200-7001-8001-000000000001';
const custodianId = '019ffbda-2201-7002-8002-000000000002';
const configuration = Object.freeze({ apiBaseUrl: '/api', itamAssetsEnabled: true });
const cookieProvider = () => 'INX_XSRF=csrf-token';

function response(payload, { status = 200, etag = '"ver-2"' } = {}) {
  return { ok: status >= 200 && status < 300, status, headers: { get: (name) => name === 'etag' ? etag : null }, async json() { return payload; } };
}

test('client is fail-closed on capability and validates collection filters', async () => {
  assert.throws(() => new ItamAssetClient({ apiBaseUrl: '/api', itamAssetsEnabled: false }), /disabled/);
  let url;
  const client = new ItamAssetClient(configuration, { fetchFunction: async (value) => { url = value; return response({ items: [] }); }, cookieProvider });
  await client.list({ organization_id: assetId, asset_type: 'hardware', limit: 50, ignored: 'x' });
  assert.equal(url, `/api/v1/itam/assets?organization_id=${assetId}&asset_type=hardware&limit=50`);
});

test('create carries CSRF and idempotency without optimistic header', async () => {
  let call;
  const client = new ItamAssetClient(configuration, { fetchFunction: async (url, options) => { call = { url, options }; return response({ id: assetId }, { status: 201, etag: '"ver-1"' }); }, cookieProvider });
  const result = await client.create({ rsotObjectId: assetId }, 'asset-key-0001');
  assert.equal(call.url, '/api/v1/itam/assets');
  assert.equal(call.options.headers['X-CSRF-Token'], 'csrf-token');
  assert.equal(call.options.headers['Idempotency-Key'], 'asset-key-0001');
  assert.equal(call.options.headers['If-Match'], undefined);
  assert.equal(result.etag, '"ver-1"');
});

test('lifecycle mutation validates custody, reason and concurrency headers', async () => {
  let call;
  const client = new ItamAssetClient(configuration, { fetchFunction: async (url, options) => { call = { url, options }; return response({ id: assetId }); }, cookieProvider });
  await client.assign(assetId, 1, { custodianKind: 'actor', custodianId }, 'Assigned to employee', 'asset-key-0002');
  assert.equal(call.url, `/api/v1/itam/assets/${assetId}/assign`);
  assert.equal(call.options.headers['If-Match'], '"ver-1"');
  assert.deepEqual(JSON.parse(call.options.body), { custodianKind: 'actor', custodianId, reason: 'Assigned to employee' });
  await assert.rejects(async () => client.assign(assetId, 1, { custodianKind: 'none' }, 'ok', 'asset-key-0003'), /custodianKind/);
  await assert.rejects(async () => client.assign(assetId, 0, { custodianKind: 'actor', custodianId }, 'Assigned', 'asset-key-0003'), /positive integer/);
});

test('disposal requires evidence and custody pagination is bounded', async () => {
  const client = new ItamAssetClient(configuration, { fetchFunction: async () => response([]), cookieProvider });
  await assert.rejects(async () => client.dispose(assetId, 3, 'Disposed', null, 'asset-key-0004'), /evidenceReference/);
  await assert.rejects(async () => client.custody(assetId, { afterSequence: -1 }), /non-negative/);
  await assert.rejects(async () => client.custody(assetId, { limit: 201 }), /between 1 and 200/);
});

test('problem+json is translated without exposing response internals', async () => {
  const client = new ItamAssetClient(configuration, {
    fetchFunction: async () => response({ code: 'ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE', detail: 'blocked' }, { status: 403, etag: null }), cookieProvider,
  });
  await assert.rejects(async () => client.get(assetId), (error) => {
    assert.ok(error instanceof ItamAssetApiError); assert.equal(error.status, 403); assert.equal(error.code, 'ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE'); return true;
  });
});

test('mutations require a CSRF cookie and safe idempotency key', async () => {
  const noCsrf = new ItamAssetClient(configuration, { fetchFunction: async () => response({}), cookieProvider: () => '' });
  await assert.rejects(async () => noCsrf.retire(assetId, 1, 'Retire asset', 'asset-key-0005'), /CSRF/);
  const client = new ItamAssetClient(configuration, { fetchFunction: async () => response({}), cookieProvider });
  await assert.rejects(async () => client.retire(assetId, 1, 'Retire asset', 'short'), /8 to 200/);
});


test('producer correction uses optimistic concurrency and governed UUIDs', async () => {
  let call;
  const producerId = '019ffbda-2202-7003-8003-000000000003';
  const client = new ItamAssetClient(configuration, { fetchFunction: async (url, options) => { call = { url, options }; return response({ id: assetId, producerPartnerId: producerId }); }, cookieProvider });
  await client.setProducer(assetId, 2, producerId, 'Correct manufacturer authority', 'asset-key-0006');
  assert.equal(call.url, `/api/v1/itam/assets/${assetId}/producer`);
  assert.equal(call.options.headers['If-Match'], '"ver-2"');
  assert.deepEqual(JSON.parse(call.options.body), { producerPartnerId: producerId, reason: 'Correct manufacturer authority' });
  await assert.rejects(async () => client.setProducer(assetId, 2, 'not-a-uuid', 'Correct manufacturer authority', 'asset-key-0007'), /producerPartnerId/);
});
