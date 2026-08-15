const DAY_MS = 86_400_000;

/**
 * Normalize InfraNexum temporal fields to native browser controls styled by
 * Bootstrap 5. The browser owns the calendar/time popup; the submitted value
 * remains the standard local `date` or `datetime-local` value consumed by the
 * Server, which applies the configured server timezone when no zone is present.
 */
export function initializeTemporalPickers(documentObject = document) {
  const controls = [];
  for (const input of documentObject?.querySelectorAll?.('input[data-inx-temporal]') ?? []) {
    controls.push(enhanceTemporalInput(documentObject, input));
  }
  return Object.freeze({
    count: controls.length,
    closeAll() {},
    destroy() { for (const controller of controls) controller.destroy(); },
  });
}

/**
 * Preserve the calendar model as a stable utility contract for callers/tests;
 * presentation is intentionally delegated to the browser's native picker.
 */
export function calendarMonthModel(year, monthIndex, selectedIsoDate = '') {
  if (!Number.isInteger(year) || !Number.isInteger(monthIndex) || monthIndex < 0 || monthIndex > 11) {
    throw new TypeError('calendar year/month is invalid');
  }
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

export function enhanceTemporalInput(_documentObject, input) {
  const kind = input.getAttribute?.('data-inx-temporal') === 'date' ? 'date' : 'datetime';
  const expectedType = kind === 'date' ? 'date' : 'datetime-local';
  const originalType = input.type;
  const originalReadOnly = input.readOnly === true;

  input.type = expectedType;
  input.readOnly = false;
  input.classList?.add?.('form-control');
  input.setAttribute?.('data-inx-temporal-enhanced', 'native');

  return Object.freeze({
    open() {
      // showPicker is optional and may only be invoked from a user activation.
      // Native click/focus behavior remains authoritative when it is unavailable.
      try { input.showPicker?.(); } catch { /* browser security policy keeps native fallback */ }
    },
    close() {},
    render() {},
    contains: (target) => target === input,
    destroy() {
      input.removeAttribute?.('data-inx-temporal-enhanced');
      input.readOnly = originalReadOnly;
      if (originalType) input.type = originalType;
    },
  });
}

function parseIsoDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(String(value ?? '')) ? String(value) : '';
}
