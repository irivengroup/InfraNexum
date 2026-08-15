import assert from 'node:assert/strict';
import test from 'node:test';

import { calendarMonthModel, splitTemporalValue } from '../../src/applications/web/public/assets/temporal-picker.mjs';

test('calendar month model is Monday-first, six weeks wide and preserves the selected date', () => {
  const model = calendarMonthModel(2026, 7, '2026-08-14');
  assert.equal(model.length, 42);
  assert.equal(model[0].iso, '2026-07-27');
  assert.equal(model.at(-1).iso, '2026-09-06');
  assert.equal(model.filter((day) => day.selected).length, 1);
  assert.equal(model.find((day) => day.selected)?.iso, '2026-08-14');
});

test('calendar handles leap years deterministically', () => {
  const model = calendarMonthModel(2028, 1, '2028-02-29');
  const leap = model.find((day) => day.iso === '2028-02-29');
  assert.equal(leap?.inMonth, true);
  assert.equal(leap?.selected, true);
});

test('temporal values preserve browser-local date/time syntax and reject arbitrary text', () => {
  assert.deepEqual(splitTemporalValue('2026-08-14T15:45'), { date: '2026-08-14', time: '15:45' });
  assert.deepEqual(splitTemporalValue('2026-08-14'), { date: '2026-08-14', time: '' });
  assert.deepEqual(splitTemporalValue('not-a-date'), { date: '', time: '' });
  assert.deepEqual(splitTemporalValue(''), { date: '', time: '' });
});

class FakeClassList {
  constructor(owner) { this.owner = owner; this.values = new Set(); }
  add(...names) { names.forEach((name) => this.values.add(name)); this.owner.className = [...this.values].join(' '); }
  remove(...names) { names.forEach((name) => this.values.delete(name)); this.owner.className = [...this.values].join(' '); }
  contains(name) { return this.values.has(name) || String(this.owner.className).split(/\s+/).includes(name); }
}
class FakeElement extends EventTarget {
  constructor(tagName, ownerDocument) {
    super(); this.tagName = tagName.toUpperCase(); this.ownerDocument = ownerDocument; this.attributes = new Map(); this.children = [];
    this.className = ''; this.classList = new FakeClassList(this); this.hidden = false; this.disabled = false; this.value = ''; this.readOnly = false; this.parentElement = null; this.textContent = '';
  }
  setAttribute(name, value) { this.attributes.set(name, String(value)); if (name === 'class') this.className = String(value); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  removeAttribute(name) { this.attributes.delete(name); }
  append(...nodes) { for (const node of nodes) { node.parentElement = this; this.children.push(node); } }
  appendChild(node) { this.append(node); return node; }
  replaceChildren(...nodes) { this.children = []; this.append(...nodes); }
  insertAdjacentElement(_position, node) { node.parentElement = this.parentElement; this.ownerDocument.created.push(node); return node; }
  replaceWith(node) { if (this.parentElement) this.parentElement.append(node); }
  contains(target) { return target === this || this.children.some((child) => child.contains?.(target)); }
  focus() {}
  querySelector(selector) {
    if (selector.includes('[data-inx-day]')) return flatten(this).find((node) => node.getAttribute?.('data-inx-day')) ?? null;
    return null;
  }
}
function flatten(root) { return [root, ...root.children.flatMap((child) => flatten(child))]; }
class FakeTemporalDocument extends EventTarget {
  constructor(input) {
    super(); this.input = input; this.created = []; this.documentElement = { getAttribute: () => 'en' };
    this.defaultView = { Event };
    input.ownerDocument = this;
  }
  querySelectorAll(selector) { return selector === 'input[data-inx-temporal]' ? [this.input] : []; }
  createElement(tagName) { const node = new FakeElement(tagName, this); this.created.push(node); return node; }
  querySelector() { return null; }
}

test('calendar controller opens on the real input click and commits a selected date-time', async () => {
  const input = new FakeElement('input', null);
  input.type = 'datetime-local'; input.value = ''; input.setAttribute('data-inx-temporal', 'datetime');
  const documentObject = new FakeTemporalDocument(input);
  const parent = new FakeElement('form', documentObject); input.parentElement = parent; parent.append(input);
  const { initializeTemporalPickers } = await import('../../src/applications/web/public/assets/temporal-picker.mjs');
  const controller = initializeTemporalPickers(documentObject);
  assert.equal(controller.count, 1);

  input.dispatchEvent(new Event('click', { bubbles: true }));
  const popover = documentObject.created.find((node) => node.className === 'inx-temporal-popover');
  assert.equal(popover.hidden, false, 'click must open the deterministic calendar');
  const day = flatten(popover).find((node) => node.getAttribute?.('data-inx-day') && !node.className.includes('outside'));
  assert.ok(day);
  day.dispatchEvent(new Event('click'));
  const time = flatten(popover).find((node) => node.type === 'time');
  assert.ok(time);
  time.value = '14:30'; time.dispatchEvent(new Event('input'));
  const apply = flatten(popover).find((node) => String(node.className).includes('btn-primary'));
  assert.ok(apply);
  apply.dispatchEvent(new Event('click'));
  assert.match(input.value, /^\d{4}-\d{2}-\d{2}T14:30$/);
  assert.equal(popover.hidden, true);
});
