import { localeFromDocument, translate } from './i18n.mjs';

const CONTROLLERS = new WeakMap();
const DATA_TABLES = new WeakMap();
const DATA_TABLE_PAGE_SIZES = Object.freeze([20, 50, 100, 200]);
const CRUD_HANDLED_EVENTS = new WeakSet();

/**
 * Converts InfraNexum tab panels into a list-first enterprise CRUD workflow.
 * The list remains the default view. Create/edit/lifecycle forms are presented
 * as one dedicated editor page at a time and successful mutations return to the
 * list automatically.
 */
export function initializeCrudNavigation(root = document) {
  const panels = [...(root?.querySelectorAll?.('[data-inx-crud-panel]') ?? [])];
  for (const panel of panels) wireCrudPanel(root, panel);
  return Object.freeze({ count: panels.length, open: (panel, key) => openCrudEditor(panel, key) });
}

export function wireCrudPanel(documentObject, panel) {
  if (!panel || panel.getAttribute?.('data-inx-crud-wired') === 'true') return CONTROLLERS.get(panel) ?? null;
  const list = panel.querySelector?.('[data-inx-crud-list]');
  const editor = panel.querySelector?.('[data-inx-crud-editor]');
  if (!list || !editor) return null;
  const forms = [...(editor.querySelectorAll?.('[data-inx-crud-form]') ?? [])];
  if (forms.length === 0) return null;

  const showList = ({ focus = true } = {}) => {
    list.hidden = false;
    list.setAttribute?.('aria-hidden', 'false');
    editor.hidden = true;
    editor.setAttribute?.('aria-hidden', 'true');
    panel.setAttribute?.('data-inx-crud-mode', 'list');
    for (const form of forms) form.hidden = true;
    if (focus) panel.querySelector?.('[data-inx-crud-new], [data-inx-crud-list] input, [data-inx-crud-list] button')?.focus?.();
    return true;
  };

  const open = (key, { focus = true, mode = 'edit' } = {}) => {
    const target = forms.find((candidate) => candidate.getAttribute('data-inx-crud-form') === String(key));
    if (!target) return false;
    if (mode !== 'create') ensureTechnicalIdentifierField(documentObject, target, panel.getAttribute?.('data-inx-selected-id'));
    list.hidden = true;
    list.setAttribute?.('aria-hidden', 'true');
    editor.hidden = false;
    editor.setAttribute?.('aria-hidden', 'false');
    panel.setAttribute?.('data-inx-crud-mode', 'form');
    panel.setAttribute?.('data-inx-crud-editor-mode', mode === 'create' ? 'create' : 'edit');
    for (const form of forms) {
      const active = form === target;
      form.hidden = !active;
      form.setAttribute?.('aria-hidden', active ? 'false' : 'true');
    }
    const title = editor.querySelector?.('[data-inx-crud-editor-title]');
    const titleKey = target.getAttribute?.('data-inx-crud-title-key');
    if (title && titleKey) title.textContent = translate(localeFromDocument(documentObject), titleKey);
    if (focus) target.querySelector?.('input:not([type="hidden"]), select, textarea, button')?.focus?.();
    return true;
  };

  const handleOpenButton = (button, event) => {
    if (!button || (event && CRUD_HANDLED_EVENTS.has(event))) return false;
    if (event && typeof event === 'object') CRUD_HANDLED_EVENTS.add(event);
    event?.preventDefault?.();
    const key = button.getAttribute('data-inx-crud-new') || button.getAttribute('data-inx-crud-open');
    const mode = button.getAttribute('data-inx-crud-editor-mode') || (button.hasAttribute?.('data-inx-crud-new') ? 'create' : 'edit');
    return open(key, { mode });
  };
  // Direct listeners preserve compatibility with simple/non-bubbling DOM runtimes.
  for (const button of panel.querySelectorAll?.('[data-inx-crud-new], [data-inx-crud-open]') ?? []) {
    button.addEventListener?.('click', (event) => handleOpenButton(button, event));
  }
  // Delegation is authoritative for buttons injected after initial wiring.
  panel.addEventListener?.('click', (event) => {
    const button = event?.target?.closest?.('[data-inx-crud-new], [data-inx-crud-open]');
    if (button && panel.contains?.(button) !== false) handleOpenButton(button, event);
  });
  panel.addEventListener?.('infranexum:row-selected', (event) => {
    const id = String(event?.detail?.row?.id ?? '').trim();
    if (id) setCrudTechnicalIdentifier(panel, id);
  });
  for (const button of panel.querySelectorAll?.('[data-inx-crud-back]') ?? []) {
    button.addEventListener?.('click', (event) => { event?.preventDefault?.(); showList(); });
  }
  editor.addEventListener?.('infranexum:form-success', () => showList());
  editor.addEventListener?.('infranexum:action-success', () => showList());

  panel.setAttribute?.('data-inx-crud-wired', 'true');
  const controller = Object.freeze({ open, showList, panel, list, editor });
  CONTROLLERS.set(panel, controller);
  showList({ focus: false });
  return controller;
}

export function setCrudTechnicalIdentifier(panel, value) {
  if (!panel) return false;
  const normalized = String(value ?? '').trim();
  if (!normalized) return false;
  panel.setAttribute?.('data-inx-selected-id', normalized);
  for (const field of panel.querySelectorAll?.('[data-inx-technical-id]') ?? []) field.value = normalized;
  return true;
}

function ensureTechnicalIdentifierField(documentObject, target, value) {
  const normalized = String(value ?? '').trim();
  if (!normalized || !documentObject?.createElement || !target?.querySelector) return null;
  const form = target.matches?.('form') ? target : target.querySelector('form');
  if (!form) return null;
  let input = form.querySelector?.('[data-inx-technical-id]');
  if (!input) {
    const wrapper = documentObject.createElement('div');
    wrapper.className = 'col-12 inx-technical-id-field';
    const label = documentObject.createElement('label');
    label.className = 'form-label small text-body-secondary';
    label.textContent = translate(localeFromDocument(documentObject), 'common.identifier');
    input = documentObject.createElement('input');
    input.type = 'text';
    input.className = 'form-control font-monospace';
    input.readOnly = true;
    input.setAttribute('readonly', '');
    input.setAttribute('data-inx-technical-id', '');
    input.setAttribute('aria-label', translate(localeFromDocument(documentObject), 'common.identifier'));
    wrapper.append(label, input);
    form.insertBefore?.(wrapper, form.firstChild ?? null);
  }
  input.value = normalized;
  return input;
}

export function openCrudEditor(panel, key, options = {}) {
  return CONTROLLERS.get(panel)?.open?.(key, options) ?? false;
}

export function showCrudList(panel, options = {}) {
  return CONTROLLERS.get(panel)?.showList?.(options) ?? false;
}

/**
 * Enhances every current table as a keyboard-accessible sortable and paginated
 * data table. Pagination is client-side over the bounded result sets returned by
 * InfraNexum list APIs (maximum 200 rows), so the table never needs a nested
 * vertical or horizontal scrolling region.
 */
export function initializeEnterpriseDataTables(root = document) {
  const tables = [...(root?.querySelectorAll?.('table') ?? [])];
  let wired = 0;
  for (const table of tables) {
    const headerRow = table.querySelector?.('thead tr');
    const tbody = table.querySelector?.('tbody');
    if (!headerRow || !tbody) continue;

    table.classList?.add?.('inx-data-table');
    const responsiveContainer = table.closest?.('.table-responsive');
    responsiveContainer?.parentElement?.classList?.add?.('inx-datatable-frame');
    let state = DATA_TABLES.get(table);
    if (!state) {
      state = createDataTableState(root, table);
      DATA_TABLES.set(table, state);
      const headers = [...(headerRow.children ?? [])];
      headers.forEach((header, index) => {
        if (isActionHeader(header)) {
          header.setAttribute?.('data-inx-sortable', 'false');
          return;
        }
        header.setAttribute?.('data-inx-sortable', 'true');
        header.setAttribute?.('aria-sort', 'none');
        header.tabIndex = 0;
        const sort = () => {
          sortTableByColumn(table, index, header);
          state.page = 0;
          refreshDataTable(table);
        };
        header.addEventListener?.('click', sort);
        header.addEventListener?.('keydown', (event) => {
          if (event.key !== 'Enter' && event.key !== ' ') return;
          event.preventDefault?.();
          sort();
        });
      });
      table.setAttribute?.('data-inx-datatable-wired', 'true');
      wired += 1;
    }
    concealTechnicalIdentifierColumns(table);
    classifyDataTableColumns(table);
    refreshDataTable(table);
  }
  return wired;
}

/** Re-applies row visibility and pagination metadata after a table render. */
export function refreshEnterpriseDataTable(table) {
  return refreshDataTable(table);
}

export function dataTablePageSizes() {
  return DATA_TABLE_PAGE_SIZES;
}

export function pageWindow(totalRows, pageSize, requestedPage) {
  const total = Math.max(0, Number.isSafeInteger(totalRows) ? totalRows : 0);
  const size = DATA_TABLE_PAGE_SIZES.includes(pageSize) ? pageSize : DATA_TABLE_PAGE_SIZES[0];
  const pageCount = Math.max(1, Math.ceil(total / size));
  const page = Math.min(Math.max(0, Number.isSafeInteger(requestedPage) ? requestedPage : 0), pageCount - 1);
  const start = page * size;
  return Object.freeze({ total, size, page, pageCount, start, end: Math.min(total, start + size) });
}

export function sortTableByColumn(table, columnIndex, header = null) {
  const tbody = table?.querySelector?.('tbody');
  const headerRow = table?.querySelector?.('thead tr');
  if (!tbody || !headerRow) return false;
  const headers = [...(headerRow.children ?? [])];
  const target = header ?? headers[columnIndex];
  if (!target || target.getAttribute?.('data-inx-sortable') === 'false') return false;
  const previous = target.getAttribute?.('aria-sort');
  const direction = previous === 'ascending' ? 'descending' : 'ascending';
  for (const candidate of headers) {
    if (candidate.getAttribute?.('data-inx-sortable') === 'true') candidate.setAttribute?.('aria-sort', candidate === target ? direction : 'none');
  }
  const rows = dataRows(tbody).filter((row) => (row.children?.length ?? 0) > columnIndex);
  const indexed = rows.map((row, index) => ({ row, index, value: sortableValue(row.children[columnIndex]?.textContent) }));
  indexed.sort((left, right) => {
    const comparison = compareSortable(left.value, right.value);
    if (comparison === 0) return left.index - right.index;
    return direction === 'ascending' ? comparison : -comparison;
  });
  for (const item of indexed) tbody.appendChild?.(item.row);
  return true;
}

function concealTechnicalIdentifierColumns(table) {
  const headerRow = table?.querySelector?.('thead tr');
  const tbody = table?.querySelector?.('tbody');
  if (!headerRow || !tbody) return 0;
  const headers = [...(headerRow.children ?? [])];
  let hidden = 0;
  headers.forEach((header, index) => {
    const key = String(header.getAttribute?.('data-i18n') ?? '').trim().toLowerCase();
    const text = String(header.textContent ?? '').trim().toLowerCase();
    const technical = text === 'id' || text === 'uuid' || /(?:^|\.)(?:id|uuid)$/.test(key);
    if (!technical) return;
    header.hidden = true;
    header.setAttribute?.('aria-hidden', 'true');
    header.setAttribute?.('data-inx-technical-id-column', 'true');
    header.setAttribute?.('data-inx-sortable', 'false');
    for (const row of tbody.children ?? []) {
      const cell = row.children?.[index];
      if (!cell) continue;
      cell.hidden = true;
      cell.setAttribute?.('aria-hidden', 'true');
      cell.setAttribute?.('data-inx-technical-id-cell', 'true');
    }
    hidden += 1;
  });
  return hidden;
}

function classifyDataTableColumns(table) {
  const headerRow = table?.querySelector?.('thead tr');
  const tbody = table?.querySelector?.('tbody');
  if (!headerRow || !tbody) return;
  const headers = [...(headerRow.children ?? [])];
  headers.forEach((header, index) => {
    if (header.hidden) return;
    if (isActionHeader(header)) {
      header.setAttribute?.('data-inx-column-size', 'actions');
      for (const row of tbody.children ?? []) row.children?.[index]?.setAttribute?.('data-inx-column-size', 'actions');
      return;
    }
    const values = [...(tbody.children ?? [])]
      .map((row) => String(row.children?.[index]?.textContent ?? '').trim())
      .filter(Boolean);
    const longest = Math.max(String(header.textContent ?? '').trim().length, ...values.map((value) => value.length), 0);
    const size = longest <= 12 ? 'compact' : longest <= 32 ? 'content' : 'flex';
    header.setAttribute?.('data-inx-column-size', size);
    for (const row of tbody.children ?? []) row.children?.[index]?.setAttribute?.('data-inx-column-size', size);
  });
}

function createDataTableState(documentObject, table) {
  const state = { page: 0, pageSize: DATA_TABLE_PAGE_SIZES[0], pager: null, summary: null, previous: null, next: null, pageButtons: null, sizeSelect: null };
  const doc = table?.ownerDocument ?? documentObject;
  if (!doc?.createElement) return state;

  const pager = doc.createElement('div');
  pager.className = 'inx-datatable-pagination';
  pager.setAttribute('role', 'navigation');
  pager.setAttribute('aria-label', translate(localeFromDocument(doc), 'datatable.pagination'));

  const summary = doc.createElement('span');
  summary.className = 'inx-datatable-summary';
  summary.setAttribute('aria-live', 'polite');

  const controls = doc.createElement('div');
  controls.className = 'inx-datatable-controls';
  const sizeLabel = doc.createElement('label');
  sizeLabel.className = 'inx-datatable-size';
  const sizeText = doc.createElement('span');
  sizeText.textContent = translate(localeFromDocument(doc), 'datatable.rowsPerPage');
  const sizeSelect = doc.createElement('select');
  sizeSelect.className = 'form-select form-select-sm';
  sizeSelect.setAttribute('aria-label', translate(localeFromDocument(doc), 'datatable.rowsPerPage'));
  for (const size of DATA_TABLE_PAGE_SIZES) {
    const option = doc.createElement('option');
    option.value = String(size);
    option.textContent = String(size);
    sizeSelect.appendChild(option);
  }
  sizeSelect.value = String(state.pageSize);
  sizeSelect.addEventListener('change', () => {
    const requested = Number(sizeSelect.value);
    state.pageSize = DATA_TABLE_PAGE_SIZES.includes(requested) ? requested : DATA_TABLE_PAGE_SIZES[0];
    state.page = 0;
    refreshDataTable(table);
  });
  sizeLabel.append(sizeText, sizeSelect);

  const nav = doc.createElement('div');
  nav.className = 'btn-group btn-group-sm inx-datatable-pages';
  nav.setAttribute('role', 'group');
  const previous = pagerButton(doc, '‹', 'datatable.previous', () => { state.page -= 1; refreshDataTable(table); });
  const pageButtons = doc.createElement('span');
  pageButtons.className = 'inx-datatable-page-buttons';
  const next = pagerButton(doc, '›', 'datatable.next', () => { state.page += 1; refreshDataTable(table); });
  nav.append(previous, pageButtons, next);
  controls.append(sizeLabel, nav);
  pager.append(summary, controls);

  const container = table.closest?.('.table-responsive') ?? table;
  const parent = container?.parentElement;
  if (parent?.insertBefore) parent.insertBefore(pager, container.nextSibling ?? null);
  else if (container?.insertAdjacentElement) container.insertAdjacentElement('afterend', pager);

  Object.assign(state, { pager, summary, previous, next, pageButtons, sizeSelect });
  return state;
}

function pagerButton(doc, text, labelKey, action) {
  const button = doc.createElement('button');
  button.type = 'button';
  button.className = 'btn btn-outline-primary inx-datatable-page-button';
  button.textContent = text;
  button.setAttribute('aria-label', translate(localeFromDocument(doc), labelKey));
  button.addEventListener('click', action);
  return button;
}

function refreshDataTable(table) {
  concealTechnicalIdentifierColumns(table);
  classifyDataTableColumns(table);
  const state = DATA_TABLES.get(table);
  const tbody = table?.querySelector?.('tbody');
  if (!state || !tbody) return false;
  ensureDataTableEmptyState(table, tbody);
  const rows = dataRows(tbody);
  const window = pageWindow(rows.length, state.pageSize, state.page);
  state.page = window.page;
  rows.forEach((row, index) => {
    const visible = index >= window.start && index < window.end;
    row.hidden = !visible;
    row.setAttribute?.('data-inx-page-hidden', visible ? 'false' : 'true');
  });
  if (!state.pager) return true;
  state.pager.hidden = rows.length <= window.size;
  state.summary.textContent = formatSummary(table.ownerDocument, window);
  state.previous.disabled = window.page === 0;
  state.next.disabled = window.page >= window.pageCount - 1;
  renderPageButtons(table.ownerDocument, table, state, window);
  return true;
}

function renderPageButtons(doc, table, state, window) {
  if (!state.pageButtons?.replaceChildren || !doc?.createElement) return;
  const fragment = [];
  const indexes = compactPageIndexes(window.pageCount, window.page);
  for (const index of indexes) {
    if (index === null) {
      const ellipsis = doc.createElement('span');
      ellipsis.className = 'btn inx-datatable-ellipsis disabled';
      ellipsis.textContent = '…';
      ellipsis.setAttribute('aria-hidden', 'true');
      fragment.push(ellipsis);
      continue;
    }
    const button = doc.createElement('button');
    button.type = 'button';
    button.className = `btn ${index === window.page ? 'btn-primary' : 'btn-outline-primary'} inx-datatable-page-button`;
    button.textContent = String(index + 1);
    button.setAttribute('aria-label', `${translate(localeFromDocument(doc), 'datatable.page')} ${index + 1}`);
    if (index === window.page) button.setAttribute('aria-current', 'page');
    button.addEventListener('click', () => { state.page = index; refreshDataTable(table); });
    fragment.push(button);
  }
  state.pageButtons.replaceChildren(...fragment);
}

function compactPageIndexes(pageCount, current) {
  if (pageCount <= 7) return Array.from({ length: pageCount }, (_, index) => index);
  const values = new Set([0, pageCount - 1, current - 1, current, current + 1].filter((value) => value >= 0 && value < pageCount));
  const ordered = [...values].sort((a, b) => a - b);
  const result = [];
  for (let index = 0; index < ordered.length; index += 1) {
    if (index > 0 && ordered[index] - ordered[index - 1] > 1) result.push(null);
    result.push(ordered[index]);
  }
  return result;
}

function formatSummary(doc, window) {
  if (window.total === 0) return translate(localeFromDocument(doc), 'datatable.empty');
  return `${window.start + 1}–${window.end} / ${window.total}`;
}

function ensureDataTableEmptyState(table, tbody) {
  const children = [...(tbody?.children ?? [])];
  const data = children.filter((row) => !isStructuralRow(row));
  const current = children.find((row) => row.getAttribute?.('data-inx-empty-state') === 'true');
  if (data.length > 0) {
    current?.remove?.();
    return false;
  }

  // Preserve explicit loading/error structural rows. Only synthesize an empty
  // row when no other structural state is already rendered by the workspace.
  if (!current && children.length > 0) return false;
  const doc = table?.ownerDocument;
  if (!doc?.createElement) return false;
  const row = current ?? doc.createElement('tr');
  row.setAttribute?.('data-inx-empty-state', 'true');
  let cell = row.children?.[0] ?? null;
  if (!cell) {
    cell = doc.createElement('td');
    row.appendChild?.(cell);
  }
  const columns = Math.max(1, table.querySelector?.('thead tr')?.children?.length ?? 1);
  cell.colSpan = columns;
  cell.className = 'inx-datatable-empty text-body-secondary text-center py-4';
  cell.textContent = translate(localeFromDocument(doc), 'common.emptyList');
  if (!current) tbody.replaceChildren?.(row);
  return true;
}

function dataRows(tbody) {
  return [...(tbody?.children ?? [])].filter((row) => !isStructuralRow(row));
}

function isStructuralRow(row) {
  return (row?.children?.length ?? 0) === 1 && Number(row.children?.[0]?.colSpan ?? 1) > 1;
}

export function sortableValue(value) {
  const text = String(value ?? '').trim();
  if (!text || text === '—') return Object.freeze({ kind: 'empty', value: '' });
  const numeric = Number(text.replace(/\s/g, '').replace(',', '.'));
  if (Number.isFinite(numeric) && /^[-+]?\d[\d\s]*(?:[.,]\d+)?$/.test(text)) return Object.freeze({ kind: 'number', value: numeric });
  const timestamp = Date.parse(text);
  if (Number.isFinite(timestamp) && /^\d{4}-\d{2}-\d{2}/.test(text)) return Object.freeze({ kind: 'date', value: timestamp });
  return Object.freeze({ kind: 'text', value: text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLocaleLowerCase() });
}

function compareSortable(left, right) {
  if (left.kind === 'empty' && right.kind !== 'empty') return 1;
  if (right.kind === 'empty' && left.kind !== 'empty') return -1;
  if (left.kind === right.kind && (left.kind === 'number' || left.kind === 'date')) return left.value - right.value;
  return String(left.value).localeCompare(String(right.value), undefined, { numeric: true, sensitivity: 'base' });
}

function isActionHeader(header) {
  if (header?.getAttribute?.('data-inx-actions-column') === 'true') return true;
  const key = String(header?.getAttribute?.('data-i18n') ?? '');
  return key === 'common.actions' || key.endsWith('.actions');
}
