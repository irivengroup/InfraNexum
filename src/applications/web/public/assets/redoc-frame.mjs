import {
  OPENAPI_RENDER_SPEC_URL,
  REDOC_FRAME_MESSAGE_SOURCE,
  REDOC_SCRIPT_FALLBACK_URLS,
  REDOC_SCRIPT_URL,
  containsRedocFatalError,
  loadCertifiedOpenApi,
  loadExternalAsset,
  loadExternalAssetCandidates,
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

  postFrameMessage(windowObject, 'boot', { version: '2.5.3' });
  try {
    postFrameMessage(windowObject, 'phase', { phase: 'contract' });
    const specification = await specLoader(windowObject, OPENAPI_RENDER_SPEC_URL);
    postFrameMessage(windowObject, 'phase', { phase: 'renderer' });
    if (assetLoader === loadExternalAsset) {
      await loadExternalAssetCandidates(
        documentObject,
        windowObject,
        [REDOC_SCRIPT_URL, ...REDOC_SCRIPT_FALLBACK_URLS],
        'script',
      );
    } else {
      await assetLoader(documentObject, windowObject, REDOC_SCRIPT_URL, 'script');
    }
    const redoc = windowObject?.Redoc ?? globalThis.Redoc;
    if (!redoc || typeof redoc.init !== 'function') throw new Error('ReDoc bundle did not expose Redoc.init');
    root.replaceChildren?.();
    postFrameMessage(windowObject, 'phase', { phase: 'render' });
    await initializeRenderer(redoc, specification, redocConfiguration(documentObject), root);
    if (containsRedocFatalError(root)) throw new Error('ReDoc reported an embedded rendering failure');
    installHeightReporter(documentObject, windowObject, root);
    postFrameMessage(windowObject, 'ready');
    reportHeight(documentObject, windowObject);
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

export function reportHeight(documentObject, windowObject) {
  const height = Math.max(
    Number(documentObject?.documentElement?.scrollHeight ?? 0),
    Number(documentObject?.body?.scrollHeight ?? 0),
  );
  if (height > 0) postFrameMessage(windowObject, 'resize', { height });
  return height;
}

function installHeightReporter(documentObject, windowObject, root) {
  const resizeObserver = windowObject?.ResizeObserver;
  if (typeof resizeObserver !== 'function') return null;
  const observer = new resizeObserver(() => reportHeight(documentObject, windowObject));
  observer.observe(root);
  windowObject?.addEventListener?.('pagehide', () => observer.disconnect(), { once: true });
  return observer;
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
