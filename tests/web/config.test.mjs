import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { WebRuntimeConfiguration } from '../../src/applications/web/runtime/config.mjs';

const options = { version: '2.0.0-alpha.0.115', baseDirectory: os.tmpdir() };

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
    integrationsConnectorsEnabled: false,
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


test('local authentication publication accepts explicit true and rejects malformed booleans', () => {
  const enabled = WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_LOCAL_AUTH_ENABLED: 'true' }, options);
  assert.equal(enabled.localAuthEnabled, true);
  assert.equal(enabled.publicConfiguration().localAuthEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_LOCAL_AUTH_ENABLED: 'yes' }, options), /LOCAL_AUTH_ENABLED/);
});


test('identity-access publication is fail-closed and requires auth plus organization foundation', () => {
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_IDENTITY_ACCESS_ENABLED: 'true',
  }, options), /requires organization foundation and local authentication/);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ORGANIZATION_FOUNDATION_ENABLED: 'true',
    INFRANEXUM_WEB_IDENTITY_ACCESS_ENABLED: 'true',
  }, options), /requires organization foundation and local authentication/);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_LOCAL_AUTH_ENABLED: 'true',
    INFRANEXUM_WEB_IDENTITY_ACCESS_ENABLED: 'true',
  }, options), /requires organization foundation and local authentication/);
  const enabled = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ENVIRONMENT: 'production',
    INFRANEXUM_WEB_ORGANIZATION_FOUNDATION_ENABLED: 'true',
    INFRANEXUM_WEB_LOCAL_AUTH_ENABLED: 'true',
    INFRANEXUM_WEB_IDENTITY_ACCESS_ENABLED: 'true',
  }, options);
  assert.equal(enabled.identityAccessEnabled, true);
  assert.equal(enabled.publicConfiguration().identityAccessEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_IDENTITY_ACCESS_ENABLED: 'yes',
  }, options), /IDENTITY_ACCESS_ENABLED/);
});


test('advanced authorization publication is fail-closed and requires identity access', () => {
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ADVANCED_AUTHORIZATION_ENABLED: 'true',
  }, options), /requires identity-access capability/);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ORGANIZATION_FOUNDATION_ENABLED: 'true',
    INFRANEXUM_WEB_LOCAL_AUTH_ENABLED: 'true',
    INFRANEXUM_WEB_ADVANCED_AUTHORIZATION_ENABLED: 'true',
  }, options), /requires identity-access capability/);
  const enabled = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ORGANIZATION_FOUNDATION_ENABLED: 'true',
    INFRANEXUM_WEB_LOCAL_AUTH_ENABLED: 'true',
    INFRANEXUM_WEB_IDENTITY_ACCESS_ENABLED: 'true',
    INFRANEXUM_WEB_ADVANCED_AUTHORIZATION_ENABLED: 'true',
  }, options);
  assert.equal(enabled.advancedAuthorizationEnabled, true);
  assert.equal(enabled.publicConfiguration().advancedAuthorizationEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ADVANCED_AUTHORIZATION_ENABLED: 'yes',
  }, options), /ADVANCED_AUTHORIZATION_ENABLED/);
});


test('RSOT core publication is fail-closed and validates explicit booleans', () => {
  const enabled = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_RSOT_CORE_ENABLED: 'true',
  }, options);
  assert.equal(enabled.rsotCoreEnabled, true);
  assert.equal(enabled.publicConfiguration().rsotCoreEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_RSOT_CORE_ENABLED: 'yes',
  }, options), /RSOT_CORE_ENABLED/);
});


test('ITAM Partner publication is fail-closed and validates explicit booleans', () => {
  const enabled = WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_ITAM_PARTNERS_ENABLED: 'true' }, options);
  assert.equal(enabled.itamPartnersEnabled, true);
  assert.equal(enabled.publicConfiguration().itamPartnersEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_ITAM_PARTNERS_ENABLED: 'yes' }, options), /ITAM_PARTNERS_ENABLED/);
});


test('ITAM Asset publication is fail-closed and requires Partner catalogue capability', () => {
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_ITAM_ASSETS_ENABLED: 'true' }, options), /requires the Partner catalogue capability/);
  const enabled = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ITAM_PARTNERS_ENABLED: 'true',
    INFRANEXUM_WEB_ITAM_ASSETS_ENABLED: 'true',
  }, options);
  assert.equal(enabled.itamAssetsEnabled, true);
  assert.equal(enabled.publicConfiguration().itamAssetsEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_ITAM_ASSETS_ENABLED: 'yes' }, options), /ITAM_ASSETS_ENABLED/);
});


test('ITAM Compliance publication is fail-closed and requires Partner and Asset capabilities', () => {
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_ITAM_COMPLIANCE_ENABLED: 'true' }, options), /requires Partner catalogue and Asset lifecycle/);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_ITAM_PARTNERS_ENABLED: 'true', INFRANEXUM_WEB_ITAM_COMPLIANCE_ENABLED: 'true' }, options), /requires Partner catalogue and Asset lifecycle/);
  const enabled = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ITAM_PARTNERS_ENABLED: 'true',
    INFRANEXUM_WEB_ITAM_ASSETS_ENABLED: 'true',
    INFRANEXUM_WEB_ITAM_COMPLIANCE_ENABLED: 'true',
  }, options);
  assert.equal(enabled.itamComplianceEnabled, true);
  assert.equal(enabled.publicConfiguration().itamComplianceEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_ITAM_COMPLIANCE_ENABLED: 'yes' }, options), /ITAM_COMPLIANCE_ENABLED/);
});


test('DCIM Facility publication is fail-closed and validates explicit booleans', () => {
  const enabled = WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_DCIM_FACILITIES_ENABLED: 'true' }, options);
  assert.equal(enabled.dcimFacilitiesEnabled, true);
  assert.equal(enabled.publicConfiguration().dcimFacilitiesEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_DCIM_FACILITIES_ENABLED: 'yes' }, options), /DCIM_FACILITIES_ENABLED/);
});


test('DCIM Physical publication is fail-closed and requires Facilities', () => {
  assert.equal(WebRuntimeConfiguration.fromEnvironment({}, options).dcimPhysicalEnabled, false);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_DCIM_PHYSICAL_ENABLED: 'true' }, options), /requires the Facilities hierarchy/);
  const cfg=WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_DCIM_FACILITIES_ENABLED: 'true', INFRANEXUM_WEB_DCIM_PHYSICAL_ENABLED: 'true' }, options);
  assert.equal(cfg.dcimPhysicalEnabled,true);
});


test('DDI/IPAM publication is fail-closed and requires DCIM Facilities', () => {
  assert.equal(WebRuntimeConfiguration.fromEnvironment({}, options).ddiIpamEnabled, false);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_DDI_IPAM_ENABLED: 'true' }, options), /requires the DCIM Facilities capability/);
  const cfg = WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_DCIM_FACILITIES_ENABLED: 'true', INFRANEXUM_WEB_DDI_IPAM_ENABLED: 'true' }, options);
  assert.equal(cfg.ddiIpamEnabled, true);
  assert.equal(cfg.publicConfiguration().ddiIpamEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_DDI_IPAM_ENABLED: 'yes' }, options), /true or false/);
});


test('Integrations connector publication is fail-closed and validates explicit booleans', () => {
  assert.equal(WebRuntimeConfiguration.fromEnvironment({}, options).integrationsConnectorsEnabled, false);
  const cfg = WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_INTEGRATIONS_CONNECTORS_ENABLED: 'true' }, options);
  assert.equal(cfg.integrationsConnectorsEnabled, true);
  assert.equal(cfg.publicConfiguration().integrationsConnectorsEnabled, true);
  assert.throws(() => WebRuntimeConfiguration.fromEnvironment({ INFRANEXUM_WEB_INTEGRATIONS_CONNECTORS_ENABLED: 'yes' }, options), /true or false/);
});
