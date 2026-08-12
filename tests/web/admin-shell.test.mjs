import assert from 'node:assert/strict';
import test from 'node:test';

import {
  applyRoute,
  buildCommands,
  filterCommands,
  normalizeRoute,
  routeForHash,
  setOrganizationAvailability,
} from '../../src/applications/web/public/assets/admin-shell.mjs';

class ClassList {
  constructor(initial = '') { this.values = new Set(initial.split(/\s+/).filter(Boolean)); }
  toggle(name, enabled) { if (enabled) this.values.add(name); else this.values.delete(name); }
  contains(name) { return this.values.has(name); }
}

class Element {
  constructor(attributes = {}) {
    this.attributes = { ...attributes };
    this.hidden = false;
    this.textContent = '';
    this.classList = new ClassList(attributes.class ?? '');
  }
  getAttribute(name) { return this.attributes[name] ?? null; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  removeAttribute(name) { delete this.attributes[name]; }
  scrollIntoView() { this.scrolled = true; }
  click() { this.clicked = true; }
}

function shellDocument({ organizations = false } = {}) {
  const root = new Element({ lang: 'en', 'data-route': 'overview' });
  const overviewView = new Element();
  const workspace = new Element({ 'data-capability-enabled': String(organizations) });
  const overviewLink = new Element({ 'data-route': 'overview', class: 'inx-nav-link active' });
  const organizationsLink = new Element({ 'data-route': 'organizations', class: 'inx-nav-link' });
  organizationsLink.hidden = !organizations;
  const breadcrumb = new Element();
  const title = new Element();
  const runtimeTitle = new Element();
  const theme = new Element();
  const preferences = new Element();
  const notifications = new Element();
  const byId = new Map([
    ['overview-view', overviewView],
    ['organization-workspace', workspace],
    ['nav-organizations', organizationsLink],
    ['breadcrumb-current', breadcrumb],
    ['topbar-page-title', title],
    ['runtime-title', runtimeTitle],
    ['theme-toggle', theme],
    ['preferences-trigger', preferences],
    ['notification-trigger', notifications],
  ]);
  return {
    documentElement: root,
    title: '',
    getElementById: (id) => byId.get(id),
    querySelectorAll: (selector) => selector === '[data-route]' ? [overviewLink, organizationsLink] : [],
    overviewView,
    workspace,
    overviewLink,
    organizationsLink,
    breadcrumb,
    topbarTitle: title,
    runtimeTitle,
    theme,
    preferences,
    notifications,
  };
}

function windowFixture(hash = '') {
  const location = { hash };
  return {
    location,
    history: {
      pushState(_state, _title, next) { location.hash = next; },
      replaceState(_state, _title, next) { location.hash = next; },
    },
    matchMedia: () => ({ matches: true }),
  };
}

test('route parser accepts only known administration routes', () => {
  assert.equal(normalizeRoute('organizations'), 'organizations');
  assert.equal(routeForHash('#/organizations'), 'organizations');
  assert.equal(routeForHash('#overview'), 'overview');
  assert.equal(routeForHash('#/unknown'), 'overview');
});

test('organization route is fail-closed until the capability is explicitly available', () => {
  const documentObject = shellDocument({ organizations: false });
  const windowObject = windowFixture('#/organizations');
  assert.equal(applyRoute(documentObject, 'organizations', windowObject, { replaceHash: true }), 'overview');
  assert.equal(documentObject.overviewView.hidden, false);
  assert.equal(documentObject.workspace.hidden, true);
  assert.equal(windowObject.location.hash, '#/overview');

  setOrganizationAvailability(documentObject, true, windowObject);
  assert.equal(documentObject.organizationsLink.hidden, false);
  assert.equal(documentObject.organizationsLink.getAttribute('aria-disabled'), 'false');
  assert.equal(applyRoute(documentObject, 'organizations', windowObject), 'organizations');
  assert.equal(documentObject.workspace.hidden, false);
  assert.equal(documentObject.overviewView.hidden, true);
  assert.equal(documentObject.breadcrumb.textContent, 'Organizations');
  assert.equal(documentObject.topbarTitle.textContent, 'Organizations & subdivisions');
});

test('command catalogue exposes only actionable capabilities', () => {
  const documentObject = shellDocument({ organizations: false });
  const windowObject = windowFixture();
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'runtime', 'theme', 'preferences', 'notifications']);
  setOrganizationAvailability(documentObject, true, windowObject);
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'organizations', 'runtime', 'theme', 'preferences', 'notifications']);
});

test('command search is localized, case-insensitive and accent-insensitive', () => {
  const documentObject = shellDocument({ organizations: true });
  const commands = buildCommands(documentObject, windowFixture());
  assert.deepEqual(filterCommands(commands, 'ORGANIZACIONES', 'es').map((item) => item.id), ['organizations']);
  assert.deepEqual(filterCommands(commands, 'déploiement', 'fr').map((item) => item.id), ['overview', 'runtime']);
  assert.deepEqual(filterCommands(commands, 'tema oscuro', 'es').map((item) => item.id), ['theme']);
  assert.equal(filterCommands(commands, 'does-not-exist', 'en').length, 0);
});

test('runtime, theme, preferences and notifications commands execute only local idempotent UI actions', () => {
  const documentObject = shellDocument({ organizations: true });
  const windowObject = windowFixture();
  const commands = buildCommands(documentObject, windowObject);
  commands.find((item) => item.id === 'runtime').run();
  assert.equal(documentObject.runtimeTitle.scrolled, true);
  commands.find((item) => item.id === 'theme').run();
  assert.equal(documentObject.theme.clicked, true);
  commands.find((item) => item.id === 'preferences').run();
  assert.equal(documentObject.preferences.clicked, true);
  commands.find((item) => item.id === 'notifications').run();
  assert.equal(documentObject.notifications.clicked, true);
});
