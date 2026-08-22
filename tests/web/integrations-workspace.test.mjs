import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';

import { initializeIntegrationsWorkspace, integrationsWorkspaceTemplate, isExecutableMutator } from '../../src/applications/web/public/assets/integrations-workspace.mjs';

test('Integrations workspace is capability gated and exposes Jira Assets plus ServiceNow federated-read UI', async () => {
  const root = { attributes: {}, setAttribute(name, value) { this.attributes[name] = String(value); } };
  const documentObject = { getElementById: (id) => id === 'integrations-workspace' ? root : null };
  const disabled = await initializeIntegrationsWorkspace(documentObject, { apiBaseUrl: '/api', integrationsConnectorsEnabled: false });
  assert.equal(disabled.enabled, false);
  assert.equal(root.attributes['data-capability-enabled'], 'false');

  const template = integrationsWorkspaceTemplate();
  for (const id of ['jira-assets-connectors', 'jira-assets-search', 'jira-assets-connector', 'jira-assets-aql', 'jira-assets-results', 'service-now-connectors', 'service-now-search', 'service-now-connector', 'service-now-query', 'service-now-results', 'connector-governance-policies', 'connector-governance-page', 'connector-sync-runs', 'connector-sync-checkpoints', 'connector-sync-execute', 'connector-sync-connector', 'connector-sync-direction', 'connector-sync-reason', 'notification-endpoints', 'notification-publish', 'notification-endpoint', 'notification-event-id', 'notification-event-type', 'notification-payload', 'notification-dlq']) {
    assert.match(template, new RegExp(`id="${id}"`));
  }
  for (const key of ['integrations.connector', 'integrations.direction', 'integrations.authority', 'integrations.governance.execution', 'integrations.search.title']) {
    assert.match(template, new RegExp(`data-i18n="${key}"`));
  }
  assert.match(template, /maxlength="4096"/);
  assert.doesNotMatch(template, /password|bearer|token|credential/i);
  for (const tab of ['governance', 'sync', 'jira-assets', 'service-now', 'notifications']) {
    assert.match(template, new RegExp(`data-integrations-tab="${tab}"`));
    assert.match(template, new RegExp(`data-integrations-panel="${tab}"`));
  }
  assert.match(template, /aria-selected="true"[^>]+data-integrations-tab="governance"/);
  assert.match(template, /data-integrations-panel="governance"[^>]+aria-hidden="false"/);
  assert.match(template, /data-integrations-panel="sync"[^>]+hidden aria-hidden="true"/);
});

test('Integrations workspace keeps providers read-only while exposing governed generic sync runtime', async () => {
  const source = await readFile(new URL('../../src/applications/web/public/assets/integrations-workspace.mjs', import.meta.url), 'utf8');
  assert.match(source, /bindTabSet\(documentObject, '\[data-integrations-tab\]', '\[data-integrations-panel\]', 'data-integrations-tab'\)/);
  assert.match(source, /new JiraAssetsClient/);
  assert.match(source, /new NotificationClient/);
  assert.match(source, /new ConnectorGovernanceClient/);
  assert.match(source, /new ConnectorSyncClient/);
  assert.match(source, /client\.runs\(/);
  assert.match(source, /sync\.execute\(/);
  assert.match(source, /client\.connectors\(\)/);
  assert.match(source, /client\.health\(item\.connectorKey\)/);
  assert.match(source, /jira\.search\(/);
  assert.match(source, /sn\.search\(/);
  assert.match(source, /notifications\.publish\(/);
  assert.match(source, /client\.plan\(item\.connectorKey/);
  assert.match(source, /client\.replay\(/);
  assert.match(source, /client\.resume\(/);
  assert.doesNotMatch(source, /(?:jira|sn)\.(create|update|delete|import|synchronize|push|execute)\(/);
  assert.match(source, /FEDERATED_READ|mutating/);
});

test('Synchronization execution is admitted only for explicitly enabled mutating governance', () => {
  assert.equal(isExecutableMutator({ direction: 'INBOUND', mutating: true, executionEnabled: true }), true);
  assert.equal(isExecutableMutator({ direction: 'INBOUND', mutating: true, executionEnabled: false }), false);
  assert.equal(isExecutableMutator({ direction: 'FEDERATED_READ', mutating: false, executionEnabled: false }), false);
  assert.equal(isExecutableMutator({ direction: 'OUTBOUND', mutating: true }), false);
  assert.equal(isExecutableMutator(null), false);
});
