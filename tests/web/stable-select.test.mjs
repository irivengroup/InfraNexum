import assert from 'node:assert/strict';
import test from 'node:test';
import { initializeStableSelects, stableSelectModel } from '../../src/applications/web/public/assets/stable-select.mjs';

test('stable select model preserves the native select value as the authoritative form value', () => {
  const select = { value: 'SUBDIVISION', disabled: false, options: [
    { value: 'ORGANIZATION', textContent: 'Organization', disabled: false, selected: false },
    { value: 'SUBDIVISION', textContent: 'Subdivision', disabled: false, selected: true },
    { value: 'PLATFORM', textContent: 'Platform', disabled: true, selected: false },
  ] };
  const model = stableSelectModel(select);
  assert.equal(model.selectedIndex, 1);
  assert.equal(model.options[1].label, 'Subdivision');
  assert.equal(model.options[2].disabled, true);
});

test('stable select model follows the native value when no option selected flag is authoritative', () => {
  const select = { value: 'GROUP', disabled: true, options: [
    { value: 'USER', textContent: 'USER', disabled: false, selected: true },
    { value: 'GROUP', textContent: 'GROUP', disabled: false, selected: false },
  ] };
  assert.equal(stableSelectModel(select).selectedIndex, 0);
  select.options[0].selected = false;
  assert.equal(stableSelectModel(select).selectedIndex, 1);
  assert.equal(stableSelectModel(select).disabled, true);
});

test('initializer leaves each native Bootstrap form-select visible, focusable and uncloned', () => {
  const removed = [];
  const classes = new Set(['form-select']);
  const select = {
    tabIndex: -1,
    classList: { add: (...values) => values.forEach((value) => classes.add(value)) },
    removeAttribute: (name) => removed.push(name),
  };
  const documentObject = { querySelectorAll: (selector) => selector === 'select.form-select' ? [select] : [] };
  const controller = initializeStableSelects(documentObject);
  assert.equal(controller.count, 1);
  assert.equal(select.tabIndex, 0);
  assert.equal(classes.has('form-select'), true);
  assert.deepEqual(removed.sort(), ['aria-hidden', 'data-inx-select-enhanced']);
});
