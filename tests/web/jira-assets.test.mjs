import assert from 'node:assert/strict';
import test from 'node:test';

import { JiraAssetsApiError, JiraAssetsClient } from '../../src/applications/web/public/assets/jira-assets.mjs';

const configuration = { apiBaseUrl: '/api', integrationsConnectorsEnabled: true };
const headers = (values = {}) => ({ get(name) { return values[name] ?? values[name.toLowerCase()] ?? null; } });
const response = (payload, { status = 200, headerValues = {} } = {}) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: headers(headerValues),
  async json() { if (payload instanceof Error) throw payload; return payload; },
});

test('Jira Assets client is capability-gated and performs safe same-origin reads', async () => {
  assert.throws(() => new JiraAssetsClient({ apiBaseUrl: '/api', integrationsConnectorsEnabled: false }), /disabled/);
  assert.throws(() => new JiraAssetsClient({ integrationsConnectorsEnabled: true }), /apiBaseUrl/);
  const calls = [];
  const client = new JiraAssetsClient(configuration, { fetchFunction: async (url, options) => {
    calls.push({ url, options });
    return response([{ connectorKey: 'jira-assets.test', authority: 'EXTERNAL' }]);
  }});
  const result = await client.connectors();
  assert.equal(result.payload[0].authority, 'EXTERNAL');
  assert.equal(calls[0].url, '/api/v1/integrations/providers/jira-assets');
  assert.equal(calls[0].options.method, 'GET');
  assert.equal(calls[0].options.credentials, 'same-origin');
  assert.equal(calls[0].options.cache, 'no-store');
  assert.equal(calls[0].options.headers.Authorization, undefined);

  await client.health('JIRA-ASSETS.TEST');
  assert.equal(calls[1].url, '/api/v1/integrations/providers/jira-assets/jira-assets.test/health');
  assert.throws(() => client.health('!invalid'), /connectorKey/);
});

test('federated AQL search sends only AQL plus CSRF and preserves pagination headers', async () => {
  const calls = [];
  const client = new JiraAssetsClient(configuration, {
    cookieProvider: () => 'other=x; INX_XSRF=csrf%2Dtoken',
    fetchFunction: async (url, options) => {
      calls.push({ url, options });
      return response([{ objectKey: 'SRV-1' }], { headerValues: { 'X-Page-Limit': '50', 'X-Next-Offset': '50' } });
    },
  });
  const result = await client.search('jira-assets.test', ' objectType = Server ', { offset: 0, limit: 50 });
  assert.equal(calls[0].url, '/api/v1/integrations/providers/jira-assets/jira-assets.test/objects/search?offset=0&limit=50');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.headers['X-CSRF-Token'], 'csrf-token');
  assert.equal(calls[0].options.headers.Authorization, undefined);
  assert.deepEqual(JSON.parse(calls[0].options.body), { aql: 'objectType = Server' });
  assert.equal(result.pagination.nextOffset, 50);
  assert.equal(result.pagination.hasNext, true);

  for (const aql of [null, '', 'x\ny', 'x'.repeat(4097)]) assert.throws(() => client.search('jira-assets.test', aql), /AQL/);
  for (const [offset, limit] of [[-1, 50], [1_000_001, 50], [0, 0], [0, 201], [1.5, 50]]) {
    assert.throws(() => client.search('jira-assets.test', 'x', { offset, limit }), /bounds/);
  }
  const noCsrf = new JiraAssetsClient(configuration, { cookieProvider: () => '', fetchFunction: async () => response([]) });
  await assert.rejects(noCsrf.search('jira-assets.test', 'x'), /CSRF token/);
});

test('provider problems and aborts become stable browser errors without raw fallbacks', async () => {
  const problemClient = new JiraAssetsClient(configuration, {
    fetchFunction: async () => response({ code: 'INFRANEXUM_JIRA_ASSETS_RATE_LIMITED', detail: 'temporarily unavailable' }, { status: 503 }),
  });
  await assert.rejects(problemClient.connectors(), (error) => {
    assert.ok(error instanceof JiraAssetsApiError);
    assert.equal(error.status, 503);
    assert.equal(error.code, 'INFRANEXUM_JIRA_ASSETS_RATE_LIMITED');
    return true;
  });

  const malformedProblem = new JiraAssetsClient(configuration, {
    fetchFunction: async () => response(new Error('bad-json'), { status: 502 }),
  });
  await assert.rejects(malformedProblem.connectors(), (error) => error instanceof JiraAssetsApiError && error.status === 502);

  const aborted = new JiraAssetsClient(configuration, {
    fetchFunction: async () => { const error = new Error('aborted'); error.name = 'AbortError'; throw error; },
  });
  await assert.rejects(aborted.connectors(), (error) => error instanceof JiraAssetsApiError && error.code === 'JIRA_ASSETS_TIMEOUT');
});
