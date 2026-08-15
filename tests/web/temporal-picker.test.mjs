import assert from 'node:assert/strict';
import test from 'node:test';
import { calendarMonthModel, enhanceTemporalInput, initializeTemporalPickers, splitTemporalValue } from '../../src/applications/web/public/assets/temporal-picker.mjs';

test('calendar utility remains Monday-first, six weeks wide and preserves the selected date', () => {
  const model = calendarMonthModel(2026, 7, '2026-08-14');
  assert.equal(model.length, 42);
  assert.equal(model[0].iso, '2026-07-27');
  assert.equal(model.at(-1).iso, '2026-09-06');
  assert.equal(model.find((day) => day.selected)?.iso, '2026-08-14');
});

test('calendar utility handles leap years deterministically', () => {
  const leap = calendarMonthModel(2028, 1, '2028-02-29').find((day) => day.iso === '2028-02-29');
  assert.equal(leap?.inMonth, true);
  assert.equal(leap?.selected, true);
});

test('temporal values preserve browser-local date/time syntax and reject arbitrary text', () => {
  assert.deepEqual(splitTemporalValue('2026-08-14T15:45'), { date: '2026-08-14', time: '15:45' });
  assert.deepEqual(splitTemporalValue('2026-08-14'), { date: '2026-08-14', time: '' });
  assert.deepEqual(splitTemporalValue('not-a-date'), { date: '', time: '' });
});

class FakeClassList { constructor() { this.values = new Set(); } add(...values) { values.forEach((v) => this.values.add(v)); } contains(v) { return this.values.has(v); } }
class FakeInput {
  constructor(kind) { this.attributes = new Map([['data-inx-temporal', kind]]); this.type = 'text'; this.readOnly = true; this.classList = new FakeClassList(); this.tabIndex = 0; this.showPickerCalls = 0; }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  removeAttribute(name) { this.attributes.delete(name); }
  showPicker() { this.showPickerCalls += 1; }
}

test('native temporal enhancement keeps the real input authoritative and creates no custom calendar DOM', () => {
  const input = new FakeInput('datetime');
  const documentObject = { querySelectorAll: (selector) => selector === 'input[data-inx-temporal]' ? [input] : [] };
  const controller = initializeTemporalPickers(documentObject);
  assert.equal(controller.count, 1);
  assert.equal(input.type, 'datetime-local');
  assert.equal(input.readOnly, false);
  assert.equal(input.classList.contains('form-control'), true);
  assert.equal(input.getAttribute('data-inx-temporal-enhanced'), 'native');
});

test('explicit open delegates to showPicker when supported without replacing native fallback', () => {
  const input = new FakeInput('date');
  const controller = enhanceTemporalInput({}, input);
  controller.open();
  assert.equal(input.type, 'date');
  assert.equal(input.showPickerCalls, 1);
  controller.destroy();
  assert.equal(input.getAttribute('data-inx-temporal-enhanced'), null);
});
