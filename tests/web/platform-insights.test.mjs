import assert from 'node:assert/strict';
import test from 'node:test';

import {
  initializePlatformAutoRefresh,
  loadPlatformInsights,
  summarizePlatformInsights,
  validateCapabilitySnapshot,
  validateQuotaPlan,
} from '../../src/applications/web/public/assets/platform-insights.mjs';

class Element {
  constructor() { this.textContent = ''; this.attributes = {}; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
}

function documentFixture() {
  const ids = [
    'platform-profile', 'platform-tier', 'platform-capabilities', 'platform-ha', 'platform-split-web',
    'platform-org-limit', 'platform-server-limit', 'platform-web-limit', 'platform-catalog-version', 'platform-insights-state',
  ];
  const elements = new Map(ids.map((id) => [id, new Element()]));
  return {
    documentElement: { getAttribute: () => 'en' },
    getElementById: (id) => elements.get(id),
    addEventListener(name, listener) { this.listenerName = name; this.listener = listener; },
    elements,
  };
}

const capabilityPayload = {
  catalogVersion: '2026.08',
  capabilities: [
    { capabilityCode: 'deployment.high_availability', available: true, profile: 'PRO', topology: 'high-availability', evaluatedAt: '2026-08-12T20:00:00Z' },
    { capabilityCode: 'deployment.split_web', available: true, profile: 'PRO', topology: 'high-availability', evaluatedAt: '2026-08-12T20:00:00Z' },
    { capabilityCode: 'database.oracle', available: false, profile: 'PRO', topology: 'high-availability', evaluatedAt: '2026-08-12T20:00:00Z' },
  ],
};
const quotaPayload = {
  catalogVersion: '2026.08', profile: 'PRO', allocationTier: 'STANDARD',
  quotas: {
    'organization.organizations.max': 10,
    'deployment.server.nodes_total.max': 4,
    'deployment.web.nodes_total.max': 2,
  },
};

test('platform insight contracts accept secret-free effective decisions and reject malformed quotas', () => {
  const snapshot = validateCapabilitySnapshot(capabilityPayload);
  const quotas = validateQuotaPlan(quotaPayload);
  assert.equal(Object.isFrozen(snapshot.capabilities), true);
  assert.equal(quotas.profile, 'PRO');
  assert.throws(() => validateCapabilitySnapshot({ catalogVersion: 'x', capabilities: [{ capabilityCode: 'x', available: 'yes' }] }), /boolean/i);
  assert.throws(() => validateQuotaPlan({ ...quotaPayload, quotas: { 'organization.organizations.max': 10 } }), /required quota/i);
});

test('platform summary is derived only from API decisions and effective quotas', () => {
  const summary = summarizePlatformInsights(validateCapabilitySnapshot(capabilityPayload), validateQuotaPlan(quotaPayload));
  assert.deepEqual(summary, {
    profile: 'PRO', allocationTier: 'STANDARD', capabilitiesAvailable: 2, capabilitiesTotal: 3,
    highAvailability: true, splitWeb: true, organizationLimit: 10, serverNodeLimit: 4, webNodeLimit: 2, catalogVersion: '2026.08',
  });
});

test('platform loader renders real capability/quota data and emits an observed success notification', async () => {
  const documentObject = documentFixture();
  const notices = [];
  const fetchFunction = async (url) => ({ ok: true, async json() { return url.endsWith('/capabilities') ? capabilityPayload : quotaPayload; } });
  const result = await loadPlatformInsights(documentObject, { apiBaseUrl: '/api' }, fetchFunction, { upsert: (notice) => notices.push(notice) });
  assert.equal(result.profile, 'PRO');
  assert.equal(documentObject.elements.get('platform-profile').textContent, 'PRO');
  assert.equal(documentObject.elements.get('platform-capabilities').textContent, '2/3');
  assert.equal(documentObject.elements.get('platform-server-limit').textContent, '4');
  assert.equal(documentObject.elements.get('platform-ha').textContent, 'Enabled');
  assert.equal(notices[0].id, 'platform-api');
  assert.equal(notices[0].severity, 'success');
});

test('platform loader fails closed when either backend contract is unavailable', async () => {
  const documentObject = documentFixture();
  const notices = [];
  const result = await loadPlatformInsights(documentObject, { apiBaseUrl: '/api' }, async () => ({ ok: false, status: 503 }), { upsert: (notice) => notices.push(notice) });
  assert.equal(result, null);
  assert.equal(documentObject.elements.get('platform-profile').textContent, '—');
  assert.equal(documentObject.elements.get('platform-insights-state').textContent, 'Unavailable');
  assert.equal(notices[0].severity, 'error');
});

test('operational refresh cadence is bounded to the structured preference', () => {
  const documentObject = documentFixture();
  const scheduled = [];
  const cleared = [];
  const timer = {
    setInterval(callback, delay) { scheduled.push({ callback, delay }); return scheduled.length; },
    clearInterval(id) { cleared.push(id); },
  };
  const controller = initializePlatformAutoRefresh(documentObject, () => {}, { refreshIntervalSeconds: 60 }, timer);
  assert.equal(scheduled[0].delay, 60_000);
  documentObject.listener({ detail: { refreshIntervalSeconds: 30 } });
  assert.equal(cleared[0], 1);
  assert.equal(scheduled[1].delay, 30_000);
  controller.reschedule({ refreshIntervalSeconds: 0 });
  assert.equal(scheduled.length, 2);
});
