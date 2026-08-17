import { applyTranslations, localeFromDocument, translate } from './i18n.mjs';
import { initializeEnterpriseDataTables, refreshEnterpriseDataTable } from './enterprise-crud.mjs';
import { JiraAssetsClient } from './jira-assets.mjs';

/** PGM-10-E06 phase-1 UI: configured Jira Assets connectors plus governed federated-read query. */
export async function initializeIntegrationsWorkspace(documentObject = document, configuration, fetchFunction = fetch) {
  const root = documentObject?.getElementById?.('integrations-workspace');
  if (!root) return Object.freeze({ enabled: false });
  const enabled = configuration?.integrationsConnectorsEnabled === true;
  root.setAttribute('data-capability-enabled', String(enabled));
  if (!enabled) return Object.freeze({ enabled: false });
  root.innerHTML = template();
  applyTranslations(documentObject, localeFromDocument(documentObject));
  const client = new JiraAssetsClient(configuration, { fetchFunction });
  const state = { connectors: [], selected: null };
  wire(documentObject, client, state);
  initializeEnterpriseDataTables(root);
  try {
    await refreshConnectors(documentObject, client, state);
    setStatus(documentObject, 'integrations.status.ready', 'success');
  } catch (error) {
    setStatus(documentObject, 'integrations.status.unavailable', 'danger', error?.message);
  }
  return Object.freeze({ enabled: true, refresh: () => refreshConnectors(documentObject, client, state) });
}

export function integrationsWorkspaceTemplate() { return template(); }

function template() {
  return `<header class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-4"><div><p class="small text-uppercase fw-bold text-primary mb-1" data-i18n="integrations.eyebrow">Integrations</p><h2 id="integrations-workspace-title" data-i18n="integrations.title">Connector providers</h2><p data-i18n="integrations.description">Governed external providers with explicit authority and sync direction.</p></div><button id="integrations-refresh" class="btn btn-outline-primary" type="button" data-i18n="common.refresh">Refresh</button></header>
<p id="integrations-status" class="alert alert-info py-2" role="status" aria-live="polite" data-state="info" data-i18n="integrations.status.loading">Loading connectors…</p>
<section class="mb-4"><div class="d-flex flex-wrap align-items-end justify-content-between gap-2 mb-2"><div><h3 class="h5 mb-1" data-i18n="integrations.jira.title">Jira Assets</h3><p class="small text-body-secondary mb-0" data-i18n="integrations.jira.federated">Federated read · external authority · no implicit import</p></div></div><div class="table-responsive"><table class="table table-hover align-middle"><thead><tr><th data-i18n="integrations.connector">Connector</th><th data-i18n="integrations.provider">Provider</th><th data-i18n="integrations.direction">Direction</th><th data-i18n="integrations.authority">Authority</th><th data-i18n="common.status">Status</th><th data-inx-action-column data-i18n="common.actions">Actions</th></tr></thead><tbody id="jira-assets-connectors"></tbody></table></div></section>
<section class="border rounded p-3 bg-body-tertiary"><h3 class="h5" data-i18n="integrations.search.title">Federated AQL read</h3><p class="small text-body-secondary" data-i18n="integrations.search.description">Query the selected Jira Assets instance without copying provider attributes into InfraNexum.</p><form id="jira-assets-search" class="row g-3"><div class="col-md-4"><label class="form-label" for="jira-assets-connector" data-i18n="integrations.connector">Connector</label><select id="jira-assets-connector" class="form-select" required></select></div><div class="col-md-6"><label class="form-label" for="jira-assets-aql" data-i18n="integrations.aql">AQL</label><input id="jira-assets-aql" class="form-control" maxlength="4096" required autocomplete="off" placeholder="objectType = Server"></div><div class="col-md-2 d-flex align-items-end"><button class="btn btn-primary w-100" type="submit" data-i18n="integrations.search">Search</button></div></form><div class="table-responsive mt-3"><table class="table table-hover align-middle"><thead><tr><th data-i18n="integrations.objectKey">Object key</th><th data-i18n="integrations.label">Label</th><th data-i18n="integrations.objectType">Object type</th><th data-i18n="integrations.remoteId">Remote ID</th></tr></thead><tbody id="jira-assets-results"></tbody></table></div><p id="jira-assets-page" class="small text-body-secondary mb-0" aria-live="polite"></p></section>`;
}

function wire(documentObject, client, state) {
  documentObject.getElementById('integrations-refresh')?.addEventListener('click', async () => {
    try { await refreshConnectors(documentObject, client, state); setStatus(documentObject, 'integrations.status.ready', 'success'); }
    catch (error) { setStatus(documentObject, 'integrations.status.unavailable', 'danger', error?.message); }
  });
  documentObject.getElementById('jira-assets-search')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const connector = documentObject.getElementById('jira-assets-connector')?.value;
    const aql = documentObject.getElementById('jira-assets-aql')?.value;
    try {
      setStatus(documentObject, 'integrations.status.searching', 'info');
      const result = await client.search(connector, aql, { limit: 50 });
      renderObjects(documentObject, result.payload, result.pagination);
      setStatus(documentObject, 'integrations.status.ready', 'success');
    } catch (error) { setStatus(documentObject, 'integrations.status.searchFailed', 'danger', error?.message); }
  });
}

async function refreshConnectors(documentObject, client, state) {
  const result = await client.connectors();
  state.connectors = Array.isArray(result.payload) ? result.payload : [];
  const tbody = documentObject.getElementById('jira-assets-connectors');
  tbody?.replaceChildren(...state.connectors.map((item) => connectorRow(documentObject, client, item)));
  if (tbody?.closest) refreshEnterpriseDataTable(tbody.closest('table'));
  const select = documentObject.getElementById('jira-assets-connector');
  if (select) {
    select.replaceChildren(...state.connectors.filter((item) => item.enabled).map((item) => option(documentObject, item.connectorKey)));
    select.disabled = select.options.length === 0;
  }
}

function connectorRow(documentObject, client, item) {
  const row = documentObject.createElement('tr');
  append(row, item.connectorKey); append(row, item.provider); append(row, item.direction); append(row, item.authority);
  const status = documentObject.createElement('td'); status.textContent = item.enabled ? translate(localeFromDocument(documentObject), 'common.enabled') : translate(localeFromDocument(documentObject), 'common.disabled'); row.appendChild(status);
  const actions = documentObject.createElement('td'); const button = documentObject.createElement('button'); button.type = 'button'; button.className = 'btn btn-sm btn-outline-primary'; button.textContent = translate(localeFromDocument(documentObject), 'integrations.health'); button.disabled = !item.enabled;
  button.addEventListener('click', async () => { try { const response = await client.health(item.connectorKey); setStatus(documentObject, response.payload?.status === 'UP' ? 'integrations.health.up' : 'integrations.status.unavailable', response.payload?.status === 'UP' ? 'success' : 'danger'); } catch (error) { setStatus(documentObject, 'integrations.health.down', 'danger', error?.message); } });
  actions.appendChild(button); row.appendChild(actions); return row;
}
function renderObjects(documentObject, objects, pagination) {
  const rows = Array.isArray(objects) ? objects : [];
  const tbody = documentObject.getElementById('jira-assets-results');
  tbody?.replaceChildren(...rows.map((item) => { const row = documentObject.createElement('tr'); append(row, item.objectKey); append(row, item.label); append(row, item.objectTypeName); append(row, item.id); return row; }));
  if (tbody?.closest) refreshEnterpriseDataTable(tbody.closest('table'));
  const page = documentObject.getElementById('jira-assets-page'); if (page) { const next = pagination?.nextOffset == null ? '—' : `${translate(localeFromDocument(documentObject), 'integrations.page.next')} ${pagination.nextOffset}`; page.textContent = `${rows.length} · ${next}`; }
}
function option(documentObject, value) { const item = documentObject.createElement('option'); item.value = value; item.textContent = value; return item; }
function append(row, value) { const cell = row.ownerDocument.createElement('td'); cell.textContent = value ?? '—'; row.appendChild(cell); }
function setStatus(documentObject, key, contextual, detail = '') { const node = documentObject.getElementById('integrations-status'); if (!node) return; const base = translate(localeFromDocument(documentObject), key); node.textContent = detail ? `${base} ${detail}` : base; node.className = `alert alert-${contextual} py-2`; node.setAttribute('data-state', contextual); }
