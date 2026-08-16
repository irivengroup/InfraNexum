import { csrfToken } from './auth.mjs';

const REQUEST_TIMEOUT_MS = 15_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const REVISION_PATTERN = /^[1-9][0-9]*$/;

/** Safe browser error retaining the problem code without exposing server internals. */
export class RsotSchemaRegistryApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `RSOT schema registry request failed with HTTP ${status}`);
    this.name = 'RsotSchemaRegistryApiError';
    this.status = status;
    this.code = code || 'RSOT_SCHEMA_HTTP_ERROR';
  }
}

/** Capability-gated client for the PGM-06-E03 registry. */
export class RsotSchemaRegistryClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '' } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.rsotCoreEnabled !== true) throw new Error('RSOT core Web capability is disabled');
    this.configuration = Object.freeze({ apiBaseUrl: configuration.apiBaseUrl });
    this.fetchFunction = fetchFunction;
    this.cookieProvider = cookieProvider;
  }

  listSchemas(filters = {}) { return this.request(`/v1/rsot/schemas${query(filters, ['schemaKey', 'kind', 'status', 'offset', 'limit'])}`); }
  getSchema(id) { return this.request(`/v1/rsot/schemas/${uuid(id, 'schemaId')}`); }
  createSchema(body) { return this.request('/v1/rsot/schemas', { method: 'POST', body }); }
  updateSchema(id, revision, definition) {
    return this.request(`/v1/rsot/schemas/${uuid(id, 'schemaId')}`, { method: 'PATCH', revision, body: { definition } });
  }
  compatibility(id) { return this.request(`/v1/rsot/schemas/${uuid(id, 'schemaId')}/compatibility`); }
  publishSchema(id, revision, breakingApprovalReference = null) {
    return this.request(`/v1/rsot/schemas/${uuid(id, 'schemaId')}/publish`, {
      method: 'POST', revision, body: breakingApprovalReference ? { breakingApprovalReference } : {},
    });
  }
  deprecateSchema(id, revision, sunsetAt, reason) {
    return this.request(`/v1/rsot/schemas/${uuid(id, 'schemaId')}/deprecate`, { method: 'POST', revision, body: { sunsetAt, reason } });
  }
  listProfiles(filters = {}) { return this.request(`/v1/rsot/schema-profiles${query(filters, ['code', 'status', 'offset', 'limit'])}`); }
  getProfile(id) { return this.request(`/v1/rsot/schema-profiles/${uuid(id, 'profileId')}`); }
  createProfile(body) { return this.request('/v1/rsot/schema-profiles', { method: 'POST', body }); }
  publishProfile(id, revision) {
    return this.request(`/v1/rsot/schema-profiles/${uuid(id, 'profileId')}/publish`, { method: 'POST', revision });
  }
  deprecateProfile(id, revision, sunsetAt, reason) {
    return this.request(`/v1/rsot/schema-profiles/${uuid(id, 'profileId')}/deprecate`, { method: 'POST', revision, body: { sunsetAt, reason } });
  }

  /** Sends one same-origin request with CSRF, optimistic revision and bounded latency. */
  async request(path, { method = 'GET', body, revision, idempotencyKey } = {}) {
    if (typeof path !== 'string' || !path.startsWith('/v1/rsot/')) throw new TypeError('path is outside the RSOT schema registry boundary');
    const verb = String(method).toUpperCase();
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (revision !== undefined) headers['If-Match'] = `"rev-${positiveRevision(revision)}"`;
    if (!['GET', 'HEAD'].includes(verb)) {
      const csrf = csrfToken(this.cookieProvider());
      if (!csrf) throw new Error('CSRF token is unavailable');
      headers['X-CSRF-Token'] = csrf;
      headers['Idempotency-Key'] = validatedIdempotencyKey(idempotencyKey ?? newIdempotencyKey());
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    timer.unref?.();
    try {
      const response = await this.fetchFunction(`${this.configuration.apiBaseUrl}${path}`, {
        method: verb, headers, credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (!response.ok) {
        const problem = await safeJson(response);
        throw new RsotSchemaRegistryApiError(response.status, problem?.code ?? problem?.title, problem?.detail ?? problem?.message);
      }
      const etag = response.headers?.get?.('etag') ?? null;
      const payload = response.status === 204 ? null : await response.json();
      return Object.freeze({ payload, etag });
    } catch (error) {
      if (error?.name === 'AbortError') throw new RsotSchemaRegistryApiError(0, 'RSOT_SCHEMA_TIMEOUT', 'RSOT schema registry request timed out');
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }
}


function newIdempotencyKey() {
  const generated = globalThis.crypto?.randomUUID?.();
  if (typeof generated !== 'string') throw new Error('secure UUID idempotency generation is unavailable');
  return generated;
}

function validatedIdempotencyKey(value) {
  const normalized = String(value ?? '').trim();
  if (!/^[A-Za-z0-9._:-]{8,200}$/.test(normalized)) throw new Error('Idempotency key must contain 8 to 200 safe characters');
  return normalized;
}

function uuid(value, field) {
  const normalized = String(value ?? '').trim();
  if (!UUID_PATTERN.test(normalized)) throw new TypeError(`${field} must be a UUID`);
  return normalized.toLowerCase();
}

function positiveRevision(value) {
  const normalized = String(value ?? '').trim();
  if (!REVISION_PATTERN.test(normalized)) throw new TypeError('revision must be a positive integer');
  return normalized;
}

function query(values, allowed) {
  const parameters = new URLSearchParams();
  for (const key of allowed) {
    const value = values?.[key];
    if (value !== undefined && value !== null && String(value).trim() !== '') parameters.set(key, String(value));
  }
  const encoded = parameters.toString();
  return encoded ? `?${encoded}` : '';
}

async function safeJson(response) {
  try { return await response.json(); } catch { return null; }
}
