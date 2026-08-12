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
  setText(documentObject, 'runtime-message', 'Runtime configuration loaded.');
  setText(documentObject, 'sidebar-version', `Version ${configuration.version}`);
  setText(documentObject, 'footer-version', `Version ${configuration.version}`);
  setText(documentObject, 'topbar-environment', `Environment ${configuration.environment}`);
  setText(documentObject, 'dashboard-runtime', 'Operational');
  setText(documentObject, 'dashboard-environment', configuration.environment);
  setText(documentObject, 'dashboard-version', `Version ${configuration.version}`);
  setText(documentObject, 'dashboard-foundation', configuration.organizationFoundationEnabled ? 'Enabled' : 'Disabled');
  setText(documentObject, 'sidebar-runtime-state', 'Operational');
  setBadge(documentObject, 'runtime-health-badge', 'UP', 'text-bg-success');
  setBadge(documentObject, 'api-health-badge', 'Configured', 'text-bg-primary');
  setBadge(
    documentObject,
    'foundation-health-badge',
    configuration.organizationFoundationEnabled ? 'Enabled' : 'Disabled',
    configuration.organizationFoundationEnabled ? 'text-bg-success' : 'text-bg-secondary',
  );

  const workspace = documentObject.getElementById('organization-workspace');
  if (workspace) {
    workspace.hidden = !configuration.organizationFoundationEnabled;
  }
  if (configuration.organizationFoundationEnabled) {
    void loadOrganizations(documentObject, configuration);
  } else {
    setText(documentObject, 'dashboard-organization-count', 'N/A');
  }
}

export function renderRuntimeFailure(documentObject) {
  setText(documentObject, 'runtime-message', 'The Web runtime configuration could not be loaded. Contact an InfraNexum administrator.');
  documentObject.getElementById('runtime-message')?.setAttribute('data-state', 'error');
  setText(documentObject, 'dashboard-runtime', 'Unavailable');
  setText(documentObject, 'sidebar-runtime-state', 'Unavailable');
  setBadge(documentObject, 'runtime-health-badge', 'DOWN', 'text-bg-danger');
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
  applyTheme(root, button, initial, Boolean(persisted));
  button.addEventListener('click', () => {
    const next = root.getAttribute('data-bs-theme') === 'dark' ? 'light' : 'dark';
    applyTheme(root, button, next, true);
    try {
      storageObject?.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // Storage may be unavailable in hardened/private browser contexts.
    }
  });
}

function preferredTheme() {
  try {
    return globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

function applyTheme(root, button, theme, explicit) {
  root.setAttribute('data-bs-theme', theme);
  if (explicit) root.setAttribute('data-theme-user', 'true');
  else root.removeAttribute?.('data-theme-user');
  button.setAttribute('aria-pressed', theme === 'dark' ? 'true' : 'false');
  button.setAttribute('aria-label', theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
}

export async function bootstrap({ fetchFunction = fetch, documentObject = document } = {}) {
  try {
    const response = await fetchFunction('/runtime-config.json', {
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) {
      throw new Error(`Runtime configuration returned HTTP ${response.status}`);
    }
    renderRuntimeConfiguration(
      documentObject,
      validatePublicConfiguration(await response.json()),
    );
  } catch {
    renderRuntimeFailure(documentObject);
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
    status.textContent = `${organizations.length} organisation(s)`;
    setText(documentObject, 'dashboard-organization-count', String(organizations.length));
  } catch {
    status.textContent = 'Organisation data unavailable';
    setText(documentObject, 'dashboard-organization-count', 'Unavailable');
  }
}

export async function loadSubdivisions(documentObject, configuration, organization, fetchFunction = fetch) {
  const panel = documentObject.getElementById('subdivision-panel');
  const title = documentObject.getElementById('subdivision-title');
  const status = documentObject.getElementById('subdivision-status');
  const table = documentObject.getElementById('subdivision-rows');
  if (!panel || !title || !status || !table) return;

  panel.hidden = false;
  title.textContent = `Subdivisions — ${organization.code}`;
  status.textContent = 'Loading…';

  try {
    const response = await fetchFunction(
      `${configuration.apiBaseUrl}/v1/iam/organizations/${encodeURIComponent(organization.id)}/subdivisions?limit=50`,
      { headers: { Accept: 'application/json' }, cache: 'no-store' },
    );
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const subdivisions = await response.json();
    table.replaceChildren(...subdivisions.map((item) => subdivisionRow(documentObject, item)));
    status.textContent = `${subdivisions.length} subdivision(s)`;
  } catch {
    table.replaceChildren();
    status.textContent = 'Subdivision data unavailable';
  }
}

function organizationRow(documentObject, item, configuration, fetchFunction) {
  const row = documentObject.createElement('tr');
  appendPillCell(documentObject, row, item.code, 'inx-code-pill');
  appendTextCell(documentObject, row, item.displayName);
  appendTextCell(documentObject, row, item.countryCode);
  appendStateCell(documentObject, row, item.status);
  appendTextCell(documentObject, row, String(item.version));

  const actionCell = documentObject.createElement('td');
  const button = documentObject.createElement('button');
  button.type = 'button';
  button.className = 'btn btn-sm btn-outline-primary';
  button.textContent = 'View hierarchy';
  button.setAttribute('aria-label', `Show subdivisions for ${item.code}`);
  button.addEventListener('click', () => { void loadSubdivisions(documentObject, configuration, item, fetchFunction); });
  actionCell.appendChild(button);
  row.appendChild(actionCell);
  return row;
}

function subdivisionRow(documentObject, item) {
  const row = documentObject.createElement('tr');
  appendPillCell(documentObject, row, item.code, 'inx-code-pill');
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
  const classSuffix = ['active', 'suspended'].includes(normalized) ? normalized : 'default';
  appendPillCell(documentObject, row, value, `inx-state-pill inx-state-${classSuffix}`);
}

function setText(documentObject, id, value) {
  const element = documentObject.getElementById(id);
  if (element) element.textContent = value;
}

function setBadge(documentObject, id, text, className) {
  const element = documentObject.getElementById(id);
  if (!element) return;
  element.textContent = text;
  element.className = `badge rounded-pill ${className}`;
}

if (typeof document !== 'undefined') {
  initializeTheme(document);
  void bootstrap();
}
