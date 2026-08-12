import { setLocalizedText } from './i18n.mjs';

const REQUIRED_QUOTAS = Object.freeze([
  'organization.organizations.max',
  'deployment.server.nodes_total.max',
  'deployment.web.nodes_total.max',
]);

export function validateCapabilitySnapshot(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Capability snapshot must be an object');
  if (typeof value.catalogVersion !== 'string' || value.catalogVersion.length === 0) throw new Error('Capability catalog version is required');
  if (!Array.isArray(value.capabilities)) throw new Error('Capabilities must be an array');
  const capabilities = value.capabilities.map((item) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) throw new Error('Capability decision must be an object');
    if (typeof item.capabilityCode !== 'string' || item.capabilityCode.length === 0) throw new Error('Capability code is required');
    if (typeof item.available !== 'boolean') throw new Error('Capability availability must be boolean');
    return Object.freeze({
      capabilityCode: item.capabilityCode,
      available: item.available,
      profile: typeof item.profile === 'string' ? item.profile : '',
      topology: typeof item.topology === 'string' ? item.topology : '',
      evaluatedAt: typeof item.evaluatedAt === 'string' ? item.evaluatedAt : '',
    });
  });
  return Object.freeze({ catalogVersion: value.catalogVersion, capabilities: Object.freeze(capabilities) });
}

export function validateQuotaPlan(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Quota plan must be an object');
  if (typeof value.profile !== 'string' || value.profile.length === 0) throw new Error('Quota profile is required');
  if (typeof value.allocationTier !== 'string' || value.allocationTier.length === 0) throw new Error('Quota allocation tier is required');
  if (!value.quotas || typeof value.quotas !== 'object' || Array.isArray(value.quotas)) throw new Error('Quota map is required');
  const quotas = {};
  for (const [key, raw] of Object.entries(value.quotas)) {
    if (!Number.isSafeInteger(raw) || raw < 0) throw new Error(`Quota ${key} must be a non-negative integer`);
    quotas[key] = raw;
  }
  for (const key of REQUIRED_QUOTAS) {
    if (!Object.hasOwn(quotas, key)) throw new Error(`Required quota ${key} is missing`);
  }
  return Object.freeze({
    catalogVersion: typeof value.catalogVersion === 'string' ? value.catalogVersion : '',
    profile: value.profile,
    allocationTier: value.allocationTier,
    quotas: Object.freeze(quotas),
  });
}

export function summarizePlatformInsights(snapshot, quotaPlan) {
  const available = snapshot.capabilities.filter((item) => item.available).length;
  const decisions = new Map(snapshot.capabilities.map((item) => [item.capabilityCode, item]));
  return Object.freeze({
    profile: quotaPlan.profile,
    allocationTier: quotaPlan.allocationTier,
    capabilitiesAvailable: available,
    capabilitiesTotal: snapshot.capabilities.length,
    highAvailability: decisions.get('deployment.high_availability')?.available === true,
    splitWeb: decisions.get('deployment.split_web')?.available === true,
    organizationLimit: quotaPlan.quotas['organization.organizations.max'],
    serverNodeLimit: quotaPlan.quotas['deployment.server.nodes_total.max'],
    webNodeLimit: quotaPlan.quotas['deployment.web.nodes_total.max'],
    catalogVersion: snapshot.catalogVersion,
  });
}

export async function loadPlatformInsights(
  documentObject,
  configuration,
  fetchFunction = fetch,
  notificationCenter = null,
) {
  try {
    const [capabilitiesResponse, quotasResponse] = await Promise.all([
      fetchFunction(`${configuration.apiBaseUrl}/v1/platform/capabilities`, { headers: { Accept: 'application/json' }, cache: 'no-store' }),
      fetchFunction(`${configuration.apiBaseUrl}/v1/platform/quotas`, { headers: { Accept: 'application/json' }, cache: 'no-store' }),
    ]);
    if (!capabilitiesResponse.ok) throw new Error(`Capabilities returned HTTP ${capabilitiesResponse.status}`);
    if (!quotasResponse.ok) throw new Error(`Quotas returned HTTP ${quotasResponse.status}`);
    const snapshot = validateCapabilitySnapshot(await capabilitiesResponse.json());
    const quotas = validateQuotaPlan(await quotasResponse.json());
    const insights = summarizePlatformInsights(snapshot, quotas);
    renderPlatformInsights(documentObject, insights);
    notificationCenter?.upsert?.({
      id: 'platform-api', severity: 'success', titleKey: 'notification.platformReady.title', bodyKey: 'notification.platformReady.body',
      parameters: { profile: insights.profile, tier: insights.allocationTier },
    });
    return insights;
  } catch {
    renderPlatformInsightsFailure(documentObject);
    notificationCenter?.upsert?.({
      id: 'platform-api', severity: 'error', titleKey: 'notification.platformUnavailable.title', bodyKey: 'notification.platformUnavailable.body',
    });
    return null;
  }
}

export function renderPlatformInsights(documentObject, insights) {
  setText(documentObject, 'platform-profile', insights.profile);
  setText(documentObject, 'platform-tier', insights.allocationTier);
  setText(documentObject, 'platform-capabilities', `${insights.capabilitiesAvailable}/${insights.capabilitiesTotal}`);
  setLocalizedText(documentObject, 'platform-ha', insights.highAvailability ? 'common.enabled' : 'common.disabled');
  setLocalizedText(documentObject, 'platform-split-web', insights.splitWeb ? 'common.enabled' : 'common.disabled');
  setText(documentObject, 'platform-org-limit', String(insights.organizationLimit));
  setText(documentObject, 'platform-server-limit', String(insights.serverNodeLimit));
  setText(documentObject, 'platform-web-limit', String(insights.webNodeLimit));
  setText(documentObject, 'platform-catalog-version', insights.catalogVersion || '—');
  setLocalizedText(documentObject, 'platform-insights-state', 'platform.ready');
}

export function renderPlatformInsightsFailure(documentObject) {
  for (const id of [
    'platform-profile', 'platform-tier', 'platform-capabilities', 'platform-ha', 'platform-split-web',
    'platform-org-limit', 'platform-server-limit', 'platform-web-limit', 'platform-catalog-version',
  ]) setText(documentObject, id, '—');
  setLocalizedText(documentObject, 'platform-insights-state', 'runtime.unavailable');
}

export function initializePlatformAutoRefresh(
  documentObject,
  refresh,
  initialPreferences,
  timerObject = globalThis,
) {
  let timer = null;
  const schedule = (preferences) => {
    if (timer !== null) timerObject.clearInterval?.(timer);
    timer = null;
    const seconds = Number(preferences?.refreshIntervalSeconds ?? 0);
    if (seconds > 0) timer = timerObject.setInterval?.(() => { void refresh(); }, seconds * 1000) ?? null;
  };
  schedule(initialPreferences);
  const listener = (event) => schedule(event?.detail ?? {});
  documentObject?.addEventListener?.('infranexum:preferences-change', listener);
  return Object.freeze({
    reschedule: schedule,
    stop: () => { if (timer !== null) timerObject.clearInterval?.(timer); timer = null; },
  });
}

function setText(documentObject, id, value) {
  const element = documentObject?.getElementById?.(id);
  if (element) element.textContent = value;
}
