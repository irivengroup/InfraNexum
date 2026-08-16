import { localeFromDocument, translate } from './i18n.mjs';

export const OPENAPI_SPEC_URL = '/assets/generated/infranexum-openapi.yaml';
export const SWAGGER_UI_VERSION = '5.32.13';
export const REDOC_VERSION = '2.5.3';
const SWAGGER_SCRIPT = `https://cdn.jsdelivr.net/npm/swagger-ui-dist@${SWAGGER_UI_VERSION}/swagger-ui-bundle.js`;
const SWAGGER_STYLE = `https://cdn.jsdelivr.net/npm/swagger-ui-dist@${SWAGGER_UI_VERSION}/swagger-ui.css`;
const REDOC_SCRIPT = `https://cdn.jsdelivr.net/npm/redoc@${REDOC_VERSION}/bundles/redoc.standalone.js`;
const ASSET_TIMEOUT_MS = 15_000;
const initialized = new Set();

/**
 * Lazy, bounded documentation renderers. The OpenAPI contract is always local;
 * only the presentation libraries are loaded from pinned upstream distributions.
 * If the renderer cannot load, the raw local contract remains available.
 */
export function initializeApiDocumentation(documentObject = document, windowObject = globalThis.window, assetLoader = loadExternalAsset) {
  const render = (route) => {
    if (route === 'swagger') void renderSwagger(documentObject, windowObject, assetLoader);
    if (route === 'redoc') void renderRedoc(documentObject, windowObject, assetLoader);
  };
  documentObject?.addEventListener?.('infranexum:route-change', (event) => render(event?.detail?.route));
  render(documentObject?.documentElement?.getAttribute?.('data-route'));
  documentObject?.addEventListener?.('infranexum:theme-change', () => {
    if (documentObject?.documentElement?.getAttribute?.('data-route') === 'redoc' && initialized.has('redoc')) {
      initialized.delete('redoc');
      const host = documentObject?.getElementById?.('redoc-ui');
      host?.replaceChildren?.();
      void renderRedoc(documentObject, windowObject, assetLoader);
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

export async function renderRedoc(documentObject, windowObject, assetLoader = loadExternalAsset) {
  const host = documentObject?.getElementById?.('redoc-ui');
  if (!host || initialized.has('redoc')) return Boolean(host);
  setLoading(documentObject, 'redoc');
  try {
    await assetLoader(documentObject, windowObject, REDOC_SCRIPT, 'script');
    const redoc = windowObject?.Redoc ?? globalThis.Redoc;
    if (!redoc || typeof redoc.init !== 'function') throw new Error('ReDoc bundle did not expose Redoc.init');
    host.replaceChildren?.();
    await new Promise((resolve, reject) => {
      try { redoc.init(OPENAPI_SPEC_URL, redocConfiguration(documentObject), host, resolve); }
      catch (error) { reject(error); }
    });
    initialized.add('redoc');
    setReady(documentObject, 'redoc');
    return true;
  } catch (error) {
    setUnavailable(documentObject, 'redoc', error);
    return false;
  }
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

export function loadExternalAsset(documentObject, windowObject, url, kind) {
  if (!['script', 'style'].includes(kind)) return Promise.reject(new TypeError(`unsupported asset kind ${kind}`));
  const selector = `[data-inx-doc-asset="${url}"]`;
  const existing = documentObject?.querySelector?.(selector);
  if (existing?.getAttribute?.('data-inx-loaded') === 'true') return Promise.resolve(existing);
  return new Promise((resolve, reject) => {
    const element = existing ?? documentObject?.createElement?.(kind === 'style' ? 'link' : 'script');
    if (!element) { reject(new Error('document asset creation unavailable')); return; }
    element.setAttribute?.('data-inx-doc-asset', url);
    if (kind === 'style') { element.rel = 'stylesheet'; element.href = url; }
    else { element.src = url; element.async = true; element.defer = true; }
    const clear = () => windowObject?.clearTimeout?.(timer);
    element.onload = () => { clear(); element.setAttribute?.('data-inx-loaded', 'true'); resolve(element); };
    element.onerror = () => { clear(); reject(new Error(`documentation renderer asset unavailable: ${url}`)); };
    const timer = windowObject?.setTimeout?.(() => reject(new Error(`documentation renderer asset timeout: ${url}`)), ASSET_TIMEOUT_MS);
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
function safeMessage(error) {
  const value = String(error?.message ?? '').replace(/https?:\/\/\S+/g, '').trim();
  return value.length > 180 ? `${value.slice(0, 177)}…` : value;
}
