/**
 * Accessible InfraNexum select surface backed by the native HTMLSelectElement.
 *
 * The native select remains the sole form value and validation source. A custom
 * .inx-select combobox/listbox handles pointer interaction so hardened Windows /
 * Chromium stacks cannot close the operating-system picker on mouse release.
 * The layer is idempotent and safely enhances selects added by dynamic workspaces.
 */
const ENHANCED_ATTRIBUTE = 'data-inx-select-enhanced';
const documentStates = new WeakMap();
const selectControllers = new WeakMap();
let sequence = 0;

export function initializeStableSelects(documentObject = document) {
  const state = documentState(documentObject);
  enhancePendingSelects(documentObject, state);
  state.sync();
  return state.facade;
}

export function stableSelectModel(select) {
  const options = [...(select?.options ?? [])].map((option, index) => Object.freeze({
    index,
    value: String(option.value ?? ''),
    label: String(option.textContent ?? option.label ?? option.value ?? ''),
    disabled: option.disabled === true,
    selected: option.selected === true || String(option.value ?? '') === String(select?.value ?? ''),
    group: String(option.parentElement?.tagName ?? '').toUpperCase() === 'OPTGROUP' ? String(option.parentElement?.label ?? '') : '',
  }));
  const explicit = options.findIndex((option) => option.selected);
  const selectedIndex = explicit >= 0 ? explicit : Math.max(0, options.findIndex((option) => option.value === String(select?.value ?? '')));
  return Object.freeze({ options, selectedIndex, disabled: select?.disabled === true });
}

function documentState(documentObject) {
  const existing = documentStates.get(documentObject);
  if (existing) return existing;

  const controllers = new Set();
  const closeOutside = (event) => {
    for (const controller of controllers) if (!controller.contains(event?.target)) controller.close();
  };
  const sync = () => {
    enhancePendingSelects(documentObject, state);
    for (const controller of controllers) controller.sync();
  };
  const onKeyDown = (event) => {
    if (event?.key !== 'Escape') return;
    for (const controller of controllers) controller.close();
  };
  const onFormReset = (event) => {
    if (event?.defaultPrevented === true) return;
    const schedule = documentObject?.defaultView?.queueMicrotask ?? globalThis.queueMicrotask;
    if (typeof schedule === 'function') schedule(sync);
    else setTimeout(sync, 0);
  };

  const state = {
    controllers,
    sync,
    facade: null,
  };
  state.facade = Object.freeze({
    get count() { return controllers.size; },
    sync,
    destroy() {
      documentObject?.removeEventListener?.('pointerdown', closeOutside, true);
      documentObject?.removeEventListener?.('keydown', onKeyDown, true);
      documentObject?.removeEventListener?.('reset', onFormReset, true);
      documentObject?.removeEventListener?.('infranexum:locale-change', sync);
      documentObject?.removeEventListener?.('infranexum:preferences-change', sync);
      for (const controller of [...controllers]) controller.destroy();
      controllers.clear();
      documentStates.delete(documentObject);
    },
  });

  documentObject?.addEventListener?.('pointerdown', closeOutside, true);
  documentObject?.addEventListener?.('keydown', onKeyDown, true);
  documentObject?.addEventListener?.('reset', onFormReset, true);
  documentObject?.addEventListener?.('infranexum:locale-change', sync);
  documentObject?.addEventListener?.('infranexum:preferences-change', sync);
  documentStates.set(documentObject, state);
  return state;
}

function enhancePendingSelects(documentObject, state) {
  for (const select of documentObject?.querySelectorAll?.('select.form-select') ?? []) {
    if (select.multiple === true) {
      normalizeNativeSelect(select);
      continue;
    }
    const existing = selectControllers.get(select);
    if (existing) {
      existing.sync();
      state.controllers.add(existing);
      continue;
    }
    const controller = enhanceSelect(documentObject, select, state);
    selectControllers.set(select, controller);
    state.controllers.add(controller);
  }
}

function enhanceSelect(documentObject, select, state) {
  const wrapper = documentObject.createElement('div');
  wrapper.className = 'inx-select';
  wrapper.setAttribute('data-inx-select-for', select.id || select.name || `select-${++sequence}`);

  const trigger = documentObject.createElement('button');
  trigger.type = 'button';
  trigger.className = 'inx-select-trigger';
  trigger.setAttribute('role', 'combobox');
  trigger.setAttribute('aria-haspopup', 'listbox');
  trigger.setAttribute('aria-expanded', 'false');
  trigger.setAttribute('aria-autocomplete', 'none');

  const valueNode = documentObject.createElement('span');
  valueNode.className = 'inx-select-value';
  const chevron = documentObject.createElement('span');
  chevron.className = 'inx-select-chevron';
  chevron.setAttribute('aria-hidden', 'true');
  chevron.textContent = '⌄';
  trigger.append(valueNode, chevron);

  const menu = documentObject.createElement('div');
  menu.className = 'inx-select-menu';
  menu.hidden = true;
  menu.setAttribute('role', 'listbox');
  const menuId = `inx-select-menu-${++sequence}`;
  menu.id = menuId;
  trigger.setAttribute('aria-controls', menuId);

  syncAccessibleName(documentObject, select, trigger);
  select.setAttribute(ENHANCED_ATTRIBUTE, 'true');
  select.classList?.add?.('inx-select-native');
  select.tabIndex = -1;
  select.setAttribute?.('aria-hidden', 'true');
  select.insertAdjacentElement?.('afterend', wrapper);
  wrapper.append(trigger, menu);

  let open = false;
  let activeIndex = 0;
  let optionButtons = [];
  let searchBuffer = '';
  let searchResetTimer = null;

  const optionButton = (option) => {
    const button = documentObject.createElement('button');
    button.type = 'button';
    button.className = 'inx-select-option';
    button.setAttribute('role', 'option');
    button.setAttribute('data-inx-select-index', String(option.index));
    button.setAttribute('data-inx-select-value', option.value);
    button.setAttribute('aria-selected', option.selected ? 'true' : 'false');
    button.disabled = option.disabled;
    button.textContent = option.label || '—';
    button.addEventListener?.('click', (event) => {
      event?.preventDefault?.();
      event?.stopPropagation?.();
      choose(option.index);
    });
    return button;
  };

  const updateActiveDescendant = () => {
    for (const [index, button] of optionButtons.entries()) {
      const active = index === activeIndex;
      button.classList?.toggle?.('active', active);
      button.setAttribute?.('data-active', active ? 'true' : 'false');
      if (!button.id) button.id = `${menuId}-option-${index}`;
    }
    if (open && optionButtons[activeIndex]?.id) trigger.setAttribute('aria-activedescendant', optionButtons[activeIndex].id);
    else trigger.removeAttribute?.('aria-activedescendant');
  };

  const sync = () => {
    syncAccessibleName(documentObject, select, trigger);
    const model = stableSelectModel(select);
    activeIndex = clampSelectedIndex(model);
    trigger.disabled = model.disabled;
    trigger.setAttribute('aria-disabled', model.disabled ? 'true' : 'false');
    trigger.setAttribute('aria-required', select.required === true ? 'true' : 'false');
    valueNode.textContent = model.options[activeIndex]?.label || '—';
    const menuNodes = [];
    let lastGroup = null;
    for (const option of model.options) {
      if (option.group && option.group !== lastGroup) {
        const heading = documentObject.createElement('div');
        heading.className = 'inx-select-group';
        heading.setAttribute('role', 'presentation');
        heading.textContent = option.group;
        menuNodes.push(heading);
        lastGroup = option.group;
      } else if (!option.group) lastGroup = null;
      menuNodes.push(optionButton(option));
    }
    menu.replaceChildren(...menuNodes);
    optionButtons = [...(menu.querySelectorAll?.('[data-inx-select-index]') ?? [])];
    wrapper.classList?.toggle?.('disabled', model.disabled);
    wrapper.setAttribute?.('data-inx-select-state', select.getAttribute?.('data-inx-select-state') || select.getAttribute?.('data-inx-entity-state') || (model.options.length ? 'ready' : 'empty'));
    if (model.disabled) close();
    updateActiveDescendant();
  };

  const dispatchNative = (type) => {
    const EventConstructor = documentObject?.defaultView?.Event ?? globalThis.Event;
    if (typeof EventConstructor === 'function') select.dispatchEvent?.(new EventConstructor(type, { bubbles: true }));
    else select.dispatchEvent?.({ type, bubbles: true });
  };

  const choose = (index) => {
    const model = stableSelectModel(select);
    const option = model.options[index];
    if (!option || option.disabled || select.disabled === true) return false;
    select.value = option.value;
    for (const nativeOption of select.options ?? []) nativeOption.selected = String(nativeOption.value) === option.value;
    activeIndex = index;
    trigger.classList?.remove?.('is-invalid');
    dispatchNative('input');
    dispatchNative('change');
    sync();
    close();
    trigger.focus?.({ preventScroll: true });
    return true;
  };

  const show = () => {
    if (trigger.disabled || open) return false;
    open = true;
    menu.hidden = false;
    trigger.setAttribute('aria-expanded', 'true');
    wrapper.classList?.add?.('open');
    activeIndex = clampSelectedIndex(stableSelectModel(select));
    updateActiveDescendant();
    optionButtons[activeIndex]?.scrollIntoView?.({ block: 'nearest' });
    return true;
  };

  const close = () => {
    if (!open) return false;
    open = false;
    menu.hidden = true;
    trigger.setAttribute('aria-expanded', 'false');
    wrapper.classList?.remove?.('open');
    trigger.removeAttribute?.('aria-activedescendant');
    return true;
  };

  const move = (delta) => {
    const model = stableSelectModel(select);
    if (model.options.length === 0) return;
    let index = activeIndex;
    for (let attempt = 0; attempt < model.options.length; attempt += 1) {
      index = (index + delta + model.options.length) % model.options.length;
      if (!model.options[index].disabled) {
        activeIndex = index;
        break;
      }
    }
    updateActiveDescendant();
    optionButtons[activeIndex]?.scrollIntoView?.({ block: 'nearest' });
  };

  const typeAhead = (character) => {
    const model = stableSelectModel(select);
    if (!model.options.length) return;
    const normalized = String(character ?? '').toLocaleLowerCase();
    if (!normalized) return;
    searchBuffer = `${searchBuffer}${normalized}`;
    if (searchResetTimer !== null) clearTimeout(searchResetTimer);
    searchResetTimer = setTimeout(() => { searchBuffer = ''; searchResetTimer = null; }, 650);
    const ordered = [...model.options.keys()].map((offset) => (activeIndex + 1 + offset) % model.options.length);
    const match = ordered.find((index) => !model.options[index].disabled && model.options[index].label.toLocaleLowerCase().startsWith(searchBuffer));
    if (match === undefined) return;
    activeIndex = match;
    updateActiveDescendant();
    optionButtons[activeIndex]?.scrollIntoView?.({ block: 'nearest' });
  };

  trigger.addEventListener?.('click', (event) => {
    event?.preventDefault?.();
    event?.stopPropagation?.();
    if (open) close();
    else show();
  });
  trigger.addEventListener?.('keydown', (event) => {
    if (event.key === 'ArrowDown') { event.preventDefault?.(); if (!open) show(); else move(1); }
    else if (event.key === 'ArrowUp') { event.preventDefault?.(); if (!open) show(); else move(-1); }
    else if (event.key === 'Home' && open) { event.preventDefault?.(); activeIndex = firstEnabledIndex(select); updateActiveDescendant(); }
    else if (event.key === 'End' && open) { event.preventDefault?.(); activeIndex = lastEnabledIndex(select); updateActiveDescendant(); }
    else if ((event.key === 'Enter' || event.key === ' ') && open) { event.preventDefault?.(); choose(activeIndex); }
    else if (event.key === 'Escape' && open) { event.preventDefault?.(); close(); }
    else if (event.key === 'Tab') close();
    else if (event.key?.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) { event.preventDefault?.(); if (!open) show(); typeAhead(event.key); }
  });

  const onInvalid = (event) => {
    event?.preventDefault?.();
    trigger.classList?.add?.('is-invalid');
    trigger.focus?.({ preventScroll: false });
  };
  const onNativeChange = () => { trigger.classList?.remove?.('is-invalid'); sync(); };
  select.addEventListener?.('invalid', onInvalid);
  select.addEventListener?.('change', onNativeChange);
  select.addEventListener?.('infranexum:entity-sync', sync);

  let observer = null;
  const MutationObserverConstructor = documentObject?.defaultView?.MutationObserver ?? globalThis.MutationObserver;
  if (typeof MutationObserverConstructor === 'function') {
    observer = new MutationObserverConstructor(sync);
    observer.observe(select, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'required', 'selected', 'label', 'data-inx-select-state', 'data-inx-entity-state'] });
  }

  const controller = Object.freeze({
    open: show,
    close,
    sync,
    contains: (target) => wrapper.contains?.(target) === true || target === select,
    destroy() {
      observer?.disconnect?.();
      if (searchResetTimer !== null) clearTimeout(searchResetTimer);
      select.removeEventListener?.('invalid', onInvalid);
      select.removeEventListener?.('change', onNativeChange);
      select.removeEventListener?.('infranexum:entity-sync', sync);
      select.classList?.remove?.('inx-select-native');
      select.removeAttribute?.(ENHANCED_ATTRIBUTE);
      select.removeAttribute?.('aria-hidden');
      select.tabIndex = 0;
      wrapper.remove?.();
      selectControllers.delete(select);
      state.controllers.delete(controller);
    },
  });

  sync();
  return controller;
}

function normalizeNativeSelect(select) {
  select.classList?.add?.('form-select');
  select.removeAttribute?.('aria-hidden');
  select.removeAttribute?.(ENHANCED_ATTRIBUTE);
  if (typeof select.tabIndex === 'number' && select.tabIndex < 0) select.tabIndex = 0;
}

function syncAccessibleName(documentObject, select, trigger) {
  const ariaLabel = select.getAttribute?.('aria-label');
  if (ariaLabel) trigger.setAttribute('aria-label', ariaLabel);
  else trigger.removeAttribute?.('aria-label');

  const id = select.id;
  if (!id) return;
  const label = documentObject.querySelector?.(`label[for="${cssEscape(id)}"]`);
  if (!label) return;
  if (!label.id) label.id = `inx-select-label-${++sequence}`;
  trigger.setAttribute('aria-labelledby', label.id);
}

function clampSelectedIndex(model) {
  if (!model.options.length) return 0;
  return Math.min(Math.max(model.selectedIndex, 0), model.options.length - 1);
}

function firstEnabledIndex(select) {
  const options = stableSelectModel(select).options;
  const index = options.findIndex((option) => !option.disabled);
  return index < 0 ? 0 : index;
}

function lastEnabledIndex(select) {
  const options = stableSelectModel(select).options;
  for (let index = options.length - 1; index >= 0; index -= 1) if (!options[index].disabled) return index;
  return 0;
}

function cssEscape(value) {
  if (globalThis.CSS?.escape) return globalThis.CSS.escape(value);
  return String(value).replace(/["\\]/g, '\\$&');
}
