import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';

import { initializeIntegrationsWorkspace, integrationsWorkspaceTemplate } from '../../src/applications/web/public/assets/integrations-workspace.mjs';

test('Integrations workspace is capability gated and exposes Jira Assets plus ServiceNow federated-read UI', async () => {
  const root = { attributes: {}, setAttribute(name, value) { this.attributes[name] = String(value); } };
  const documentObject = { getElementById: (id) => id === 'integrations-workspace' ? root : null };
  const disabled = await initializeIntegrationsWorkspace(documentObject, { apiBaseUrl: '/api', integrationsConnectorsEnabled: false });
  assert.equal(disabled.enabled, false);
  assert.equal(root.attributes['data-capability-enabled'], 'false');

  const template = integrationsWorkspaceTemplate();
  for (const id of ['jira-assets-connectors', 'jira-assets-search', 'jira-assets-connector', 'jira-assets-aql', 'jira-assets-results', 'service-now-connectors', 'service-now-search', 'service-now-connector', 'service-now-query', 'service-now-results']) {
    assert.match(template, new RegExp(`id="${id}"`));
  }
  for (const key of ['integrations.connector', 'integrations.direction', 'integrations.authority', 'integrations.search.title']) {
    assert.match(template, new RegExp(`data-i18n="${key}"`));
  }
  assert.match(template, /maxlength="4096"/);
  assert.doesNotMatch(template, /password|bearer|token|credential/i);
});

test('Integrations workspace source only performs governed reads through provider clients', async () => {
  const source = await readFile(new URL('../../src/applications/web/public/assets/integrations-workspace.mjs', import.meta.url), 'utf8');
  assert.match(source, /new JiraAssetsClient/);
  assert.match(source, /client\.connectors\(\)/);
  assert.match(source, /client\.health\(item\.connectorKey\)/);
  assert.match(source, /jira\.search\(/);
  assert.match(source, /sn\.search\(/);
  assert.doesNotMatch(source, /(?:client|jira|sn)\.(create|update|delete|import|synchronize|push)\(/);
});
