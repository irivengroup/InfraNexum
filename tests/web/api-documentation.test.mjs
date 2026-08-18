import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

import {
  OPENAPI_SPEC_URL,
  OPENAPI_RENDER_SPEC_URL,
  REDOC_FRAME_MESSAGE_SOURCE,
  REDOC_FRAME_URL,
  REDOC_SCRIPT_URL,
  REDOC_VERSION,
  SWAGGER_UI_VERSION,
  redocConfiguration,
  renderRedoc,
  validateCertifiedOpenApi,
  swaggerConfiguration,
} from '../../src/applications/web/public/assets/api-documentation.mjs';
import { initializeRedocFrame } from '../../src/applications/web/public/assets/redoc-frame.mjs';

const root = path.resolve(import.meta.dirname, '..', '..');
const read = (relative) => readFile(path.join(root, relative), 'utf8');

test('documentation navigation exposes Swagger and ReDoc inside the authenticated InfraNexum shell', async () => {
  const [index, shell] = await Promise.all([
    read('src/applications/web/public/index.html'),
    read('src/applications/web/public/assets/admin-shell.mjs'),
  ]);
  assert.match(index, /data-i18n="nav\.documentation">Documentation/);
  assert.match(index, /id="nav-swagger"[^>]+data-route="swagger"/);
  assert.match(index, /id="nav-redoc"[^>]+data-route="redoc"/);
  assert.match(index, /id="swagger-workspace"[^>]+data-view="swagger"/);
  assert.match(index, /id="redoc-workspace"[^>]+data-view="redoc"/);
  assert.match(shell, /swagger:\s*Object\.freeze\(\{\s*viewId:\s*'swagger-workspace'/);
  assert.match(shell, /redoc:\s*Object\.freeze\(\{\s*viewId:\s*'redoc-workspace'/);
  assert.match(shell, /infranexum:route-change/);
});

test('documentation renderers are version-pinned, read the local certified contract and keep Swagger mutations disabled', () => {
  assert.equal(SWAGGER_UI_VERSION, '5.32.13');
  assert.equal(REDOC_VERSION, '2.5.3');
  assert.equal(OPENAPI_SPEC_URL, '/assets/generated/infranexum-openapi.yaml');
  assert.equal(OPENAPI_RENDER_SPEC_URL, '/assets/generated/infranexum-openapi.json');
  assert.equal(REDOC_FRAME_URL, '/assets/redoc-frame.html');
  assert.match(REDOC_SCRIPT_URL, /redoc@2\.5\.3\/bundles\/redoc\.standalone\.js$/);
  const swagger = swaggerConfiguration();
  assert.equal(swagger.url, OPENAPI_SPEC_URL);
  assert.equal(swagger.deepLinking, true);
  assert.deepEqual([...swagger.supportedSubmitMethods], ['get', 'head', 'options']);
  assert.equal(swagger.persistAuthorization, false);
  assert.equal(swagger.tryItOutEnabled, false);
});

test('ReDoc follows InfraNexum light/dark palette and typography tokens', () => {
  const light = redocConfiguration({ documentElement: { getAttribute: () => 'light' } });
  const dark = redocConfiguration({ documentElement: { getAttribute: () => 'dark' } });
  assert.equal(light.theme.colors.primary.main, '#003d8f');
  assert.equal(light.theme.colors.success.main, '#087f5b');
  assert.equal(light.theme.colors.warning.main, '#ffaa00');
  assert.equal(light.theme.rightPanel.backgroundColor, '#001b41');
  assert.notEqual(light.theme.colors.text.primary, dark.theme.colors.text.primary);
});

test('documentation CSP isolates ReDoc inline styling without weakening the authenticated shell', async () => {
  const source = await read('src/applications/web/runtime/web-application.mjs');
  const shell = source.match(/SHELL_CONTENT_SECURITY_POLICY = "([^"]+)"/)?.[1] ?? '';
  const redoc = source.match(/REDOC_FRAME_CONTENT_SECURITY_POLICY = "([^"]+)"/)?.[1] ?? '';
  assert.match(shell, /script-src 'self' https:\/\/cdn\.jsdelivr\.net/);
  assert.match(shell, /style-src 'self' https:\/\/cdn\.jsdelivr\.net/);
  assert.doesNotMatch(shell, /unsafe-inline/);
  assert.match(shell, /frame-src 'self'/);
  assert.match(redoc, /frame-ancestors 'self'/);
  assert.match(redoc, /style-src 'self' 'unsafe-inline'/);
  assert.match(redoc, /script-src 'self' https:\/\/cdn\.jsdelivr\.net/);
  assert.doesNotMatch(redoc, /connect-src[^;]*https:/);
});


test('ReDoc shell renderer creates an isolated auto-sized frame and reaches ready state only after its handshake', async () => {
  const attributes = new Map();
  const status = {
    className: '', hidden: false, textContent: '',
    setAttribute: (name, value) => attributes.set(name, value),
  };
  const raw = { hidden: true };
  const contentWindow = {};
  const frameAttributes = new Map();
  const frame = {
    contentWindow,
    setAttribute: (name, value) => frameAttributes.set(name, value),
  };
  let appended = null;
  const host = { replaceChildren: (child) => { appended = child ?? null; } };
  const documentObject = {
    documentElement: { getAttribute: (name) => name === 'lang' ? 'en' : name === 'data-bs-theme' ? 'dark' : null },
    getElementById: (id) => ({ 'redoc-ui': host, 'redoc-docs-status': status, 'redoc-raw-spec': raw }[id] ?? null),
    createElement: (name) => name === 'iframe' ? frame : null,
  };
  let messageHandler = null;
  const windowObject = {
    location: { origin: 'https://infranexum.example' },
    addEventListener: (name, handler) => { if (name === 'message') messageHandler = handler; },
    removeEventListener: () => {},
    setTimeout: () => 17,
    clearTimeout: () => {},
  };

  const rendering = renderRedoc(documentObject, windowObject);
  assert.equal(appended, frame);
  assert.equal(frame.id, 'redoc-frame');
  assert.equal(frame.className, 'inx-redoc-frame');
  assert.equal(frame.src, `${REDOC_FRAME_URL}?theme=dark`);
  assert.equal(frameAttributes.get('sandbox'), 'allow-scripts allow-same-origin');
  assert.equal(frameAttributes.get('scrolling'), 'no');
  assert.equal(frameAttributes.get('height'), '576');

  messageHandler({
    origin: 'https://infranexum.example',
    source: contentWindow,
    data: { source: REDOC_FRAME_MESSAGE_SOURCE, type: 'resize', height: 1280.2 },
  });
  assert.equal(frameAttributes.get('height'), '1281');
  messageHandler({
    origin: 'https://infranexum.example',
    source: contentWindow,
    data: { source: REDOC_FRAME_MESSAGE_SOURCE, type: 'ready' },
  });
  assert.equal(await rendering, true);
  assert.equal(attributes.get('data-state'), 'ready');
  assert.equal(status.hidden, true);
});


test('ReDoc frame loads the certified local contract and pinned renderer inside its isolated document', async () => {
  const htmlAttributes = new Map();
  const root = { textContent: 'loading', replaceChildren: () => {} };
  const messages = [];
  const documentObject = {
    documentElement: {
      scrollHeight: 900,
      getAttribute: (name) => name === 'data-bs-theme' ? htmlAttributes.get(name) ?? 'light' : null,
      setAttribute: (name, value) => htmlAttributes.set(name, value),
    },
    body: { scrollHeight: 850 },
    getElementById: (id) => id === 'redoc-frame-root' ? root : null,
  };
  let initializedSpec = null;
  const windowObject = {
    location: { href: 'https://infranexum.example/assets/redoc-frame.html?theme=dark', origin: 'https://infranexum.example' },
    parent: { postMessage: (payload, origin) => messages.push({ payload, origin }) },
    Redoc: {
      init: (specification, configuration, target, done) => {
        initializedSpec = specification;
        assert.equal(target, root);
        assert.equal(configuration.theme.colors.text.primary, '#f2f7ff');
        done();
      },
    },
  };
  const assetLoader = async (_document, _window, url, kind) => {
    assert.equal(url, REDOC_SCRIPT_URL);
    assert.equal(kind, 'script');
  };
  const specLoader = async (_window, url) => {
    assert.equal(url, OPENAPI_RENDER_SPEC_URL);
    return { openapi: '3.1.0', info: { version: '2.0.0-alpha.0.122' }, paths: {} };
  };

  assert.equal(await initializeRedocFrame(documentObject, windowObject, assetLoader, specLoader), true);
  assert.equal(initializedSpec.openapi, '3.1.0');
  assert.equal(htmlAttributes.get('data-bs-theme'), 'dark');
  assert.ok(messages.some(({ payload }) => payload.type === 'ready'));
  assert.ok(messages.some(({ payload }) => payload.type === 'resize' && payload.height === 900));
  assert.ok(messages.every(({ payload }) => payload.source === REDOC_FRAME_MESSAGE_SOURCE));
  assert.ok(messages.every(({ origin }) => origin === 'https://infranexum.example'));
});


test('ReDoc rejects malformed embedded contracts before invoking the renderer', () => {
  assert.throws(() => validateCertifiedOpenApi(null), /must be an object/);
  assert.throws(() => validateCertifiedOpenApi({ openapi: '3.0.3', info: { version: 'x' }, paths: {} }), /3\.1\.0/);
  assert.throws(() => validateCertifiedOpenApi({ openapi: '3.1.0', info: {}, paths: {} }), /metadata/);
  assert.equal(validateCertifiedOpenApi({ openapi: '3.1.0', info: { version: 'x' }, paths: {} }), true);
});
