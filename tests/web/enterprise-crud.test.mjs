import assert from 'node:assert/strict';
import test from 'node:test';

import { sortTableByColumn, sortableValue, wireCrudPanel } from '../../src/applications/web/public/assets/enterprise-crud.mjs';

class Classes {
  constructor(...names) { this.values = new Set(names); }
  add(...names) { names.forEach((name) => this.values.add(name)); }
  remove(...names) { names.forEach((name) => this.values.delete(name)); }
  contains(name) { return this.values.has(name); }
  toggle(name, enabled) { enabled ? this.add(name) : this.remove(name); }
}
class Node {
  constructor(attrs = {}) { this.attrs = new Map(Object.entries(attrs)); this.hidden = false; this.listeners = new Map(); this.classList = new Classes(); this.focused = false; this.tabIndex = -1; }
  getAttribute(name) { return this.attrs.get(name) ?? null; }
  setAttribute(name, value) { this.attrs.set(name, String(value)); }
  hasAttribute(name) { return this.attrs.has(name); }
  addEventListener(type, fn) { if (!this.listeners.has(type)) this.listeners.set(type, []); this.listeners.get(type).push(fn); }
  emit(type, event = {}) { for (const fn of this.listeners.get(type) ?? []) fn({ preventDefault() {}, ...event }); }
  focus() { this.focused = true; }
  querySelector() { return null; }
}

test('enterprise CRUD remains list-first, opens exactly one requested form and returns after successful mutation', () => {
  const list = new Node({ 'data-inx-crud-list': '' });
  const editor = new Node({ 'data-inx-crud-editor': '' });
  const create = new Node({ 'data-inx-crud-form': 'create', 'data-inx-crud-title-key': 'common.new' });
  const edit = new Node({ 'data-inx-crud-form': 'edit', 'data-inx-crud-title-key': 'common.edit' });
  const newButton = new Node({ 'data-inx-crud-new': 'create', 'data-inx-crud-editor-mode': 'create' });
  const back = new Node({ 'data-inx-crud-back': '' });
  const title = new Node();
  const field = new Node(); create.querySelector = () => field; edit.querySelector = () => field;
  editor.querySelectorAll = (selector) => selector === '[data-inx-crud-form]' ? [create, edit] : [];
  editor.querySelector = (selector) => selector === '[data-inx-crud-editor-title]' ? title : null;
  const panel = new Node({ 'data-inx-crud-panel': 'example' });
  panel.querySelector = (selector) => selector === '[data-inx-crud-list]' ? list : selector === '[data-inx-crud-editor]' ? editor : selector.includes('[data-inx-crud-new]') ? newButton : null;
  panel.querySelectorAll = (selector) => selector.includes('[data-inx-crud-new]') ? [newButton] : selector === '[data-inx-crud-back]' ? [back] : [];
  const documentObject = { documentElement: { lang: 'en' } };

  const controller = wireCrudPanel(documentObject, panel);
  assert.ok(controller);
  assert.equal(panel.getAttribute('data-inx-crud-mode'), 'list');
  assert.equal(list.hidden, false);
  assert.equal(editor.hidden, true);

  newButton.emit('click');
  assert.equal(panel.getAttribute('data-inx-crud-mode'), 'form');
  assert.equal(panel.getAttribute('data-inx-crud-editor-mode'), 'create');
  assert.equal(list.hidden, true);
  assert.equal(editor.hidden, false);
  assert.equal(create.hidden, false);
  assert.equal(edit.hidden, true);
  assert.equal(field.focused, true);

  editor.emit('infranexum:form-success');
  assert.equal(panel.getAttribute('data-inx-crud-mode'), 'list');
  assert.equal(list.hidden, false);
  assert.equal(editor.hidden, true);
});

test('enterprise DataTable sorting is stable, toggles direction and keeps action headers inert', () => {
  const name = new Node(); const actions = new Node({ 'data-inx-actions-column': 'true' });
  name.setAttribute('data-inx-sortable', 'true'); name.setAttribute('aria-sort', 'none');
  actions.setAttribute('data-inx-sortable', 'false');
  const row = (text) => ({ hidden: false, children: [{ textContent: text }], marker: text });
  const a = row('Zulu'), b = row('Álpha'), c = row('Alpha');
  const tbody = { children: [a,b,c], appendChild(item) { this.children = this.children.filter((x) => x !== item); this.children.push(item); } };
  const table = { querySelector(selector) { if (selector === 'tbody') return tbody; if (selector === 'thead tr') return { children: [name, actions] }; return null; } };

  assert.equal(sortTableByColumn(table, 0, name), true);
  assert.deepEqual(tbody.children.map((item) => item.marker), ['Álpha','Alpha','Zulu']);
  assert.equal(name.getAttribute('aria-sort'), 'ascending');
  assert.equal(sortTableByColumn(table, 0, name), true);
  assert.deepEqual(tbody.children.map((item) => item.marker), ['Zulu','Álpha','Alpha']);
  assert.equal(name.getAttribute('aria-sort'), 'descending');
  assert.equal(sortTableByColumn(table, 1, actions), false);
});

test('sortable values normalize numbers, ISO dates, accents and empty cells deterministically', () => {
  assert.deepEqual(sortableValue('1 234,5'), { kind: 'number', value: 1234.5 });
  assert.equal(sortableValue('2026-08-16').kind, 'date');
  assert.deepEqual(sortableValue('Équipement'), { kind: 'text', value: 'equipement' });
  assert.deepEqual(sortableValue('—'), { kind: 'empty', value: '' });
});
