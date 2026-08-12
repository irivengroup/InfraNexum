import assert from 'node:assert/strict';
import test from 'node:test';

import {
  DEFAULT_PREFERENCES,
  PREFERENCES_SCHEMA,
  PREFERENCES_STORAGE_KEY,
  applyPreferences,
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
  ]);
  return {
    documentElement: new Element(),
    getElementById: (id) => elements.get(id),
    elements,
  };
}

test('structured preferences validate strictly and fall back safely', () => {
  assert.deepEqual(validatePreferences(null), DEFAULT_PREFERENCES);
  assert.deepEqual(validatePreferences({ density: 'invalid', navigation: 'wide', refreshIntervalSeconds: 9 }), DEFAULT_PREFERENCES);
  assert.deepEqual(validatePreferences({
    schema: 'old', density: 'compact', navigation: 'expanded', refreshIntervalSeconds: 300,
  }), {
    schema: PREFERENCES_SCHEMA, density: 'compact', navigation: 'expanded', refreshIntervalSeconds: 300,
  });
});

test('preferences survive storage corruption without breaking the dashboard', () => {
  assert.deepEqual(loadPreferences({ getItem: () => '{broken' }), DEFAULT_PREFERENCES);
  assert.deepEqual(loadPreferences({ getItem() { throw new Error('blocked'); } }), DEFAULT_PREFERENCES);
});

test('preferences persist as one versioned JSON document and apply to the root', () => {
  const values = new Map();
  const storage = { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
  const selected = persistPreferences(storage, { density: 'compact', navigation: 'compact', refreshIntervalSeconds: 30 });
  assert.equal(JSON.parse(values.get(PREFERENCES_STORAGE_KEY)).schema, PREFERENCES_SCHEMA);
  const documentObject = documentFixture();
  applyPreferences(documentObject, selected);
  assert.equal(documentObject.documentElement.getAttribute('data-density'), 'compact');
  assert.equal(documentObject.documentElement.getAttribute('data-navigation'), 'compact');
  assert.equal(documentObject.documentElement.getAttribute('data-refresh-seconds'), '30');
  assert.equal(documentObject.elements.get('preference-refresh').value, '30');
});
