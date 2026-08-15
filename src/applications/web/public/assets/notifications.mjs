import { localeFromDocument, translate } from './i18n.mjs';

const CENTERS = new WeakMap();
const SEVERITIES = new Set(['info', 'success', 'warning', 'error']);

export function initializeNotificationCenter(documentObject = document, clock = () => Date.now()) {
  const existing = CENTERS.get(documentObject);
  if (existing) return existing;

  const dialog = documentObject?.getElementById?.('notification-center');
  const trigger = documentObject?.getElementById?.('notification-trigger');
  const closer = documentObject?.getElementById?.('notification-close');
  const markRead = documentObject?.getElementById?.('notification-mark-read');
  const list = documentObject?.getElementById?.('notification-list');
  const count = documentObject?.getElementById?.('notification-count');
  const notices = new Map();

  const render = () => {
    if (!list) return;
    const locale = localeFromDocument(documentObject);
    const ordered = [...notices.values()].sort((left, right) => right.observedAt - left.observedAt);
    const unread = ordered.filter((notice) => !notice.read).length;
    if (count) {
      count.textContent = String(unread);
      count.hidden = unread === 0;
      count.setAttribute?.('aria-label', translate(locale, 'notification.unread', { count: unread }));
    }
    if (ordered.length === 0) {
      const empty = documentObject.createElement?.('div');
      if (empty) {
        empty.className = 'list-group-item text-body-secondary text-center py-4';
        empty.textContent = translate(locale, 'notification.empty');
        list.replaceChildren?.(empty);
      }
      return;
    }
    list.replaceChildren?.(...ordered.map((notice) => noticeElement(documentObject, notice, locale)));
  };
  const markAllRead = () => {
    for (const [id, notice] of notices) notices.set(id, Object.freeze({ ...notice, read: true }));
    render();
  };
  const open = () => {
    if (dialog && !dialog.open) dialog.showModal?.();
    trigger?.setAttribute?.('aria-expanded', 'true');
    markAllRead();
  };
  const close = () => {
    if (dialog?.open) dialog.close?.();
    trigger?.setAttribute?.('aria-expanded', 'false');
    trigger?.focus?.();
  };
  const upsert = (notice) => {
    const normalized = normalizeNotice(notice, clock);
    const previous = notices.get(normalized.id);
    notices.set(normalized.id, Object.freeze({
      ...normalized,
      read: previous?.fingerprint === normalized.fingerprint ? previous.read : false,
    }));
    render();
    return notices.get(normalized.id);
  };
  const remove = (id) => {
    notices.delete(String(id));
    render();
  };

  trigger?.addEventListener?.('click', open);
  closer?.addEventListener?.('click', close);
  markRead?.addEventListener?.('click', markAllRead);
  dialog?.addEventListener?.('click', (event) => { if (event.target === dialog) close(); });
  documentObject?.addEventListener?.('infranexum:locale-change', render);

  const center = Object.freeze({ open, close, upsert, remove, markAllRead, snapshot: () => [...notices.values()] });
  CENTERS.set(documentObject, center);
  render();
  return center;
}

export function notificationCenterFor(documentObject) {
  return CENTERS.get(documentObject) ?? null;
}

export function normalizeNotice(value, clock = () => Date.now()) {
  if (!value || typeof value !== 'object') throw new TypeError('notification must be an object');
  const id = requireToken(value.id, 'notification id');
  const severity = SEVERITIES.has(value.severity) ? value.severity : 'info';
  const titleKey = requireToken(value.titleKey, 'notification titleKey');
  const bodyKey = requireToken(value.bodyKey, 'notification bodyKey');
  const parameters = value.parameters && typeof value.parameters === 'object' && !Array.isArray(value.parameters)
    ? Object.freeze({ ...value.parameters })
    : Object.freeze({});
  const observedAt = Number.isFinite(value.observedAt) ? value.observedAt : Number(clock());
  const fingerprint = JSON.stringify([severity, titleKey, bodyKey, parameters]);
  return Object.freeze({ id, severity, titleKey, bodyKey, parameters, observedAt, fingerprint, read: false });
}

function noticeElement(documentObject, notice, locale) {
  const contextual = Object.freeze({ info: 'info', success: 'success', warning: 'warning', error: 'danger' });
  const bootstrapContext = contextual[notice.severity] ?? 'info';
  const article = documentObject.createElement('article');
  article.className = `alert alert-${bootstrapContext} d-flex align-items-start gap-2 mb-2`;
  article.setAttribute('data-severity', notice.severity);

  const marker = documentObject.createElement('span');
  marker.className = `badge rounded-pill text-bg-${bootstrapContext}`;
  marker.setAttribute('aria-hidden', 'true');
  marker.textContent = '•';

  const copy = documentObject.createElement('div');
  copy.className = 'flex-grow-1';
  const title = documentObject.createElement('strong');
  title.className = 'd-block';
  const body = documentObject.createElement('p');
  body.className = 'mb-0 small';
  title.textContent = translate(locale, notice.titleKey, notice.parameters);
  body.textContent = translate(locale, notice.bodyKey, notice.parameters);
  copy.appendChild(title);
  copy.appendChild(body);

  const severity = documentObject.createElement('span');
  severity.className = `badge rounded-pill text-bg-${bootstrapContext}`;
  severity.textContent = translate(locale, `notification.severity.${notice.severity}`);

  article.appendChild(marker);
  article.appendChild(copy);
  article.appendChild(severity);
  return article;
}

function requireToken(value, field) {
  if (typeof value !== 'string' || value.trim() !== value || value.length === 0 || value.length > 120) {
    throw new TypeError(`${field} must be a non-empty trimmed string`);
  }
  return value;
}
