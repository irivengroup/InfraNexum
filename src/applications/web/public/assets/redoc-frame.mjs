import {
  OPENAPI_RENDER_SPEC_URL,
  REDOC_FRAME_MESSAGE_SOURCE,
  REDOC_SCRIPT_URL,
  REDOC_VERSION,
  containsRedocFatalError,
  loadCertifiedOpenApi,
  loadExternalAsset,
  redocConfiguration,
} from './api-documentation.mjs';

/**
 * Boots ReDoc inside its dedicated browsing context. styled-components may inject
 * inline style elements here; the runtime grants that capability only to this
 * frame while the authenticated InfraNexum shell keeps its strict CSP.
 */
export async function initializeRedocFrame(
  documentObject = document,
  windowObject = globalThis.window,
  assetLoader = loadExternalAsset,
  specLoader = loadCertifiedOpenApi,
) {
  const root = documentObject?.getElementById?.('redoc-frame-root');
  if (!root) throw new Error('ReDoc frame root is unavailable');
  applyRequestedTheme(documentObject, windowObject);

  postFrameMessage(windowObject, 'boot', { version: REDOC_VERSION });
  try {
    postFrameMessage(windowObject, 'phase', { phase: 'contract' });
    const specification = await specLoader(windowObject, OPENAPI_RENDER_SPEC_URL);
    postFrameMessage(windowObject, 'phase', { phase: 'renderer' });
    await assetLoader(documentObject, windowObject, REDOC_SCRIPT_URL, 'script');
    const redoc = windowObject?.Redoc ?? globalThis.Redoc;
    if (!redoc || typeof redoc.init !== 'function') throw new Error('ReDoc bundle did not expose Redoc.init');
    root.replaceChildren?.();
    postFrameMessage(windowObject, 'phase', { phase: 'render' });
    await initializeRenderer(redoc, specification, redocConfiguration(documentObject), root);
    if (containsRedocFatalError(root)) throw new Error('ReDoc reported an embedded rendering failure');
    installManagedRedocLayout(documentObject, windowObject, root);
    installHeightReporter(windowObject, root);
    postFrameMessage(windowObject, 'ready');
    reportHeight(root, windowObject);
    return true;
  } catch (error) {
    root.textContent = '';
    postFrameMessage(windowObject, 'error', { message: safeFrameMessage(error) });
    return false;
  }
}

export function initializeRenderer(redoc, specification, configuration, root) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      if (error) {
        if (error instanceof Error) reject(error);
        else reject(new Error(safeFrameMessage(error)));
      } else {
        resolve();
      }
    };
    try {
      const result = redoc.init(specification, configuration, root, finish);
      if (result && typeof result.then === 'function') {
        result.catch((error) => finish(error));
      }
    } catch (error) {
      finish(error);
    }
  });
}

export function applyRequestedTheme(documentObject, windowObject) {
  const href = String(windowObject?.location?.href ?? 'http://infranexum.invalid/assets/redoc-frame.html');
  const theme = new URL(href, 'http://infranexum.invalid').searchParams.get('theme') === 'dark' ? 'dark' : 'light';
  documentObject?.documentElement?.setAttribute?.('data-bs-theme', theme);
  return theme;
}

/**
 * Converts ReDoc's continuous document into a bounded, tab-like reference view.
 * The menu remains fully expandable while the article column contains only the
 * selected ReDoc item, preventing the iframe from inheriting the cumulative
 * height of every operation in the OpenAPI contract.
 */
export function installManagedRedocLayout(documentObject, windowObject, root) {
  const menu = root?.querySelector?.('.menu-content');
  const api = root?.querySelector?.('.api-content');
  if (!menu || !api) return Object.freeze({ managed: false, activate: () => false, dispose: () => {} });

  root.setAttribute?.('data-inx-redoc-managed', 'true');
  const contentFor = (id) => {
    const normalized = String(id ?? '').trim();
    if (!normalized) return null;
    const candidate = documentObject?.getElementById?.(normalized);
    return candidate && api.contains?.(candidate) !== false ? candidate : null;
  };
  const managedVisibility = new Map();
  const rememberVisibility = (node) => {
    if (!node || managedVisibility.has(node)) return;
    managedVisibility.set(node, {
      hidden: Boolean(node.hidden),
      ariaHidden: node.getAttribute?.('aria-hidden') ?? null,
    });
  };
  const restoreVisibility = () => {
    for (const [node, state] of managedVisibility) {
      node.hidden = state.hidden;
      if (state.ariaHidden === null) node.removeAttribute?.('aria-hidden');
      else node.setAttribute?.('aria-hidden', state.ariaHidden);
      node.removeAttribute?.('data-inx-redoc-managed-hidden');
    }
    managedVisibility.clear();
  };
  const hideSibling = (node) => {
    rememberVisibility(node);
    node.hidden = true;
    node.setAttribute?.('aria-hidden', 'true');
    node.setAttribute?.('data-inx-redoc-managed-hidden', 'true');
  };
  const activate = (id) => {
    const target = contentFor(id);
    if (!target) return false;

    // ReDoc nests operations below tag/section wrappers. Hide siblings at every
    // ancestor level so an operation selection does not leave the rest of its
    // tag visible, while selecting a parent tag still exposes that complete tag.
    restoreVisibility();
    let branch = target;
    while (branch && branch !== api) {
      const parent = branch.parentElement;
      if (!parent) return false;
      for (const sibling of parent.children ?? []) {
        if (sibling !== branch) hideSibling(sibling);
      }
      branch = parent;
    }
    if (branch !== api) {
      restoreVisibility();
      return false;
    }
    target.hidden = false;
    target.setAttribute?.('aria-hidden', 'false');
    target.setAttribute?.('data-inx-redoc-active', 'true');
    root.setAttribute?.('data-inx-redoc-active-id', String(id));
    reportHeight(root, windowObject);
    return true;
  };
  const itemFromEvent = (event) => event?.target?.closest?.('[data-item-id]');
  const onActivate = (event) => {
    const item = itemFromEvent(event);
    if (!item || menu.contains?.(item) === false) return;
    activate(item.getAttribute?.('data-item-id'));
  };
  const onKeyDown = (event) => {
    if (event?.key !== 'Enter' && event?.key !== ' ') return;
    onActivate(event);
  };
  menu.addEventListener?.('click', onActivate, true);
  menu.addEventListener?.('keydown', onKeyDown, true);

  const requestedId = decodeURIComponent(String(windowObject?.location?.hash ?? '').replace(/^#/, ''));
  const items = [...(menu.querySelectorAll?.('[data-item-id]') ?? [])];
  const requestedItem = requestedId
    ? items.find((item) => item.getAttribute?.('data-item-id') === requestedId)
    : null;
  const initial = requestedItem ?? items.find((item) => contentFor(item.getAttribute?.('data-item-id')));
  if (initial) activate(initial.getAttribute?.('data-item-id'));

  const dispose = () => {
    menu.removeEventListener?.('click', onActivate, true);
    menu.removeEventListener?.('keydown', onKeyDown, true);
    restoreVisibility();
  };
  windowObject?.addEventListener?.('pagehide', dispose, { once: true });
  return Object.freeze({ managed: true, activate, dispose });
}

export function reportHeight(root, windowObject) {
  if (!root) return 0;
  const rectHeight = Number(root.getBoundingClientRect?.().height ?? 0);
  const height = Math.max(
    Number(root.scrollHeight ?? 0),
    Number(root.offsetHeight ?? 0),
    Number.isFinite(rectHeight) ? rectHeight : 0,
  );
  if (height > 0) postFrameMessage(windowObject, 'resize', { height: Math.ceil(height) });
  return height > 0 ? Math.ceil(height) : 0;
}

export function installHeightReporter(windowObject, root) {
  const report = () => reportHeight(root, windowObject);
  const resizeObserver = typeof windowObject?.ResizeObserver === 'function'
    ? new windowObject.ResizeObserver(report)
    : null;
  const mutationObserver = typeof windowObject?.MutationObserver === 'function'
    ? new windowObject.MutationObserver(report)
    : null;

  resizeObserver?.observe(root);
  mutationObserver?.observe(root, { childList: true, subtree: true, attributes: true, characterData: true });

  const dispose = () => {
    resizeObserver?.disconnect?.();
    mutationObserver?.disconnect?.();
  };
  windowObject?.addEventListener?.('pagehide', dispose, { once: true });
  return Object.freeze({ resizeObserver, mutationObserver, dispose });
}

function postFrameMessage(windowObject, type, detail = {}) {
  const origin = windowObject?.location?.origin;
  const targetOrigin = origin && origin !== 'null' ? origin : '*';
  windowObject?.parent?.postMessage?.({ source: REDOC_FRAME_MESSAGE_SOURCE, type, ...detail }, targetOrigin);
}

function safeFrameMessage(error) {
  const candidate = error instanceof Error ? error.message : error?.message ?? error;
  if (candidate && typeof candidate.then === 'function') return 'ReDoc rendering failed';
  if (candidate && typeof candidate === 'object') return 'ReDoc rendering failed';
  const value = String(candidate ?? 'ReDoc rendering failed').replace(/https?:\/\/\S+/g, '').trim();
  if (!value || /^\[object (?:Promise|Object)\]$/.test(value)) return 'ReDoc rendering failed';
  return value.length > 180 ? `${value.slice(0, 177)}…` : value;
}

if (typeof document !== 'undefined' && typeof window !== 'undefined' && document.documentElement?.getAttribute?.('data-inx-redoc-frame') === 'true') {
  void initializeRedocFrame();
}
