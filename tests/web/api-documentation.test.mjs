import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

import {
  OPENAPI_SPEC_URL,
  OPENAPI_RENDER_SPEC_URL,
  REDOC_VERSION,
  SWAGGER_UI_VERSION,
  redocConfiguration,
  renderRedoc,
  validateCertifiedOpenApi,
  swaggerConfiguration,
} from '../../src/applications/web/public/assets/api-documentation.mjs';

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

test('documentation CSP permits only the pinned renderer CDN origin while the OpenAPI document remains same-origin', async () => {
  const source = await read('src/applications/web/runtime/web-application.mjs');
  assert.match(source, /script-src 'self' https:\/\/cdn\.jsdelivr\.net/);
  assert.match(source, /style-src 'self' https:\/\/cdn\.jsdelivr\.net/);
  assert.doesNotMatch(source, /script-src[^;]*https:\/\/(?!cdn\.jsdelivr\.net)/);
});


test('ReDoc renderer initializes the certified local specification and reaches the ready state', async () => {
  const attributes = new Map();
  const status = {
    className: '', hidden: false, textContent: '',
    setAttribute: (name, value) => attributes.set(name, value),
  };
  const raw = { hidden: true };
  let replaced = 0;
  const host = { replaceChildren: () => { replaced += 1; } };
  const documentObject = {
    documentElement: { getAttribute: (name) => name === 'lang' ? 'en' : 'light' },
    getElementById: (id) => ({ 'redoc-ui': host, 'redoc-docs-status': status, 'redoc-raw-spec': raw }[id] ?? null),
  };
  let initializedSpec = null;
  const windowObject = {
    Redoc: {
      init: (specification, configuration, target, done) => {
        initializedSpec = specification;
        assert.equal(target, host);
        assert.equal(configuration.theme.colors.primary.main, '#003d8f');
        done();
      },
    },
  };
  const assetLoader = async (_document, _window, url, kind) => {
    assert.match(url, /redoc@2\.5\.3/);
    assert.equal(kind, 'script');
  };

  const specLoader = async () => ({ openapi: '3.1.0', info: { version: '2.0.0-alpha.0.114' }, paths: {} });
  assert.equal(await renderRedoc(documentObject, windowObject, assetLoader, specLoader), true);
  assert.equal(initializedSpec.openapi, '3.1.0');
  assert.equal(replaced, 1);
  assert.equal(attributes.get('data-state'), 'ready');
  assert.equal(status.hidden, true);
});


test('ReDoc rejects malformed embedded contracts before invoking the renderer', () => {
  assert.throws(() => validateCertifiedOpenApi(null), /must be an object/);
  assert.throws(() => validateCertifiedOpenApi({ openapi: '3.0.3', info: { version: 'x' }, paths: {} }), /3\.1\.0/);
  assert.throws(() => validateCertifiedOpenApi({ openapi: '3.1.0', info: {}, paths: {} }), /metadata/);
  assert.equal(validateCertifiedOpenApi({ openapi: '3.1.0', info: { version: 'x' }, paths: {} }), true);
});
