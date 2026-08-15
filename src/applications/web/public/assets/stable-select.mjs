/**
 * Keep native HTML <select> elements as the authoritative Bootstrap 5 control.
 *
 * Earlier releases cloned every select into a JavaScript combobox/listbox. That
 * duplicate presentation layer caused pointer/focus regressions and required
 * non-Bootstrap CSS. Bootstrap's supported contract is the native select styled
 * with `form-select`, so this module now performs only idempotent normalization.
 */
export function initializeStableSelects(documentObject = document) {
  const selects = [...(documentObject?.querySelectorAll?.('select.form-select') ?? [])];
  for (const select of selects) normalizeNativeSelect(select);
  return Object.freeze({
    count: selects.length,
    sync() { for (const select of selects) normalizeNativeSelect(select); },
    destroy() {},
  });
}

export function stableSelectModel(select) {
  const options = [...(select?.options ?? [])].map((option, index) => Object.freeze({
    index,
    value: String(option.value ?? ''),
    label: String(option.textContent ?? option.label ?? option.value ?? ''),
    disabled: option.disabled === true,
    selected: option.selected === true || String(option.value ?? '') === String(select?.value ?? ''),
  }));
  const explicit = options.findIndex((option) => option.selected);
  const selectedIndex = explicit >= 0 ? explicit : 0;
  return Object.freeze({ options, selectedIndex, disabled: select?.disabled === true });
}

function normalizeNativeSelect(select) {
  select.classList?.add?.('form-select');
  select.removeAttribute?.('aria-hidden');
  select.removeAttribute?.('data-inx-select-enhanced');
  if (typeof select.tabIndex === 'number' && select.tabIndex < 0) select.tabIndex = 0;
}
