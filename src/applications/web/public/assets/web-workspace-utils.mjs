import { localeFromDocument, translate } from './i18n.mjs';

/** Creates a non-secret idempotency token suitable for browser mutation headers. */
export function idempotencyKey(prefix = 'web') {
  const uuid = globalThis.crypto?.randomUUID?.() ?? fallbackUuid();
  return `${prefix}:${uuid}`;
}

export function listItems(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.items)) return payload.items;
  return [];
}

export function clean(value) { return value === null || value === undefined ? '' : String(value).trim(); }
export function nullable(value) { const normalized = clean(value); return normalized || null; }
export function lines(value) { return clean(value).split(/\r?\n/).map((item) => item.trim()).filter(Boolean); }
export function csv(value) { return clean(value).split(',').map((item) => item.trim()).filter(Boolean); }
export function numberValue(value, field) {
  const number = Number(value);
  if (!Number.isFinite(number)) throw new TypeError(`${field} must be numeric`);
  return number;
}
export function integerValue(value, field, min = 1) {
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number < min) throw new TypeError(`${field} must be an integer >= ${min}`);
  return number;
}
export function parseJsonObject(value, field = 'JSON') {
  let parsed;
  try { parsed = JSON.parse(clean(value)); } catch { throw new TypeError(`${field} must contain valid JSON`); }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new TypeError(`${field} must contain a JSON object`);
  return parsed;
}
export function parseJsonArray(value, field = 'JSON') {
  let parsed;
  const normalized = clean(value);
  if (!normalized) return [];
  try { parsed = JSON.parse(normalized); } catch { throw new TypeError(`${field} must contain valid JSON`); }
  if (!Array.isArray(parsed)) throw new TypeError(`${field} must contain a JSON array`);
  return parsed;
}
export function checkedValues(form, name) {
  return [...(form?.querySelectorAll?.(`input[name="${name}"]:checked`) ?? [])].map((input) => input.value);
}
export function selectedValues(select) { return [...(select?.selectedOptions ?? [])].map((option) => option.value).filter(Boolean); }
export function field(form, name) {
  const element = form?.elements?.namedItem?.(name) ?? form?.querySelector?.(`[name="${name}"]`);
  return clean(element?.value);
}
export function optionalField(form, name) { return nullable(field(form, name)); }

export function setWorkspaceStatus(documentObject, id, key, state = 'info', parameters = {}) {
  const element = documentObject?.getElementById?.(id);
  if (!element) return;
  const contextual = Object.freeze({ info: 'info', success: 'success', warning: 'warning', error: 'danger' });
  element.textContent = translate(localeFromDocument(documentObject), key, parameters);
  element.className = `alert alert-${contextual[state] ?? 'info'} py-2`;
  element.setAttribute?.('data-state', state);
  element.setAttribute?.('data-i18n-dynamic', key);
  element.setAttribute?.('data-i18n-params', JSON.stringify(parameters));
}

export function fillSelect(documentObject, select, items, {
  placeholderKey = 'entity.choose',
  emptyKey = 'common.emptyList',
  value = (item) => item.id,
  label = (item) => item.displayName ?? item.code ?? item.id,
  preserve = true,
  selectFirst = false,
  disabled = false,
} = {}) {
  if (!select) return '';
  const source = Array.isArray(items) ? items : [];
  const previousValues = select.multiple
    ? new Set(preserve ? selectedValues(select) : [])
    : new Set(preserve && clean(select.value) ? [clean(select.value)] : []);
  const options = [];

  if (!select.multiple) {
    const placeholder = documentObject.createElement('option');
    placeholder.value = '';
    placeholder.textContent = translate(localeFromDocument(documentObject), source.length ? placeholderKey : emptyKey);
    placeholder.disabled = select.required === true && source.length > 0;
    placeholder.selected = true;
    options.push(placeholder);
  }

  for (const item of source) {
    const option = documentObject.createElement('option');
    option.value = clean(value(item));
    option.textContent = clean(label(item)) || option.value;
    option.selected = previousValues.has(option.value);
    options.push(option);
  }

  select.replaceChildren(...options);
  if (!select.multiple) {
    const previous = [...previousValues][0] ?? '';
    if (previous && options.some((option) => option.value === previous)) select.value = previous;
    else if (selectFirst && source[0]) select.value = clean(value(source[0]));
    else select.value = '';
  }
  select.disabled = disabled === true;
  select.setAttribute?.('data-inx-select-state', disabled ? 'disabled' : source.length ? 'ready' : 'empty');
  select.setAttribute?.('aria-disabled', select.disabled ? 'true' : 'false');
  notifySelectVisual(documentObject, select);
  return clean(select.value);
}


function notifySelectVisual(documentObject, select) {
  const EventConstructor = documentObject?.defaultView?.Event ?? globalThis.Event;
  if (typeof EventConstructor === 'function') {
    select.dispatchEvent?.(new EventConstructor('infranexum:entity-sync', { bubbles: false }));
  } else {
    select.dispatchEvent?.({ type: 'infranexum:entity-sync', bubbles: false });
  }
}

export function replaceRows(documentObject, tbody, rows, cellValues, onSelect) {
  if (!tbody) return;
  if (!rows.length) {
    const tr = documentObject.createElement('tr'); const td = documentObject.createElement('td');
    td.colSpan = Math.max(1, cellValues.length); td.className = 'text-body-secondary text-center py-4'; td.textContent = translate(localeFromDocument(documentObject), 'common.emptyList');
    tr.appendChild(td); tbody.replaceChildren(tr); return;
  }
  const nodes = rows.map((row) => {
    const tr = documentObject.createElement('tr'); tr.tabIndex = 0; tr.setAttribute('data-row-id', clean(row.id));
    for (const cellValue of cellValues) { const td = documentObject.createElement('td'); td.textContent = clean(cellValue(row)) || '—'; tr.appendChild(td); }
    const choose = () => onSelect?.(row, tr);
    tr.addEventListener?.('click', choose);
    tr.addEventListener?.('keydown', (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault?.(); choose(); } });
    return tr;
  });
  tbody.replaceChildren(...nodes);
}

export function bindTabSet(documentObject, selector, panelSelector, dataAttribute) {
  const tabs = [...(documentObject.querySelectorAll?.(selector) ?? [])];
  const panels = [...(documentObject.querySelectorAll?.(panelSelector) ?? [])];
  const panelAttribute = dataAttribute.replace('tab', 'panel');
  const activate = (value, focus = false) => {
    const target = String(value ?? '');
    const targetTab = tabs.find((tab) => tab.getAttribute(dataAttribute) === target && !tab.hidden && !tab.disabled);
    if (!targetTab) return false;
    for (const tab of tabs) {
      const active = tab === targetTab;
      tab.classList?.toggle?.('active', active);
      tab.setAttribute?.('aria-selected', active ? 'true' : 'false');
      tab.tabIndex = active ? 0 : -1;
    }
    for (const panel of panels) {
      const active = panel.getAttribute(panelAttribute) === target;
      panel.hidden = !active;
      panel.setAttribute?.('aria-hidden', active ? 'false' : 'true');
      panel.classList?.toggle?.('active', active);
      panel.classList?.toggle?.('show', active);
    }
    if (focus) targetTab.focus?.();
    return true;
  };
  const enabledTabs = () => tabs.filter((tab) => !tab.hidden && !tab.disabled);
  for (const tab of tabs) {
    tab.addEventListener?.('click', () => activate(tab.getAttribute(dataAttribute)));
    tab.addEventListener?.('keydown', (event) => {
      if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
      const available = enabledTabs();
      const current = available.indexOf(tab);
      if (current < 0 || available.length === 0) return;
      event.preventDefault?.();
      let next = current;
      if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = available.length - 1;
      else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (current - 1 + available.length) % available.length;
      else next = (current + 1) % available.length;
      activate(available[next].getAttribute(dataAttribute), true);
    });
  }
  const initial = tabs.find((tab) => tab.getAttribute('aria-selected') === 'true' && !tab.hidden && !tab.disabled) ?? enabledTabs()[0];
  if (initial) activate(initial.getAttribute(dataAttribute));
  return Object.freeze({ activate });
}

export async function organizationDirectory(configuration, fetchFunction = fetch) {
  const response = await fetchFunction(`${configuration.apiBaseUrl}/v1/iam/organizations?limit=200`, { headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new Error(`Organization directory returned HTTP ${response.status}`);
  const payload = await response.json(); return listItems(payload);
}

export async function subdivisionDirectory(configuration, organizationId, fetchFunction = fetch) {
  if (!organizationId) return [];
  const response = await fetchFunction(`${configuration.apiBaseUrl}/v1/iam/organizations/${encodeURIComponent(organizationId)}/subdivisions?limit=200`, { headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new Error(`Subdivision directory returned HTTP ${response.status}`);
  return listItems(await response.json());
}

export async function userDirectory(configuration, fetchFunction = fetch) {
  const response = await fetchFunction(`${configuration.apiBaseUrl}/v1/iam/users?limit=200`, { headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) return [];
  return listItems(await response.json());
}

function fallbackUuid() {
  const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40; bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.map((byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
