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
