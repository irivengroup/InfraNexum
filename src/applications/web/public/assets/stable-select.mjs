/**
 * Deterministic, theme-native replacement for browser select popups.
 *
 * InfraNexum keeps the real <select> in the form so FormData, validation and
 * existing change handlers remain authoritative. Pointer/keyboard interaction
 * is handled by an accessible combobox/listbox surface, avoiding platform popup
 * behaviour that can close on mouse release in hardened/embedded Chromium.
 */

const ENHANCED_ATTRIBUTE = 'data-inx-select-enhanced';
let sequence = 0;

export function initializeStableSelects(documentObject = document) {
  const controllers = [];
  for (const select of documentObject?.querySelectorAll?.('select.form-select') ?? []) {
    if (select.multiple || select.getAttribute?.(ENHANCED_ATTRIBUTE) === 'true') continue;
    controllers.push(enhanceSelect(documentObject, select));
  }

  const closeAll = (event) => {
    for (const controller of controllers) {
      if (!controller.contains(event?.target)) controller.close();
    }
  };
  documentObject?.addEventListener?.('pointerdown', closeAll);
  documentObject?.addEventListener?.('infranexum:locale-change', () => controllers.forEach((controller) => controller.sync()));
  documentObject?.addEventListener?.('infranexum:preferences-change', () => controllers.forEach((controller) => controller.sync()));

  return Object.freeze({
    count: controllers.length,
    sync: () => controllers.forEach((controller) => controller.sync()),
    destroy() {
      documentObject?.removeEventListener?.('pointerdown', closeAll);
      controllers.forEach((controller) => controller.destroy());
    },
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
  const selectedIndex = Math.max(0, options.findIndex((option) => option.selected));
  return Object.freeze({ options, selectedIndex, disabled: select?.disabled === true });
}

function enhanceSelect(documentObject, select) {
  const wrapper = documentObject.createElement('div');
  wrapper.className = 'inx-stable-select';
  wrapper.setAttribute('data-inx-select-for', select.id || select.name || `select-${++sequence}`);

  const trigger = documentObject.createElement('button');
  trigger.type = 'button';
  trigger.className = 'inx-stable-select-trigger';
  trigger.setAttribute('role', 'combobox');
  trigger.setAttribute('aria-haspopup', 'listbox');
  trigger.setAttribute('aria-expanded', 'false');
  trigger.autocomplete = 'off';

  const valueNode = documentObject.createElement('span');
  valueNode.className = 'inx-stable-select-value';
  const chevron = documentObject.createElement('span');
  chevron.className = 'inx-stable-select-chevron';
  chevron.setAttribute('aria-hidden', 'true');
  chevron.textContent = '⌄';
  trigger.append(valueNode, chevron);

  const menu = documentObject.createElement('div');
  menu.className = 'inx-stable-select-menu';
  menu.hidden = true;
  menu.setAttribute('role', 'listbox');
  const menuId = `inx-select-menu-${++sequence}`;
  menu.id = menuId;
  trigger.setAttribute('aria-controls', menuId);

  copyAccessibleName(documentObject, select, trigger);
  select.setAttribute(ENHANCED_ATTRIBUTE, 'true');
  select.classList?.add?.('inx-stable-select-native');
  select.tabIndex = -1;
  select.setAttribute?.('aria-hidden', 'true');
  select.insertAdjacentElement?.('afterend', wrapper);
  wrapper.append(trigger, menu);

  let open = false;
  let activeIndex = 0;
  let optionButtons = [];

  const sync = () => {
    syncAccessibleName(documentObject, select, trigger);
    const model = stableSelectModel(select);
    activeIndex = model.selectedIndex;
    trigger.disabled = model.disabled;
    trigger.setAttribute('aria-disabled', model.disabled ? 'true' : 'false');
    valueNode.textContent = model.options[model.selectedIndex]?.label || '—';
    menu.replaceChildren(...model.options.map((option) => optionButton(option)));
    optionButtons = [...(menu.querySelectorAll?.('[data-inx-select-index]') ?? [])];
    updateActiveDescendant();
  };

  const optionButton = (option) => {
    const button = documentObject.createElement('button');
    button.type = 'button';
    button.className = 'inx-stable-select-option';
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

  const dispatchChange = () => {
    const EventConstructor = documentObject?.defaultView?.Event ?? globalThis.Event;
    if (typeof EventConstructor === 'function') select.dispatchEvent?.(new EventConstructor('change', { bubbles: true }));
    else select.dispatchEvent?.({ type: 'change', bubbles: true });
  };

  const choose = (index) => {
    const model = stableSelectModel(select);
    const option = model.options[index];
    if (!option || option.disabled) return false;
    select.value = option.value;
    for (const nativeOption of select.options ?? []) nativeOption.selected = String(nativeOption.value) === option.value;
    activeIndex = index;
    dispatchChange();
    sync();
    close();
    trigger.focus?.({ preventScroll: true });
    return true;
  };

  const show = () => {
    if (trigger.disabled || open) return;
    open = true;
    menu.hidden = false;
    trigger.setAttribute('aria-expanded', 'true');
    wrapper.classList?.add?.('open');
    updateActiveDescendant();
  };
  const close = () => {
    if (!open) return;
    open = false;
    menu.hidden = true;
    trigger.setAttribute('aria-expanded', 'false');
    trigger.removeAttribute?.('aria-activedescendant');
    wrapper.classList?.remove?.('open');
  };
  const move = (delta) => {
    const model = stableSelectModel(select);
    if (model.options.length === 0) return;
    let index = activeIndex;
    for (let attempt = 0; attempt < model.options.length; attempt += 1) {
      index = (index + delta + model.options.length) % model.options.length;
      if (!model.options[index].disabled) { activeIndex = index; break; }
    }
    updateActiveDescendant();
    optionButtons[activeIndex]?.scrollIntoView?.({ block: 'nearest' });
  };
  const updateActiveDescendant = () => {
    for (const [index, button] of optionButtons.entries()) {
      const active = index === activeIndex;
      button.classList?.toggle?.('active', active);
      button.setAttribute?.('data-active', active ? 'true' : 'false');
      if (!button.id) button.id = `${menuId}-option-${index}`;
    }
    if (open && optionButtons[activeIndex]?.id) trigger.setAttribute('aria-activedescendant', optionButtons[activeIndex].id);
  };

  trigger.addEventListener?.('click', (event) => {
    event?.preventDefault?.();
    event?.stopPropagation?.();
    if (open) close(); else show();
  });
  trigger.addEventListener?.('keydown', (event) => {
    if (event.key === 'ArrowDown') { event.preventDefault?.(); if (!open) show(); else move(1); }
    else if (event.key === 'ArrowUp') { event.preventDefault?.(); if (!open) show(); else move(-1); }
    else if (event.key === 'Home' && open) { event.preventDefault?.(); activeIndex = firstEnabledIndex(select); updateActiveDescendant(); }
    else if (event.key === 'End' && open) { event.preventDefault?.(); activeIndex = lastEnabledIndex(select); updateActiveDescendant(); }
    else if ((event.key === 'Enter' || event.key === ' ') && open) { event.preventDefault?.(); choose(activeIndex); }
    else if (event.key === 'Escape' && open) { event.preventDefault?.(); close(); }
    else if (event.key === 'Tab') close();
  });
  select.addEventListener?.('change', sync);
  select.addEventListener?.('infranexum:entity-sync', sync);

  let observer = null;
  const MutationObserverConstructor = documentObject?.defaultView?.MutationObserver ?? globalThis.MutationObserver;
  if (typeof MutationObserverConstructor === 'function') {
    observer = new MutationObserverConstructor(sync);
    observer.observe(select, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'selected', 'label'] });
  }

  sync();
  return Object.freeze({
    open: show,
    close,
    sync,
    contains: (target) => wrapper.contains?.(target) === true || target === select,
    destroy() {
      observer?.disconnect?.();
      select.removeEventListener?.('change', sync);
      select.removeEventListener?.('infranexum:entity-sync', sync);
      select.classList?.remove?.('inx-stable-select-native');
      select.removeAttribute?.(ENHANCED_ATTRIBUTE);
      select.removeAttribute?.('aria-hidden');
      select.tabIndex = 0;
      wrapper.remove?.();
    },
  });
}

function copyAccessibleName(documentObject, select, trigger) {
  syncAccessibleName(documentObject, select, trigger);
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
