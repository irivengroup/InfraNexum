import { csrfToken } from './auth.mjs';

const REQUEST_TIMEOUT_MS = 15_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const VERSION_PATTERN = /^[1-9][0-9]*$/;
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,199}$/;
const MUTATIONS = new Set(['receive', 'stock', 'assign', 'deploy', 'transfer', 'maintenance/start', 'maintenance/return', 'retire', 'dispose']);

/** Safe browser-bound error for the PGM-07-E02 asset lifecycle. */
export class ItamAssetApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `ITAM Asset request failed with HTTP ${status}`);
    this.name = 'ItamAssetApiError';
    this.status = status;
    this.code = code || 'ITAM_ASSET_HTTP_ERROR';
  }
}

/** Capability-gated browser client for ITAM asset state and append-only custody history. */
export class ItamAssetClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '' } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.itamAssetsEnabled !== true) throw new Error('ITAM Asset Web capability is disabled');
    this.configuration = Object.freeze({ apiBaseUrl: configuration.apiBaseUrl });
    this.fetchFunction = fetchFunction;
    this.cookieProvider = cookieProvider;
  }

  list(filters = {}) {
    return this.request(`/v1/itam/assets${query(filters, ['organization_id', 'asset_type', 'lifecycle_status', 'rsot_object_id', 'cursor', 'limit'])}`);
  }

  get(id) { return this.request(`/v1/itam/assets/${uuid(id, 'assetId')}`); }

  custody(id, { afterSequence = 0, limit = 100 } = {}) {
    const after = Number(afterSequence);
    const boundedLimit = Number(limit);
    if (!Number.isSafeInteger(after) || after < 0) throw new TypeError('afterSequence must be a non-negative integer');
    if (!Number.isSafeInteger(boundedLimit) || boundedLimit < 1 || boundedLimit > 200) throw new TypeError('limit must be between 1 and 200');
    return this.request(`/v1/itam/assets/${uuid(id, 'assetId')}/custody?after_sequence=${after}&limit=${boundedLimit}`);
  }

  create(body, idempotencyKey) {
    return this.request('/v1/itam/assets', { method: 'POST', body, idempotencyKey });
  }

  transition(id, operation, version, body, idempotencyKey) {
    if (!MUTATIONS.has(operation)) throw new TypeError('unsupported ITAM Asset transition');
    const payload = validateTransitionBody(body, operation);
    return this.request(`/v1/itam/assets/${uuid(id, 'assetId')}/${operation}`, {
      method: 'POST', body: payload, version, idempotencyKey,
    });
  }

  receive(id, version, custodian, reason, key) { return this.transition(id, 'receive', version, { ...custodian, reason }, key); }
  stock(id, version, custodian, reason, key) { return this.transition(id, 'stock', version, { ...custodian, reason }, key); }
  assign(id, version, custodian, reason, key) { return this.transition(id, 'assign', version, { ...custodian, reason }, key); }
  deploy(id, version, custodian, reason, key) { return this.transition(id, 'deploy', version, { ...custodian, reason }, key); }
  transfer(id, version, custodian, reason, key) { return this.transition(id, 'transfer', version, { ...custodian, reason }, key); }
  startMaintenance(id, version, custodian, reason, key) { return this.transition(id, 'maintenance/start', version, { ...custodian, reason }, key); }
  returnFromMaintenance(id, version, custodian, reason, key) { return this.transition(id, 'maintenance/return', version, { ...custodian, reason }, key); }
  retire(id, version, reason, key) { return this.transition(id, 'retire', version, { reason }, key); }
  dispose(id, version, reason, evidenceReference, key) { return this.transition(id, 'dispose', version, { reason, evidenceReference }, key); }

  /** Same-origin request with bounded latency, CSRF, idempotency and optimistic concurrency. */
  async request(path, { method = 'GET', body, version, idempotencyKey } = {}) {
    if (typeof path !== 'string' || !path.startsWith('/v1/itam/assets')) {
      throw new TypeError('path is outside the ITAM Asset boundary');
    }
    const verb = String(method).toUpperCase();
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (version !== undefined) headers['If-Match'] = `"ver-${positiveVersion(version)}"`;
    if (!['GET', 'HEAD'].includes(verb)) {
      const csrf = csrfToken(this.cookieProvider());
      if (!csrf) throw new Error('CSRF token is unavailable');
      headers['X-CSRF-Token'] = csrf;
      headers['Idempotency-Key'] = validatedIdempotencyKey(idempotencyKey);
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
        throw new ItamAssetApiError(response.status, problem?.code ?? problem?.title, problem?.detail ?? problem?.message);
      }
      const etag = response.headers?.get?.('etag') ?? null;
      const payload = response.status === 204 ? null : await response.json();
      return Object.freeze({ payload, etag });
    } catch (error) {
      if (error?.name === 'AbortError') throw new ItamAssetApiError(0, 'ITAM_ASSET_TIMEOUT', 'ITAM Asset request timed out');
      throw error;
    } finally { clearTimeout(timer); }
  }
}

function validateTransitionBody(body, operation) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new TypeError('transition body must be an object');
  const reason = String(body.reason ?? '').trim();
  if (reason.length < 2 || reason.length > 1024 || /[\u0000-\u001f\u007f]/.test(reason)) {
    throw new TypeError('reason must contain 2 to 1024 printable characters');
  }
  const result = { reason };
  if (!['retire', 'dispose'].includes(operation)) {
    const kind = String(body.custodianKind ?? '').trim();
    if (!['organization', 'subdivision', 'actor', 'partner'].includes(kind)) throw new TypeError('custodianKind is invalid');
    result.custodianKind = kind;
    result.custodianId = uuid(body.custodianId, 'custodianId');
  }
  if (body.evidenceReference !== undefined && body.evidenceReference !== null) {
    const evidence = String(body.evidenceReference).trim();
    if (evidence.length < 1 || evidence.length > 240 || /[\u0000-\u001f\u007f]/.test(evidence)) throw new TypeError('evidenceReference is invalid');
    result.evidenceReference = evidence;
  }
  if (operation === 'dispose' && !result.evidenceReference) throw new TypeError('dispose requires evidenceReference');
  return result;
}

function uuid(value, field) {
  const normalized = String(value ?? '').trim();
  if (!UUID_PATTERN.test(normalized)) throw new TypeError(`${field} must be a UUID`);
  return normalized.toLowerCase();
}
function positiveVersion(value) {
  const normalized = String(value ?? '').trim();
  if (!VERSION_PATTERN.test(normalized)) throw new TypeError('version must be a positive integer');
  return normalized;
}
function validatedIdempotencyKey(value) {
  const normalized = String(value ?? '').trim();
  if (!IDEMPOTENCY_KEY_PATTERN.test(normalized)) throw new TypeError('idempotencyKey must contain 8 to 200 safe characters');
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
async function safeJson(response) { try { return await response.json(); } catch { return null; } }
