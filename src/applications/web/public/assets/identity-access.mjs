import { csrfToken } from './auth.mjs';
import { setIdentityAccessAvailability } from './admin-shell.mjs';
import { localeFromDocument, setLocalizedElementText, translate } from './i18n.mjs';

const REQUEST_TIMEOUT_MS = 15_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** Error raised by the IAM browser adapter while preserving the server problem code when available. */
export class IdentityAccessApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `IAM request failed with HTTP ${status}`);
    this.name = 'IdentityAccessApiError';
    this.status = status;
    this.code = code || 'IAM_HTTP_ERROR';
  }
}

/**
 * Performs one same-origin IAM API request with bounded latency and CSRF on mutations.
 * Secrets are never persisted by this module; it relies exclusively on the HttpOnly session cookie.
 */
export async function identityAccessRequest(
  configuration,
  path,
  { method = 'GET', body, fetchFunction = fetch, cookieString = globalThis.document?.cookie ?? '' } = {},
) {
  if (!configuration?.identityAccessEnabled) throw new Error('Identity-access Web capability is disabled');
  if (typeof path !== 'string' || !path.startsWith('/v1/')) throw new TypeError('IAM API path must start with /v1/');
  const verb = String(method).toUpperCase();
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (!['GET', 'HEAD'].includes(verb)) {
    const csrf = csrfToken(cookieString);
    if (!csrf) throw new Error('CSRF token is unavailable');
    headers['X-CSRF-Token'] = csrf;
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  timer.unref?.();
  try {
    const response = await fetchFunction(`${configuration.apiBaseUrl}${path}`, {
      method: verb,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!response.ok) {
      const problem = await safeJson(response);
      throw new IdentityAccessApiError(response.status, problem?.code ?? problem?.title, problem?.detail ?? problem?.message);
    }
    if (response.status === 204) return null;
    return response.json();
  } catch (error) {
    if (error?.name === 'AbortError') throw new IdentityAccessApiError(0, 'IAM_TIMEOUT', 'IAM request timed out');
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

/** Enables and wires the E03 IAM administration workspace after local authentication succeeds. */
export async function initializeIdentityAccess(documentObject, configuration, fetchFunction = fetch, confirmFunction = globalThis.confirm) {
  setIdentityAccessAvailability(documentObject, configuration.identityAccessEnabled === true);
  if (!configuration.identityAccessEnabled) return Object.freeze({ enabled: false, refresh: async () => {} });

  const organizationSelect = requiredElement(documentObject, 'iam-organization');
  const status = requiredElement(documentObject, 'iam-status');
  const api = (path, options = {}) => identityAccessRequest(configuration, path, {
    ...options, fetchFunction, cookieString: documentObject.cookie,
  });

  await bindOrganizationSelector(documentObject, organizationSelect, api, status);
  bindForms(documentObject, organizationSelect, api, status);
  bindDelegatedActions(documentObject, organizationSelect, api, status, confirmFunction);

  const refresh = async () => refreshWorkspace(documentObject, organizationSelect.value, api, status);
  organizationSelect.addEventListener('change', () => { void refresh(); });
  await refresh();
  return Object.freeze({ enabled: true, refresh });
}

async function bindOrganizationSelector(documentObject, select, api, status) {
  setStatus(documentObject, status, 'iam.loading', false);
  try {
    const organizations = requireArray(await api('/v1/iam/organizations?limit=200'));
    const options = organizations.map((organization) => {
      const option = documentObject.createElement('option');
      option.value = requiredUuid(organization.id, 'organization.id');
      option.textContent = `${safeText(organization.code)} — ${safeText(organization.displayName)}`;
      return option;
    });
    select.replaceChildren(...options);
    select.disabled = options.length === 0;
    setStatus(documentObject, status, options.length === 0 ? 'iam.noOrganizations' : 'iam.ready', options.length === 0);
  } catch (error) {
    select.replaceChildren();
    select.disabled = true;
    setError(documentObject, status, error);
  }
}

function bindForms(documentObject, organizationSelect, api, status) {
  bindForm(documentObject, 'iam-user-create-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const user = await api('/v1/iam/users', { method: 'POST', body: {
      login: field(values, 'login'), email: field(values, 'email'), displayName: field(values, 'displayName'),
      activate: values.get('activate') === 'on', reason: optionalField(values, 'reason'),
    }});
    try {
      await api(`/v1/iam/users/${requiredUuid(user.id, 'user.id')}/memberships`, { method: 'POST', body: {
        organizationId: org, subdivisionId: nullableUuid(optionalField(values, 'subdivisionId')),
        effectiveFrom: null, effectiveTo: null, reason: optionalField(values, 'reason'),
      }});
    } catch (error) {
      try { await api(`/v1/iam/users/${user.id}?reason=${encodeURIComponent('Compensation after membership failure')}`, { method: 'DELETE' }); } catch { /* audited original failure remains authoritative */ }
      throw error;
    }
    form.reset();
  }, documentObject, organizationSelect, api, status, 'users');

  bindForm(documentObject, 'iam-user-update-form', async (form) => {
    const values = new FormData(form);
    const userId = requiredUuid(field(values, 'userId'), 'userId');
    await api(`/v1/iam/users/${userId}`, { method: 'PATCH', body: {
      email: field(values, 'email'), displayName: field(values, 'displayName'), reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'users');

  bindForm(documentObject, 'iam-membership-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/iam/users/${requiredUuid(field(values, 'userId'), 'userId')}/memberships`, { method: 'POST', body: {
      organizationId: org, subdivisionId: nullableUuid(optionalField(values, 'subdivisionId')),
      effectiveFrom: optionalField(values, 'effectiveFrom') || null, effectiveTo: optionalField(values, 'effectiveTo') || null,
      reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'users');

  bindForm(documentObject, 'iam-user-role-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/iam/users/${requiredUuid(field(values, 'userId'), 'userId')}/roles`, { method: 'POST', body: {
      roleId: requiredUuid(field(values, 'roleId'), 'roleId'), scopeKind: field(values, 'scopeKind').toUpperCase(), organizationId: org,
      subdivisionId: nullableUuid(optionalField(values, 'subdivisionId')), effectiveFrom: optionalField(values, 'effectiveFrom') || null,
      effectiveTo: optionalField(values, 'effectiveTo') || null, reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'users');

  bindForm(documentObject, 'iam-group-create-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/organizations/${org}/groups`, { method: 'POST', body: {
      code: field(values, 'code'), displayName: field(values, 'displayName'), reason: optionalField(values, 'reason'),
    }});
    form.reset();
  }, documentObject, organizationSelect, api, status, 'groups');

  bindForm(documentObject, 'iam-group-update-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const groupId = requiredUuid(field(values, 'groupId'), 'groupId');
    await api(`/v1/organizations/${org}/groups/${groupId}`, { method: 'PATCH', body: {
      displayName: field(values, 'displayName'), reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'groups');

  bindForm(documentObject, 'iam-group-member-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const groupId = requiredUuid(field(values, 'groupId'), 'groupId');
    await api(`/v1/organizations/${org}/groups/${groupId}/members`, { method: 'POST', body: {
      memberType: field(values, 'memberType').toUpperCase(), memberId: requiredUuid(field(values, 'memberId'), 'memberId'),
      reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'groups');

  bindForm(documentObject, 'iam-group-member-remove-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const groupId = requiredUuid(field(values, 'groupId'), 'groupId');
    const memberId = requiredUuid(field(values, 'memberId'), 'memberId');
    const memberType = field(values, 'memberType').toUpperCase();
    const reason = optionalField(values, 'reason');
    await api(`/v1/organizations/${org}/groups/${groupId}/members/${memberId}?memberType=${encodeURIComponent(memberType)}${reason ? `&reason=${encodeURIComponent(reason)}` : ''}`, { method: 'DELETE' });
  }, documentObject, organizationSelect, api, status, 'groups');

  bindForm(documentObject, 'iam-group-role-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const groupId = requiredUuid(field(values, 'groupId'), 'groupId');
    await api(`/v1/organizations/${org}/groups/${groupId}/roles`, { method: 'POST', body: {
      roleId: requiredUuid(field(values, 'roleId'), 'roleId'), scopeKind: field(values, 'scopeKind').toUpperCase(),
      subdivisionId: nullableUuid(optionalField(values, 'subdivisionId')), effectiveFrom: optionalField(values, 'effectiveFrom') || null,
      effectiveTo: optionalField(values, 'effectiveTo') || null, reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'groups');

  bindForm(documentObject, 'iam-role-create-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/organizations/${org}/roles`, { method: 'POST', body: {
      code: field(values, 'code'), displayName: field(values, 'displayName'), scopeKind: field(values, 'scopeKind').toUpperCase(),
      permissionCodes: csv(field(values, 'permissionCodes')), reason: optionalField(values, 'reason'),
    }});
    form.reset();
  }, documentObject, organizationSelect, api, status, 'roles');

  bindForm(documentObject, 'iam-role-update-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const roleId = requiredUuid(field(values, 'roleId'), 'roleId');
    const permissionCodes = optionalCsv(optionalField(values, 'permissionCodes'));
    await api(`/v1/organizations/${org}/roles/${roleId}`, { method: 'PATCH', body: {
      code: field(values, 'code'), displayName: field(values, 'displayName'),
      ...(permissionCodes === null ? {} : { permissionCodes }),
      reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'roles');

  bindForm(documentObject, 'iam-role-assignment-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const roleId = requiredUuid(field(values, 'roleId'), 'roleId');
    await api(`/v1/organizations/${org}/roles/${roleId}/assignments`, { method: 'POST', body: {
      actorType: field(values, 'actorType').toUpperCase(), actorId: requiredUuid(field(values, 'actorId'), 'actorId'),
      scopeKind: field(values, 'scopeKind').toUpperCase(), subdivisionId: nullableUuid(optionalField(values, 'subdivisionId')),
      effectiveFrom: optionalField(values, 'effectiveFrom') || null, effectiveTo: optionalField(values, 'effectiveTo') || null,
      reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'roles');

  bindForm(documentObject, 'iam-role-revoke-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const roleId = requiredUuid(field(values, 'roleId'), 'roleId');
    const assignmentId = requiredUuid(field(values, 'assignmentId'), 'assignmentId');
    await api(`/v1/organizations/${org}/roles/${roleId}/assignments/${assignmentId}?reason=${encodeURIComponent(optionalField(values, 'reason') || 'Web role revocation')}`, { method: 'DELETE' });
  }, documentObject, organizationSelect, api, status, 'roles');

  bindForm(documentObject, 'iam-permission-create-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/organizations/${org}/permissions`, { method: 'POST', body: {
      code: field(values, 'code'), resourceType: field(values, 'resourceType'), action: field(values, 'action'),
      sensitivity: field(values, 'sensitivity'), scopeKind: field(values, 'scopeKind').toUpperCase(), reason: optionalField(values, 'reason'),
    }});
    form.reset();
  }, documentObject, organizationSelect, api, status, 'permissions');

  bindForm(documentObject, 'iam-permission-update-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const permissionId = requiredUuid(field(values, 'permissionId'), 'permissionId');
    await api(`/v1/organizations/${org}/permissions/${permissionId}`, { method: 'PATCH', body: {
      resourceType: field(values, 'resourceType'), action: field(values, 'action'),
      sensitivity: field(values, 'sensitivity'), scopeKind: field(values, 'scopeKind').toUpperCase(),
      active: values.get('active') === 'on', reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'permissions');

  bindForm(documentObject, 'iam-permission-evaluate-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const actorId = requiredUuid(field(values, 'actorId'), 'actorId');
    const subdivisionId = nullableUuid(optionalField(values, 'subdivisionId'));
    const result = await api(`/v1/organizations/${org}/permissions/effective?actorId=${actorId}${subdivisionId ? `&subdivisionId=${subdivisionId}` : ''}`);
    const output = requiredElement(documentObject, 'iam-effective-permissions');
    output.textContent = requireArray(result.permissionCodes).join('\n') || '—';
  }, documentObject, organizationSelect, api, status, 'permissions', false);
}

function bindForm(documentObject, formId, execute, organizationSelect, api, status, section, refresh = true) {
  const form = requiredElement(documentObject, formId);
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const submit = form.querySelector?.('button[type="submit"]');
    if (submit) submit.disabled = true;
    setStatus(documentObject, status, 'iam.working', false);
    void execute(form).then(async () => {
      setStatus(documentObject, status, 'iam.saved', false);
      if (refresh) await refreshSection(documentObject, requiredOrganization(organizationSelect), api, section);
    }).catch((error) => setError(documentObject, status, error)).finally(() => { if (submit) submit.disabled = false; });
  });
}

function bindDelegatedActions(documentObject, organizationSelect, api, status, confirmFunction) {
  const workspace = requiredElement(documentObject, 'identity-access-workspace');
  workspace.addEventListener('click', (event) => {
    const button = event.target?.closest?.('[data-iam-action]');
    if (!button) return;
    event.preventDefault();
    const action = button.getAttribute('data-iam-action');
    const id = requiredUuid(button.getAttribute('data-iam-id'), 'action.id');
    const org = requiredOrganization(organizationSelect);
    if (action?.startsWith('delete-') && typeof confirmFunction === 'function' && !confirmFunction('Confirm logical deletion?')) return;
    button.disabled = true;
    setStatus(documentObject, status, 'iam.working', false);
    void executeAction(action, id, org, api).then(async (result) => {
      if (action === 'effective-group' && result) {
        requiredElement(documentObject, 'iam-effective-group-members').textContent = requireArray(result.userIds).join('\n') || '—';
      }
      setStatus(documentObject, status, 'iam.saved', false);
      await refreshWorkspace(documentObject, org, api, status);
    }).catch((error) => setError(documentObject, status, error)).finally(() => { button.disabled = false; });
  });
}

async function executeAction(action, id, org, api) {
  switch (action) {
    case 'activate-user': await api(`/v1/iam/users/${id}/activate`, { method: 'POST', body: { reason: 'Web administration' } }); break;
    case 'suspend-user': await api(`/v1/iam/users/${id}/suspend`, { method: 'POST', body: { reason: 'Web administration' } }); break;
    case 'delete-user': await api(`/v1/iam/users/${id}?reason=${encodeURIComponent('Web administration')}`, { method: 'DELETE' }); break;
    case 'delete-group': await api(`/v1/organizations/${org}/groups/${id}?reason=${encodeURIComponent('Web administration')}`, { method: 'DELETE' }); break;
    case 'delete-role': await api(`/v1/organizations/${org}/roles/${id}?reason=${encodeURIComponent('Web administration')}`, { method: 'DELETE' }); break;
    case 'delete-permission': await api(`/v1/organizations/${org}/permissions/${id}?reason=${encodeURIComponent('Web administration')}`, { method: 'DELETE' }); break;
    case 'effective-group': {
      const result = await api(`/v1/iam/groups/${id}/effective-members`);
      return result;
    }
    default: throw new Error(`Unsupported IAM Web action ${action}`);
  }
  return null;
}

async function refreshWorkspace(documentObject, org, api, status) {
  if (!org) return;
  setStatus(documentObject, status, 'iam.loading', false);
  try {
    await Promise.all(['users', 'groups', 'roles', 'permissions'].map((section) => refreshSection(documentObject, org, api, section)));
    setStatus(documentObject, status, 'iam.ready', false);
  } catch (error) {
    setError(documentObject, status, error);
  }
}

async function refreshSection(documentObject, org, api, section) {
  switch (section) {
    case 'users': renderRows(documentObject, 'iam-user-rows', requireArray(await api('/v1/iam/users?limit=200')), userCells); break;
    case 'groups': renderRows(documentObject, 'iam-group-rows', requireArray(await api(`/v1/organizations/${org}/groups?limit=200`)), groupCells); break;
    case 'roles': renderRows(documentObject, 'iam-role-rows', requireArray(await api(`/v1/organizations/${org}/roles?limit=200`)), roleCells); break;
    case 'permissions': renderRows(documentObject, 'iam-permission-rows', requireArray(await api(`/v1/organizations/${org}/permissions?limit=200`)), permissionCells); break;
    default: throw new Error(`Unsupported IAM section ${section}`);
  }
}

function renderRows(documentObject, targetId, items, cells) {
  const target = requiredElement(documentObject, targetId);
  target.replaceChildren(...items.map((item) => {
    const row = documentObject.createElement('tr');
    for (const value of cells(item, documentObject)) {
      if (value?.action) {
        const cell = documentObject.createElement('td');
        const button = documentObject.createElement('button');
        button.type = 'button'; button.className = 'btn btn-sm btn-outline-secondary me-1';
        button.textContent = translate(localeFromDocument(documentObject), value.labelKey); button.setAttribute('data-iam-action', value.action); button.setAttribute('data-iam-id', requiredUuid(item.id, 'item.id'));
        cell.appendChild(button); row.appendChild(cell);
      } else {
        const cell = documentObject.createElement('td');
        cell.textContent = safeText(value); row.appendChild(cell);
      }
    }
    return row;
  }));
}

function userCells(item) {
  requiredUuid(item.id, 'user.id');
  const active = item.status === 'ACTIVE' || item.status === 'active';
  return [item.login, item.email, item.displayName, item.status,
    { action: active ? 'suspend-user' : 'activate-user', labelKey: active ? 'iam.suspend' : 'iam.activate' },
    { action: 'delete-user', labelKey: 'iam.delete' }];
}
function groupCells(item) {
  requiredUuid(item.id, 'group.id');
  return [item.code, item.displayName,
    { action: 'effective-group', labelKey: 'iam.effectiveMembers' },
    { action: 'delete-group', labelKey: 'iam.delete' }];
}
function roleCells(item) {
  requiredUuid(item.id, 'role.id');
  return [item.code, item.displayName, item.scopeKind, item.systemRole ? 'system' : 'custom', { action: 'delete-role', labelKey: 'iam.delete' }];
}
function permissionCells(item) {
  requiredUuid(item.id, 'permission.id');
  return [item.code, item.resourceType, item.action, item.sensitivity, item.scopeKind, item.systemDefined ? 'system' : 'custom', { action: 'delete-permission', labelKey: 'iam.delete' }];
}

function setStatus(documentObject, element, key, isError) {
  setLocalizedElementText(documentObject, element, key);
  element.className = `badge rounded-pill ${isError ? 'text-bg-danger' : 'text-bg-primary'}`;
}
function setError(documentObject, element, error) {
  element.textContent = error instanceof IdentityAccessApiError ? `${error.code} (HTTP ${error.status || 'timeout'})` : 'IAM unavailable';
  element.className = 'badge rounded-pill text-bg-danger';
}
function requiredOrganization(select) {
  if (!select.value) throw new Error('Select an organization');
  return requiredUuid(select.value, 'organization');
}
function field(data, name) {
  const value = String(data.get(name) ?? '').trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}
function optionalField(data, name) { return String(data.get(name) ?? '').trim(); }
function csv(value) {
  const values = String(value).split(',').map((item) => item.trim()).filter(Boolean);
  if (values.length === 0) throw new Error('At least one permission code is required');
  return [...new Set(values)];
}
function optionalCsv(value) {
  const normalized = String(value ?? '').trim();
  return normalized ? csv(normalized) : null;
}
function nullableUuid(value) { return value ? requiredUuid(value, 'UUID') : null; }
function requiredUuid(value, fieldName) {
  const normalized = String(value ?? '').trim().toLowerCase();
  if (!UUID_PATTERN.test(normalized)) throw new Error(`${fieldName} must be a UUID`);
  return normalized;
}
function requireArray(value) { if (!Array.isArray(value)) throw new Error('IAM API response must be an array'); return value; }
function safeText(value) { return value === null || value === undefined || value === '' ? '—' : String(value); }
function requiredElement(documentObject, id) {
  const element = documentObject?.getElementById?.(id);
  if (!element) throw new Error(`Identity-access UI is incomplete: missing #${id}`);
  return element;
}
async function safeJson(response) { try { return await response.json(); } catch { return null; } }
