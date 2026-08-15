import path from 'node:path';

const ENVIRONMENT_PATTERN = /^[a-z][a-z0-9-]{0,31}$/;
const HOST_PATTERN = /^(?:\[[0-9a-fA-F:]+\]|[a-zA-Z0-9._-]+):(\d{1,5})$/;
const VERSION_PATTERN = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;
const BASELINE_PATTERN = /^\d+\.\d+\.\d+-draft\.\d+$/;

/**
 * Immutable, validated configuration for the Web runtime host.
 * Only public values are exposed to the browser; secrets are intentionally absent.
 */
export class WebRuntimeConfiguration {
  #listenHost;
  #listenPort;
  #apiBaseUrl;
  #environment;
  #staticRoot;
  #shutdownTimeoutMs;
  #version;
  #architectureBaseline;
  #organizationFoundationEnabled;
  #localAuthEnabled;
  #identityAccessEnabled;
  #advancedAuthorizationEnabled;
  #rsotCoreEnabled;
  #itamPartnersEnabled;

  constructor({
    listenHost,
    listenPort,
    apiBaseUrl,
    environment,
    staticRoot,
    shutdownTimeoutMs,
    version,
    architectureBaseline,
    organizationFoundationEnabled,
    localAuthEnabled,
    identityAccessEnabled,
    advancedAuthorizationEnabled,
    rsotCoreEnabled,
    itamPartnersEnabled,
  }) {
    this.#listenHost = listenHost;
    this.#listenPort = listenPort;
    this.#apiBaseUrl = apiBaseUrl;
    this.#environment = environment;
    this.#staticRoot = staticRoot;
    this.#shutdownTimeoutMs = shutdownTimeoutMs;
    this.#version = version;
    this.#architectureBaseline = architectureBaseline;
    this.#organizationFoundationEnabled = organizationFoundationEnabled;
    this.#localAuthEnabled = localAuthEnabled;
    this.#identityAccessEnabled = identityAccessEnabled;
    this.#advancedAuthorizationEnabled = advancedAuthorizationEnabled;
    this.#rsotCoreEnabled = rsotCoreEnabled;
    this.#itamPartnersEnabled = itamPartnersEnabled;
    Object.freeze(this);
  }

  static fromEnvironment(environment, { version, baseDirectory = process.cwd() }) {
    if (!environment || typeof environment !== 'object') {
      throw new TypeError('environment must be an object');
    }
    if (!VERSION_PATTERN.test(version ?? '')) {
      throw new Error('InfraNexum version must be an exact semantic version');
    }

    const [listenHost, listenPort] = parseListenAddress(
      readString(environment, 'INFRANEXUM_WEB_LISTEN_ADDRESS', '127.0.0.1:8080'),
    );
    const runtimeEnvironment = readString(
      environment,
      'INFRANEXUM_WEB_ENVIRONMENT',
      'production',
    ).toLowerCase();
    if (!ENVIRONMENT_PATTERN.test(runtimeEnvironment)) {
      throw new Error('INFRANEXUM_WEB_ENVIRONMENT is invalid');
    }

    const apiBaseUrl = validateApiBaseUrl(
      readString(environment, 'INFRANEXUM_WEB_API_BASE_URL', '/api'),
      runtimeEnvironment,
    );
    const staticRoot = path.resolve(
      baseDirectory,
      readString(environment, 'INFRANEXUM_WEB_STATIC_ROOT', './public'),
    );
    const shutdownTimeoutMs = readInteger(
      environment,
      'INFRANEXUM_WEB_SHUTDOWN_TIMEOUT_MS',
      20_000,
      100,
      120_000,
    );
    const architectureBaseline = readString(
      environment,
      'INFRANEXUM_ARCHITECTURE_BASELINE',
      '2.0.0-draft.21',
    );
    if (!BASELINE_PATTERN.test(architectureBaseline)) {
      throw new Error('INFRANEXUM_ARCHITECTURE_BASELINE is invalid');
    }

    const organizationFoundationEnabled = readBoolean(
      environment,
      'INFRANEXUM_WEB_ORGANIZATION_FOUNDATION_ENABLED',
      false,
    );
    const localAuthEnabled = readBoolean(
      environment,
      'INFRANEXUM_WEB_LOCAL_AUTH_ENABLED',
      false,
    );
    const identityAccessEnabled = readBoolean(
      environment,
      'INFRANEXUM_WEB_IDENTITY_ACCESS_ENABLED',
      false,
    );
    if (identityAccessEnabled && (!organizationFoundationEnabled || !localAuthEnabled)) {
      throw new Error('Identity-access UI requires organization foundation and local authentication');
    }
    const advancedAuthorizationEnabled = readBoolean(
      environment,
      'INFRANEXUM_WEB_ADVANCED_AUTHORIZATION_ENABLED',
      false,
    );
    const rsotCoreEnabled = readBoolean(
      environment,
      'INFRANEXUM_WEB_RSOT_CORE_ENABLED',
      false,
    );
    const itamPartnersEnabled = readBoolean(
      environment,
      'INFRANEXUM_WEB_ITAM_PARTNERS_ENABLED',
      false,
    );
    if (advancedAuthorizationEnabled && !identityAccessEnabled) {
      throw new Error('Advanced-authorization UI requires identity-access capability');
    }

    return new WebRuntimeConfiguration({
      listenHost,
      listenPort,
      apiBaseUrl,
      environment: runtimeEnvironment,
      staticRoot,
      shutdownTimeoutMs,
      version,
      architectureBaseline,
      organizationFoundationEnabled,
      localAuthEnabled,
      identityAccessEnabled,
      advancedAuthorizationEnabled,
      rsotCoreEnabled,
      itamPartnersEnabled,
    });
  }

  get listenHost() { return this.#listenHost; }
  get listenPort() { return this.#listenPort; }
  get apiBaseUrl() { return this.#apiBaseUrl; }
  get environment() { return this.#environment; }
  get staticRoot() { return this.#staticRoot; }
  get shutdownTimeoutMs() { return this.#shutdownTimeoutMs; }
  get version() { return this.#version; }
  get architectureBaseline() { return this.#architectureBaseline; }
  get organizationFoundationEnabled() { return this.#organizationFoundationEnabled; }
  get localAuthEnabled() { return this.#localAuthEnabled; }
  get identityAccessEnabled() { return this.#identityAccessEnabled; }
  get advancedAuthorizationEnabled() { return this.#advancedAuthorizationEnabled; }
  get rsotCoreEnabled() { return this.#rsotCoreEnabled; }
  get itamPartnersEnabled() { return this.#itamPartnersEnabled; }

  publicConfiguration() {
    return Object.freeze({
      schema: 'infranexum.web-runtime-config/v1',
      product: 'InfraNexum',
      component: 'web',
      version: this.#version,
      architectureBaseline: this.#architectureBaseline,
      environment: this.#environment,
      apiBaseUrl: this.#apiBaseUrl,
      organizationFoundationEnabled: this.#organizationFoundationEnabled,
      localAuthEnabled: this.#localAuthEnabled,
      identityAccessEnabled: this.#identityAccessEnabled,
      advancedAuthorizationEnabled: this.#advancedAuthorizationEnabled,
      rsotCoreEnabled: this.#rsotCoreEnabled,
      itamPartnersEnabled: this.#itamPartnersEnabled,
    });
  }
}

function readString(environment, name, fallback) {
  const raw = environment[name];
  if (raw === undefined || raw === null || raw === '') {
    return fallback;
  }
  if (typeof raw !== 'string' || raw.trim() !== raw || raw.includes('\0')) {
    throw new Error(`${name} must be a trimmed string`);
  }
  return raw;
}

function readInteger(environment, name, fallback, minimum, maximum) {
  const raw = readString(environment, name, String(fallback));
  if (!/^\d+$/.test(raw)) {
    throw new Error(`${name} must be an integer`);
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be between ${minimum} and ${maximum}`);
  }
  return value;
}

function parseListenAddress(value) {
  const match = HOST_PATTERN.exec(value);
  if (!match) {
    throw new Error('INFRANEXUM_WEB_LISTEN_ADDRESS must use host:port syntax');
  }
  const port = Number(match[1]);
  if (port > 65_535) {
    throw new Error('INFRANEXUM_WEB_LISTEN_ADDRESS port is invalid');
  }
  const host = value.slice(0, value.lastIndexOf(':'));
  return [host.startsWith('[') ? host.slice(1, -1) : host, port];
}

function validateApiBaseUrl(value, runtimeEnvironment) {
  if (value.startsWith('/')) {
    if (value.startsWith('//') || value.includes('\\') || value.includes('\0')) {
      throw new Error('INFRANEXUM_WEB_API_BASE_URL relative path is invalid');
    }
    return value.replace(/\/$/, '') || '/';
  }

  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error('INFRANEXUM_WEB_API_BASE_URL must be an absolute URL or root-relative path');
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('INFRANEXUM_WEB_API_BASE_URL must not contain credentials, query, or fragment');
  }
  const loopback = ['localhost', '127.0.0.1', '::1'].includes(parsed.hostname);
  if (
    parsed.protocol !== 'https:'
    && !(parsed.protocol === 'http:' && loopback && runtimeEnvironment !== 'production')
  ) {
    throw new Error('INFRANEXUM_WEB_API_BASE_URL requires HTTPS outside non-production loopback');
  }
  return parsed.toString().replace(/\/$/, '');
}

function readBoolean(environment, name, fallback) {
  const raw = environment[name];
  if (raw === undefined || raw === null || raw === '') {
    return fallback;
  }
  if (raw === 'true') {
    return true;
  }
  if (raw === 'false') {
    return false;
  }
  throw new Error(`${name} must be true or false`);
}
