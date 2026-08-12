const REQUIRED_SCHEMA = 'infranexum.web-runtime-config/v1';

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
  documentObject.getElementById('runtime-version').textContent = configuration.version;
  documentObject.getElementById('runtime-environment').textContent = configuration.environment;
  documentObject.getElementById('runtime-api').textContent = configuration.apiBaseUrl;
  documentObject.getElementById('runtime-message').textContent = 'Runtime configuration loaded.';

  const workspace = documentObject.getElementById('organization-workspace');
  if (workspace) {
    workspace.hidden = !configuration.organizationFoundationEnabled;
  }
  if (configuration.organizationFoundationEnabled) {
    void loadOrganizations(documentObject, configuration);
  }
}

export function renderRuntimeFailure(documentObject) {
  documentObject.getElementById('runtime-message').textContent = 'The Web runtime configuration could not be loaded. Contact an InfraNexum administrator.';
  documentObject.getElementById('runtime-message').setAttribute('data-state', 'error');
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
  if (!table || !status) {
    return;
  }

  try {
    const response = await fetchFunction(
      `${configuration.apiBaseUrl}/v1/iam/organizations?limit=50`,
      { headers: { Accept: 'application/json' }, cache: 'no-store' },
    );
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const organizations = await response.json();
    table.replaceChildren(
      ...organizations.map((item) => organizationRow(documentObject, item, configuration, fetchFunction)),
    );
    status.textContent = `${organizations.length} organisation(s)`;
  } catch {
    status.textContent = 'Organisation data unavailable';
  }
}

export async function loadSubdivisions(
  documentObject,
  configuration,
  organization,
  fetchFunction = fetch,
) {
  const panel = documentObject.getElementById('subdivision-panel');
  const title = documentObject.getElementById('subdivision-title');
  const status = documentObject.getElementById('subdivision-status');
  const table = documentObject.getElementById('subdivision-rows');
  if (!panel || !title || !status || !table) {
    return;
  }

  panel.hidden = false;
  title.textContent = `Subdivisions — ${organization.code}`;
  status.textContent = 'Loading…';

  try {
    const response = await fetchFunction(
      `${configuration.apiBaseUrl}/v1/iam/organizations/${encodeURIComponent(organization.id)}/subdivisions?limit=50`,
      { headers: { Accept: 'application/json' }, cache: 'no-store' },
    );
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
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
  for (const value of [item.code, item.displayName, item.countryCode, item.status, String(item.version)]) {
    appendTextCell(documentObject, row, value);
  }

  const actionCell = documentObject.createElement('td');
  const button = documentObject.createElement('button');
  button.type = 'button';
  button.className = 'btn btn-sm btn-outline-primary';
  button.textContent = 'Subdivisions';
  button.setAttribute('aria-label', `Show subdivisions for ${item.code}`);
  button.addEventListener('click', () => {
    void loadSubdivisions(documentObject, configuration, item, fetchFunction);
  });
  actionCell.appendChild(button);
  row.appendChild(actionCell);
  return row;
}

function subdivisionRow(documentObject, item) {
  const row = documentObject.createElement('tr');
  for (const value of [item.code, item.displayName, item.type, item.status, String(item.version)]) {
    appendTextCell(documentObject, row, value);
  }
  return row;
}

function appendTextCell(documentObject, row, value) {
  const cell = documentObject.createElement('td');
  cell.textContent = value ?? '—';
  row.appendChild(cell);
}

if (typeof document !== 'undefined') {
  void bootstrap();
}
