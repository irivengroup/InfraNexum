import assert from 'node:assert/strict';
import test from 'node:test';

import {
  applyRoute,
  buildCommands,
  filterCommands,
  normalizeRoute,
  routeForHash,
  setIdentityAccessAvailability,
  setItamAvailability,
  setOrganizationAvailability,
  setRsotAvailability,
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

function shellDocument({ organizations = false, access = false, rsot = false, itam = false } = {}) {
  const root = new Element({ lang: 'en', 'data-route': 'overview' });
  const overviewView = new Element();
  const workspace = new Element({ 'data-capability-enabled': String(organizations) });
  const accessWorkspace = new Element({ 'data-capability-enabled': String(access) });
  const rsotWorkspace = new Element({ 'data-capability-enabled': String(rsot) });
  const itamWorkspace = new Element({ 'data-capability-enabled': String(itam) });
  const overviewLink = new Element({ 'data-route': 'overview', class: 'inx-nav-link active' });
  const organizationsLink = new Element({ 'data-route': 'organizations', class: 'inx-nav-link' });
  organizationsLink.hidden = !organizations;
  const accessLink = new Element({ 'data-route': 'access', class: 'inx-nav-link' });
  accessLink.hidden = !access;
  const rsotLink = new Element({ 'data-route': 'rsot', 'data-capability-enabled': String(rsot), class: 'inx-nav-link' });
  rsotLink.hidden = !rsot;
  const itamLink = new Element({ 'data-route': 'itam', 'data-capability-enabled': String(itam), class: 'inx-nav-link' });
  itamLink.hidden = !itam;
  const breadcrumb = new Element();
  const title = new Element();
  const runtimeTitle = new Element();
  const theme = new Element();
  const preferences = new Element();
  const notifications = new Element();
  const byId = new Map([
    ['overview-view', overviewView],
    ['organization-workspace', workspace],
    ['identity-access-workspace', accessWorkspace],
    ['rsot-workspace', rsotWorkspace],
    ['itam-workspace', itamWorkspace],
    ['nav-organizations', organizationsLink],
    ['nav-access', accessLink],
    ['nav-rsot', rsotLink],
    ['nav-itam', itamLink],
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
    querySelectorAll: (selector) => selector === '[data-route]' ? [overviewLink, organizationsLink, accessLink, rsotLink, itamLink] : [],
    overviewView,
    workspace,
    accessWorkspace,
    rsotWorkspace,
    itamWorkspace,
    overviewLink,
    organizationsLink,
    accessLink,
    rsotLink,
    itamLink,
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
  assert.equal(normalizeRoute('access'), 'access');
  assert.equal(normalizeRoute('rsot'), 'rsot');
  assert.equal(normalizeRoute('itam'), 'itam');
  assert.equal(routeForHash('#/access'), 'access');
  assert.equal(routeForHash('#/organizations'), 'organizations');
  assert.equal(routeForHash('#/rsot'), 'rsot');
  assert.equal(routeForHash('#/itam'), 'itam');
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

test('identity-access route is fail-closed until its capability is explicitly available', () => {
  const documentObject = shellDocument({ organizations: true, access: false });
  const windowObject = windowFixture('#/access');
  assert.equal(applyRoute(documentObject, 'access', windowObject, { replaceHash: true }), 'overview');
  assert.equal(documentObject.accessWorkspace.hidden, true);
  assert.equal(windowObject.location.hash, '#/overview');

  setIdentityAccessAvailability(documentObject, true, windowObject);
  assert.equal(documentObject.accessLink.hidden, false);
  assert.equal(documentObject.accessLink.getAttribute('aria-disabled'), 'false');
  assert.equal(applyRoute(documentObject, 'access', windowObject), 'access');
  assert.equal(documentObject.accessWorkspace.hidden, false);
  assert.equal(documentObject.overviewView.hidden, true);
  assert.equal(documentObject.breadcrumb.textContent, 'Identity & access');
  assert.equal(documentObject.topbarTitle.textContent, 'Identity & access');
});


test('RSOT and ITAM routes are fail-closed and become navigable only when their capabilities are available', () => {
  const documentObject = shellDocument();
  const windowObject = windowFixture('#/rsot');
  assert.equal(applyRoute(documentObject, 'rsot', windowObject, { replaceHash: true }), 'overview');
  assert.equal(documentObject.rsotWorkspace.hidden, true);

  setRsotAvailability(documentObject, true, windowObject);
  assert.equal(documentObject.rsotLink.hidden, false);
  assert.equal(applyRoute(documentObject, 'rsot', windowObject), 'rsot');
  assert.equal(documentObject.rsotWorkspace.hidden, false);
  assert.equal(documentObject.topbarTitle.textContent, 'RSOT & schema governance');

  setItamAvailability(documentObject, true, windowObject);
  assert.equal(documentObject.itamLink.hidden, false);
  assert.equal(applyRoute(documentObject, 'itam', windowObject), 'itam');
  assert.equal(documentObject.itamWorkspace.hidden, false);
  assert.equal(documentObject.topbarTitle.textContent, 'IT asset management');

  setRsotAvailability(documentObject, false, windowObject);
  assert.equal(documentObject.rsotLink.hidden, true);
});

test('command catalogue exposes only actionable capabilities', () => {
  const documentObject = shellDocument({ organizations: false });
  const windowObject = windowFixture();
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'runtime', 'theme', 'preferences', 'notifications']);
  setOrganizationAvailability(documentObject, true, windowObject);
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'organizations', 'runtime', 'theme', 'preferences', 'notifications']);
  setIdentityAccessAvailability(documentObject, true, windowObject);
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'access', 'organizations', 'runtime', 'theme', 'preferences', 'notifications']);
  setRsotAvailability(documentObject, true, windowObject);
  setItamAvailability(documentObject, true, windowObject);
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'access', 'organizations', 'rsot', 'itam', 'runtime', 'theme', 'preferences', 'notifications']);
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
