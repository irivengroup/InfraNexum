import { localeFromDocument, translate } from './i18n.mjs';

const ENHANCED = 'data-inx-temporal-enhanced';
const DAY_MS = 86_400_000;
let sequence = 0;

/**
 * Theme-native calendar/date-time picker synchronized with the real form input.
 * It avoids depending on platform-specific native picker popups while preserving
 * FormData, constraint validation and the Server-side timezone contract.
 */
export function initializeTemporalPickers(documentObject = document) {
  const controllers = [];
  for (const input of documentObject?.querySelectorAll?.('input[data-inx-temporal]') ?? []) {
    if (input.getAttribute?.(ENHANCED) === 'true') continue;
    controllers.push(enhanceTemporalInput(documentObject, input));
  }
  const closeOutside = (event) => {
    for (const controller of controllers) if (!controller.contains(event?.target)) controller.close();
  };
  documentObject?.addEventListener?.('pointerdown', closeOutside);
  documentObject?.addEventListener?.('infranexum:locale-change', () => controllers.forEach((controller) => controller.render()));
  return Object.freeze({
    count: controllers.length,
    closeAll: () => controllers.forEach((controller) => controller.close()),
    destroy() {
      documentObject?.removeEventListener?.('pointerdown', closeOutside);
      controllers.forEach((controller) => controller.destroy());
    },
  });
}

export function calendarMonthModel(year, monthIndex, selectedIsoDate = '') {
  if (!Number.isInteger(year) || !Number.isInteger(monthIndex) || monthIndex < 0 || monthIndex > 11) throw new TypeError('calendar year/month is invalid');
  const first = Date.UTC(year, monthIndex, 1);
  const mondayOffset = (new Date(first).getUTCDay() + 6) % 7;
  const start = first - mondayOffset * DAY_MS;
  const selected = parseIsoDate(selectedIsoDate);
  return Object.freeze(Array.from({ length: 42 }, (_, index) => {
    const date = new Date(start + index * DAY_MS);
    const iso = `${date.getUTCFullYear().toString().padStart(4, '0')}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-${String(date.getUTCDate()).padStart(2, '0')}`;
    return Object.freeze({
      iso,
      day: date.getUTCDate(),
      inMonth: date.getUTCMonth() === monthIndex,
      selected: selected === iso,
    });
  }));
}

export function splitTemporalValue(value) {
  const normalized = String(value ?? '').trim();
  if (!normalized) return Object.freeze({ date: '', time: '' });
  const match = normalized.match(/^(\d{4}-\d{2}-\d{2})(?:T(\d{2}:\d{2})(?::\d{2}(?:\.\d{1,9})?)?)?$/);
  if (!match) return Object.freeze({ date: '', time: '' });
  return Object.freeze({ date: match[1], time: match[2] ?? '' });
}

export function enhanceTemporalInput(documentObject, input) {
  const kind = input.getAttribute('data-inx-temporal') === 'date' ? 'date' : 'datetime';
  const wrapper = documentObject.createElement('div');
  wrapper.className = 'inx-temporal-control';
  const trigger = documentObject.createElement('button');
  trigger.type = 'button';
  trigger.className = 'inx-temporal-trigger';
  trigger.setAttribute('aria-haspopup', 'dialog');
  trigger.setAttribute('aria-expanded', 'false');
  trigger.innerHTML = '<span aria-hidden="true">▦</span>';
  const popover = documentObject.createElement('div');
  popover.className = 'inx-temporal-popover';
  popover.hidden = true;
  popover.setAttribute('role', 'dialog');
  popover.setAttribute('aria-modal', 'false');
  popover.id = `inx-temporal-popover-${++sequence}`;
  trigger.setAttribute('aria-controls', popover.id);

  input.setAttribute(ENHANCED, 'true');
  input.classList?.add?.('inx-temporal-native');
  input.readOnly = true;
  input.insertAdjacentElement?.('afterend', wrapper);
  wrapper.append(input, trigger, popover);

  let open = false;
  let view = initialView(input.value);
  let draft = splitTemporalValue(input.value);

  const openPicker = () => {
    if (input.disabled || open) return;
    draft = splitTemporalValue(input.value);
    view = initialView(draft.date || input.value);
    open = true;
    popover.hidden = false;
    wrapper.classList?.add?.('open');
    trigger.setAttribute('aria-expanded', 'true');
    render();
    popover.querySelector?.('[data-inx-day][aria-selected="true"], [data-inx-day]')?.focus?.({ preventScroll: true });
  };
  const close = () => {
    if (!open) return;
    open = false;
    popover.hidden = true;
    wrapper.classList?.remove?.('open');
    trigger.setAttribute('aria-expanded', 'false');
  };
  const commit = () => {
    if (!draft.date) { setValue(input, ''); close(); return; }
    if (kind === 'datetime' && !draft.time) return;
    setValue(input, kind === 'date' ? draft.date : `${draft.date}T${draft.time}`);
    close();
    trigger.focus?.({ preventScroll: true });
  };
  const render = () => {
    const locale = localeFromDocument(documentObject);
    popover.replaceChildren();
    const header = documentObject.createElement('div');
    header.className = 'inx-temporal-header';
    const previous = button(documentObject, '‹', translate(locale, 'temporal.previousMonth'));
    const title = documentObject.createElement('strong');
    title.textContent = monthTitle(locale, view.year, view.month);
    const next = button(documentObject, '›', translate(locale, 'temporal.nextMonth'));
    previous.addEventListener?.('click', () => { view = shiftMonth(view, -1); render(); });
    next.addEventListener?.('click', () => { view = shiftMonth(view, 1); render(); });
    header.append(previous, title, next);

    const weekdays = documentObject.createElement('div');
    weekdays.className = 'inx-temporal-weekdays';
    for (const label of weekdayLabels(locale)) {
      const node = documentObject.createElement('span');
      node.textContent = label;
      weekdays.appendChild(node);
    }

    const grid = documentObject.createElement('div');
    grid.className = 'inx-temporal-grid';
    grid.setAttribute('role', 'grid');
    for (const day of calendarMonthModel(view.year, view.month, draft.date)) {
      const cell = documentObject.createElement('button');
      cell.type = 'button';
      cell.className = `inx-temporal-day${day.inMonth ? '' : ' outside'}`;
      cell.textContent = String(day.day);
      cell.setAttribute('data-inx-day', day.iso);
      cell.setAttribute('aria-selected', day.selected ? 'true' : 'false');
      cell.addEventListener?.('click', () => {
        draft = Object.freeze({ date: day.iso, time: draft.time || (kind === 'datetime' ? '09:00' : '') });
        const [year, month] = day.iso.split('-').map(Number);
        view = { year, month: month - 1 };
        if (kind === 'date') commit(); else render();
      });
      grid.appendChild(cell);
    }

    const footer = documentObject.createElement('div');
    footer.className = 'inx-temporal-footer';
    if (kind === 'datetime') {
      const timeWrap = documentObject.createElement('label');
      timeWrap.className = 'inx-temporal-time';
      const caption = documentObject.createElement('span');
      caption.textContent = translate(locale, 'temporal.time');
      const time = documentObject.createElement('input');
      time.type = 'time';
      time.step = '60';
      time.className = 'form-control form-control-sm';
      time.value = draft.time || '';
      time.addEventListener?.('input', () => { draft = Object.freeze({ date: draft.date, time: time.value }); });
      timeWrap.append(caption, time);
      footer.appendChild(timeWrap);
    }
    const actions = documentObject.createElement('div');
    actions.className = 'inx-temporal-actions';
    const clear = button(documentObject, translate(locale, 'temporal.clear'), translate(locale, 'temporal.clear'));
    clear.className = 'btn btn-sm btn-outline-secondary';
    clear.addEventListener?.('click', () => { draft = Object.freeze({ date: '', time: '' }); setValue(input, ''); close(); });
    const today = button(documentObject, translate(locale, 'temporal.today'), translate(locale, 'temporal.today'));
    today.className = 'btn btn-sm btn-outline-primary';
    today.addEventListener?.('click', () => {
      const now = new Date();
      draft = Object.freeze({ date: localDateIso(now), time: kind === 'datetime' ? localTimeIso(now) : '' });
      view = { year: now.getFullYear(), month: now.getMonth() };
      if (kind === 'date') commit(); else render();
    });
    actions.append(clear, today);
    if (kind === 'datetime') {
      const apply = button(documentObject, translate(locale, 'temporal.apply'), translate(locale, 'temporal.apply'));
      apply.className = 'btn btn-sm btn-primary';
      apply.disabled = !draft.date || !draft.time;
      apply.addEventListener?.('click', commit);
      actions.appendChild(apply);
    }
    footer.appendChild(actions);
    popover.append(header, weekdays, grid, footer);
  };

  trigger.addEventListener?.('click', (event) => { event.preventDefault?.(); event.stopPropagation?.(); if (open) close(); else openPicker(); });
  input.addEventListener?.('click', (event) => { event.preventDefault?.(); openPicker(); });
  input.addEventListener?.('keydown', (event) => {
    if (['Enter', ' ', 'ArrowDown'].includes(event.key)) { event.preventDefault?.(); openPicker(); }
    else if (event.key === 'Escape') close();
  });
  trigger.setAttribute('aria-label', temporalLabel(documentObject, input, kind));

  return Object.freeze({
    open: openPicker,
    close,
    render,
    contains: (target) => wrapper.contains?.(target) === true,
    destroy() {
      input.classList?.remove?.('inx-temporal-native');
      input.removeAttribute?.(ENHANCED);
      input.readOnly = false;
      wrapper.replaceWith?.(input);
    },
  });
}

function setValue(input, value) {
  input.value = value;
  const EventConstructor = input?.ownerDocument?.defaultView?.Event ?? globalThis.Event;
  if (typeof EventConstructor === 'function') {
    input.dispatchEvent?.(new EventConstructor('input', { bubbles: true }));
    input.dispatchEvent?.(new EventConstructor('change', { bubbles: true }));
  }
}
function initialView(value) {
  const parsed = splitTemporalValue(value);
  if (parsed.date) {
    const [year, month] = parsed.date.split('-').map(Number);
    if (year >= 1 && month >= 1 && month <= 12) return { year, month: month - 1 };
  }
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth() };
}
function shiftMonth(view, delta) {
  const date = new Date(Date.UTC(view.year, view.month + delta, 1));
  return { year: date.getUTCFullYear(), month: date.getUTCMonth() };
}
function monthTitle(locale, year, month) {
  return new Intl.DateTimeFormat(locale || 'en', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(year, month, 1)));
}
function weekdayLabels(locale) {
  const formatter = new Intl.DateTimeFormat(locale || 'en', { weekday: 'short', timeZone: 'UTC' });
  const monday = Date.UTC(2021, 10, 1);
  return Array.from({ length: 7 }, (_, index) => formatter.format(new Date(monday + index * DAY_MS)));
}
function localDateIso(date) { return `${date.getFullYear().toString().padStart(4, '0')}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; }
function localTimeIso(date) { return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`; }
function parseIsoDate(value) { return /^\d{4}-\d{2}-\d{2}$/.test(String(value ?? '')) ? String(value) : ''; }
function temporalLabel(documentObject, input, kind) {
  const locale = localeFromDocument(documentObject);
  const id = input.id;
  const label = id ? documentObject.querySelector?.(`label[for="${cssEscape(id)}"]`)?.textContent?.trim() : '';
  return `${label || translate(locale, kind === 'date' ? 'temporal.date' : 'temporal.dateTime')} — ${translate(locale, 'temporal.openCalendar')}`;
}
function button(documentObject, text, label) {
  const node = documentObject.createElement('button');
  node.type = 'button';
  node.className = 'inx-temporal-nav';
  node.textContent = text;
  node.setAttribute('aria-label', label);
  return node;
}
function cssEscape(value) { return String(value).replace(/(["\\])/g, '\\$1'); }
