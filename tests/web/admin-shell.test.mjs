import assert from 'node:assert/strict';
import test from 'node:test';

import {
  applyRoute,
  buildCommands,
  filterCommands,
  normalizeRoute,
  routeForHash,
  setIdentityAccessAvailability,
  setIntegrationsAvailability,
  setDcimAvailability,
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

function shellDocument({ organizations = false, access = false, rsot = false, itam = false, dcim = false, integrations = false } = {}) {
  const root = new Element({ lang: 'en', 'data-route': 'overview' });
  const overviewView = new Element();
  const workspace = new Element({ 'data-capability-enabled': String(organizations) });
  const accessWorkspace = new Element({ 'data-capability-enabled': String(access) });
  const rsotWorkspace = new Element({ 'data-capability-enabled': String(rsot) });
  const itamWorkspace = new Element({ 'data-capability-enabled': String(itam) });
  const dcimWorkspace = new Element({ 'data-capability-enabled': String(dcim) });
  const integrationsWorkspace = new Element({ 'data-capability-enabled': String(integrations) });
  const overviewLink = new Element({ 'data-route': 'overview', class: 'nav-link active' });
  const organizationsLink = new Element({ 'data-route': 'organizations', class: 'nav-link' });
  organizationsLink.hidden = !organizations;
  const accessLink = new Element({ 'data-route': 'access', class: 'nav-link' });
  accessLink.hidden = !access;
  const rsotLink = new Element({ 'data-route': 'rsot', 'data-capability-enabled': String(rsot), class: 'nav-link' });
  rsotLink.hidden = !rsot;
  const itamLink = new Element({ 'data-route': 'itam', 'data-capability-enabled': String(itam), class: 'nav-link' });
  itamLink.hidden = !itam;
  const dcimLink = new Element({ 'data-route': 'dcim', 'data-capability-enabled': String(dcim), class: 'nav-link' });
  dcimLink.hidden = !dcim;
  const integrationsLink = new Element({ 'data-route': 'integrations', 'data-capability-enabled': String(integrations), class: 'nav-link' });
  integrationsLink.hidden = !integrations;
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
    ['dcim-workspace', dcimWorkspace],
    ['integrations-workspace', integrationsWorkspace],
    ['nav-organizations', organizationsLink],
    ['nav-access', accessLink],
    ['nav-rsot', rsotLink],
    ['nav-itam', itamLink],
    ['nav-dcim', dcimLink],
    ['nav-integrations', integrationsLink],
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
    querySelectorAll: (selector) => selector === '[data-route]' ? [overviewLink, organizationsLink, accessLink, rsotLink, itamLink, dcimLink, integrationsLink] : [],
    overviewView,
    workspace,
    accessWorkspace,
    rsotWorkspace,
    itamWorkspace,
    dcimWorkspace,
    integrationsWorkspace,
    overviewLink,
    organizationsLink,
    accessLink,
    rsotLink,
    itamLink,
    dcimLink,
    integrationsLink,
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
  assert.equal(normalizeRoute('dcim'), 'dcim');
  assert.equal(normalizeRoute('integrations'), 'integrations');
  assert.equal(routeForHash('#/access'), 'access');
  assert.equal(routeForHash('#/organizations'), 'organizations');
  assert.equal(routeForHash('#/rsot'), 'rsot');
  assert.equal(routeForHash('#/itam'), 'itam');
  assert.equal(routeForHash('#/dcim'), 'dcim');
  assert.equal(routeForHash('#/integrations'), 'integrations');
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


test('RSOT, ITAM and DCIM routes are fail-closed and become navigable only when their capabilities are available', () => {
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

  setDcimAvailability(documentObject, true, windowObject);
  assert.equal(documentObject.dcimLink.hidden, false);
  assert.equal(applyRoute(documentObject, 'dcim', windowObject), 'dcim');
  assert.equal(documentObject.dcimWorkspace.hidden, false);
  assert.equal(documentObject.topbarTitle.textContent, 'Physical infrastructure');
  setDcimAvailability(documentObject, false, windowObject);
  assert.equal(documentObject.dcimLink.hidden, true);
});

test('Integrations route is fail-closed until the connector capability is explicitly available', () => {
  const documentObject = shellDocument({ integrations: false });
  const windowObject = windowFixture('#/integrations');
  assert.equal(applyRoute(documentObject, 'integrations', windowObject, { replaceHash: true }), 'overview');
  assert.equal(documentObject.integrationsWorkspace.hidden, true);
  assert.equal(windowObject.location.hash, '#/overview');

  setIntegrationsAvailability(documentObject, true, windowObject);
  assert.equal(documentObject.integrationsLink.hidden, false);
  assert.equal(documentObject.integrationsLink.getAttribute('aria-disabled'), 'false');
  assert.equal(applyRoute(documentObject, 'integrations', windowObject), 'integrations');
  assert.equal(documentObject.integrationsWorkspace.hidden, false);
  assert.equal(documentObject.topbarTitle.textContent, 'External integrations');

  setIntegrationsAvailability(documentObject, false, windowObject);
  assert.equal(documentObject.integrationsLink.hidden, true);
  assert.equal(applyRoute(documentObject, 'integrations', windowObject), 'overview');
});

test('command catalogue exposes only actionable capabilities', () => {
  const documentObject = shellDocument({ organizations: false });
  const windowObject = windowFixture();
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'runtime', 'theme', 'preferences', 'notifications', 'swagger', 'redoc']);
  setOrganizationAvailability(documentObject, true, windowObject);
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'organizations', 'runtime', 'theme', 'preferences', 'notifications', 'swagger', 'redoc']);
  setIdentityAccessAvailability(documentObject, true, windowObject);
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'access', 'organizations', 'runtime', 'theme', 'preferences', 'notifications', 'swagger', 'redoc']);
  setRsotAvailability(documentObject, true, windowObject);
  setItamAvailability(documentObject, true, windowObject);
  setDcimAvailability(documentObject, true, windowObject);
  setIntegrationsAvailability(documentObject, true, windowObject);
  assert.deepEqual(buildCommands(documentObject, windowObject).map((item) => item.id), ['overview', 'access', 'organizations', 'rsot', 'dcim', 'integrations', 'itam', 'runtime', 'theme', 'preferences', 'notifications', 'swagger', 'redoc']);
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
