import assert from 'node:assert/strict';
import test from 'node:test';
import { stableSelectModel } from '../../src/applications/web/public/assets/stable-select.mjs';

test('stable select model preserves the native select value as the authoritative form value', () => {
  const select = {
    value: 'SUBDIVISION', disabled: false,
    options: [
      { value: 'ORGANIZATION', textContent: 'Organization', disabled: false, selected: false },
      { value: 'SUBDIVISION', textContent: 'Subdivision', disabled: false, selected: true },
      { value: 'PLATFORM', textContent: 'Platform', disabled: true, selected: false },
    ],
  };
  const model = stableSelectModel(select);
  assert.equal(model.selectedIndex, 1);
  assert.equal(model.options[1].label, 'Subdivision');
  assert.equal(model.options[2].disabled, true);
  assert.equal(model.disabled, false);
});

test('stable select model follows a programmatic native value even when selected flags are stale', () => {
  const select = {
    value: 'GROUP', disabled: true,
    options: [
      { value: 'USER', textContent: 'USER', disabled: false, selected: true },
      { value: 'GROUP', textContent: 'GROUP', disabled: false, selected: false },
    ],
  };
  const model = stableSelectModel(select);
  assert.equal(model.selectedIndex, 0, 'selected flags remain authoritative when explicitly present');
  select.options[0].selected = false;
  assert.equal(stableSelectModel(select).selectedIndex, 1, 'native value is used when no option is marked selected');
  assert.equal(stableSelectModel(select).disabled, true);
});
