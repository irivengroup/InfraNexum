import assert from 'node:assert/strict';
import test from 'node:test';

import { editableBusinessField, initializeBusinessFormFocus } from '../../src/applications/web/public/assets/business-form-focus.mjs';

class Field {
  constructor({ type = 'text', auth = false, search = false, readonly = false } = {}) {
    this.tagName = 'INPUT';
    this.type = type;
    this.disabled = false;
    this.readOnly = readonly;
    this.auth = auth;
    this.search = search;
    this.focusCalls = 0;
  }
  closest(selector) {
    if (selector === 'form') return {};
    if (selector.includes('#auth-gate')) return this.auth ? {} : null;
    if (selector === '[role="search"]') return this.search ? {} : null;
    return null;
  }
  matches(selector) {
    if (selector.includes('[role="search"]')) return this.search;
    if (selector.includes('[data-inx-temporal]')) return false;
    return false;
  }
  focus() { this.focusCalls += 1; }
}

class DocumentFixture {
  constructor(shell) { this.shell = shell; this.listeners = new Map(); this.activeElement = null; }
  getElementById(id) { return id === 'app-shell' ? this.shell : null; }
  addEventListener(type, listener, capture) { this.listeners.set(`${type}:${capture}`, listener); }
  removeEventListener(type, _listener, capture) { this.listeners.delete(`${type}:${capture}`); }
}

test('business form focus guard focuses CRUD text fields before typing', () => {
  const field = new Field();
  const shell = { contains: (candidate) => candidate === field };
  const documentObject = new DocumentFixture(shell);
  const guard = initializeBusinessFormFocus(documentObject);
  assert.equal(guard.enabled, true);
  documentObject.listeners.get('pointerdown:true')({ target: field });
  assert.equal(field.focusCalls, 1);
  guard.destroy();
  assert.equal(documentObject.listeners.size, 0);
});

test('business form focus guard deliberately excludes login and search controls', () => {
  const login = new Field({ auth: true });
  const search = new Field({ type: 'search', search: true });
  const readonly = new Field({ readonly: true });
  const normal = new Field();
  const shell = { contains: () => true };
  assert.equal(editableBusinessField(login, shell), null);
  assert.equal(editableBusinessField(search, shell), null);
  assert.equal(editableBusinessField(readonly, shell), null);
  assert.equal(editableBusinessField(normal, shell), normal);
});
