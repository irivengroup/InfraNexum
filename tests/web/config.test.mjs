import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { WebRuntimeConfiguration } from '../../src/applications/web/runtime/config.mjs';

const options = { version: '2.0.0-alpha.0.50', baseDirectory: os.tmpdir() };

test('configuration applies safe defaults and exposes only public values', () => {
  const configuration = WebRuntimeConfiguration.fromEnvironment({}, options);
  assert.equal(configuration.listenHost, '127.0.0.1');
  assert.equal(configuration.listenPort, 8080);
  assert.equal(configuration.apiBaseUrl, '/api');
  assert.equal(configuration.environment, 'production');
  assert.equal(configuration.staticRoot, path.resolve(os.tmpdir(), 'public'));
  assert.equal(configuration.shutdownTimeoutMs, 20_000);
  assert.equal(configuration.version, options.version);
  assert.equal(configuration.architectureBaseline, '2.0.0-draft.21');
  assert.deepEqual(configuration.publicConfiguration(), {
    schema: 'infranexum.web-runtime-config/v1',
    product: 'InfraNexum',
    component: 'web',
    version: options.version,
    architectureBaseline: '2.0.0-draft.21',
    environment: 'production',
    apiBaseUrl: '/api',
  });
  assert.equal(Object.isFrozen(configuration), true);
  assert.equal(Object.isFrozen(configuration.publicConfiguration()), true);
  const nullable = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ENVIRONMENT: null,
    INFRANEXUM_WEB_API_BASE_URL: '',
  }, options);
  assert.equal(nullable.environment, 'production');
  assert.equal(nullable.apiBaseUrl, '/api');
});

test('configuration accepts explicit IPv4, IPv6, relative and safe absolute API locations', () => {
  const explicit = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_LISTEN_ADDRESS: '0.0.0.0:0',
    INFRANEXUM_WEB_API_BASE_URL: '/control/',
    INFRANEXUM_WEB_ENVIRONMENT: 'staging-1',
    INFRANEXUM_WEB_STATIC_ROOT: '../assets',
    INFRANEXUM_WEB_SHUTDOWN_TIMEOUT_MS: '100',
    INFRANEXUM_ARCHITECTURE_BASELINE: '2.0.0-draft.21',
  }, options);
  assert.equal(explicit.listenPort, 0);
  assert.equal(explicit.apiBaseUrl, '/control');
  assert.equal(explicit.environment, 'staging-1');
  assert.equal(explicit.shutdownTimeoutMs, 100);

  const ipv6 = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_LISTEN_ADDRESS: '[::1]:8443',
    INFRANEXUM_WEB_API_BASE_URL: 'http://localhost:9090/api/',
    INFRANEXUM_WEB_ENVIRONMENT: 'test',
  }, options);
  assert.equal(ipv6.listenHost, '::1');
  assert.equal(ipv6.listenPort, 8443);
  assert.equal(ipv6.apiBaseUrl, 'http://localhost:9090/api');

  const https = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_API_BASE_URL: 'https://server.example.test/api/',
  }, options);
  assert.equal(https.apiBaseUrl, 'https://server.example.test/api');
});

test('configuration rejects invalid construction and string values', () => {
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment(null, options), /environment must be an object/);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({}, { ...options, version: 'latest' }), /exact semantic version/);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_ENVIRONMENT: ' Production ' }, options), /trimmed string/);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_ENVIRONMENT: 'bad/value' }, options), /environment is invalid/i);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_STATIC_ROOT: 4 }, options), /trimmed string/);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_STATIC_ROOT: 'bad\0path' }, options), /trimmed string/);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_ARCHITECTURE_BASELINE: 'draft' }, options), /baseline is invalid/i);
});

test('configuration rejects invalid listener and timeout values', () => {
  for (const value of ['localhost', 'localhost:', ':8080', 'localhost:65536', 'local host:80']) {
    assert.throws(
      () => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_LISTEN_ADDRESS: value }, options),
      /LISTEN_ADDRESS/,
      value,
    );
  }
  for (const value of ['99', '120001', '1.5', '-1', '9007199254740992']) {
    assert.throws(
      () => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_SHUTDOWN_TIMEOUT_MS: value }, options),
      /SHUTDOWN_TIMEOUT_MS/,
      value,
    );
  }
});

test('configuration enforces safe public API URLs', () => {
  for (const value of [
    '//evil.example.test',
    '/api\\admin',
    '/api\0admin',
    'server.example.test/api',
    'ftp://server.example.test/api',
    'https://user:password@server.example.test/api',
    'https://server.example.test/api?secret=true',
    'https://server.example.test/api#fragment',
    'http://server.example.test/api',
  ]) {
    assert.throws(
      () => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_API_BASE_URL: value }, options),
      /API_BASE_URL/,
      value,
    );
  }
  assert.throws(
    () => WebRuntimeConfiguration.fromEnvironment({
      INFRANEXUM_WEB_API_BASE_URL: 'http://localhost:8080/api',
      INFRANEXUM_WEB_ENVIRONMENT: 'production',
    }, options),
    /requires HTTPS/,
  );
});
