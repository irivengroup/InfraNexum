import { csrfToken } from './auth.mjs';
import { setIdentityAccessAvailability } from './admin-shell.mjs';
import { localeFromDocument, setLocalizedElementText, translate } from './i18n.mjs';
import { wireAsyncForm } from './form-controller.mjs';
import { createIamEntityDirectory } from './entity-selects.mjs';

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
  { method = 'GET', body, justification, idempotencyKey, fetchFunction = fetch, cookieString = globalThis.document?.cookie ?? '' } = {},
) {
  if (!configuration?.identityAccessEnabled) throw new Error('Identity-access Web capability is disabled');
  if (typeof path !== 'string' || !path.startsWith('/v1/')) throw new TypeError('IAM API path must start with /v1/');
  const verb = String(method).toUpperCase();
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (justification !== undefined) {
    if (typeof justification !== 'string') throw new TypeError('Justification must be a string');
    const normalized = justification.trim();
    if (normalized.length < 8 || normalized.length > 500 || /[\u0000-\u001F\u007F]/.test(normalized)) {
      throw new Error('Justification must contain 8 to 500 printable characters');
    }
    headers['X-InfraNexum-Justification'] = normalized;
  }
  if (!['GET', 'HEAD'].includes(verb)) {
    const csrf = csrfToken(cookieString);
    if (!csrf) throw new Error('CSRF token is unavailable');
    headers['X-CSRF-Token'] = csrf;
    if (requiresIdempotency(path)) headers['Idempotency-Key'] = validatedIdempotencyKey(idempotencyKey ?? newIdempotencyKey());
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


function requiresIdempotency(path) {
  return !(/\/permissions\/validate(?:\?|$)/.test(path) || /\/authorization\/(?:decisions|explain)(?:\?|$)/.test(path));
}

function newIdempotencyKey() {
  const generated = globalThis.crypto?.randomUUID?.();
  if (typeof generated !== 'string') throw new Error('secure UUID idempotency generation is unavailable');
  return generated;
}

function validatedIdempotencyKey(value) {
  const normalized = String(value ?? '').trim();
  if (!/^[A-Za-z0-9._:-]{8,200}$/.test(normalized)) throw new Error('Idempotency key must contain 8 to 200 safe characters');
  return normalized;
}

/** Enables and wires the E03 IAM administration workspace after local authentication succeeds. */
export async function initializeIdentityAccess(documentObject, configuration, fetchFunction = fetch, confirmFunction = globalThis.confirm) {
  setIdentityAccessAvailability(documentObject, configuration.identityAccessEnabled === true);
  if (!configuration.identityAccessEnabled) return Object.freeze({ enabled: false, refresh: async () => {} });

  initializeIamSectionNavigation(documentObject);
  const workflowNavigation = initializeIamWorkflowNavigation(documentObject);
  const organizationSelect = requiredElement(documentObject, 'iam-organization');
  const status = requiredElement(documentObject, 'iam-status');
  const api = (path, options = {}) => identityAccessRequest(configuration, path, {
    ...options, fetchFunction, cookieString: documentObject.cookie,
  });

  const entityDirectory = createIamEntityDirectory(documentObject, api, organizationSelect);
  const organizations = await bindOrganizationSelector(documentObject, organizationSelect, api, status);
  entityDirectory.setOrganizations(organizations);
  await entityDirectory.setOrganization(organizationSelect.value);
  bindForms(documentObject, organizationSelect, api, status, entityDirectory);
  bindDelegatedActions(documentObject, organizationSelect, api, status, confirmFunction, workflowNavigation, entityDirectory);
  bindWorkspaceChrome(documentObject, organizationSelect, api, status, workflowNavigation);

  const refresh = async () => refreshWorkspace(documentObject, organizationSelect.value, api, status, entityDirectory);
  organizationSelect.addEventListener('change', () => {
    void entityDirectory.setOrganization(organizationSelect.value).then(refresh);
  });
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
    if (options.length === 0) {
      const empty = documentObject.createElement('option');
      empty.value = '';
      empty.textContent = translate(localeFromDocument(documentObject), 'iam.noOrganizations');
      empty.selected = true;
      select.replaceChildren(empty);
      select.value = '';
    } else {
      select.replaceChildren(...options);
      select.value = options[0].value;
    }
    select.disabled = false;
    select.setAttribute?.('data-inx-select-state', options.length === 0 ? 'empty' : 'ready');
    setStatus(documentObject, status, options.length === 0 ? 'iam.noOrganizations' : 'iam.ready', options.length === 0);
    return organizations;
  } catch (error) {
    select.replaceChildren();
    select.disabled = true;
    setError(documentObject, status, error);
    return [];
  }
}

function bindForms(documentObject, organizationSelect, api, status, entityDirectory) {
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
  }, documentObject, organizationSelect, api, status, 'users', true, entityDirectory);

  bindForm(documentObject, 'iam-user-update-form', async (form) => {
    const values = new FormData(form);
    const userId = requiredUuid(field(values, 'userId'), 'userId');
    await api(`/v1/iam/users/${userId}`, { method: 'PATCH', body: {
      email: field(values, 'email'), displayName: field(values, 'displayName'), reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'users', true, entityDirectory);

  bindForm(documentObject, 'iam-membership-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/iam/users/${requiredUuid(field(values, 'userId'), 'userId')}/memberships`, { method: 'POST', body: {
      organizationId: org, subdivisionId: nullableUuid(optionalField(values, 'subdivisionId')),
      effectiveFrom: optionalField(values, 'effectiveFrom') || null, effectiveTo: optionalField(values, 'effectiveTo') || null,
      reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'users', true, entityDirectory);

  bindForm(documentObject, 'iam-user-role-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/iam/users/${requiredUuid(field(values, 'userId'), 'userId')}/roles`, { method: 'POST', body: {
      roleId: requiredUuid(field(values, 'roleId'), 'roleId'), scopeKind: field(values, 'scopeKind').toUpperCase(), organizationId: org,
      subdivisionId: nullableUuid(optionalField(values, 'subdivisionId')), effectiveFrom: optionalField(values, 'effectiveFrom') || null,
      effectiveTo: optionalField(values, 'effectiveTo') || null, reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'users', true, entityDirectory);

  bindForm(documentObject, 'iam-group-create-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/organizations/${org}/groups`, { method: 'POST', body: {
      code: field(values, 'code'), displayName: field(values, 'displayName'), reason: optionalField(values, 'reason'),
    }});
    form.reset();
  }, documentObject, organizationSelect, api, status, 'groups', true, entityDirectory);

  bindForm(documentObject, 'iam-group-update-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const groupId = requiredUuid(field(values, 'groupId'), 'groupId');
    await api(`/v1/organizations/${org}/groups/${groupId}`, { method: 'PATCH', body: {
      displayName: field(values, 'displayName'), reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'groups', true, entityDirectory);

  bindForm(documentObject, 'iam-group-member-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const groupId = requiredUuid(field(values, 'groupId'), 'groupId');
    await api(`/v1/organizations/${org}/groups/${groupId}/members`, { method: 'POST', body: {
      memberType: field(values, 'memberType').toUpperCase(), memberId: requiredUuid(field(values, 'memberId'), 'memberId'),
      reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'groups', true, entityDirectory);

  bindForm(documentObject, 'iam-group-member-remove-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const groupId = requiredUuid(field(values, 'groupId'), 'groupId');
    const memberId = requiredUuid(field(values, 'memberId'), 'memberId');
    const memberType = field(values, 'memberType').toUpperCase();
    const reason = optionalField(values, 'reason');
    await api(`/v1/organizations/${org}/groups/${groupId}/members/${memberId}?memberType=${encodeURIComponent(memberType)}${reason ? `&reason=${encodeURIComponent(reason)}` : ''}`, { method: 'DELETE' });
  }, documentObject, organizationSelect, api, status, 'groups', true, entityDirectory);

  bindForm(documentObject, 'iam-group-role-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const groupId = requiredUuid(field(values, 'groupId'), 'groupId');
    await api(`/v1/organizations/${org}/groups/${groupId}/roles`, { method: 'POST', body: {
      roleId: requiredUuid(field(values, 'roleId'), 'roleId'), scopeKind: field(values, 'scopeKind').toUpperCase(),
      subdivisionId: nullableUuid(optionalField(values, 'subdivisionId')), effectiveFrom: optionalField(values, 'effectiveFrom') || null,
      effectiveTo: optionalField(values, 'effectiveTo') || null, reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'groups', true, entityDirectory);

  bindForm(documentObject, 'iam-role-create-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/organizations/${org}/roles`, { method: 'POST', body: {
      code: field(values, 'code'), displayName: field(values, 'displayName'), scopeKind: field(values, 'scopeKind').toUpperCase(),
      permissionCodes: csv(field(values, 'permissionCodes')), reason: optionalField(values, 'reason'),
    }});
    form.reset();
  }, documentObject, organizationSelect, api, status, 'roles', true, entityDirectory);

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
  }, documentObject, organizationSelect, api, status, 'roles', true, entityDirectory);

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
  }, documentObject, organizationSelect, api, status, 'roles', true, entityDirectory);

  bindForm(documentObject, 'iam-role-revoke-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const roleId = requiredUuid(field(values, 'roleId'), 'roleId');
    const assignmentId = requiredUuid(field(values, 'assignmentId'), 'assignmentId');
    await api(`/v1/organizations/${org}/roles/${roleId}/assignments/${assignmentId}?reason=${encodeURIComponent(optionalField(values, 'reason') || 'Web role revocation')}`, { method: 'DELETE' });
  }, documentObject, organizationSelect, api, status, 'roles', true, entityDirectory);

  bindForm(documentObject, 'iam-permission-create-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    await api(`/v1/organizations/${org}/permissions`, { method: 'POST', body: {
      code: field(values, 'code'), resourceType: field(values, 'resourceType'), action: field(values, 'action'),
      sensitivity: field(values, 'sensitivity'), scopeKind: field(values, 'scopeKind').toUpperCase(), reason: optionalField(values, 'reason'),
    }});
    form.reset();
  }, documentObject, organizationSelect, api, status, 'permissions', true, entityDirectory);

  bindForm(documentObject, 'iam-permission-update-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const permissionId = requiredUuid(field(values, 'permissionId'), 'permissionId');
    await api(`/v1/organizations/${org}/permissions/${permissionId}`, { method: 'PATCH', body: {
      resourceType: field(values, 'resourceType'), action: field(values, 'action'),
      sensitivity: field(values, 'sensitivity'), scopeKind: field(values, 'scopeKind').toUpperCase(),
      active: values.get('active') === 'on', reason: optionalField(values, 'reason'),
    }});
  }, documentObject, organizationSelect, api, status, 'permissions', true, entityDirectory);

  bindForm(documentObject, 'iam-permission-evaluate-form', async (form) => {
    const org = requiredOrganization(organizationSelect);
    const values = new FormData(form);
    const actorId = requiredUuid(field(values, 'actorId'), 'actorId');
    const subdivisionId = nullableUuid(optionalField(values, 'subdivisionId'));
    const result = await api(`/v1/organizations/${org}/permissions/effective?actorId=${actorId}${subdivisionId ? `&subdivisionId=${subdivisionId}` : ''}`);
    const output = requiredElement(documentObject, 'iam-effective-permissions');
    output.textContent = requireArray(result.permissionCodes).join('\n') || '—';
  }, documentObject, organizationSelect, api, status, 'permissions', false, entityDirectory);
}

function bindForm(documentObject, formId, execute, organizationSelect, api, status, section, refresh = true, entityDirectory = null) {
  const form = requiredElement(documentObject, formId);
  wireAsyncForm(form, {
    execute: async () => {
      clearFeedback(documentObject);
      await execute(form);
      if (refresh) await refreshIamSectionSafely(documentObject, requiredOrganization(organizationSelect), api, section, entityDirectory);
    },
    onWorking: () => setStatus(documentObject, status, 'iam.working', false),
    onSuccess: () => {
      setStatus(documentObject, status, 'iam.saved', false);
      setFeedback(documentObject, 'iam.saved', false);
    },
    onError: (error) => setError(documentObject, status, error),
  });
}

function bindDelegatedActions(documentObject, organizationSelect, api, status, confirmFunction, workflowNavigation, entityDirectory = null) {
  const workspace = requiredElement(documentObject, 'identity-access-workspace');
  workspace.addEventListener('click', (event) => {
    const button = event.target?.closest?.('[data-iam-action]');
    if (!button) return;
    event.preventDefault();
    const action = button.getAttribute('data-iam-action');
    const id = requiredUuid(button.getAttribute('data-iam-id'), 'action.id');
    if (action?.startsWith('select-')) {
      populateSelection(documentObject, action, button.getAttribute('data-iam-record'), workflowNavigation);
      setStatus(documentObject, status, 'iam.selected', false);
      setFeedback(documentObject, 'iam.selected', false);
      return;
    }
    const org = requiredOrganization(organizationSelect);
    if (action?.startsWith('delete-') && typeof confirmFunction === 'function' && !confirmFunction(translate(localeFromDocument(documentObject), 'iam.confirmDelete'))) return;
    button.disabled = true;
    clearFeedback(documentObject);
    setStatus(documentObject, status, 'iam.working', false);
    void executeAction(action, id, org, api).then(async (result) => {
      if (action === 'effective-group' && result) {
        requiredElement(documentObject, 'iam-effective-group-members').textContent = requireArray(result.userIds).join('\n') || '—';
      }
      setStatus(documentObject, status, 'iam.saved', false);
      await refreshWorkspace(documentObject, org, api, status, entityDirectory);
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

const SECTION_LIST_PERMISSION = Object.freeze({
  users: 'iam.user.search',
  groups: 'iam.group.search',
  roles: 'iam.role.search',
  permissions: 'iam.permission.search',
});

const SECTION_ROW_TARGET = Object.freeze({
  users: 'iam-user-rows',
  groups: 'iam-group-rows',
  roles: 'iam-role-rows',
  permissions: 'iam-permission-rows',
});

/**
 * Refreshes every IAM list independently. Search permission on one resource family
 * must never make the whole Identity & Access workspace unusable. In particular,
 * operators allowed to manage users/groups but not enumerate roles still retain a
 * functional workspace and receive a scoped access-denied state for the Roles list.
 */
export async function refreshIamWorkspace(documentObject, org, api, status, entityDirectory = null) {
  if (!org) return Object.freeze({ loaded: 0, restricted: 0, failed: 0 });
  setStatus(documentObject, status, 'iam.loading', false);
  const results = await Promise.all(['users', 'groups', 'roles', 'permissions'].map(
    (section) => refreshIamSectionSafely(documentObject, org, api, section, entityDirectory),
  ));
  const loaded = results.filter((result) => result === 'loaded').length;
  const restricted = results.filter((result) => result === 'restricted').length;
  const failed = results.filter((result) => result === 'failed').length;

  if (failed > 0) setStatus(documentObject, status, 'iam.partialFailure', true);
  else if (restricted > 0) setStatus(documentObject, status, 'iam.partialAccess', false);
  else setStatus(documentObject, status, 'iam.ready', false);
  return Object.freeze({ loaded, restricted, failed });
}

async function refreshWorkspace(documentObject, org, api, status, entityDirectory = null) {
  await refreshIamWorkspace(documentObject, org, api, status, entityDirectory);
}

/** Refreshes one list and contains permission failures inside that list boundary. */
export async function refreshIamSectionSafely(documentObject, org, api, section, entityDirectory = null) {
  try {
    await refreshSection(documentObject, org, api, section, entityDirectory);
    markSectionListState(documentObject, section, 'loaded');
    return 'loaded';
  } catch (error) {
    if (isAuthorizationDenied(error)) {
      renderSectionMessage(documentObject, section, 'iam.listRestricted', SECTION_LIST_PERMISSION[section]);
      markSectionListState(documentObject, section, 'restricted');
      entityDirectory?.setUnavailable?.(({ users: 'user', groups: 'group', roles: 'role', permissions: 'permission' })[section]);
      return 'restricted';
    }
    renderSectionMessage(documentObject, section, 'iam.listUnavailable', null, error);
    markSectionListState(documentObject, section, 'failed');
    return 'failed';
  }
}

async function refreshSection(documentObject, org, api, section, entityDirectory = null) {
  let items;
  switch (section) {
    case 'users': items = requireArray(await api('/v1/iam/users?limit=200')); renderRows(documentObject, 'iam-user-rows', items, userCells); entityDirectory?.setCatalog?.('user', items); break;
    case 'groups': items = requireArray(await api(`/v1/organizations/${org}/groups?limit=200`)); renderRows(documentObject, 'iam-group-rows', items, groupCells); entityDirectory?.setCatalog?.('group', items); break;
    case 'roles': items = requireArray(await api(`/v1/organizations/${org}/roles?limit=200`)); renderRows(documentObject, 'iam-role-rows', items, roleCells); entityDirectory?.setCatalog?.('role', items); break;
    case 'permissions': items = requireArray(await api(`/v1/organizations/${org}/permissions?limit=200`)); renderRows(documentObject, 'iam-permission-rows', items, permissionCells); entityDirectory?.setCatalog?.('permission', items); break;
    default: throw new Error(`Unsupported IAM section ${section}`);
  }
  applyCurrentFilter(documentObject, section);
}

function isAuthorizationDenied(error) {
  return error instanceof IdentityAccessApiError
    && error.status === 403
    && ['INFRANEXUM_AUTHORIZATION_DENIED', 'IAM_AUTHORIZATION_DENIED', 'FORBIDDEN'].includes(String(error.code));
}

function renderSectionMessage(documentObject, section, key, permission = null, error = null) {
  const targetId = SECTION_ROW_TARGET[section];
  if (!targetId) return;
  const target = requiredElement(documentObject, targetId);
  const row = documentObject.createElement('tr');
  row.setAttribute?.('data-iam-list-state', permission ? 'restricted' : 'failed');
  const cell = documentObject.createElement('td');
  cell.colSpan = 20;
  cell.className = `small ${permission ? 'text-warning' : 'text-danger'}`;
  const locale = localeFromDocument(documentObject);
  const detail = permission
    ? translate(locale, key, { permission })
    : `${translate(locale, key)}${error?.code ? ` · ${safeText(error.code)}` : ''}`;
  cell.textContent = detail;
  row.appendChild(cell);
  target.replaceChildren(row);
}

function markSectionListState(documentObject, section, state) {
  const navigation = documentObject?.querySelector?.(`[data-iam-section="${section}"]`);
  const panel = documentObject?.querySelector?.(`[data-iam-panel="${section}"]`);
  navigation?.setAttribute?.('data-iam-list-state', state);
  panel?.setAttribute?.('data-iam-list-state', state);
  if (navigation) {
    if (state === 'restricted') navigation.setAttribute?.('title', SECTION_LIST_PERMISSION[section] ?? '');
    else navigation.removeAttribute?.('title');
  }
}

function applyCurrentFilter(documentObject, section) {
  const input = documentObject?.querySelector?.(`[data-iam-filter="${section}"]`);
  if (input) applyTableFilter(documentObject, section, input.value);
}

function renderRows(documentObject, targetId, items, cells) {
  const target = requiredElement(documentObject, targetId);
  if (items.length === 0) {
    const row = documentObject.createElement('tr');
    const cell = documentObject.createElement('td');
    cell.colSpan = 20;
    cell.className = 'text-body-secondary py-4 text-center';
    cell.textContent = translate(localeFromDocument(documentObject), 'iam.empty');
    row.appendChild(cell);
    target.replaceChildren(row);
    return;
  }
  target.replaceChildren(...items.map((item) => {
    const row = documentObject.createElement('tr');
    for (const value of cells(item)) {
      const cell = documentObject.createElement('td');
      if (value?.actions) {
        cell.className = 'text-nowrap';
        for (const action of value.actions) {
          const button = documentObject.createElement('button');
          button.type = 'button';
          button.className = `btn btn-sm ${action.danger ? 'btn-outline-danger' : action.primary ? 'btn-outline-primary' : 'btn-outline-secondary'} me-1 mb-1`;
          button.textContent = translate(localeFromDocument(documentObject), action.labelKey);
          button.setAttribute('data-iam-action', action.action);
          button.setAttribute('data-iam-id', requiredUuid(item.id, 'item.id'));
          if (action.record) button.setAttribute('data-iam-record', JSON.stringify(action.record));
          cell.appendChild(button);
        }
      } else if (value && typeof value === 'object' && 'text' in value) {
        cell.textContent = safeText(value.text);
        if (value.title) cell.title = safeText(value.title);
        if (value.className) cell.className = value.className;
      } else {
        cell.textContent = safeText(value);
      }
      row.appendChild(cell);
    }
    return row;
  }));
}

function idCell(id) {
  const normalized = requiredUuid(id, 'item.id');
  return { text: `${normalized.slice(0, 8)}…${normalized.slice(-4)}`, title: normalized, className: 'font-monospace small' };
}
function selectAction(kind, item) {
  return { action: `select-${kind}`, labelKey: 'iam.select', primary: true, record: item };
}
function userCells(item) {
  requiredUuid(item.id, 'user.id');
  const active = item.status === 'ACTIVE' || item.status === 'active';
  return [idCell(item.id), item.login, item.email, item.displayName, item.status,
    { actions: [selectAction('user', item),
      { action: active ? 'suspend-user' : 'activate-user', labelKey: active ? 'iam.suspend' : 'iam.activate' },
      { action: 'delete-user', labelKey: 'iam.delete', danger: true }] }];
}
function groupCells(item) {
  requiredUuid(item.id, 'group.id');
  const actions = [selectAction('group', item), { action: 'effective-group', labelKey: 'iam.effectiveMembers' }];
  if (!item.systemGroup) actions.push({ action: 'delete-group', labelKey: 'iam.delete', danger: true });
  return [idCell(item.id), item.code, item.displayName, { actions }];
}
function roleCells(item) {
  requiredUuid(item.id, 'role.id');
  const actions = [selectAction('role', item)];
  if (!item.systemRole) actions.push({ action: 'delete-role', labelKey: 'iam.delete', danger: true });
  return [idCell(item.id), item.code, item.displayName, item.scopeKind, item.systemRole ? 'system' : 'custom', { actions }];
}
function permissionCells(item) {
  requiredUuid(item.id, 'permission.id');
  const actions = [selectAction('permission', item)];
  if (!item.systemDefined) actions.push({ action: 'delete-permission', labelKey: 'iam.delete', danger: true });
  return [idCell(item.id), item.code, item.resourceType, item.action, item.sensitivity, item.scopeKind, item.systemDefined ? 'system' : 'custom', { actions }];
}

function populateSelection(documentObject, action, rawRecord, workflowNavigation) {
  let item;
  try { item = JSON.parse(rawRecord || '{}'); } catch { throw new Error('Selected IAM record is invalid'); }
  const id = requiredUuid(item.id, 'selected.id');
  const set = (formId, name, value) => {
    const form = documentObject.getElementById(formId);
    const field = form?.querySelector?.(`[name="${name}"]`);
    if (field && value !== undefined && value !== null) {
      field.value = String(value);
      if (String(field.tagName ?? '').toUpperCase() === 'SELECT') {
        const EventConstructor = documentObject?.defaultView?.Event ?? globalThis.Event;
        if (typeof EventConstructor === 'function') field.dispatchEvent?.(new EventConstructor('change', { bubbles: true }));
      }
    }
  };
  if (action === 'select-user') {
    set('iam-user-update-form', 'userId', id); set('iam-user-update-form', 'email', item.email ?? ''); set('iam-user-update-form', 'displayName', item.displayName ?? '');
    set('iam-membership-form', 'userId', id); set('iam-user-role-form', 'userId', id); set('iam-permission-evaluate-form', 'actorId', id);
    const policySubject = documentObject.getElementById('iam-policy-subject'); if (policySubject) { policySubject.value = id; dispatchChange(documentObject, policySubject); }
    workflowNavigation?.activate?.('users:settings');
  } else if (action === 'select-group') {
    for (const formId of ['iam-group-update-form', 'iam-group-member-form', 'iam-group-member-remove-form', 'iam-group-role-form']) set(formId, 'groupId', id);
    set('iam-group-update-form', 'displayName', item.displayName ?? '');
    set('iam-role-assignment-form', 'actorId', id); set('iam-role-assignment-form', 'actorType', 'GROUP');
    workflowNavigation?.activate?.('groups:settings');
  } else if (action === 'select-role') {
    for (const formId of ['iam-user-role-form', 'iam-group-role-form', 'iam-role-update-form', 'iam-role-assignment-form', 'iam-role-revoke-form']) set(formId, 'roleId', id);
    set('iam-role-update-form', 'code', item.code ?? ''); set('iam-role-update-form', 'displayName', item.displayName ?? '');
    workflowNavigation?.activate?.('roles:settings');
  } else if (action === 'select-permission') {
    set('iam-permission-update-form', 'permissionId', id); set('iam-permission-update-form', 'resourceType', item.resourceType ?? '');
    set('iam-permission-update-form', 'action', item.action ?? ''); set('iam-permission-update-form', 'sensitivity', item.sensitivity ?? 'normal');
    set('iam-permission-update-form', 'scopeKind', item.scopeKind ?? 'ORGANIZATION');
    const active = documentObject.getElementById('iam-permission-active'); if (active) active.checked = item.active !== false;
    workflowNavigation?.activate?.('permissions:settings');
  } else throw new Error(`Unsupported IAM selection ${action}`);
}


/**
 * Controls the FreeIPA-inspired object action facets. Exactly one operation pane
 * is visible for an IAM resource at a time, which keeps list/detail workflows
 * readable without changing any Server contract.
 */
export function initializeIamWorkflowNavigation(documentObject) {
  const buttons = [...(documentObject?.querySelectorAll?.('[data-iam-workflow]') ?? [])];
  const panels = [...(documentObject?.querySelectorAll?.('[data-iam-workflow-panel]') ?? [])];
  if (buttons.length === 0 || panels.length === 0) return Object.freeze({ activate: () => false });

  const activate = (name, focus = false) => {
    const target = buttons.find((button) => button.getAttribute('data-iam-workflow') === name && !button.hidden);
    if (!target) return false;
    const [section] = String(name).split(':', 1);
    for (const button of buttons) {
      if (!String(button.getAttribute('data-iam-workflow') ?? '').startsWith(`${section}:`)) continue;
      const active = button === target;
      button.classList?.toggle?.('active', active);
      button.setAttribute?.('aria-selected', active ? 'true' : 'false');
      button.tabIndex = active ? 0 : -1;
    }
    for (const panel of panels) {
      if (!String(panel.getAttribute('data-iam-workflow-panel') ?? '').startsWith(`${section}:`)) continue;
      const active = panel.getAttribute('data-iam-workflow-panel') === name;
      panel.classList?.toggle?.('active', active);
      panel.hidden = !active;
      panel.setAttribute?.('aria-hidden', active ? 'false' : 'true');
    }
    if (focus) target.focus?.();
    return true;
  };

  for (const button of buttons) {
    button.addEventListener?.('click', () => activate(button.getAttribute('data-iam-workflow')));
    button.addEventListener?.('keydown', (event) => {
      const currentName = String(button.getAttribute('data-iam-workflow') ?? '');
      const [section] = currentName.split(':', 1);
      const visible = buttons.filter((candidate) => !candidate.hidden && String(candidate.getAttribute('data-iam-workflow') ?? '').startsWith(`${section}:`));
      const index = visible.indexOf(button);
      let next = null;
      if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = visible[(index + 1) % visible.length];
      else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = visible[(index - 1 + visible.length) % visible.length];
      else if (event.key === 'Home') next = visible[0];
      else if (event.key === 'End') next = visible[visible.length - 1];
      if (next) { event.preventDefault?.(); activate(next.getAttribute('data-iam-workflow'), true); }
    });
  }
  const sections = [...new Set(buttons.map((button) => String(button.getAttribute('data-iam-workflow') ?? '').split(':', 1)[0]).filter(Boolean))];
  for (const section of sections) {
    const current = buttons.find((button) => !button.hidden && String(button.getAttribute('data-iam-workflow') ?? '').startsWith(`${section}:`) && button.getAttribute('aria-selected') === 'true')
      ?? buttons.find((button) => !button.hidden && String(button.getAttribute('data-iam-workflow') ?? '').startsWith(`${section}:`));
    if (current) activate(current.getAttribute('data-iam-workflow'));
  }
  return Object.freeze({ activate });
}

function bindWorkspaceChrome(documentObject, organizationSelect, api, status, workflowNavigation) {
  for (const input of documentObject?.querySelectorAll?.('[data-iam-filter]') ?? []) {
    input.addEventListener?.('input', () => applyTableFilter(documentObject, input.getAttribute('data-iam-filter'), input.value));
  }
  for (const button of documentObject?.querySelectorAll?.('[data-iam-refresh]') ?? []) {
    button.addEventListener?.('click', () => {
      const section = button.getAttribute('data-iam-refresh');
      button.disabled = true;
      setStatus(documentObject, status, 'iam.loading', false);
      void refreshIamSectionSafely(documentObject, requiredOrganization(organizationSelect), api, section)
        .then((result) => setStatus(documentObject, status, result === 'loaded' ? 'iam.ready' : result === 'restricted' ? 'iam.partialAccess' : 'iam.partialFailure', result === 'failed'))
        .finally(() => { button.disabled = false; });
    });
  }
  for (const button of documentObject?.querySelectorAll?.('[data-iam-open-workflow]') ?? []) {
    button.addEventListener?.('click', () => {
      const name = button.getAttribute('data-iam-open-workflow');
      workflowNavigation?.activate?.(name, true);
      const panel = documentObject?.querySelector?.(`[data-iam-workflow-panel="${name}"]`);
      panel?.querySelector?.('input, select, textarea, button')?.focus?.();
    });
  }
}

export function applyTableFilter(documentObject, section, rawQuery) {
  const targetId = ({ users: 'iam-user-rows', groups: 'iam-group-rows', roles: 'iam-role-rows', permissions: 'iam-permission-rows' })[section];
  if (!targetId) return 0;
  const tbody = documentObject?.getElementById?.(targetId);
  if (!tbody) return 0;
  const query = String(rawQuery ?? '').normalize('NFD').replace(/\p{Diacritic}/gu, '').trim().toLowerCase();
  let visible = 0;
  for (const row of tbody.children ?? []) {
    const text = String(row.textContent ?? '').normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();
    const matches = !query || text.includes(query);
    row.hidden = !matches;
    if (matches) visible += 1;
  }
  return visible;
}

/** Creates accessible, keyboard-navigable IAM sub-area navigation. */
export function initializeIamSectionNavigation(documentObject) {
  const buttons = [...(documentObject?.querySelectorAll?.('[data-iam-section]') ?? [])];
  const panels = [...(documentObject?.querySelectorAll?.('[data-iam-panel]') ?? [])];
  if (buttons.length === 0 || panels.length === 0) return Object.freeze({ activate: () => false });
  const activate = (name, focus = false) => {
    const target = buttons.find((button) => button.getAttribute('data-iam-section') === name && !button.hidden);
    if (!target) return false;
    for (const button of buttons) {
      const active = button === target;
      button.classList?.toggle?.('active', active);
      button.setAttribute?.('aria-selected', active ? 'true' : 'false');
      button.tabIndex = active ? 0 : -1;
    }
    for (const panel of panels) {
      const active = panel.getAttribute('data-iam-panel') === name;
      panel.classList?.toggle?.('active', active);
      panel.hidden = !active;
      panel.setAttribute?.('aria-hidden', active ? 'false' : 'true');
    }
    if (focus) target.focus?.();
    return true;
  };
  for (const button of buttons) {
    button.addEventListener?.('click', () => activate(button.getAttribute('data-iam-section')));
    button.addEventListener?.('keydown', (event) => {
      const visible = buttons.filter((candidate) => !candidate.hidden);
      const index = visible.indexOf(button);
      let next = null;
      if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = visible[(index + 1) % visible.length];
      else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = visible[(index - 1 + visible.length) % visible.length];
      else if (event.key === 'Home') next = visible[0];
      else if (event.key === 'End') next = visible[visible.length - 1];
      if (next) { event.preventDefault?.(); activate(next.getAttribute('data-iam-section'), true); }
    });
  }
  const current = buttons.find((button) => button.getAttribute('aria-selected') === 'true' && !button.hidden) ?? buttons.find((button) => !button.hidden);
  if (current) activate(current.getAttribute('data-iam-section'));
  return Object.freeze({ activate });
}

function dispatchChange(documentObject, field) {
  const EventConstructor = documentObject?.defaultView?.Event ?? globalThis.Event;
  if (typeof EventConstructor === 'function') field.dispatchEvent?.(new EventConstructor('change', { bubbles: true }));
}

function setStatus(documentObject, element, key, isError) {
  setLocalizedElementText(documentObject, element, key);
  element.className = `badge rounded-pill ${isError ? 'text-bg-danger' : 'text-bg-primary'}`;
}
function setError(documentObject, element, error) {
  const locale = localeFromDocument(documentObject);
  const code = error instanceof IdentityAccessApiError ? error.code : 'IAM_CLIENT_VALIDATION';
  const status = error instanceof IdentityAccessApiError ? (error.status || 'timeout') : 'client';
  const detail = safeErrorDetail(error, translate(locale, 'iam.unavailable'));
  element.textContent = `${code} (${status})`;
  element.className = 'badge rounded-pill text-bg-danger';
  setFeedbackText(documentObject, `${detail} · ${code}`, true);
}
function setFeedback(documentObject, key, isError) {
  const feedback = documentObject?.getElementById?.('iam-feedback');
  if (!feedback) return;
  setLocalizedElementText(documentObject, feedback, key);
  feedback.className = `alert ${isError ? 'alert-danger' : 'alert-success'} mb-3`;
  feedback.hidden = false;
}
function setFeedbackText(documentObject, text, isError) {
  const feedback = documentObject?.getElementById?.('iam-feedback');
  if (!feedback) return;
  feedback.textContent = text;
  feedback.className = `alert ${isError ? 'alert-danger' : 'alert-success'} mb-3`;
  feedback.hidden = false;
}
function clearFeedback(documentObject) {
  const feedback = documentObject?.getElementById?.('iam-feedback');
  if (!feedback) return;
  feedback.hidden = true;
  feedback.textContent = '';
}
function safeErrorDetail(error, fallback) {
  const value = typeof error?.message === 'string' ? error.message.trim() : '';
  if (!value || value.length > 500 || /[\u0000-\u001F\u007F]/.test(value)) return fallback;
  return value;
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
