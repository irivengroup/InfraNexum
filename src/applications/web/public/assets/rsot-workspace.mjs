import { wireAsyncForm } from './form-controller.mjs';
import { applyTranslations, localeFromDocument, translate } from './i18n.mjs';
import { RsotCanonicalObjectClient } from './rsot-canonical-objects.mjs';
import { RsotSchemaRegistryClient } from './rsot-schema-registry.mjs';
import { initializeStableSelects } from './stable-select.mjs';
import { initializeTemporalPickers } from './temporal-picker.mjs';
import {
  bindTabSet,
  field,
  fillSelect,
  listItems,
  nullable,
  organizationDirectory,
  parseJsonObject,
  replaceRows,
  selectedValues,
  setWorkspaceStatus,
} from './web-workspace-utils.mjs';

/**
 * RSOT administration workspace.
 *
 * The UI never accepts a free-form identifier when a catalogue endpoint exists:
 * organizations and schema-profile members are populated from governed sources.
 */
export async function initializeRsotWorkspace(documentObject = document, configuration, fetchFunction = fetch) {
  const workspace = documentObject?.getElementById?.('rsot-workspace');
  if (!workspace) return Object.freeze({ enabled: false });
  const enabled = configuration?.rsotCoreEnabled === true;
  workspace.setAttribute('data-capability-enabled', String(enabled));
  if (!enabled) return Object.freeze({ enabled: false });

  workspace.innerHTML = rsotWorkspaceTemplate();
  applyTranslations(documentObject, localeFromDocument(documentObject));
  initializeStableSelects(documentObject);
  initializeTemporalPickers(documentObject);

  const objects = new RsotCanonicalObjectClient(configuration, { fetchFunction });
  const registry = new RsotSchemaRegistryClient(configuration, { fetchFunction });
  const state = { objects: [], schemas: [], profiles: [], selectedSchema: null, selectedProfile: null };

  bindTabSet(documentObject, '[data-rsot-tab]', '[data-rsot-panel]', 'data-rsot-tab');
  await populateOrganizations(documentObject, configuration, fetchFunction);
  wireObjectFilters(documentObject, objects, state);
  wireSchemaActions(documentObject, registry, state);
  wireProfileActions(documentObject, registry, state);

  documentObject.getElementById('rsot-refresh')?.addEventListener('click', () => void refreshAll(documentObject, objects, registry, state));
  documentObject.addEventListener?.('infranexum:locale-change', () => {
    renderObjects(documentObject, state);
    renderSchemas(documentObject, state);
    renderProfiles(documentObject, state);
  });

  await refreshAll(documentObject, objects, registry, state);
  return Object.freeze({ enabled: true, refresh: () => refreshAll(documentObject, objects, registry, state) });
}

export function rsotWorkspaceTemplate() {
  return `
    <header class="inx-page-intro inx-domain-intro">
      <div><p class="inx-eyebrow" data-i18n="rsot.eyebrow">Resource source of truth</p><h2 id="rsot-workspace-title" data-i18n="rsot.title">RSOT</h2><p data-i18n="rsot.description">Canonical resources, immutable schemas and composable profiles.</p></div>
      <button id="rsot-refresh" class="btn btn-outline-primary" type="button" data-i18n="common.refresh">Refresh</button>
    </header>
    <p id="rsot-status" class="inx-workspace-status" role="status" aria-live="polite" data-state="info" data-i18n="workspace.ready">Ready.</p>
    <div class="inx-domain-tabs" role="tablist" aria-label="RSOT">
      <button class="inx-domain-tab active" type="button" role="tab" aria-selected="true" data-rsot-tab="objects" data-i18n="rsot.objects">Canonical objects</button>
      <button class="inx-domain-tab" type="button" role="tab" aria-selected="false" data-rsot-tab="schemas" data-i18n="rsot.schemas">Schema registry</button>
      <button class="inx-domain-tab" type="button" role="tab" aria-selected="false" data-rsot-tab="profiles" data-i18n="rsot.profiles">Schema profiles</button>
    </div>

    <section class="inx-domain-panel" role="tabpanel" data-rsot-panel="objects">
      <form id="rsot-object-filter" class="inx-domain-toolbar row g-2" autocomplete="off">
        <div class="col-lg-5"><label class="form-label" for="rsot-object-organization" data-i18n="common.organization">Organization</label><select id="rsot-object-organization" name="organizationId" class="form-select"></select></div>
        <div class="col-lg-3"><label class="form-label" for="rsot-object-type" data-i18n="rsot.objectType">Object type</label><input id="rsot-object-type" name="objectType" class="form-control" maxlength="160" /></div>
        <div class="col-lg-2"><label class="form-label" for="rsot-object-status" data-i18n="common.status">Status</label><select id="rsot-object-status" name="status" class="form-select"><option value="" data-i18n="common.all">All</option><option>proposed</option><option>validated</option><option>reconciled</option><option>deprecated</option><option>archived</option></select></div>
        <div class="col-lg-2 d-flex align-items-end"><button class="btn btn-primary w-100" type="submit" data-i18n="common.filter">Filter</button></div>
      </form>
      ${table('rsot-object-table-body', [['rsot.id','ID'],['rsot.objectType','Object type'],['common.organization','Organization'],['common.status','Status'],['common.version','Version'],['common.updated','Updated']])}
      <pre id="rsot-object-detail" class="inx-domain-detail" tabindex="0" aria-label="RSOT object detail">—</pre>
    </section>

    <section class="inx-domain-panel" role="tabpanel" data-rsot-panel="schemas" hidden aria-hidden="true">
      <div class="inx-domain-split">
        <div>
          <form id="rsot-schema-filter" class="inx-domain-toolbar row g-2" autocomplete="off">
            <div class="col-md-4"><label class="form-label" for="rsot-schema-key-filter" data-i18n="rsot.schemaKey">Schema key</label><input id="rsot-schema-key-filter" name="schemaKey" class="form-control" maxlength="160" /></div>
            <div class="col-md-3"><label class="form-label" for="rsot-schema-kind-filter" data-i18n="rsot.kind">Kind</label><select id="rsot-schema-kind-filter" name="kind" class="form-select"><option value="" data-i18n="common.all">All</option><option value="CORE">CORE</option><option value="RSOT_EXTENSION">RSOT_EXTENSION</option></select></div>
            <div class="col-md-3"><label class="form-label" for="rsot-schema-status-filter" data-i18n="common.status">Status</label><select id="rsot-schema-status-filter" name="status" class="form-select"><option value="" data-i18n="common.all">All</option><option value="DRAFT">DRAFT</option><option value="PUBLISHED">PUBLISHED</option><option value="DEPRECATED">DEPRECATED</option></select></div>
            <div class="col-md-2 d-flex align-items-end"><button class="btn btn-outline-primary w-100" type="submit" data-i18n="common.filter">Filter</button></div>
          </form>
          ${table('rsot-schema-table-body', [['rsot.schemaKey','Schema key'],['rsot.kind','Kind'],['common.version','Version'],['common.status','Status'],['rsot.revision','Revision']])}
          <pre id="rsot-schema-detail" class="inx-domain-detail" tabindex="0">—</pre>
        </div>
        <div class="inx-domain-form-stack">
          <form id="rsot-schema-form" class="inx-domain-form row g-2">
            <h3 data-i18n="rsot.schemaCreate">Create schema</h3>
            <div class="col-12"><label class="form-label" for="rsot-schema-key" data-i18n="rsot.schemaKey">Schema key</label><input id="rsot-schema-key" name="schemaKey" class="form-control" maxlength="160" required /></div>
            <div class="col-md-6"><label class="form-label" for="rsot-schema-kind" data-i18n="rsot.kind">Kind</label><select id="rsot-schema-kind" name="kind" class="form-select" required><option value="CORE">CORE</option><option value="RSOT_EXTENSION">RSOT_EXTENSION</option></select></div>
            <div class="col-md-6"><label class="form-label" for="rsot-schema-version" data-i18n="common.version">Version</label><input id="rsot-schema-version" name="version" class="form-control" value="1.0.0" maxlength="64" required /></div>
            <div class="col-12"><label class="form-label" for="rsot-schema-owner" data-i18n="rsot.owner">Owner</label><input id="rsot-schema-owner" name="owner" class="form-control" maxlength="160" required /></div>
            <div class="col-12"><label class="form-label" for="rsot-schema-effective" data-i18n="rsot.effectiveAt">Effective at</label><input id="rsot-schema-effective" name="effectiveAt" class="form-control" type="datetime-local" data-inx-temporal="datetime" /><div class="form-text" data-i18n="temporal.serverTimezoneHint">Timezone omitted: Server timezone is used.</div></div>
            <div class="col-12"><label class="form-label" for="rsot-schema-definition" data-i18n="rsot.definition">JSON Schema definition</label><textarea id="rsot-schema-definition" name="definition" class="form-control font-monospace" rows="12" required spellcheck="false">{"type":"object","properties":{}}</textarea></div>
            <div class="col-12"><button class="btn btn-primary" type="submit" data-i18n="common.create">Create</button></div>
          </form>
          <form id="rsot-schema-lifecycle" class="inx-domain-form row g-2">
            <h3 data-i18n="rsot.schemaLifecycle">Selected schema</h3>
            <input name="schemaId" type="hidden" /><input name="revision" type="hidden" />
            <div class="col-12"><label class="form-label" for="rsot-schema-update-definition" data-i18n="rsot.definition">JSON Schema definition</label><textarea id="rsot-schema-update-definition" name="definition" class="form-control font-monospace" rows="8" required spellcheck="false"></textarea></div>
            <div class="col-12"><label class="form-label" for="rsot-breaking-approval" data-i18n="rsot.breakingApproval">Breaking-change approval reference</label><input id="rsot-breaking-approval" name="breakingApprovalReference" class="form-control" maxlength="240" /></div>
            <div class="col-md-6"><label class="form-label" for="rsot-schema-sunset" data-i18n="rsot.sunsetAt">Sunset at</label><input id="rsot-schema-sunset" name="sunsetAt" class="form-control" type="datetime-local" data-inx-temporal="datetime" /></div>
            <div class="col-md-6"><label class="form-label" for="rsot-schema-reason" data-i18n="common.reason">Reason</label><input id="rsot-schema-reason" name="reason" class="form-control" maxlength="500" /></div>
            <div class="col-12 d-flex flex-wrap gap-2"><button class="btn btn-outline-primary" type="submit" value="update" data-i18n="common.update">Update draft</button><button class="btn btn-outline-secondary" type="submit" value="compatibility" data-i18n="rsot.compatibility">Compatibility</button><button class="btn btn-outline-success" type="submit" value="publish" data-i18n="common.publish">Publish</button><button class="btn btn-outline-warning" type="submit" value="deprecate" data-i18n="common.deprecate">Deprecate</button></div>
            <pre id="rsot-compatibility-result" class="inx-domain-detail" tabindex="0">—</pre>
          </form>
        </div>
      </div>
    </section>

    <section class="inx-domain-panel" role="tabpanel" data-rsot-panel="profiles" hidden aria-hidden="true">
      <div class="inx-domain-split">
        <div>${table('rsot-profile-table-body', [['rsot.profileCode','Profile code'],['rsot.owner','Owner'],['common.version','Version'],['common.status','Status'],['rsot.revision','Revision']])}<pre id="rsot-profile-detail" class="inx-domain-detail" tabindex="0">—</pre></div>
        <div class="inx-domain-form-stack">
          <form id="rsot-profile-form" class="inx-domain-form row g-2">
            <h3 data-i18n="rsot.profileCreate">Create profile</h3>
            <div class="col-md-6"><label class="form-label" for="rsot-profile-code" data-i18n="rsot.profileCode">Profile code</label><input id="rsot-profile-code" name="code" class="form-control" maxlength="160" required /></div>
            <div class="col-md-6"><label class="form-label" for="rsot-profile-version" data-i18n="common.version">Version</label><input id="rsot-profile-version" name="version" class="form-control" value="1.0.0" maxlength="64" required /></div>
            <div class="col-12"><label class="form-label" for="rsot-profile-owner" data-i18n="rsot.owner">Owner</label><input id="rsot-profile-owner" name="owner" class="form-control" maxlength="160" required /></div>
            <div class="col-12"><label class="form-label" for="rsot-profile-schemas" data-i18n="rsot.profileSchemas">Schemas</label><select id="rsot-profile-schemas" name="schemaIds" class="form-select" multiple size="8" required></select></div>
            <div class="col-12"><button class="btn btn-primary" type="submit" data-i18n="common.create">Create</button></div>
          </form>
          <form id="rsot-profile-lifecycle" class="inx-domain-form row g-2">
            <h3 data-i18n="rsot.profileLifecycle">Selected profile</h3><input name="profileId" type="hidden" /><input name="revision" type="hidden" />
            <div class="col-md-6"><label class="form-label" for="rsot-profile-sunset" data-i18n="rsot.sunsetAt">Sunset at</label><input id="rsot-profile-sunset" name="sunsetAt" class="form-control" type="datetime-local" data-inx-temporal="datetime" /></div>
            <div class="col-md-6"><label class="form-label" for="rsot-profile-reason" data-i18n="common.reason">Reason</label><input id="rsot-profile-reason" name="reason" class="form-control" maxlength="500" /></div>
            <div class="col-12 d-flex gap-2"><button class="btn btn-outline-success" type="submit" value="publish" data-i18n="common.publish">Publish</button><button class="btn btn-outline-warning" type="submit" value="deprecate" data-i18n="common.deprecate">Deprecate</button></div>
          </form>
        </div>
      </div>
    </section>`;
}

async function populateOrganizations(documentObject, configuration, fetchFunction) {
  try {
    const organizations = await organizationDirectory(configuration, fetchFunction);
    fillSelect(documentObject, documentObject.getElementById('rsot-object-organization'), organizations, { label: (item) => `${item.code ?? ''} — ${item.displayName ?? item.id}` });
  } catch (error) {
    setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.directoryUnavailable', 'warning');
  }
}

function wireObjectFilters(documentObject, client, state) {
  const form = documentObject.getElementById('rsot-object-filter');
  form?.addEventListener('submit', (event) => { event.preventDefault(); void loadObjects(documentObject, client, state); });
  form?.querySelectorAll?.('select,input')?.forEach((control) => control.addEventListener?.('change', () => void loadObjects(documentObject, client, state)));
}

function wireSchemaActions(documentObject, client, state) {
  const filter = documentObject.getElementById('rsot-schema-filter');
  filter?.addEventListener('submit', (event) => { event.preventDefault(); void loadSchemas(documentObject, client, state); });
  wireAsyncForm(documentObject.getElementById('rsot-schema-form'), {
    execute: async (form) => {
      const body = { schemaKey: field(form, 'schemaKey'), kind: field(form, 'kind'), owner: field(form, 'owner'), version: field(form, 'version'), definition: parseJsonObject(field(form, 'definition'), 'definition'), effectiveAt: nullable(field(form, 'effectiveAt')) };
      await client.createSchema(body); await loadSchemas(documentObject, client, state);
    },
    onWorking: () => setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.saving'),
    onSuccess: () => setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.saved', 'success'),
    onError: (error) => showError(documentObject, 'rsot-status', error),
  });
  wireAsyncForm(documentObject.getElementById('rsot-schema-lifecycle'), {
    execute: async (form, submitter) => {
      if (!state.selectedSchema) throw new Error(translate(localeFromDocument(documentObject), 'workspace.selectRecord'));
      const operation = submitter?.value;
      const id = state.selectedSchema.id; const revision = state.selectedSchema.revision;
      if (operation === 'update') await client.updateSchema(id, revision, parseJsonObject(field(form, 'definition'), 'definition'));
      else if (operation === 'compatibility') {
        const result = await client.compatibility(id); documentObject.getElementById('rsot-compatibility-result').textContent = JSON.stringify(result.payload, null, 2); return;
      } else if (operation === 'publish') await client.publishSchema(id, revision, nullable(field(form, 'breakingApprovalReference')));
      else if (operation === 'deprecate') await client.deprecateSchema(id, revision, field(form, 'sunsetAt'), field(form, 'reason'));
      await loadSchemas(documentObject, client, state);
    },
    onWorking: () => setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.saving'),
    onSuccess: () => setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.saved', 'success'),
    onError: (error) => showError(documentObject, 'rsot-status', error),
  });
}

function wireProfileActions(documentObject, client, state) {
  wireAsyncForm(documentObject.getElementById('rsot-profile-form'), {
    execute: async (form) => {
      const schemaIds = selectedValues(documentObject.getElementById('rsot-profile-schemas'));
      if (!schemaIds.length) throw new Error(translate(localeFromDocument(documentObject), 'rsot.profileSchemaRequired'));
      await client.createProfile({ code: field(form, 'code'), owner: field(form, 'owner'), version: field(form, 'version'), schemaIds });
      await loadProfiles(documentObject, client, state);
    },
    onWorking: () => setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.saving'),
    onSuccess: () => setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.saved', 'success'),
    onError: (error) => showError(documentObject, 'rsot-status', error),
  });
  wireAsyncForm(documentObject.getElementById('rsot-profile-lifecycle'), {
    execute: async (form, submitter) => {
      if (!state.selectedProfile) throw new Error(translate(localeFromDocument(documentObject), 'workspace.selectRecord'));
      const { id, revision } = state.selectedProfile;
      if (submitter?.value === 'publish') await client.publishProfile(id, revision);
      else if (submitter?.value === 'deprecate') await client.deprecateProfile(id, revision, field(form, 'sunsetAt'), field(form, 'reason'));
      await loadProfiles(documentObject, client, state);
    },
    onWorking: () => setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.saving'),
    onSuccess: () => setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.saved', 'success'),
    onError: (error) => showError(documentObject, 'rsot-status', error),
  });
}

async function refreshAll(documentObject, objects, registry, state) {
  setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.loading');
  const results = await Promise.allSettled([loadObjects(documentObject, objects, state), loadSchemas(documentObject, registry, state), loadProfiles(documentObject, registry, state)]);
  const failures = results.filter((result) => result.status === 'rejected').map((result) => result.reason);
  if (!failures.length) setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.ready', 'success');
  else if (failures.every((error) => error?.status === 403)) setWorkspaceStatus(documentObject, 'rsot-status', 'workspace.restricted', 'warning');
  else showError(documentObject, 'rsot-status', failures[0]);
}

async function loadObjects(documentObject, client, state) {
  const form = documentObject.getElementById('rsot-object-filter');
  const organizationId = field(form, 'organizationId');
  if (!organizationId) { state.objects = []; renderObjects(documentObject, state); return; }
  const result = await client.list({ organizationId, offset: 0, limit: 200 });
  const type = field(form, 'objectType').toLowerCase(); const status = field(form, 'status').toLowerCase();
  state.objects = listItems(result.payload).filter((item) => (!type || String(item.objectType).toLowerCase().includes(type)) && (!status || String(item.status).toLowerCase() === status));
  renderObjects(documentObject, state);
}

function renderObjects(documentObject, state) {
  replaceRows(documentObject, documentObject.getElementById('rsot-object-table-body'), state.objects,
    [(x) => x.id, (x) => x.objectType, (x) => x.organizationId, (x) => x.status, (x) => x.version, (x) => x.updatedAt],
    (item) => { documentObject.getElementById('rsot-object-detail').textContent = JSON.stringify(item, null, 2); });
}

async function loadSchemas(documentObject, client, state) {
  const form = documentObject.getElementById('rsot-schema-filter');
  const result = await client.listSchemas({ schemaKey: nullable(field(form, 'schemaKey')), kind: nullable(field(form, 'kind')), status: nullable(field(form, 'status')), offset: 0, limit: 200 });
  state.schemas = listItems(result.payload); state.selectedSchema = null; renderSchemas(documentObject, state);
  fillSelect(documentObject, documentObject.getElementById('rsot-profile-schemas'), state.schemas.filter((x) => String(x.status).toUpperCase() === 'PUBLISHED'), { placeholderKey: 'rsot.profileSelectSchemas', preserve: false, label: (x) => `${x.schemaKey} ${x.version}` });
}

function renderSchemas(documentObject, state) {
  replaceRows(documentObject, documentObject.getElementById('rsot-schema-table-body'), state.schemas,
    [(x) => x.schemaKey, (x) => x.kind, (x) => x.version, (x) => x.status, (x) => x.revision],
    (item) => selectSchema(documentObject, state, item));
}

function selectSchema(documentObject, state, item) {
  state.selectedSchema = item;
  documentObject.getElementById('rsot-schema-detail').textContent = JSON.stringify(item, null, 2);
  const form = documentObject.getElementById('rsot-schema-lifecycle');
  form.elements.namedItem('schemaId').value = item.id; form.elements.namedItem('revision').value = item.revision;
  form.elements.namedItem('definition').value = JSON.stringify(item.definition ?? {}, null, 2);
}

async function loadProfiles(documentObject, client, state) {
  const result = await client.listProfiles({ offset: 0, limit: 200 }); state.profiles = listItems(result.payload); state.selectedProfile = null; renderProfiles(documentObject, state);
}

function renderProfiles(documentObject, state) {
  replaceRows(documentObject, documentObject.getElementById('rsot-profile-table-body'), state.profiles,
    [(x) => x.code, (x) => x.owner, (x) => x.version, (x) => x.status, (x) => x.revision],
    (item) => {
      state.selectedProfile = item; documentObject.getElementById('rsot-profile-detail').textContent = JSON.stringify(item, null, 2);
      const form = documentObject.getElementById('rsot-profile-lifecycle'); form.elements.namedItem('profileId').value = item.id; form.elements.namedItem('revision').value = item.revision;
    });
}

function table(tbodyId, headings) {
  return `<div class="inx-table-shell inx-domain-table"><div class="table-responsive"><table class="table inx-data-table"><thead><tr>${headings.map(([key, fallback]) => `<th scope="col" data-i18n="${key}">${fallback}</th>`).join('')}</tr></thead><tbody id="${tbodyId}"></tbody></table></div></div>`;
}

function showError(documentObject, statusId, error) {
  const key = error?.status === 403 ? 'workspace.restricted' : error?.status === 409 ? 'workspace.conflict' : 'workspace.error';
  setWorkspaceStatus(documentObject, statusId, key, 'error', { message: String(error?.message ?? error) });
}
