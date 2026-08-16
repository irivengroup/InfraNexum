import assert from 'node:assert/strict';
import test from 'node:test';

import {
  calendarMonthModel,
  enhanceTemporalInput,
  splitTemporalValue,
  wireTemporalRange,
} from '../../src/applications/web/public/assets/temporal-picker.mjs';

test('calendar month model is Monday-first, six weeks wide and preserves the selected date', () => {
  const model = calendarMonthModel(2026, 7, '2026-08-14');
  assert.equal(model.length, 42);
  assert.equal(model[0].iso, '2026-07-27');
  assert.equal(model.at(-1).iso, '2026-09-06');
  assert.equal(model.filter((day) => day.selected).length, 1);
  assert.equal(model.find((day) => day.selected)?.iso, '2026-08-14');
});

test('calendar handles leap years deterministically', () => {
  const leap = calendarMonthModel(2028, 1, '2028-02-29').find((day) => day.iso === '2028-02-29');
  assert.equal(leap?.inMonth, true);
  assert.equal(leap?.selected, true);
});

test('temporal values preserve local date/time syntax and reject arbitrary text', () => {
  assert.deepEqual(splitTemporalValue('2026-08-14T15:45'), { date: '2026-08-14', time: '15:45' });
  assert.deepEqual(splitTemporalValue('2026-08-14'), { date: '2026-08-14', time: '' });
  assert.deepEqual(splitTemporalValue('not-a-date'), { date: '', time: '' });
});

class RangeInput extends EventTarget {
  constructor(value = '') {
    super(); this.value = value; this.min = ''; this.max = ''; this.validationMessage = ''; this.ownerDocument = { defaultView: { Event } };
  }
  setCustomValidity(value) { this.validationMessage = value; }
}

test('period invariant allows equality but never permits end before start', () => {
  const start = new RangeInput('2026-08-15');
  const end = new RangeInput('2026-08-15');
  const range = wireTemporalRange(start, end);
  assert.equal(range.validate(), true);
  assert.equal(end.min, '2026-08-15');
  assert.equal(start.max, '2026-08-15');
  assert.equal(end.validationMessage, '');

  end.value = '2026-08-14';
  end.dispatchEvent(new Event('change'));
  assert.equal(range.validate(), false);
  assert.match(end.validationMessage, /cannot be earlier/i);

  end.value = '2026-08-16';
  end.dispatchEvent(new Event('change'));
  assert.equal(range.validate(), true);
  assert.equal(end.validationMessage, '');
});

test('date-time range comparison preserves minute precision', () => {
  const start = new RangeInput('2026-08-15T10:30');
  const end = new RangeInput('2026-08-15T10:29');
  const range = wireTemporalRange(start, end);
  assert.equal(range.validate(), false);
  end.value = '2026-08-15T10:30';
  assert.equal(range.validate(), true);
});

class FakeClassList {
  constructor() { this.values = new Set(); }
  add(...values) { values.forEach((value) => this.values.add(value)); }
  remove(...values) { values.forEach((value) => this.values.delete(value)); }
  contains(value) { return this.values.has(value); }
}
class FakeTemporalNode extends EventTarget {
  constructor(tagName, ownerDocument) {
    super(); this.tagName = tagName.toUpperCase(); this.ownerDocument = ownerDocument; this.children = []; this.attributes = new Map();
    this.classList = new FakeClassList(); this.hidden = false; this.disabled = false; this.value = ''; this.min = ''; this.max = ''; this.textContent = ''; this.id = ''; this.readOnly = false;
  }
  set className(value) { this.classList = new FakeClassList(); String(value).split(/\s+/).filter(Boolean).forEach((item) => this.classList.add(item)); }
  get className() { return [...this.classList.values].join(' '); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); if (name === 'id') this.id = String(value); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  removeAttribute(name) { this.attributes.delete(name); }
  append(...nodes) { nodes.forEach((node) => this.appendChild(node)); }
  appendChild(node) { this.children.push(node); node.parent = this; return node; }
  replaceChildren(...nodes) { this.children = []; this.append(...nodes); }
  insertAdjacentElement(_where, node) { this.adjacent = node; node.parent = this.parent; return node; }
  contains(target) { return this === target || this.children.some((child) => child.contains?.(target)); }
  focus() { this.focused = true; }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  querySelectorAll(selector) {
    const all = [];
    const visit = (node) => { for (const child of node.children ?? []) { all.push(child); visit(child); } };
    visit(this);
    if (selector === '.inx-temporal-title') return all.filter((node) => node.classList?.contains('inx-temporal-title'));
    if (selector === '.inx-temporal-years') return all.filter((node) => node.classList?.contains('inx-temporal-years'));
    if (selector.startsWith('[data-inx-day]')) return all.filter((node) => node.getAttribute?.('data-inx-day') !== null);
    return [];
  }
  set innerHTML(_value) {}
}
function temporalDocument(input) {
  const documentObject = {
    documentElement: { lang: 'en' },
    defaultView: { Event },
    createElement(tag) { return new FakeTemporalNode(tag, documentObject); },
    querySelectorAll(selector) { if (selector === 'input[data-inx-temporal]') return [input]; if (selector === 'form') return []; return []; },
    querySelector() { return null; }, addEventListener() {}, removeEventListener() {},
  };
  input.ownerDocument = documentObject;
  return documentObject;
}

test('deterministic calendar opens and exposes a fast 16-year selector', () => {
  const input = new FakeTemporalNode('input');
  input.id = 'valid-from'; input.value = '2026-08-15'; input.setAttribute('data-inx-temporal', 'date'); input.parent = new FakeTemporalNode('div');
  const documentObject = temporalDocument(input);
  const controller = enhanceTemporalInput(documentObject, input);
  controller.open();
  const wrapper = input.adjacent;
  const popover = wrapper.children[2];
  assert.equal(popover.hidden, false);
  const title = popover.querySelector('.inx-temporal-title');
  assert.ok(title, 'month/year title must be interactive');
  title.dispatchEvent(new Event('click'));
  const years = popover.querySelector('.inx-temporal-years');
  assert.ok(years, 'fast year grid must be displayed');
  assert.equal(years.children.length, 16);
  assert.equal(years.children.some((node) => node.textContent === '2026'), true);
  controller.destroy();
});
