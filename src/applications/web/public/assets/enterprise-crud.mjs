import { localeFromDocument, translate } from './i18n.mjs';

const CONTROLLERS = new WeakMap();

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

  for (const button of panel.querySelectorAll?.('[data-inx-crud-new], [data-inx-crud-open]') ?? []) {
    button.addEventListener?.('click', (event) => {
      event?.preventDefault?.();
      const key = button.getAttribute('data-inx-crud-new') || button.getAttribute('data-inx-crud-open');
      const mode = button.getAttribute('data-inx-crud-editor-mode') || (button.hasAttribute?.('data-inx-crud-new') ? 'create' : 'edit');
      open(key, { mode });
    });
  }
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

export function openCrudEditor(panel, key, options = {}) {
  return CONTROLLERS.get(panel)?.open?.(key, options) ?? false;
}

export function showCrudList(panel, options = {}) {
  return CONTROLLERS.get(panel)?.showList?.(options) ?? false;
}

/** Enhances every current table as a keyboard-accessible, client-side sortable data table. */
export function initializeEnterpriseDataTables(root = document) {
  const tables = [...(root?.querySelectorAll?.('table') ?? [])];
  let wired = 0;
  for (const table of tables) {
    if (table.getAttribute?.('data-inx-datatable-wired') === 'true') continue;
    const headerRow = table.querySelector?.('thead tr');
    const tbody = table.querySelector?.('tbody');
    if (!headerRow || !tbody) continue;
    table.classList?.add?.('inx-data-table');
    const headers = [...(headerRow.children ?? [])];
    headers.forEach((header, index) => {
      if (isActionHeader(header)) {
        header.setAttribute?.('data-inx-sortable', 'false');
        return;
      }
      header.setAttribute?.('data-inx-sortable', 'true');
      header.setAttribute?.('aria-sort', 'none');
      header.tabIndex = 0;
      const sort = () => sortTableByColumn(table, index, header);
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
  return wired;
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
  const rows = [...(tbody.children ?? [])].filter((row) => !row.hidden && (row.children?.length ?? 0) > columnIndex && !(row.children?.[0]?.colSpan > 1));
  const indexed = rows.map((row, index) => ({ row, index, value: sortableValue(row.children[columnIndex]?.textContent) }));
  indexed.sort((left, right) => {
    const comparison = compareSortable(left.value, right.value);
    if (comparison === 0) return left.index - right.index;
    return direction === 'ascending' ? comparison : -comparison;
  });
  for (const item of indexed) tbody.appendChild?.(item.row);
  return true;
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
