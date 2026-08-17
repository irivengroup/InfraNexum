import { localeFromDocument, translate } from './i18n.mjs';

const ENTITY_TYPES = Object.freeze(['organization', 'subdivision', 'user', 'group', 'role', 'permission', 'policy', 'actor', 'assignment']);
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/**
 * Maintains the entity option catalog used by IAM forms.
 *
 * The controller never manufactures identifiers: every selectable value comes
 * from an API result already authorized for the current operator. Hierarchical
 * selectors (organization -> subdivision, role -> assignment, actor type ->
 * user/group) are synchronized at their parent boundary.
 */
export function createIamEntityDirectory(documentObject, api, workspaceOrganizationSelect) {
  if (!documentObject || typeof api !== 'function' || !workspaceOrganizationSelect) {
    throw new TypeError('IAM entity directory requires document, API adapter and organization selector');
  }

  const catalogs = new Map(ENTITY_TYPES.map((type) => [type, []]));
  const subdivisionsByOrganization = new Map();
  const assignmentsByRole = new Map();
  const unavailable = new Set();
  const loadingSubdivision = new Map();
  const loadingAssignments = new Map();
  let currentOrganization = String(workspaceOrganizationSelect.value ?? '');

  const allEntitySelects = () => [...(documentObject.querySelectorAll?.('select[data-inx-entity]') ?? [])];

  const setCatalog = (type, items) => {
    if (!ENTITY_TYPES.includes(type) || type === 'actor' || type === 'assignment' || type === 'subdivision') return;
    catalogs.set(type, Object.freeze(normalizeItems(type, items)));
    unavailable.delete(type);
    syncType(type);
    if (type === 'user' || type === 'group') syncType('actor');
  };

  const setUnavailable = (type) => {
    unavailable.add(type);
    if (ENTITY_TYPES.includes(type)) syncType(type);
    if (type === 'user' || type === 'group') syncType('actor');
  };

  const setOrganizations = (items) => {
    catalogs.set('organization', Object.freeze(normalizeItems('organization', items)));
    unavailable.delete('organization');
    syncType('organization');
  };

  const setOrganization = async (organizationId) => {
    currentOrganization = normalizedUuidOrEmpty(organizationId);
    assignmentsByRole.clear();
    syncOrganizationScoped();
    if (!currentOrganization) return;
    await Promise.allSettled([
      ensureSubdivisions(currentOrganization),
      loadPolicies(currentOrganization),
    ]);
  };

  const ensureSubdivisions = async (organizationId) => {
    const org = normalizedUuidOrEmpty(organizationId);
    if (!org || subdivisionsByOrganization.has(org)) return subdivisionsByOrganization.get(org) ?? [];
    if (loadingSubdivision.has(org)) return loadingSubdivision.get(org);
    const promise = api(`/v1/iam/organizations/${org}/subdivisions?limit=200`)
      .then((items) => {
        const normalized = Object.freeze(normalizeItems('subdivision', requireArray(items)));
        subdivisionsByOrganization.set(org, normalized);
        unavailable.delete(`subdivision:${org}`);
        syncType('subdivision');
        return normalized;
      })
      .catch((error) => {
        unavailable.add(`subdivision:${org}`);
        syncType('subdivision');
        throw error;
      })
      .finally(() => loadingSubdivision.delete(org));
    loadingSubdivision.set(org, promise);
    return promise;
  };

  const ensureAssignments = async (roleId) => {
    const role = normalizedUuidOrEmpty(roleId);
    if (!role || !currentOrganization) return [];
    if (assignmentsByRole.has(role)) return assignmentsByRole.get(role);
    if (loadingAssignments.has(role)) return loadingAssignments.get(role);
    const promise = api(`/v1/organizations/${currentOrganization}/roles/${role}/assignments`)
      .then((items) => {
        const normalized = Object.freeze(normalizeItems('assignment', requireArray(items)));
        assignmentsByRole.set(role, normalized);
        unavailable.delete(`assignment:${role}`);
        syncType('assignment');
        return normalized;
      })
      .catch((error) => {
        unavailable.add(`assignment:${role}`);
        syncType('assignment');
        throw error;
      })
      .finally(() => loadingAssignments.delete(role));
    loadingAssignments.set(role, promise);
    return promise;
  };

  const loadPolicies = async (organizationId) => {
    const org = normalizedUuidOrEmpty(organizationId);
    try {
      const platform = requireArray(await api('/v1/iam/policies?limit=200'));
      const organization = org ? requireArray(await api(`/v1/iam/policies?organizationId=${encodeURIComponent(org)}&limit=200`)) : [];
      const merged = [...platform, ...organization];
      catalogs.set('policy', Object.freeze(normalizeItems('policy', merged)));
      unavailable.delete('policy');
      syncType('policy');
      return merged;
    } catch (error) {
      unavailable.add('policy');
      syncType('policy');
      throw error;
    }
  };

  const syncType = (type) => {
    for (const select of allEntitySelects()) if (select.getAttribute('data-inx-entity') === type) syncSelect(select);
  };

  const syncOrganizationScoped = () => {
    for (const type of ['subdivision', 'group', 'role', 'permission', 'actor', 'assignment', 'policy']) syncType(type);
  };

  const optionsFor = (select) => {
    const type = select.getAttribute('data-inx-entity');
    if (type === 'organization') return catalogs.get('organization') ?? [];
    if (type === 'subdivision') {
      const org = organizationFor(select);
      if (org) void ensureSubdivisions(org).catch(() => {});
      return subdivisionsByOrganization.get(org) ?? [];
    }
    if (type === 'actor') {
      const kind = actorKindFor(select);
      return kind === 'GROUP' ? (catalogs.get('group') ?? []) : (catalogs.get('user') ?? []);
    }
    if (type === 'assignment') {
      const role = roleFor(select);
      if (role) void ensureAssignments(role).catch(() => {});
      return assignmentsByRole.get(role) ?? [];
    }
    return catalogs.get(type) ?? [];
  };

  const isUnavailable = (select) => {
    const type = select.getAttribute('data-inx-entity');
    if (type === 'subdivision') return unavailable.has(`subdivision:${organizationFor(select)}`);
    if (type === 'assignment') return unavailable.has(`assignment:${roleFor(select)}`);
    if (type === 'actor') return unavailable.has(actorKindFor(select) === 'GROUP' ? 'group' : 'user');
    return unavailable.has(type);
  };

  const syncSelect = (select) => {
    if (!select || String(select.tagName ?? '').toUpperCase() !== 'SELECT') return;
    const previous = String(select.value ?? '');
    applyConditionalRequirement(select);
    const required = select.required === true || select.hasAttribute?.('required');
    const options = optionsFor(select);
    const locale = localeFromDocument(documentObject);
    const unavailableState = isUnavailable(select);
    const placeholder = unavailableState
      ? translate(locale, 'iam.entity.unavailable')
      : translate(locale, required ? 'iam.entity.choose' : 'iam.entity.none');
    const nodes = [optionNode('', placeholder, required || unavailableState, !previous)];
    for (const item of options) nodes.push(optionNode(item.id, item.label, false, item.id === previous));
    select.replaceChildren(...nodes);
    const stillPresent = options.some((item) => item.id === previous);
    select.value = stillPresent ? previous : '';
    select.disabled = isUnavailable(select) || scopeDisables(select);
    select.setAttribute?.('data-inx-entity-state', isUnavailable(select) ? 'unavailable' : options.length ? 'ready' : 'empty');
    if (required && !stillPresent) select.value = '';
    dispatchEntitySync(select);
  };

  const optionNode = (value, label, disabled, selected) => {
    const option = documentObject.createElement('option');
    option.value = value;
    option.textContent = label;
    option.disabled = disabled;
    option.selected = selected;
    return option;
  };

  const organizationFor = (select) => {
    const sourceId = select.getAttribute('data-inx-organization-source');
    if (sourceId) return normalizedUuidOrEmpty(documentObject.getElementById(sourceId)?.value);
    return currentOrganization || normalizedUuidOrEmpty(workspaceOrganizationSelect.value);
  };

  const actorKindFor = (select) => {
    const sourceId = select.getAttribute('data-inx-type-source');
    const value = sourceId ? documentObject.getElementById(sourceId)?.value : null;
    return String(value ?? 'USER').toUpperCase() === 'GROUP' ? 'GROUP' : 'USER';
  };

  const roleFor = (select) => {
    const sourceId = select.getAttribute('data-inx-role-source');
    return normalizedUuidOrEmpty(sourceId ? documentObject.getElementById(sourceId)?.value : '');
  };

  const scopeDisables = (select) => {
    const scopeSource = select.getAttribute('data-inx-scope-source');
    if (!scopeSource) return false;
    const scope = String(documentObject.getElementById(scopeSource)?.value ?? '').toUpperCase();
    const type = select.getAttribute('data-inx-entity');
    if (type === 'organization') return scope === 'PLATFORM';
    if (type === 'subdivision') return scope !== 'SUBDIVISION';
    return false;
  };

  const applyConditionalRequirement = (select) => {
    const scopeSource = select.getAttribute('data-inx-scope-source');
    if (!scopeSource) return;
    const scope = String(documentObject.getElementById(scopeSource)?.value ?? '').toUpperCase();
    const type = select.getAttribute('data-inx-entity');
    if (type === 'organization') select.required = scope === 'ORGANIZATION' || scope === 'SUBDIVISION';
    else if (type === 'subdivision') select.required = scope === 'SUBDIVISION';
  };

  const labelFor = (type, id) => {
    if (!ENTITY_TYPES.includes(type)) return null;
    const normalized = normalizedUuidOrEmpty(id);
    if (!normalized) return null;
    return (catalogs.get(type) ?? []).find((item) => item.id === normalized)?.label ?? null;
  };

  const bindDependencies = () => {
    const sources = new Set();
    for (const select of allEntitySelects()) {
      for (const attribute of ['data-inx-organization-source', 'data-inx-type-source', 'data-inx-role-source', 'data-inx-scope-source']) {
        const id = select.getAttribute(attribute);
        if (id) sources.add(id);
      }
    }
    for (const id of sources) {
      const source = documentObject.getElementById(id);
      source?.addEventListener?.('change', () => {
        for (const select of allEntitySelects()) {
          if (['data-inx-organization-source', 'data-inx-type-source', 'data-inx-role-source', 'data-inx-scope-source']
            .some((attribute) => select.getAttribute(attribute) === id)) syncSelect(select);
        }
      });
    }
    workspaceOrganizationSelect.addEventListener?.('change', () => { void setOrganization(workspaceOrganizationSelect.value); });
  };

  bindDependencies();
  documentObject.addEventListener?.('infranexum:policy-changed', () => { void loadPolicies(currentOrganization).catch(() => {}); });
  allEntitySelects().forEach(syncSelect);

  return Object.freeze({
    setOrganizations,
    setCatalog,
    setUnavailable,
    setOrganization,
    ensureSubdivisions,
    ensureAssignments,
    loadPolicies,
    labelFor,
    sync: () => allEntitySelects().forEach(syncSelect),
    snapshot: () => Object.freeze({
      organizationId: currentOrganization,
      organizations: catalogs.get('organization')?.length ?? 0,
      users: catalogs.get('user')?.length ?? 0,
      groups: catalogs.get('group')?.length ?? 0,
      roles: catalogs.get('role')?.length ?? 0,
      permissions: catalogs.get('permission')?.length ?? 0,
      policies: catalogs.get('policy')?.length ?? 0,
    }),
  });
}

function normalizeItems(type, items) {
  return requireArray(items).map((item) => {
    const id = normalizedUuidOrEmpty(item?.id);
    if (!id) throw new Error(`${type} entity is missing a valid UUID`);
    return Object.freeze({ id, label: entityLabel(type, item) });
  });
}

function entityLabel(type, item) {
  const code = clean(item?.code);
  const name = clean(item?.displayName);
  if (type === 'organization' || type === 'subdivision' || type === 'group' || type === 'role') {
    return [code, name].filter(Boolean).join(' — ') || item.id;
  }
  if (type === 'user') return [clean(item?.login), name, clean(item?.email)].filter(Boolean).join(' — ') || item.id;
  if (type === 'permission') return [code, clean(item?.action), clean(item?.resourceType)].filter(Boolean).join(' · ') || item.id;
  if (type === 'policy') return `${code || 'policy'} · v${item?.version ?? '?'} · ${clean(item?.state) || 'UNKNOWN'}`;
  if (type === 'assignment') {
    const actor = `${clean(item?.actorType) || 'ACTOR'}:${shortId(item?.actorId)}`;
    const scope = clean(item?.scopeKind) || 'SCOPE';
    return `${actor} · ${scope} · ${shortId(item?.id)}`;
  }
  return name || code || item.id;
}

function shortId(value) {
  const id = normalizedUuidOrEmpty(value);
  return id ? `${id.slice(0, 8)}…${id.slice(-4)}` : '—';
}

function clean(value) { return value === null || value === undefined ? '' : String(value).trim(); }
function requireArray(value) { if (!Array.isArray(value)) throw new Error('IAM entity API response must be an array'); return value; }
function normalizedUuidOrEmpty(value) {
  const normalized = clean(value).toLowerCase();
  return UUID_PATTERN.test(normalized) ? normalized : '';
}
function dispatchEntitySync(select) {
  const EventConstructor = select?.ownerDocument?.defaultView?.CustomEvent ?? globalThis.CustomEvent;
  if (typeof EventConstructor === 'function') select.dispatchEvent?.(new EventConstructor('infranexum:entity-sync', { bubbles: false }));
}
