import assert from 'node:assert/strict';
import test from 'node:test';

import { initializeTheme, renderRuntimeConfiguration, renderRuntimeFailure } from '../../src/applications/web/public/assets/bootstrap.mjs';

class Element {
  constructor() {
    this.textContent = '';
    this.className = '';
    this.hidden = true;
    this.attributes = {};
    this.listeners = new Map();
  }
  setAttribute(name, value) { this.attributes[name] = value; }
  getAttribute(name) { return this.attributes[name]; }
  removeAttribute(name) { delete this.attributes[name]; }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  click() { this.listeners.get('click')?.(); }
}

function dashboardDocument() {
  const ids = [
    'runtime-version', 'runtime-environment', 'runtime-api', 'runtime-api-detail', 'runtime-architecture',
    'runtime-message', 'sidebar-version', 'footer-version', 'topbar-environment', 'dashboard-runtime',
    'dashboard-environment', 'dashboard-version', 'dashboard-foundation', 'dashboard-organization-count',
    'sidebar-runtime-state', 'runtime-health-badge', 'api-health-badge', 'foundation-health-badge',
    'organization-workspace', 'theme-toggle',
  ];
  const elements = new Map(ids.map((id) => [id, new Element()]));
  return {
    documentElement: new Element(),
    getElementById: (id) => elements.get(id),
    elements,
  };
}

const configuration = {
  schema: 'infranexum.web-runtime-config/v1',
  product: 'InfraNexum',
  component: 'web',
  version: '2.0.0-alpha.0.80',
  architectureBaseline: '2.0.0-draft.21',
  environment: 'local',
  apiBaseUrl: '/api',
  organizationFoundationEnabled: false,
};

test('dashboard renders truthful runtime posture without inventing unavailable metrics', () => {
  const documentObject = dashboardDocument();
  renderRuntimeConfiguration(documentObject, configuration);
  assert.equal(documentObject.elements.get('dashboard-runtime').textContent, 'Operational');
  assert.equal(documentObject.elements.get('dashboard-environment').textContent, 'local');
  assert.equal(documentObject.elements.get('dashboard-version').textContent, 'Version 2.0.0-alpha.0.80');
  assert.equal(documentObject.elements.get('dashboard-foundation').textContent, 'Disabled');
  assert.equal(documentObject.elements.get('dashboard-organization-count').textContent, 'N/A');
  assert.equal(documentObject.elements.get('runtime-health-badge').textContent, 'UP');
  assert.match(documentObject.elements.get('runtime-health-badge').className, /text-bg-success/);
});

test('dashboard failure state is explicit and fail-closed', () => {
  const documentObject = dashboardDocument();
  renderRuntimeFailure(documentObject);
  assert.equal(documentObject.elements.get('dashboard-runtime').textContent, 'Unavailable');
  assert.equal(documentObject.elements.get('sidebar-runtime-state').textContent, 'Unavailable');
  assert.equal(documentObject.elements.get('runtime-health-badge').textContent, 'DOWN');
  assert.match(documentObject.elements.get('runtime-health-badge').className, /text-bg-danger/);
});

test('theme toggle persists an explicit light or dark preference accessibly', () => {
  const documentObject = dashboardDocument();
  const values = new Map([['infranexum.theme', 'dark']]);
  const storage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };
  initializeTheme(documentObject, storage);
  const root = documentObject.documentElement;
  const button = documentObject.elements.get('theme-toggle');
  assert.equal(root.getAttribute('data-bs-theme'), 'dark');
  assert.equal(button.attributes['aria-pressed'], 'true');
  assert.equal(button.attributes['aria-label'], 'Switch to light theme');
  button.click();
  assert.equal(root.getAttribute('data-bs-theme'), 'light');
  assert.equal(values.get('infranexum.theme'), 'light');
  assert.equal(button.attributes['aria-pressed'], 'false');
});
