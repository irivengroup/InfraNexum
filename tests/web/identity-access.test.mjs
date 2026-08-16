import assert from 'node:assert/strict';
import test from 'node:test';

import {
  IdentityAccessApiError,
  identityAccessRequest,
  initializeIamSectionNavigation,
  initializeIamWorkflowNavigation,
  applyTableFilter,
} from '../../src/applications/web/public/assets/identity-access.mjs';

const configuration = Object.freeze({ apiBaseUrl: '/api', identityAccessEnabled: true });

function jsonResponse(status, payload) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() { return payload; },
  };
}

test('IAM GET uses the authenticated same-origin channel without CSRF mutation headers', async () => {
  let observed;
  const result = await identityAccessRequest(configuration, '/v1/iam/users?limit=10', {
    fetchFunction: async (url, options) => {
      observed = { url, options };
      return jsonResponse(200, [{ id: '01900000-0000-7000-8000-000000000001' }]);
    },
    cookieString: 'INX_XSRF=ignored-on-get',
  });
  assert.equal(observed.url, '/api/v1/iam/users?limit=10');
  assert.equal(observed.options.method, 'GET');
  assert.equal(observed.options.credentials, 'same-origin');
  assert.equal(observed.options.cache, 'no-store');
  assert.equal(observed.options.headers['X-CSRF-Token'], undefined);
  assert.equal(observed.options.body, undefined);
  assert.equal(result.length, 1);
});

test('IAM mutations require and forward the decoded CSRF token', async () => {
  let observed;
  await identityAccessRequest(configuration, '/v1/iam/users', {
    method: 'POST',
    body: { login: 'operator' },
    cookieString: 'other=x; INX_XSRF=csrf%20token',
    fetchFunction: async (url, options) => {
      observed = { url, options };
      return jsonResponse(201, { id: '01900000-0000-7000-8000-000000000001' });
    },
  });
  assert.equal(observed.url, '/api/v1/iam/users');
  assert.equal(observed.options.headers['X-CSRF-Token'], 'csrf token');
  assert.equal(observed.options.headers['Content-Type'], 'application/json');
  assert.match(observed.options.headers['Idempotency-Key'], /^[A-Za-z0-9._:-]{8,200}$/);
  assert.equal(observed.options.body, JSON.stringify({ login: 'operator' }));

  await assert.rejects(
    identityAccessRequest(configuration, '/v1/iam/users', {
      method: 'POST', body: {}, cookieString: '', fetchFunction: async () => jsonResponse(500, {}),
    }),
    /CSRF token is unavailable/,
  );
});

test('IAM problem responses preserve HTTP status, stable problem code and safe detail', async () => {
  await assert.rejects(
    identityAccessRequest(configuration, '/v1/iam/users', {
      fetchFunction: async () => jsonResponse(403, {
        code: 'IAM_AUTHORIZATION_DENIED', detail: 'Permission is not effective for this scope',
      }),
    }),
    (error) => error instanceof IdentityAccessApiError
      && error.status === 403
      && error.code === 'IAM_AUTHORIZATION_DENIED'
      && error.message === 'Permission is not effective for this scope',
  );
});

test('IAM browser adapter is capability-gated and rejects paths outside its API namespace', async () => {
  await assert.rejects(
    identityAccessRequest({ apiBaseUrl: '/api', identityAccessEnabled: false }, '/v1/iam/users'),
    /capability is disabled/,
  );
  await assert.rejects(
    identityAccessRequest(configuration, '/system/build'),
    /path must start with \/v1\//,
  );
});


test('IAM mutation adapter validates optional advanced-authorization justification headers', async () => {
  let headers;
  await identityAccessRequest(configuration, '/v1/iam/authorization/decisions', {
    method: 'POST', body: {}, justification: 'Operational exception approved', cookieString: 'INX_XSRF=csrf-token',
    fetchFunction: async (_url, options) => { headers = options.headers; return jsonResponse(200, {}); },
  });
  assert.equal(headers['X-InfraNexum-Justification'], 'Operational exception approved');
  assert.equal(headers['Idempotency-Key'], undefined);
  await assert.rejects(identityAccessRequest(configuration, '/v1/iam/authorization/decisions', {
    method: 'POST', body: {}, justification: 'bad\nvalue', cookieString: 'INX_XSRF=csrf-token', fetchFunction: async () => jsonResponse(200, {}),
  }), /printable characters/);
});


test('IAM section navigation exposes one active workspace and supports keyboard traversal', () => {
  class ClassList {
    constructor() { this.values = new Set(); }
    toggle(name, enabled) { if (enabled) this.values.add(name); else this.values.delete(name); }
    contains(name) { return this.values.has(name); }
  }
  class Node {
    constructor(attributes = {}) {
      this.attributes = { ...attributes };
      this.hidden = false;
      this.open = false;
      this.tabIndex = -1;
      this.classList = new ClassList();
      this.listeners = new Map();
      this.focused = false;
    }
    getAttribute(name) { return this.attributes[name] ?? null; }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    addEventListener(name, listener) { this.listeners.set(name, listener); }
    click() { this.listeners.get('click')?.({ target: this }); }
    keydown(key) {
      let prevented = false;
      this.listeners.get('keydown')?.({ key, target: this, preventDefault() { prevented = true; } });
      return prevented;
    }
    focus() { this.focused = true; }
  }
  const users = new Node({ 'data-iam-section': 'users', 'aria-selected': 'true' });
  const groups = new Node({ 'data-iam-section': 'groups', 'aria-selected': 'false' });
  const policies = new Node({ 'data-iam-section': 'policies', 'aria-selected': 'false' });
  policies.hidden = true;
  const userPanel = new Node({ 'data-iam-panel': 'users' });
  const groupPanel = new Node({ 'data-iam-panel': 'groups' });
  const policyPanel = new Node({ 'data-iam-panel': 'policies' });
  const documentObject = {
    querySelectorAll(selector) {
      if (selector === '[data-iam-section]') return [users, groups, policies];
      if (selector === '[data-iam-panel]') return [userPanel, groupPanel, policyPanel];
      return [];
    },
  };

  const controller = initializeIamSectionNavigation(documentObject);
  assert.equal(users.classList.contains('active'), true);
  assert.equal(userPanel.classList.contains('active'), true);
  assert.equal(groupPanel.classList.contains('active'), false);
  assert.equal(userPanel.hidden, false);
  assert.equal(groupPanel.hidden, true);
  assert.equal(users.tabIndex, 0);
  assert.equal(groups.tabIndex, -1);

  groups.click();
  assert.equal(users.classList.contains('active'), false);
  assert.equal(groups.classList.contains('active'), true);
  assert.equal(groupPanel.getAttribute('aria-hidden'), 'false');
  assert.equal(userPanel.getAttribute('aria-hidden'), 'true');
  assert.equal(userPanel.hidden, true);
  assert.equal(groupPanel.hidden, false);

  assert.equal(groups.keydown('ArrowRight'), true);
  assert.equal(users.classList.contains('active'), true, 'hidden policy tab must be skipped');
  assert.equal(users.focused, true);
  assert.equal(controller.activate('policies'), false, 'hidden sections cannot be activated programmatically');
});


test('IAM workflow metadata keeps one contextual editor pane visible without exposing a second keyboard tab strip', () => {
  class ClassList {
    constructor() { this.values = new Set(); }
    toggle(name, enabled) { if (enabled) this.values.add(name); else this.values.delete(name); }
    contains(name) { return this.values.has(name); }
  }
  class Node {
    constructor(attributes = {}) { this.attributes = { ...attributes }; this.hidden = false; this.tabIndex = -1; this.classList = new ClassList(); this.listeners = new Map(); this.focused = false; }
    getAttribute(name) { return this.attributes[name] ?? null; }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    addEventListener(name, listener) { this.listeners.set(name, listener); }
    click() { this.listeners.get('click')?.({ target: this }); }
    keydown(key) { let prevented = false; this.listeners.get('keydown')?.({ key, preventDefault() { prevented = true; } }); return prevented; }
    focus() { this.focused = true; }
  }
  const create = new Node({ 'data-iam-workflow': 'users:create', 'aria-selected': 'true' });
  const settings = new Node({ 'data-iam-workflow': 'users:settings', 'aria-selected': 'false' });
  const memberships = new Node({ 'data-iam-workflow': 'users:memberships', 'aria-selected': 'false' });
  const roleCreate = new Node({ 'data-iam-workflow': 'roles:create', 'aria-selected': 'true' });
  const createPanel = new Node({ 'data-iam-workflow-panel': 'users:create' });
  const settingsPanel = new Node({ 'data-iam-workflow-panel': 'users:settings' });
  const membershipsPanel = new Node({ 'data-iam-workflow-panel': 'users:memberships' });
  const rolePanel = new Node({ 'data-iam-workflow-panel': 'roles:create' });
  const documentObject = { querySelectorAll(selector) { if (selector === '[data-iam-workflow]') return [create, settings, memberships, roleCreate]; if (selector === '[data-iam-workflow-panel]') return [createPanel, settingsPanel, membershipsPanel, rolePanel]; return []; } };

  const controller = initializeIamWorkflowNavigation(documentObject);
  assert.equal(controller.activate('users:settings'), true);
  assert.equal(settings.classList.contains('active'), true);
  assert.equal(settingsPanel.classList.contains('active'), true);
  assert.equal(createPanel.getAttribute('aria-hidden'), 'true');
  assert.equal(rolePanel.getAttribute('aria-hidden'), 'false', 'each resource facet is initialized independently');
  assert.equal(rolePanel.hidden, false);
  assert.equal(settingsPanel.hidden, false);
  assert.equal(createPanel.hidden, true);

  assert.equal(settings.keydown('ArrowRight'), false, 'legacy workflow buttons are metadata, not a second tab navigation surface');
  assert.equal(settings.classList.contains('active'), true);
  assert.equal(memberships.classList.contains('active'), false);
  assert.equal(memberships.focused, false);
  assert.equal(controller.activate('unknown:settings'), false);
});

test('IAM list filter is local, accent-insensitive and never mutates API data', () => {
  const rows = [
    { textContent: '0190 Alice Méka ACTIVE', hidden: false },
    { textContent: '0191 Bob Smith SUSPENDED', hidden: false },
  ];
  const documentObject = { getElementById(id) { return id === 'iam-user-rows' ? { children: rows } : null; } };
  assert.equal(applyTableFilter(documentObject, 'users', 'meka'), 1);
  assert.equal(rows[0].hidden, false);
  assert.equal(rows[1].hidden, true);
  assert.equal(applyTableFilter(documentObject, 'users', ''), 2);
  assert.equal(rows.every((row) => row.hidden === false), true);
  assert.equal(applyTableFilter(documentObject, 'unsupported', 'x'), 0);
});

test('IAM workspace contains a denied role-search permission inside the Roles list instead of failing the page', async () => {
  const targets = new Map();
  class Element {
    constructor(tag = 'div') { this.tagName = tag.toUpperCase(); this.children = []; this.attributes = new Map(); this.className = ''; this.textContent = ''; this.hidden = false; }
    appendChild(child) { this.children.push(child); this.textContent += child.textContent ?? ''; return child; }
    replaceChildren(...children) { this.children = children; this.textContent = children.map((child) => child.textContent ?? '').join(''); }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    removeAttribute(name) { this.attributes.delete(name); }
  }
  for (const id of ['iam-user-rows', 'iam-group-rows', 'iam-role-rows', 'iam-permission-rows']) targets.set(id, new Element('tbody'));
  const status = new Element('span');
  const nav = Object.fromEntries(['users', 'groups', 'roles', 'permissions'].map((section) => [section, new Element('button')]));
  const panels = Object.fromEntries(['users', 'groups', 'roles', 'permissions'].map((section) => [section, new Element('section')]));
  const documentObject = {
    documentElement: { lang: 'en' },
    getElementById(id) { return targets.get(id) ?? null; },
    createElement(tag) { return new Element(tag); },
    querySelector(selector) {
      const navMatch = selector.match(/^\[data-iam-section="([^"]+)"\]$/); if (navMatch) return nav[navMatch[1]];
      const panelMatch = selector.match(/^\[data-iam-panel="([^"]+)"\]$/); if (panelMatch) return panels[panelMatch[1]];
      return null;
    },
  };
  const { refreshIamWorkspace } = await import('../../src/applications/web/public/assets/identity-access.mjs');
  const api = async (path) => {
    if (path.includes('/roles?')) throw new IdentityAccessApiError(403, 'INFRANEXUM_AUTHORIZATION_DENIED', 'no effective role assignment grants iam.role.search');
    return [];
  };
  const result = await refreshIamWorkspace(documentObject, '01900000-0000-7000-8000-000000000001', api, status);
  assert.deepEqual(result, { loaded: 3, restricted: 1, failed: 0 });
  assert.match(targets.get('iam-role-rows').textContent, /iam\.role\.search/);
  assert.equal(nav.roles.getAttribute('data-iam-list-state'), 'restricted');
  assert.equal(panels.roles.getAttribute('data-iam-list-state'), 'restricted');
  assert.doesNotMatch(status.textContent, /denied|error/i);
});
