import assert from 'node:assert/strict';
import test from 'node:test';

import { ConnectorGovernanceApiError, ConnectorGovernanceClient } from '../../src/applications/web/public/assets/connector-governance.mjs';

const configuration = { apiBaseUrl: '/api', integrationsConnectorsEnabled: true };
const headers = (values = {}) => ({ get(name) { return values[name] ?? values[name.toLowerCase()] ?? null; } });
const response = (payload, { status = 200, headerValues = {} } = {}) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: headers(headerValues),
  async json() { if (payload instanceof Error) throw payload; return payload; },
});

test('connector governance client is capability-gated and lists secret-free policies', async () => {
  assert.throws(() => new ConnectorGovernanceClient({ apiBaseUrl: '/api', integrationsConnectorsEnabled: false }), /disabled/);
  assert.throws(() => new ConnectorGovernanceClient({ integrationsConnectorsEnabled: true }), /apiBaseUrl/);
  const calls = [];
  const client = new ConnectorGovernanceClient(configuration, { fetchFunction: async (url, options) => {
    calls.push({ url, options });
    return response([{ connectorKey: 'jira-prod', direction: 'FEDERATED_READ', authority: 'EXTERNAL', rollbackStrategy: 'NONE_REQUIRED', fields: [] }], {
      headerValues: { 'X-Page-Limit': '50' },
    });
  }});
  const result = await client.policies();
  assert.equal(result.payload[0].authority, 'EXTERNAL');
  assert.equal(calls[0].url, '/api/v1/integrations/governance?offset=0&limit=50');
  assert.equal(calls[0].options.method, 'GET');
  assert.equal(calls[0].options.headers.Authorization, undefined);
  assert.throws(() => client.policies({ offset: -1 }), /bounds/);
  assert.throws(() => client.policy('!invalid'), /connectorKey/);
});

test('sync plan is a CSRF-protected dry-run with bounded direction and field inputs', async () => {
  const calls = [];
  const client = new ConnectorGovernanceClient(configuration, {
    cookieProvider: () => 'INX_XSRF=csrf%2Dtoken',
    fetchFunction: async (url, options) => {
      calls.push({ url, options });
      return response({ connectorKey: 'jira-prod', decision: 'ALLOW', rollbackStrategy: 'NONE_REQUIRED', reasons: [] });
    },
  });
  const result = await client.plan('JIRA-PROD', { direction: 'federated_read', fields: [], propagateDeletions: false });
  assert.equal(result.payload.decision, 'ALLOW');
  assert.equal(calls[0].url, '/api/v1/integrations/governance/jira-prod/sync-plan');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.headers['X-CSRF-Token'], 'csrf-token');
  assert.equal(calls[0].options.headers.Authorization, undefined);
  assert.deepEqual(JSON.parse(calls[0].options.body), { direction: 'FEDERATED_READ', fields: [], propagateDeletions: false });
  assert.throws(() => client.plan('jira-prod', { direction: 'IMPORT' }), /direction/);
  assert.throws(() => client.plan('jira-prod', { direction: 'INBOUND', fields: ['Bad Field'] }), /fields/);
  assert.throws(() => client.plan('jira-prod', { direction: 'INBOUND', fields: ['name','name'] }), /duplicate/);
  assert.throws(() => client.plan('jira-prod', { direction: 'INBOUND', propagateDeletions: 'yes' }), /boolean/);

  const noCsrf = new ConnectorGovernanceClient(configuration, { cookieProvider: () => '', fetchFunction: async () => response({}) });
  await assert.rejects(noCsrf.plan('jira-prod', { direction: 'FEDERATED_READ' }), /CSRF token/);
});

test('connector governance errors remain stable and do not expose provider payloads', async () => {
  const client = new ConnectorGovernanceClient(configuration, {
    fetchFunction: async () => response({ code: 'INFRANEXUM_CONNECTOR_GOVERNANCE_NOT_FOUND', detail: 'Connector governance policy was not found' }, { status: 404 }),
  });
  await assert.rejects(client.policy('jira-prod'), error => {
    assert.ok(error instanceof ConnectorGovernanceApiError);
    assert.equal(error.status, 404);
    assert.equal(error.code, 'INFRANEXUM_CONNECTOR_GOVERNANCE_NOT_FOUND');
    return true;
  });

  const aborted = new ConnectorGovernanceClient(configuration, {
    fetchFunction: async () => { const error = new Error('aborted'); error.name = 'AbortError'; throw error; },
  });
  await assert.rejects(aborted.policies(), error => error instanceof ConnectorGovernanceApiError && error.code === 'CONNECTOR_GOVERNANCE_TIMEOUT');
});
