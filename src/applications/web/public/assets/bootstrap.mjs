import { initializeAdminShell, setDcimAvailability, setDdiAvailability, setIdentityAccessAvailability, setItamAvailability, setOrganizationAvailability, setRsotAvailability } from './admin-shell.mjs';
import { initializeApiDocumentation } from './api-documentation.mjs';
import { initializeLocalAuthentication } from './auth.mjs';
import { initializeIdentityAccess } from './identity-access.mjs';
import { initializePolicyAuthorization } from './policy-authorization.mjs';
import { initializeNotificationCenter } from './notifications.mjs';
import { initializePlatformAutoRefresh, loadPlatformInsights } from './platform-insights.mjs';
import { initializePreferences } from './preferences.mjs';
import { initializeStableSelects } from './stable-select.mjs';
import { initializeTemporalPickers } from './temporal-picker.mjs';
import { initializeRsotWorkspace } from './rsot-workspace.mjs';
import { initializeItamWorkspace } from './itam-workspace.mjs';
import { initializeDcimWorkspace } from './dcim-workspace.mjs';
import { initializeDdiIpamWorkspace } from './ddi-ipam-workspace.mjs';
import {
  initializeLocalization,
  setLocalizedAriaLabel,
  setLocalizedElementText,
  setLocalizedText,
} from './i18n.mjs';

const REQUIRED_SCHEMA = 'infranexum.web-runtime-config/v1';
const THEME_STORAGE_KEY = 'infranexum.theme';

export function validatePublicConfiguration(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Runtime configuration payload is invalid');
  }
  const required = [
    'schema',
    'product',
    'component',
    'version',
    'architectureBaseline',
    'environment',
    'apiBaseUrl',
  ];
  for (const key of required) {
    if (typeof value[key] !== 'string' || value[key].length === 0) {
      throw new Error(`Runtime configuration field ${key} is invalid`);
    }
  }
  if (typeof value.organizationFoundationEnabled !== 'boolean') {
    throw new Error('Runtime configuration organizationFoundationEnabled is invalid');
  }
  if (typeof value.localAuthEnabled !== 'boolean') {
    throw new Error('Runtime configuration localAuthEnabled is invalid');
  }
  if (typeof value.identityAccessEnabled !== 'boolean') {
    throw new Error('Runtime configuration identityAccessEnabled is invalid');
  }
  if (typeof value.advancedAuthorizationEnabled !== 'boolean') {
    throw new Error('Runtime configuration advancedAuthorizationEnabled is invalid');
  }
  if (typeof value.rsotCoreEnabled !== 'boolean') {
    throw new Error('Runtime configuration rsotCoreEnabled is invalid');
  }
  if (typeof value.itamPartnersEnabled !== 'boolean') {
    throw new Error('Runtime configuration itamPartnersEnabled is invalid');
  }
  if (typeof value.itamAssetsEnabled !== 'boolean') {
    throw new Error('Runtime configuration itamAssetsEnabled is invalid');
  }
  if (typeof value.itamComplianceEnabled !== 'boolean') {
    throw new Error('Runtime configuration itamComplianceEnabled is invalid');
  }
  if (typeof value.dcimFacilitiesEnabled !== 'boolean') {
    throw new Error('Runtime configuration dcimFacilitiesEnabled is invalid');
  }
  if (typeof value.dcimPhysicalEnabled !== 'boolean') {
    throw new Error('Runtime configuration dcimPhysicalEnabled is invalid');
  }
  if (typeof value.ddiIpamEnabled !== 'boolean') {
    throw new Error('Runtime configuration ddiIpamEnabled is invalid');
  }
  if (value.itamAssetsEnabled && !value.itamPartnersEnabled) {
    throw new Error('Runtime configuration ITAM assets require ITAM partners');
  }
  if (value.itamComplianceEnabled && (!value.itamPartnersEnabled || !value.itamAssetsEnabled)) {
    throw new Error('Runtime configuration ITAM compliance requires ITAM partners and assets');
  }
  if (value.advancedAuthorizationEnabled && !value.identityAccessEnabled) {
    throw new Error('Runtime configuration advanced authorization requires identity access');
  }
  if (value.dcimPhysicalEnabled && !value.dcimFacilitiesEnabled) {
    throw new Error('Runtime configuration DCIM physical requires DCIM facilities');
  }
  if (value.ddiIpamEnabled && !value.dcimFacilitiesEnabled) {
    throw new Error('Runtime configuration DDI/IPAM requires DCIM facilities');
  }
  if (
    value.schema !== REQUIRED_SCHEMA
    || value.product !== 'InfraNexum'
    || value.component !== 'web'
  ) {
    throw new Error('Runtime configuration identity is invalid');
  }
  return Object.freeze({ ...value });
}

export function renderRuntimeConfiguration(documentObject, configuration) {
  setText(documentObject, 'runtime-version', configuration.version);
  setText(documentObject, 'runtime-environment', configuration.environment);
  setText(documentObject, 'runtime-api', configuration.apiBaseUrl);
  setText(documentObject, 'runtime-api-detail', configuration.apiBaseUrl);
  setText(documentObject, 'runtime-architecture', configuration.architectureBaseline);
  setLocalizedText(documentObject, 'runtime-message', 'runtime.loaded');
  setLocalizedText(documentObject, 'sidebar-version', 'common.version', { value: configuration.version });
  setLocalizedText(documentObject, 'footer-version', 'common.version', { value: configuration.version });
  setLocalizedText(documentObject, 'dashboard-runtime', 'runtime.operational');
  setText(documentObject, 'dashboard-environment', configuration.environment);
  setLocalizedText(documentObject, 'dashboard-version', 'common.version', { value: configuration.version });
  setLocalizedText(documentObject, 'dashboard-foundation', configuration.organizationFoundationEnabled ? 'common.enabled' : 'common.disabled');
  setLocalizedText(documentObject, 'sidebar-runtime-state', 'runtime.operational');
  setLocalizedBadge(documentObject, 'runtime-health-badge', 'common.up', 'text-bg-success');
  setLocalizedBadge(documentObject, 'api-health-badge', 'common.configured', 'text-bg-primary');
  setLocalizedBadge(
    documentObject,
    'foundation-health-badge',
    configuration.organizationFoundationEnabled ? 'common.enabled' : 'common.disabled',
    configuration.organizationFoundationEnabled ? 'text-bg-success' : 'text-bg-secondary',
  );

  setOrganizationAvailability(documentObject, configuration.organizationFoundationEnabled);
  setRsotAvailability(documentObject, configuration.rsotCoreEnabled);
  setItamAvailability(documentObject, configuration.itamPartnersEnabled || configuration.itamAssetsEnabled || configuration.itamComplianceEnabled);
  setDcimAvailability(documentObject, configuration.dcimFacilitiesEnabled);
  setDdiAvailability(documentObject, configuration.ddiIpamEnabled);
  if (configuration.organizationFoundationEnabled) {
    void loadOrganizations(documentObject, configuration);
  } else {
    setText(documentObject, 'dashboard-organization-count', 'N/A');
  }
}

export function renderRuntimeFailure(documentObject) {
  const appShell = documentObject.getElementById('app-shell');
  if (appShell) appShell.hidden = false;
  setLocalizedText(documentObject, 'runtime-message', 'runtime.loadFailure');
  const runtimeMessage = documentObject.getElementById('runtime-message');
  if (runtimeMessage) runtimeMessage.className = 'alert alert-danger py-2 mb-0';
  runtimeMessage?.setAttribute('data-state', 'error');
  setLocalizedText(documentObject, 'dashboard-runtime', 'runtime.unavailable');
  setLocalizedText(documentObject, 'sidebar-runtime-state', 'runtime.unavailable');
  setLocalizedBadge(documentObject, 'runtime-health-badge', 'common.down', 'text-bg-danger');
  setOrganizationAvailability(documentObject, false);
  setIdentityAccessAvailability(documentObject, false);
  setRsotAvailability(documentObject, false);
  setItamAvailability(documentObject, false);
  setDcimAvailability(documentObject, false);
  setDdiAvailability(documentObject, false);
}

export function initializeTheme(documentObject = document, storageObject = globalThis.localStorage) {
  const root = documentObject.documentElement;
  const button = documentObject.getElementById('theme-toggle');
  if (!root || !button) return;

  let persisted;
  try {
    persisted = storageObject?.getItem(THEME_STORAGE_KEY);
  } catch {
    persisted = undefined;
  }
  const initial = persisted === 'dark' || persisted === 'light' ? persisted : preferredTheme();
  applyTheme(documentObject, root, button, initial, Boolean(persisted));
  const commitTheme = (theme) => {
    const selected = theme === 'dark' ? 'dark' : 'light';
    applyTheme(documentObject, root, button, selected, true);
    try {
      storageObject?.setItem(THEME_STORAGE_KEY, selected);
    } catch {
      // Storage may be unavailable in hardened/private browser contexts.
    }
    dispatchThemeChange(documentObject, selected);
    return selected;
  };
  button.addEventListener('click', () => {
    commitTheme(root.getAttribute('data-bs-theme') === 'dark' ? 'light' : 'dark');
  });
  documentObject.addEventListener?.('infranexum:theme-request', (event) => {
    commitTheme(event?.detail?.theme);
  });
  documentObject.addEventListener?.('infranexum:locale-change', () => {
    applyTheme(documentObject, root, button, root.getAttribute('data-bs-theme') === 'dark' ? 'dark' : 'light', root.getAttribute('data-theme-user') === 'true');
  });
}

function preferredTheme() {
  try {
    return globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

function applyTheme(documentObject, root, button, theme, explicit) {
  root.setAttribute('data-bs-theme', theme);
  if (explicit) root.setAttribute('data-theme-user', 'true');
  else root.removeAttribute?.('data-theme-user');
  button.setAttribute('aria-pressed', theme === 'dark' ? 'true' : 'false');
  const icon = documentObject.getElementById('theme-toggle-icon');
  if (icon) icon.textContent = theme === 'dark' ? '☀' : '◐';
  setLocalizedAriaLabel(documentObject, button, theme === 'dark' ? 'theme.toLight' : 'theme.toDark');
}

function dispatchThemeChange(documentObject, theme) {
  if (!documentObject?.dispatchEvent) return;
  let event;
  try {
    event = new CustomEvent('infranexum:theme-change', { detail: { theme } });
  } catch {
    event = { type: 'infranexum:theme-change', detail: { theme } };
  }
  documentObject.dispatchEvent(event);
}

export async function bootstrap({
  fetchFunction = fetch,
  documentObject = document,
  notificationCenter = null,
  preferenceController = null,
  timerObject = globalThis,
} = {}) {
  try {
    const response = await fetchFunction('/runtime-config.json', {
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) {
      throw new Error(`Runtime configuration returned HTTP ${response.status}`);
    }
    const configuration = validatePublicConfiguration(await response.json());
    await initializeLocalAuthentication(documentObject, configuration, fetchFunction);
    renderRuntimeConfiguration(documentObject, configuration);
    await initializeIdentityAccess(documentObject, configuration, fetchFunction);
    await initializePolicyAuthorization(documentObject, configuration, fetchFunction);
    notificationCenter?.upsert?.({
      id: 'web-runtime', severity: 'success', titleKey: 'notification.runtimeReady.title', bodyKey: 'notification.runtimeReady.body',
      parameters: { version: configuration.version, environment: configuration.environment },
    });

    if (documentObject?.getElementById?.('platform-insights-state')) {
      const refreshInsights = () => loadPlatformInsights(documentObject, configuration, fetchFunction, notificationCenter);
      await refreshInsights();
      if (preferenceController) {
        initializePlatformAutoRefresh(documentObject, refreshInsights, preferenceController.get(), timerObject);
      }
    }
    return configuration;
  } catch {
    renderRuntimeFailure(documentObject);
    notificationCenter?.upsert?.({
      id: 'web-runtime', severity: 'error', titleKey: 'notification.runtimeUnavailable.title', bodyKey: 'notification.runtimeUnavailable.body',
    });
    return null;
  }
}

export async function loadOrganizations(documentObject, configuration, fetchFunction = fetch) {
  const table = documentObject.getElementById('organization-rows');
  const status = documentObject.getElementById('organization-status');
  if (!table || !status) return;

  try {
    const response = await fetchFunction(
      `${configuration.apiBaseUrl}/v1/iam/organizations?limit=50`,
      { headers: { Accept: 'application/json' }, cache: 'no-store' },
    );
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const organizations = await response.json();
    table.replaceChildren(
      ...organizations.map((item) => organizationRow(documentObject, item, configuration, fetchFunction)),
    );
    setLocalizedElementText(documentObject, status, 'organization.count', { count: organizations.length });
    setText(documentObject, 'dashboard-organization-count', String(organizations.length));
  } catch {
    setLocalizedElementText(documentObject, status, 'organization.unavailable');
    setLocalizedText(documentObject, 'dashboard-organization-count', 'runtime.unavailable');
  }
}

export async function loadSubdivisions(documentObject, configuration, organization, fetchFunction = fetch) {
  const panel = documentObject.getElementById('subdivision-panel');
  const title = documentObject.getElementById('subdivision-title');
  const status = documentObject.getElementById('subdivision-status');
  const table = documentObject.getElementById('subdivision-rows');
  if (!panel || !title || !status || !table) return;

  panel.hidden = false;
  setLocalizedElementText(documentObject, title, 'subdivision.titleFor', { code: organization.code });
  setLocalizedElementText(documentObject, status, 'organization.loading');

  try {
    const response = await fetchFunction(
      `${configuration.apiBaseUrl}/v1/iam/organizations/${encodeURIComponent(organization.id)}/subdivisions?limit=50`,
      { headers: { Accept: 'application/json' }, cache: 'no-store' },
    );
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const subdivisions = await response.json();
    table.replaceChildren(...subdivisions.map((item) => subdivisionRow(documentObject, item)));
    setLocalizedElementText(documentObject, status, 'subdivision.count', { count: subdivisions.length });
  } catch {
    table.replaceChildren();
    setLocalizedElementText(documentObject, status, 'subdivision.unavailable');
  }
}

function organizationRow(documentObject, item, configuration, fetchFunction) {
  const row = documentObject.createElement('tr');
  appendPillCell(documentObject, row, item.code, 'badge text-bg-light border font-monospace');
  appendTextCell(documentObject, row, item.displayName);
  appendTextCell(documentObject, row, item.countryCode);
  appendStateCell(documentObject, row, item.status);
  appendTextCell(documentObject, row, String(item.version));

  const actionCell = documentObject.createElement('td');
  const button = documentObject.createElement('button');
  button.type = 'button';
  button.className = 'btn btn-sm btn-outline-primary';
  setLocalizedElementText(documentObject, button, 'organization.viewHierarchy');
  setLocalizedAriaLabel(documentObject, button, 'organization.showSubdivisions', { code: item.code });
  button.addEventListener('click', () => { void loadSubdivisions(documentObject, configuration, item, fetchFunction); });
  actionCell.appendChild(button);
  row.appendChild(actionCell);
  return row;
}

function subdivisionRow(documentObject, item) {
  const row = documentObject.createElement('tr');
  appendPillCell(documentObject, row, item.code, 'badge text-bg-light border font-monospace');
  appendTextCell(documentObject, row, item.displayName);
  appendTextCell(documentObject, row, item.type);
  appendStateCell(documentObject, row, item.status);
  appendTextCell(documentObject, row, String(item.version));
  return row;
}

function appendTextCell(documentObject, row, value) {
  const cell = documentObject.createElement('td');
  cell.textContent = value ?? '—';
  row.appendChild(cell);
}

function appendPillCell(documentObject, row, value, className) {
  const cell = documentObject.createElement('td');
  const pill = documentObject.createElement('span');
  pill.className = className;
  pill.textContent = value ?? '—';
  cell.appendChild(pill);
  row.appendChild(cell);
}

function appendStateCell(documentObject, row, value) {
  const normalized = typeof value === 'string' ? value.toLowerCase() : 'unknown';
  const contextual = normalized === 'active' ? 'text-bg-success' : normalized === 'suspended' ? 'text-bg-warning' : 'text-bg-secondary';
  appendPillCell(documentObject, row, value, `badge rounded-pill ${contextual}`);
}

function setText(documentObject, id, value) {
  const element = documentObject.getElementById(id);
  if (element) element.textContent = value;
}

function setLocalizedBadge(documentObject, id, key, className) {
  const element = documentObject.getElementById(id);
  if (!element) return;
  setLocalizedElementText(documentObject, element, key);
  element.className = `badge rounded-pill ${className}`;
}

if (typeof document !== 'undefined') {
  // Authentication is the critical browser path. Non-critical dashboard modules
  // must never be able to prevent the login form from being wired. Localization
  // and theme are best-effort because auth.mjs has safe default strings.
  try { initializeLocalization(document); } catch { /* auth must remain available */ }
  try { initializeTheme(document); } catch { /* auth must remain available */ }

  // Global shell controls are wired before any asynchronous business workspace.
  // Their availability must never depend on RSOT/ITAM/DCIM/DDI network latency.
  let preferenceController = null;
  let notificationCenter = null;
  try { preferenceController = initializePreferences(document); } catch { /* non-critical */ }
  try { initializeStableSelects(document); } catch { /* native selects remain as safe fallback */ }
  try { initializeTemporalPickers(document); } catch { /* native temporal inputs remain as safe fallback */ }
  try { notificationCenter = initializeNotificationCenter(document); } catch { /* non-critical */ }
  try { initializeApiDocumentation(document, globalThis.window); } catch { /* raw local OpenAPI remains available */ }
  try { initializeAdminShell(document, globalThis.window); } catch { /* non-critical */ }

  void bootstrap({ notificationCenter, preferenceController }).then(async (configuration) => {
    if (!configuration) return;

    try { await initializeRsotWorkspace(document, configuration, fetch); } catch (error) { console.error('RSOT workspace initialization failed', error); }
    try { await initializeItamWorkspace(document, configuration, fetch); } catch (error) { console.error('ITAM workspace initialization failed', error); }
    try { await initializeDcimWorkspace(document, configuration, fetch); } catch (error) { console.error('DCIM workspace initialization failed', error); }
    try { await initializeDdiIpamWorkspace(document, configuration, fetch); } catch (error) { console.error('DDI/IPAM workspace initialization failed', error); }

    notificationCenter?.upsert?.({
      id: 'web-runtime', severity: 'success', titleKey: 'notification.runtimeReady.title', bodyKey: 'notification.runtimeReady.body',
      parameters: { version: configuration.version, environment: configuration.environment },
    });
  });
}
