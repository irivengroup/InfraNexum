import { localeFromDocument, translate } from './i18n.mjs';

export const OPENAPI_SPEC_URL = '/assets/generated/infranexum-openapi.yaml';
export const OPENAPI_RENDER_SPEC_URL = '/assets/generated/infranexum-openapi.json';
export const SWAGGER_UI_VERSION = '5.32.13';
export const REDOC_VERSION = '2.5.3';
const SWAGGER_SCRIPT = `https://cdn.jsdelivr.net/npm/swagger-ui-dist@${SWAGGER_UI_VERSION}/swagger-ui-bundle.js`;
const SWAGGER_STYLE = `https://cdn.jsdelivr.net/npm/swagger-ui-dist@${SWAGGER_UI_VERSION}/swagger-ui.css`;
export const REDOC_SCRIPT_URL = `/assets/vendor/redoc/${REDOC_VERSION}/redoc.standalone.js`;
// Kept as a stable export for downstream imports; ReDoc has no runtime network fallback.
export const REDOC_SCRIPT_FALLBACK_URLS = Object.freeze([]);
export const REDOC_FRAME_URL = '/assets/redoc-frame.html';
export const REDOC_FRAME_MESSAGE_SOURCE = 'infranexum-redoc-frame';
const ASSET_TIMEOUT_MS = 10_000;
const REDOC_FRAME_BOOT_TIMEOUT_MS = 8_000;
const REDOC_FRAME_RENDER_TIMEOUT_MS = 40_000;
const redocBridges = new WeakMap();
const initialized = new Set();

/**
 * Lazy, bounded documentation renderers. The OpenAPI contract is always local;
 * presentation libraries are loaded only from pinned upstream distributions.
 * If a renderer cannot load, the raw local contract remains available.
 */
export function initializeApiDocumentation(
  documentObject = document,
  windowObject = globalThis.window,
  assetLoader = loadExternalAsset,
  redocFrameFactory = createRedocFrame,
) {
  const render = (route) => {
    if (route === 'swagger') void renderSwagger(documentObject, windowObject, assetLoader);
    if (route === 'redoc') void renderRedoc(documentObject, windowObject, redocFrameFactory);
  };
  documentObject?.addEventListener?.('infranexum:route-change', (event) => render(event?.detail?.route));
  render(documentObject?.documentElement?.getAttribute?.('data-current-route'));
  documentObject?.addEventListener?.('infranexum:theme-change', () => {
    if (documentObject?.documentElement?.getAttribute?.('data-current-route') === 'redoc') {
      initialized.delete('redoc');
      const host = documentObject?.getElementById?.('redoc-ui');
      detachRedocBridge(host, windowObject);
      host?.replaceChildren?.();
      void renderRedoc(documentObject, windowObject, redocFrameFactory);
    }
  });
  return Object.freeze({ render });
}

export async function renderSwagger(documentObject, windowObject, assetLoader = loadExternalAsset) {
  const host = documentObject?.getElementById?.('swagger-ui');
  if (!host || initialized.has('swagger')) return Boolean(host);
  setLoading(documentObject, 'swagger');
  try {
    await assetLoader(documentObject, windowObject, SWAGGER_STYLE, 'style');
    await assetLoader(documentObject, windowObject, SWAGGER_SCRIPT, 'script');
    const renderer = windowObject?.SwaggerUIBundle ?? globalThis.SwaggerUIBundle;
    if (typeof renderer !== 'function') throw new Error('Swagger UI bundle did not expose SwaggerUIBundle');
    host.replaceChildren?.();
    renderer(swaggerConfiguration('#swagger-ui'));
    initialized.add('swagger');
    setReady(documentObject, 'swagger');
    return true;
  } catch (error) {
    setUnavailable(documentObject, 'swagger', error);
    return false;
  }
}

export async function renderRedoc(documentObject, windowObject, frameFactory = createRedocFrame) {
  const host = documentObject?.getElementById?.('redoc-ui');
  if (!host || initialized.has('redoc')) return Boolean(host);
  setLoading(documentObject, 'redoc');
  detachRedocBridge(host, windowObject);
  try {
    const frame = frameFactory(documentObject, redocFrameConfiguration(documentObject));
    if (!frame) throw new Error('ReDoc frame creation unavailable');
    host.replaceChildren?.(frame);
    return await waitForRedocFrame(documentObject, windowObject, host, frame);
  } catch (error) {
    setUnavailable(documentObject, 'redoc', error);
    return false;
  }
}

/**
 * Creates the isolated ReDoc browsing context. ReDoc's styled-components runtime
 * is intentionally kept out of the InfraNexum shell so its injected styles do
 * not collide with Bootstrap/product CSS and do not require weakening shell CSP.
 */
export function createRedocFrame(documentObject, configuration = {}) {
  const frame = documentObject?.createElement?.('iframe');
  if (!frame) return null;
  const theme = configuration.theme === 'dark' ? 'dark' : 'light';
  frame.id = 'redoc-frame';
  frame.className = 'inx-redoc-frame';
  frame.title = configuration.title ?? 'ReDoc API reference';
  frame.src = `${REDOC_FRAME_URL}?theme=${encodeURIComponent(theme)}`;
  frame.loading = 'eager';
  frame.referrerPolicy = 'no-referrer';
  frame.setAttribute?.('sandbox', 'allow-scripts allow-same-origin');
  frame.setAttribute?.('scrolling', 'no');
  return frame;
}

export function redocFrameConfiguration(documentObject) {
  return Object.freeze({
    theme: documentObject?.documentElement?.getAttribute?.('data-bs-theme') === 'dark' ? 'dark' : 'light',
    title: translate(localeFromDocument(documentObject), 'docs.redoc.title'),
  });
}

function waitForRedocFrame(documentObject, windowObject, host, frame) {
  return new Promise((resolve) => {
    let settled = false;
    let booted = false;
    let contentHeight = 0;
    let bootTimer;
    let renderTimer;

    const applyHeight = () => {
      const viewportHeight = normalizeViewportHeight(windowObject);
      const height = Math.max(contentHeight, viewportHeight);
      if (height <= 0) return;
      const value = String(Math.ceil(height));
      frame.setAttribute?.('height', value);
      if (frame.style) frame.style.height = `${value}px`;
    };
    const dispose = () => {
      windowObject?.clearTimeout?.(bootTimer);
      windowObject?.clearTimeout?.(renderTimer);
      windowObject?.removeEventListener?.('message', onMessage);
      windowObject?.removeEventListener?.('resize', onViewportResize);
      frame?.removeEventListener?.('error', onFrameError);
    };
    const finish = (ok, error) => {
      if (settled) return;
      settled = true;
      windowObject?.clearTimeout?.(bootTimer);
      windowObject?.clearTimeout?.(renderTimer);
      frame?.removeEventListener?.('error', onFrameError);
      if (ok) {
        initialized.add('redoc');
        setReady(documentObject, 'redoc');
        resolve(true);
        return;
      }
      dispose();
      if (host) redocBridges.delete(host);
      setUnavailable(documentObject, 'redoc', error);
      resolve(false);
    };
    const armRenderDeadline = () => {
      windowObject?.clearTimeout?.(renderTimer);
      renderTimer = windowObject?.setTimeout?.(
        () => finish(false, new Error('ReDoc rendering did not complete before the deadline')),
        REDOC_FRAME_RENDER_TIMEOUT_MS,
      );
    };
    const onFrameError = () => finish(false, new Error('ReDoc frame could not be loaded'));
    const onViewportResize = () => applyHeight();
    const onMessage = (event) => {
      if (!isTrustedRedocFrameMessage(event, frame, windowObject)) return;
      const payload = event.data;
      if (payload.type === 'resize') {
        const height = normalizeRedocContentHeight(payload.height);
        if (height !== null) {
          contentHeight = height;
          applyHeight();
        }
        return;
      }
      if (payload.type === 'boot') {
        booted = true;
        windowObject?.clearTimeout?.(bootTimer);
        armRenderDeadline();
        return;
      }
      if (payload.type === 'phase') {
        if (!booted) {
          booted = true;
          windowObject?.clearTimeout?.(bootTimer);
        }
        armRenderDeadline();
        return;
      }
      if (payload.type === 'ready') finish(true);
      if (payload.type === 'error') {
        const message = normalizeDisplayMessage(payload.message, 'ReDoc rendering failed');
        finish(false, new Error(message));
      }
    };
    bootTimer = windowObject?.setTimeout?.(
      () => finish(false, new Error('ReDoc frame did not start')),
      REDOC_FRAME_BOOT_TIMEOUT_MS,
    );
    windowObject?.addEventListener?.('message', onMessage);
    windowObject?.addEventListener?.('resize', onViewportResize);
    frame?.addEventListener?.('error', onFrameError, { once: true });
    redocBridges.set(host, { dispose });
    applyHeight();
  });
}

function isTrustedRedocFrameMessage(event, frame, windowObject) {
  if (event?.data?.source !== REDOC_FRAME_MESSAGE_SOURCE || event?.source !== frame?.contentWindow) return false;
  const expectedOrigin = windowObject?.location?.origin;
  return !expectedOrigin || expectedOrigin === 'null' || event.origin === expectedOrigin;
}

function normalizeRedocContentHeight(value) {
  const height = Number(value);
  if (!Number.isFinite(height) || height <= 0) return null;
  return Math.ceil(height);
}

function normalizeViewportHeight(windowObject) {
  const height = Number(windowObject?.innerHeight ?? 0);
  return Number.isFinite(height) && height > 0 ? Math.ceil(height) : 0;
}

function detachRedocBridge(host, windowObject) {
  const bridge = host && redocBridges.get(host);
  bridge?.dispose?.();
  if (host) redocBridges.delete(host);
  // Defensive cleanup for test/window implementations that omit removeEventListener.
  void windowObject;
}

export async function loadCertifiedOpenApi(windowObject = globalThis.window, url = OPENAPI_RENDER_SPEC_URL) {
  const fetchFunction = windowObject?.fetch ?? globalThis.fetch;
  if (typeof fetchFunction !== 'function') throw new Error('OpenAPI fetch is unavailable');
  const controller = typeof AbortController === 'function' ? new AbortController() : null;
  const timer = windowObject?.setTimeout?.(() => controller?.abort?.(), ASSET_TIMEOUT_MS);
  try {
    const response = await fetchFunction(url, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' }, signal: controller?.signal });
    if (!response?.ok) throw new Error(`OpenAPI contract unavailable (HTTP ${response?.status ?? 'unknown'})`);
    const specification = await response.json();
    validateCertifiedOpenApi(specification);
    return specification;
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error('OpenAPI contract loading timed out');
    throw error;
  } finally {
    if (timer !== undefined) windowObject?.clearTimeout?.(timer);
  }
}

export function validateCertifiedOpenApi(specification) {
  if (!specification || typeof specification !== 'object' || Array.isArray(specification)) throw new TypeError('OpenAPI contract must be an object');
  if (specification.openapi !== '3.1.0') throw new TypeError('OpenAPI contract must use version 3.1.0');
  if (!specification.info || typeof specification.info !== 'object' || typeof specification.info.version !== 'string') throw new TypeError('OpenAPI contract metadata is incomplete');
  if (!specification.paths || typeof specification.paths !== 'object' || Array.isArray(specification.paths)) throw new TypeError('OpenAPI contract paths are invalid');
  return true;
}

export function containsRedocFatalError(host) {
  const text = String(host?.textContent ?? '');
  return /Something went wrong|ReDoc Version:|Stack trace/i.test(text);
}

export function swaggerConfiguration(domId = '#swagger-ui') {
  return Object.freeze({
    url: OPENAPI_SPEC_URL,
    dom_id: domId,
    deepLinking: true,
    filter: true,
    displayRequestDuration: true,
    docExpansion: 'list',
    defaultModelsExpandDepth: 1,
    persistAuthorization: false,
    tryItOutEnabled: false,
    supportedSubmitMethods: Object.freeze(['get', 'head', 'options']),
  });
}

export function redocConfiguration(documentObject) {
  const dark = documentObject?.documentElement?.getAttribute?.('data-bs-theme') === 'dark';
  return Object.freeze({
    hideDownloadButton: false,
    hideHostname: false,
    nativeScrollbars: true,
    requiredPropsFirst: true,
    sortPropsAlphabetically: false,
    theme: {
      colors: {
        primary: { main: '#003d8f' },
        success: { main: '#087f5b' },
        warning: { main: '#ffaa00' },
        text: { primary: dark ? '#f2f7ff' : '#173a63', secondary: dark ? '#b9cce2' : '#526b86' },
        border: { dark: dark ? '#36506d' : '#c7d5e5', light: dark ? '#243b55' : '#e8eef6' },
      },
      typography: {
        fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
        headings: { fontFamily: 'inherit', fontWeight: '720' },
        links: { color: '#006f75' },
      },
      sidebar: { backgroundColor: dark ? '#0b223d' : '#f5f9ff', textColor: dark ? '#dcecff' : '#173a63', activeTextColor: '#003d8f' },
      rightPanel: { backgroundColor: dark ? '#08192d' : '#001b41', textColor: '#f8fbff' },
      codeBlock: { backgroundColor: dark ? '#07182a' : '#001b41' },
    },
  });
}

export async function loadExternalAssetCandidates(documentObject, windowObject, urls, kind) {
  let lastError = new Error('documentation renderer asset unavailable');
  for (const url of urls) {
    try {
      return await loadExternalAsset(documentObject, windowObject, url, kind);
    } catch (error) {
      lastError = error instanceof Error ? error : new Error(normalizeDisplayMessage(error, 'documentation renderer asset unavailable'));
    }
  }
  throw lastError;
}

export function loadExternalAsset(documentObject, windowObject, url, kind) {
  if (!['script', 'style'].includes(kind)) return Promise.reject(new TypeError(`unsupported asset kind ${kind}`));
  const selector = `[data-inx-doc-asset="${url}"]`;
  const existing = documentObject?.querySelector?.(selector);
  if (existing?.getAttribute?.('data-inx-loaded') === 'true') return Promise.resolve(existing);
  return new Promise((resolve, reject) => {
    let settled = false;
    const element = existing ?? documentObject?.createElement?.(kind === 'style' ? 'link' : 'script');
    if (!element) { reject(new Error('document asset creation unavailable')); return; }
    element.setAttribute?.('data-inx-doc-asset', url);
    if (kind === 'style') { element.rel = 'stylesheet'; element.href = url; }
    else { element.src = url; element.async = true; element.defer = true; }
    let timer;
    const cleanup = (removeFailed = false) => {
      windowObject?.clearTimeout?.(timer);
      element.onload = null;
      element.onerror = null;
      if (removeFailed) element.remove?.();
    };
    const finish = (ok, error) => {
      if (settled) return;
      settled = true;
      cleanup(!ok);
      if (ok) {
        element.setAttribute?.('data-inx-loaded', 'true');
        resolve(element);
      } else {
        reject(error);
      }
    };
    element.onload = () => finish(true);
    element.onerror = () => finish(false, new Error(`documentation renderer asset unavailable: ${url}`));
    timer = windowObject?.setTimeout?.(
      () => finish(false, new Error(`documentation renderer asset timeout: ${url}`)),
      ASSET_TIMEOUT_MS,
    );
    if (!existing) documentObject?.head?.appendChild?.(element);
  });
}

function setLoading(documentObject, renderer) { setState(documentObject, renderer, 'loading', 'docs.loading'); }
function setReady(documentObject, renderer) { setState(documentObject, renderer, 'ready', 'docs.ready'); }
function setUnavailable(documentObject, renderer, error) {
  const status = documentObject?.getElementById?.(`${renderer}-docs-status`);
  if (!status) return;
  status.className = 'alert alert-warning py-2 inx-docs-status';
  status.setAttribute?.('data-state', 'unavailable');
  status.textContent = `${translate(localeFromDocument(documentObject), 'docs.unavailable')} ${safeMessage(error)}`.trim();
  const raw = documentObject?.getElementById?.(`${renderer}-raw-spec`);
  if (raw) raw.hidden = false;
}
function setState(documentObject, renderer, state, key) {
  const status = documentObject?.getElementById?.(`${renderer}-docs-status`);
  if (!status) return;
  status.className = `alert ${state === 'ready' ? 'alert-success' : 'alert-info'} py-2 inx-docs-status`;
  status.setAttribute?.('data-state', state);
  status.textContent = translate(localeFromDocument(documentObject), key);
  if (state === 'ready') status.hidden = true;
}
export function normalizeDisplayMessage(value, fallback = '') {
  const candidate = value instanceof Error ? value.message : value?.message ?? value;
  if (candidate && typeof candidate.then === 'function') return fallback;
  if (candidate && typeof candidate === 'object') return fallback;
  const text = String(candidate ?? '').replace(/https?:\/\/\S+/g, '').trim();
  if (!text || /^\[object (?:Promise|Object)\]$/.test(text)) return fallback;
  return text.length > 180 ? `${text.slice(0, 177)}…` : text;
}

function safeMessage(error) {
  return normalizeDisplayMessage(error, '');
}
