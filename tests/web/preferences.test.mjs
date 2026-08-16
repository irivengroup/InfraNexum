import assert from 'node:assert/strict';
import test from 'node:test';

import {
  DEFAULT_PREFERENCES,
  PREFERENCES_SCHEMA,
  PREFERENCES_STORAGE_KEY,
  LEGACY_PREFERENCES_STORAGE_KEY,
  applyPreferences,
  initializePreferences,
  loadPreferences,
  persistPreferences,
  validatePreferences,
} from '../../src/applications/web/public/assets/preferences.mjs';

class Element {
  constructor() { this.attributes = {}; this.value = ''; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return this.attributes[name] ?? null; }
}

function documentFixture() {
  const elements = new Map([
    ['preference-density', new Element()],
    ['preference-navigation', new Element()],
    ['preference-refresh', new Element()],
    ['preference-layout', new Element()],
    ['preference-theme', new Element()],
  ]);
  const documentElement = new Element();
  documentElement.setAttribute('data-bs-theme', 'dark');
  return {
    documentElement,
    getElementById: (id) => elements.get(id),
    elements,
  };
}

test('structured preferences validate strictly and fall back safely', () => {
  assert.deepEqual(validatePreferences(null), DEFAULT_PREFERENCES);
  assert.deepEqual(validatePreferences({ density: 'invalid', navigation: 'wide', layout: 'wide', refreshIntervalSeconds: 9 }), DEFAULT_PREFERENCES);
  assert.deepEqual(validatePreferences({
    schema: 'old', density: 'compact', navigation: 'expanded', layout: 'fluid', refreshIntervalSeconds: 300,
  }), {
    schema: PREFERENCES_SCHEMA, density: 'compact', navigation: 'expanded', layout: 'fluid', refreshIntervalSeconds: 300,
  });
});

test('preferences survive storage corruption without breaking the dashboard', () => {
  assert.deepEqual(loadPreferences({ getItem: () => '{broken' }), DEFAULT_PREFERENCES);
  assert.deepEqual(loadPreferences({ getItem() { throw new Error('blocked'); } }), DEFAULT_PREFERENCES);
});

test('preferences persist as one versioned JSON document and apply layout independently from theme', () => {
  const values = new Map();
  const storage = { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
  const selected = persistPreferences(storage, { density: 'compact', navigation: 'compact', layout: 'fluid', refreshIntervalSeconds: 30 });
  assert.equal(JSON.parse(values.get(PREFERENCES_STORAGE_KEY)).schema, PREFERENCES_SCHEMA);
  const documentObject = documentFixture();
  applyPreferences(documentObject, selected);
  assert.equal(documentObject.documentElement.getAttribute('data-density'), 'compact');
  assert.equal(documentObject.documentElement.getAttribute('data-navigation'), 'compact');
  assert.equal(documentObject.documentElement.getAttribute('data-layout'), 'fluid');
  assert.equal(documentObject.documentElement.getAttribute('data-refresh-seconds'), '30');
  assert.equal(documentObject.documentElement.getAttribute('data-bs-theme'), 'dark');
  assert.equal(documentObject.elements.get('preference-layout').value, 'fluid');
  assert.equal(documentObject.elements.get('preference-theme').value, 'dark');
  assert.equal(documentObject.elements.get('preference-refresh').value, '30');
});

test('legacy v1 preferences migrate without losing established operator choices', () => {
  const legacy = JSON.stringify({ density: 'compact', navigation: 'expanded', refreshIntervalSeconds: 300 });
  const loaded = loadPreferences({ getItem: (key) => key === LEGACY_PREFERENCES_STORAGE_KEY ? legacy : null });
  assert.deepEqual(loaded, {
    schema: PREFERENCES_SCHEMA, density: 'compact', navigation: 'expanded', layout: 'page', refreshIntervalSeconds: 300,
  });
});


test('saving settings persists, applies every display preference, requests theme and closes the drawer', () => {
  class InteractiveElement extends Element {
    constructor() { super(); this.listeners = new Map(); this.open = false; this.focused = false; this.returnValue = ''; }
    removeAttribute(name) { delete this.attributes[name]; }
    addEventListener(name, listener) { if (!this.listeners.has(name)) this.listeners.set(name, []); this.listeners.get(name).push(listener); }
    emit(name, event = {}) { for (const listener of this.listeners.get(name) ?? []) listener({ target: this, preventDefault() {}, ...event }); }
    showModal() { this.open = true; }
    close(value = '') { this.returnValue = value; this.open = false; }
    focus() { this.focused = true; }
  }

  const ids = ['preferences-dialog', 'preferences-trigger', 'preferences-close', 'preferences-form', 'preferences-reset', 'preferences-save',
    'preference-density', 'preference-navigation', 'preference-refresh', 'preference-layout', 'preference-theme'];
  const elements = new Map(ids.map((id) => [id, new InteractiveElement()]));
  const root = new InteractiveElement();
  root.setAttribute('data-bs-theme', 'light');
  const documentListeners = new Map();
  const documentObject = {
    documentElement: root,
    getElementById: (id) => elements.get(id) ?? null,
    addEventListener(name, listener) { if (!documentListeners.has(name)) documentListeners.set(name, []); documentListeners.get(name).push(listener); },
    dispatchEvent(event) {
      if (event?.type === 'infranexum:theme-request') {
        root.setAttribute('data-bs-theme', event.detail.theme);
        const themeChange = { type: 'infranexum:theme-change', detail: { theme: event.detail.theme } };
        for (const listener of documentListeners.get(themeChange.type) ?? []) listener(themeChange);
      }
      for (const listener of documentListeners.get(event?.type) ?? []) listener(event);
      return true;
    },
  };
  const values = new Map();
  const storage = { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };

  initializePreferences(documentObject, storage);
  elements.get('preferences-trigger').emit('click');
  assert.equal(elements.get('preferences-dialog').open, true);
  elements.get('preference-density').value = 'compact';
  elements.get('preference-navigation').value = 'compact';
  elements.get('preference-layout').value = 'fluid';
  elements.get('preference-refresh').value = '30';
  elements.get('preference-theme').value = 'dark';
  elements.get('preferences-save').emit('click');

  assert.equal(root.getAttribute('data-density'), 'compact');
  assert.equal(root.getAttribute('data-navigation'), 'compact');
  assert.equal(root.getAttribute('data-layout'), 'fluid');
  assert.equal(root.getAttribute('data-refresh-seconds'), '30');
  assert.equal(root.getAttribute('data-bs-theme'), 'dark');
  assert.equal(elements.get('preferences-dialog').open, false);
  assert.equal(elements.get('preferences-dialog').returnValue, 'saved');
  assert.equal(elements.get('preferences-trigger').getAttribute('aria-expanded'), 'false');
  assert.equal(elements.get('preferences-save').getAttribute('aria-busy'), null);
  assert.equal(JSON.parse(values.get(PREFERENCES_STORAGE_KEY)).layout, 'fluid');
});
