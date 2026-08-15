import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { WebRuntimeConfiguration } from '../../src/applications/web/runtime/config.mjs';
import { JsonLogger } from '../../src/applications/web/runtime/logger.mjs';
import { StaticAssetStore } from '../../src/applications/web/runtime/static-assets.mjs';
import { WebApplication } from '../../src/applications/web/runtime/web-application.mjs';

class Sink { chunks = []; write(value) { this.chunks.push(value); } }

async function createFixture(overrides = {}) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'infranexum-web-app-'));
  await mkdir(path.join(root, 'assets'));
  await writeFile(path.join(root, 'index.html'), '<!doctype html><title>InfraNexum</title>');
  await writeFile(path.join(root, 'assets', 'app.12345678.js'), 'export {};');
  const configuration = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_LISTEN_ADDRESS: '127.0.0.1:0',
    INFRANEXUM_WEB_STATIC_ROOT: root,
    INFRANEXUM_WEB_ENVIRONMENT: 'test',
    INFRANEXUM_WEB_API_BASE_URL: 'http://127.0.0.1:9090/api',
    INFRANEXUM_WEB_SHUTDOWN_TIMEOUT_MS: '1000',
    ...overrides,
  }, { version: '2.0.0-alpha.0.83', baseDirectory: root });
  const sink = new Sink();
  const application = new WebApplication({
    configuration,
    assets: new StaticAssetStore(configuration.staticRoot),
    logger: new JsonLogger({ sink, clock: () => new Date('2026-08-03T12:00:00Z') }),
  });
  return { application, sink, root };
}

async function request(base, pathname, options) {
  return fetch(`${base}${pathname}`, options);
}

test('application validates composition root dependencies', () => {
  const valid = { publicConfiguration() {} };
  const assets = { initialize() {}, read() {} };
  const logger = { info() {}, error() {} };
  assert.throws(() => new WebApplication({ configuration: null, assets, logger }), /configuration/);
  assert.throws(() => new WebApplication({ configuration: valid, assets: null, logger }), /asset store/);
  assert.throws(() => new WebApplication({ configuration: valid, assets, logger: null }), /logger/);
});

test('application exposes health, build, public configuration and static assets securely', async (context) => {
  const { application, sink } = await createFixture();
  context.after(() => application.stop());
  const base = await application.start();
  assert.equal(application.state, 'ready');
  assert.equal(application.address, base);

  for (const endpoint of ['/health/live', '/health/ready', '/health/startup']) {
    const response = await request(base, endpoint);
    assert.equal(response.status, 200, endpoint);
    assert.deepEqual(await response.json(), { status: 'UP' });
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    assert.match(response.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  }

  const runtime = await request(base, '/runtime-config.json');
  assert.equal(runtime.status, 200);
  assert.deepEqual(await runtime.json(), {
    schema: 'infranexum.web-runtime-config/v1',
    product: 'InfraNexum',
    component: 'web',
    version: '2.0.0-alpha.0.83',
    architectureBaseline: '2.0.0-draft.21',
    environment: 'test',
    apiBaseUrl: 'http://127.0.0.1:9090/api',
    organizationFoundationEnabled: false,
    localAuthEnabled: false,
    identityAccessEnabled: false,
    advancedAuthorizationEnabled: false,
    rsotCoreEnabled: false,
    itamPartnersEnabled: false,
    itamAssetsEnabled: false,
    itamComplianceEnabled: false,
  dcimFacilitiesEnabled: false,
    dcimPhysicalEnabled: false,
    ddiIpamEnabled: false,
  });

  const build = await request(base, '/api/v1/system/build');
  assert.equal(build.status, 200);
  assert.deepEqual(await build.json(), {
    product: 'InfraNexum',
    component: 'WEB',
    version: '2.0.0-alpha.0.83',
    architectureBaseline: '2.0.0-draft.21',
    environment: 'test',
  });

  const page = await request(base, '/');
  assert.equal(page.status, 200);
  assert.match(await page.text(), /InfraNexum/);
  assert.equal(page.headers.get('content-type'), 'text/html; charset=utf-8');
  assert.equal(page.headers.get('x-frame-options'), 'DENY');

  const route = await request(base, '/sites/overview');
  assert.equal(route.status, 200);
  assert.match(await route.text(), /InfraNexum/);

  const asset = await request(base, '/assets/app.12345678.js');
  assert.equal(asset.status, 200);
  assert.equal(asset.headers.get('cache-control'), 'public, max-age=31536000, immutable');
  assert.match(sink.chunks.join(''), /web runtime started/);
});

test('application handles HEAD, unsupported methods, invalid paths, and missing assets', async (context) => {
  const { application } = await createFixture();
  context.after(() => application.stop());
  const base = await application.start();

  const head = await request(base, '/runtime-config.json', { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(await head.text(), '');
  assert.ok(Number(head.headers.get('content-length')) > 0);

  const post = await request(base, '/health/live', { method: 'POST' });
  assert.equal(post.status, 405);
  assert.equal(post.headers.get('allow'), 'GET, HEAD');
  assert.equal((await post.json()).code, 'METHOD_NOT_ALLOWED');

  const invalid = await request(base, '/%E0%A4%A');
  assert.equal(invalid.status, 400);
  assert.equal((await invalid.json()).code, 'INVALID_PATH');

  const missing = await request(base, '/missing.png');
  assert.equal(missing.status, 404);
  assert.equal((await missing.json()).code, 'NOT_FOUND');
});

test('application refuses duplicate start and supports idempotent stop', async () => {
  const { application } = await createFixture();
  await application.start();
  await assert.rejects(() => application.start(), /cannot start/);
  await application.stop();
  assert.equal(application.state, 'stopped');
  await application.stop();
  assert.equal(application.state, 'stopped');
});

test('application reports failed startup when assets are unavailable', async () => {
  const configuration = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_LISTEN_ADDRESS: '127.0.0.1:0',
    INFRANEXUM_WEB_STATIC_ROOT: '/path/that/does/not/exist',
    INFRANEXUM_WEB_ENVIRONMENT: 'test',
  }, { version: '2.0.0-alpha.0.83' });
  const sink = new Sink();
  const application = new WebApplication({
    configuration,
    assets: new StaticAssetStore(configuration.staticRoot),
    logger: new JsonLogger({ sink }),
  });
  await assert.rejects(() => application.start(), /ENOENT/);
  assert.equal(application.state, 'failed');
  assert.match(sink.chunks.join(''), /startup failed/);
  await application.stop();
  assert.equal(application.state, 'stopped');
});

test('application translates unexpected asset errors to a stable 500 contract', async (context) => {
  const configuration = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_LISTEN_ADDRESS: '127.0.0.1:0',
    INFRANEXUM_WEB_ENVIRONMENT: 'test',
  }, { version: '2.0.0-alpha.0.83' });
  const sink = new Sink();
  const application = new WebApplication({
    configuration,
    assets: {
      async initialize() {},
      async read() { throw new Error('storage unavailable'); },
    },
    logger: new JsonLogger({ sink }),
  });
  context.after(() => application.stop());
  const base = await application.start();
  const response = await request(base, '/asset.js');
  assert.equal(response.status, 500);
  assert.equal((await response.json()).code, 'WEB_INTERNAL_ERROR');
  assert.match(sink.chunks.join(''), /web request failed/);
});

test('health probe model covers startup and readiness state transitions', async () => {
  const { healthProbe } = await import('../../src/applications/web/runtime/web-application.mjs');
  assert.deepEqual(healthProbe('created', 'startup'), { statusCode: 503, status: 'DOWN' });
  assert.deepEqual(healthProbe('ready', 'startup'), { statusCode: 200, status: 'UP' });
  assert.deepEqual(healthProbe('stopping', 'startup'), { statusCode: 200, status: 'UP' });
  assert.deepEqual(healthProbe('created', 'readiness'), { statusCode: 503, status: 'DOWN' });
  assert.deepEqual(healthProbe('ready', 'readiness'), { statusCode: 200, status: 'UP' });
  assert.throws(() => healthProbe('ready', 'unknown'), /unsupported health probe/);
});

test('TCP address normalization supports IPv4 and IPv6 and rejects non-TCP addresses', async () => {
  const { normalizeTcpAddress } = await import('../../src/applications/web/runtime/web-application.mjs');
  assert.equal(normalizeTcpAddress({ address: '127.0.0.1', family: 'IPv4', port: 8080 }), 'http://127.0.0.1:8080');
  assert.equal(normalizeTcpAddress({ address: '::1', family: 'IPv6', port: 8080 }), 'http://[::1]:8080');
  assert.throws(() => normalizeTcpAddress(null), /TCP address/);
  assert.throws(() => normalizeTcpAddress('/tmp/socket'), /TCP address/);
});

test('deadline close handles callback errors and forces timed-out connections', async () => {
  const { closeServerWithDeadline } = await import('../../src/applications/web/runtime/web-application.mjs');
  await assert.rejects(
    () => closeServerWithDeadline({
      close(callback) { callback(new Error('close failed')); },
      closeIdleConnections() {},
    }, 100),
    /close failed/,
  );

  await assert.doesNotReject(() => closeServerWithDeadline({
    close(callback) { callback(); },
  }, 100));

  let forced = false;
  const delayed = {
    close(callback) { setTimeout(() => callback(), 20); },
    closeAllConnections() { forced = true; },
    closeIdleConnections() {},
  };
  await assert.rejects(() => closeServerWithDeadline(delayed, 5), /shutdown exceeded/);
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.equal(forced, true);
});

test('application surfaces listener collisions as startup failures', async () => {
  const first = await createFixture();
  const base = await first.application.start();
  const port = new URL(base).port;
  const second = await createFixture({ INFRANEXUM_WEB_LISTEN_ADDRESS: `127.0.0.1:${port}` });
  try {
    await assert.rejects(() => second.application.start(), /EADDRINUSE/);
    assert.equal(second.application.state, 'failed');
  } finally {
    await first.application.stop();
    await second.application.stop();
  }
});

test('HEAD requests are bodyless for every public contract and static response', async (context) => {
  const { application } = await createFixture();
  context.after(() => application.stop());
  const base = await application.start();
  for (const endpoint of [
    '/health/live',
    '/health/ready',
    '/health/startup',
    '/runtime-config.json',
    '/api/v1/system/build',
    '/',
    '/missing.png',
    '/%E0%A4%A',
  ]) {
    const response = await request(base, endpoint, { method: 'HEAD' });
    assert.equal(await response.text(), '', endpoint);
  }
});
