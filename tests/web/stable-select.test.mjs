import assert from 'node:assert/strict';
import test from 'node:test';
import { initializeStableSelects, stableSelectModel } from '../../src/applications/web/public/assets/stable-select.mjs';

class FakeClassList {
  constructor(values = []) { this.values = new Set(values); }
  add(...values) { for (const value of values) this.values.add(value); }
  remove(...values) { for (const value of values) this.values.delete(value); }
  contains(value) { return this.values.has(value); }
  toggle(value, force) {
    const enabled = force === undefined ? !this.values.has(value) : Boolean(force);
    if (enabled) this.values.add(value); else this.values.delete(value);
    return enabled;
  }
}

class FakeNode {
  constructor(tagName = 'div') {
    this.tagName = tagName.toUpperCase(); this.attributes = new Map(); this.children = [];
    this.listeners = new Map(); this.classList = new FakeClassList(); this.hidden = false; this.disabled = false;
    this.textContent = ''; this.id = ''; this.type = ''; this.tabIndex = 0; this.focused = false;
  }
  set className(value) { this.classList = new FakeClassList(String(value).split(/\s+/).filter(Boolean)); }
  get className() { return [...this.classList.values].join(' '); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); if (name === 'id') this.id = String(value); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  removeAttribute(name) { this.attributes.delete(name); }
  append(...nodes) { this.children.push(...nodes); for (const node of nodes) node.parent = this; }
  replaceChildren(...nodes) { this.children = []; this.append(...nodes); }
  addEventListener(type, listener) { const list = this.listeners.get(type) ?? []; list.push(listener); this.listeners.set(type, list); }
  removeEventListener(type, listener) { this.listeners.set(type, (this.listeners.get(type) ?? []).filter((item) => item !== listener)); }
  dispatchEvent(event) { event.target ??= this; for (const listener of this.listeners.get(event.type) ?? []) listener(event); return true; }
  querySelectorAll(selector) {
    if (selector === '[data-inx-select-index]') return this.children.filter((node) => node.getAttribute?.('data-inx-select-index') !== null);
    return [];
  }
  insertAdjacentElement(_position, node) { this.adjacent = node; node.parent = this.parent; return node; }
  contains(target) { return this === target || this.children.some((child) => child.contains?.(target)); }
  focus() { this.focused = true; }
  scrollIntoView() {}
  remove() { this.removed = true; }
}

function fakeDocument(select) {
  const documentListeners = new Map();
  const label = new FakeNode('label');
  label.setAttribute('for', select.id);
  const documentObject = {
    defaultView: { Event: class { constructor(type, init = {}) { this.type = type; this.bubbles = init.bubbles; } } },
    createElement: (tagName) => new FakeNode(tagName),
    querySelectorAll: (selector) => selector === 'select.form-select' ? [select] : [],
    querySelector: (selector) => selector === `label[for="${select.id}"]` ? label : null,
    addEventListener(type, listener) { const list = documentListeners.get(type) ?? []; list.push(listener); documentListeners.set(type, list); },
    removeEventListener(type, listener) { documentListeners.set(type, (documentListeners.get(type) ?? []).filter((item) => item !== listener)); },
    emit(type, event) { for (const listener of documentListeners.get(type) ?? []) listener(event); },
  };
  return documentObject;
}

function fakeSelect() {
  const select = new FakeNode('select');
  select.id = 'scope-select'; select.name = 'scopeKind'; select.value = 'ORGANIZATION'; select.required = true;
  select.classList.add('form-select'); select.parent = new FakeNode('div');
  select.options = [
    Object.assign(new FakeNode('option'), { value: 'ORGANIZATION', textContent: 'Organization', selected: true, disabled: false }),
    Object.assign(new FakeNode('option'), { value: 'SUBDIVISION', textContent: 'Subdivision', selected: false, disabled: false }),
    Object.assign(new FakeNode('option'), { value: 'PLATFORM', textContent: 'Platform', selected: false, disabled: false }),
  ];
  return select;
}

function pointerEvent(target) {
  return { target, prevented: false, stopped: false, preventDefault() { this.prevented = true; }, stopPropagation() { this.stopped = true; } };
}

test('stable select model preserves the native select value as the authoritative form value', () => {
  const select = fakeSelect();
  const model = stableSelectModel(select);
  assert.equal(model.selectedIndex, 0);
  assert.equal(model.options[1].label, 'Subdivision');
  assert.equal(model.disabled, false);
});

test('stable select model follows the native value when explicit selected flags are cleared', () => {
  const select = fakeSelect();
  select.value = 'SUBDIVISION';
  for (const option of select.options) option.selected = false;
  assert.equal(stableSelectModel(select).selectedIndex, 1);
});

test('custom select remains open across pointer release and closes only after explicit option choice', () => {
  const select = fakeSelect();
  const documentObject = fakeDocument(select);
  const changes = [];
  select.addEventListener('input', () => changes.push('input'));
  select.addEventListener('change', () => changes.push('change'));

  const controller = initializeStableSelects(documentObject);
  assert.equal(controller.count, 1);
  assert.equal(select.getAttribute('data-inx-select-enhanced'), 'true');
  assert.equal(select.classList.contains('inx-select-native'), true);

  const wrapper = select.adjacent;
  const [trigger, menu] = wrapper.children;
  documentObject.emit('pointerdown', pointerEvent(trigger));
  trigger.dispatchEvent({ type: 'click', ...pointerEvent(trigger) });
  assert.equal(trigger.getAttribute('aria-expanded'), 'true');
  assert.equal(menu.hidden, false, 'mouseup/click must not immediately close the menu');

  const secondOption = menu.children[1];
  secondOption.dispatchEvent({ type: 'click', ...pointerEvent(secondOption) });
  assert.equal(select.value, 'SUBDIVISION');
  assert.deepEqual(changes, ['input', 'change']);
  assert.equal(trigger.getAttribute('aria-expanded'), 'false');
  assert.equal(menu.hidden, true);
  assert.equal(trigger.focused, true);
  controller.destroy();
});

test('outside pointerdown closes an open custom select while inside pointerdown does not', () => {
  const select = fakeSelect();
  const documentObject = fakeDocument(select);
  const controller = initializeStableSelects(documentObject);
  const wrapper = select.adjacent;
  const [trigger, menu] = wrapper.children;
  trigger.dispatchEvent({ type: 'click', ...pointerEvent(trigger) });
  assert.equal(menu.hidden, false);
  documentObject.emit('pointerdown', pointerEvent(menu.children[0]));
  assert.equal(menu.hidden, false);
  documentObject.emit('pointerdown', pointerEvent(new FakeNode('div')));
  assert.equal(menu.hidden, true);
  controller.destroy();
});

test('keyboard typeahead navigates visible options and commits through the native select', () => {
  const select = fakeSelect();
  const documentObject = fakeDocument(select);
  const controller = initializeStableSelects(documentObject);
  const [trigger, menu] = select.adjacent.children;
  trigger.dispatchEvent({ type: 'keydown', key: 's', ctrlKey: false, metaKey: false, altKey: false, preventDefault() {} });
  assert.equal(trigger.getAttribute('aria-expanded'), 'true');
  assert.equal(menu.children[1].getAttribute('data-active'), 'true');
  trigger.dispatchEvent({ type: 'keydown', key: 'Enter', preventDefault() {} });
  assert.equal(select.value, 'SUBDIVISION');
  controller.destroy();
});

test('required native validation is redirected to the visible combobox trigger', () => {
  const select = fakeSelect();
  const documentObject = fakeDocument(select);
  const controller = initializeStableSelects(documentObject);
  const trigger = select.adjacent.children[0];
  const event = { type: 'invalid', prevented: false, preventDefault() { this.prevented = true; } };
  select.dispatchEvent(event);
  assert.equal(event.prevented, true);
  assert.equal(trigger.classList.contains('is-invalid'), true);
  assert.equal(trigger.focused, true);
  controller.destroy();
});

test('form reset resynchronizes the visible combobox after the native select resets', async () => {
  const select = fakeSelect();
  const documentObject = fakeDocument(select);
  const controller = initializeStableSelects(documentObject);
  const [trigger] = select.adjacent.children;
  const valueNode = trigger.children[0];
  assert.equal(valueNode.textContent, 'Organization');

  select.value = 'SUBDIVISION';
  for (const option of select.options) option.selected = option.value === 'SUBDIVISION';
  documentObject.emit('reset', { target: select.parent, defaultPrevented: false });
  await new Promise((resolve) => queueMicrotask(resolve));
  assert.equal(valueNode.textContent, 'Subdivision');
  controller.destroy();
});

test('multiple selects remain native because their list semantics differ from a combobox', () => {
  const select = fakeSelect();
  select.multiple = true;
  const documentObject = fakeDocument(select);
  const controller = initializeStableSelects(documentObject);
  assert.equal(controller.count, 0);
  assert.equal(select.adjacent, undefined);
  assert.equal(select.classList.contains('form-select'), true);
  controller.destroy();
});
