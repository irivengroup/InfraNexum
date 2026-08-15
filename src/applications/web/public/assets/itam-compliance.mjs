import { csrfToken } from './auth.mjs';

const REQUEST_TIMEOUT_MS = 15_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const VERSION_PATTERN = /^[1-9][0-9]*$/;
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,199}$/;
const SECRET_LICENSE_FIELDS = new Set(['licenseKey', 'license_key', 'productKey', 'product_key', 'serialKey', 'serial_key']);

/** Safe browser-bound error for PGM-07-E03 contractual governance. */
export class ItamComplianceApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `ITAM Compliance request failed with HTTP ${status}`);
    this.name = 'ItamComplianceApiError';
    this.status = status;
    this.code = code || 'ITAM_COMPLIANCE_HTTP_ERROR';
  }
}

/** Capability-gated browser client for warranties, support coverages and software-license contracts. */
export class ItamComplianceClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '' } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.itamComplianceEnabled !== true) throw new Error('ITAM Compliance Web capability is disabled');
    this.configuration = Object.freeze({ apiBaseUrl: configuration.apiBaseUrl });
    this.fetchFunction = fetchFunction;
    this.cookieProvider = cookieProvider;
  }

  warranties(assetId, options = {}) { return this.request(`/v1/itam/assets/${uuid(assetId, 'assetId')}/warranties${pageQuery(options)}`); }
  getWarranty(id) { return this.request(`/v1/itam/warranties/${uuid(id, 'warrantyId')}`); }
  createWarranty(assetId, body, key) { return this.request(`/v1/itam/assets/${uuid(assetId, 'assetId')}/warranties`, { method: 'POST', body: mutationBody(body), idempotencyKey: key }); }
  reviseWarranty(id, version, body, key) { return this.request(`/v1/itam/warranties/${uuid(id, 'warrantyId')}`, { method: 'PATCH', body: mutationBody(body), version, idempotencyKey: key }); }
  activateWarranty(id, version, reason, key) { return this.lifecycle('warranties', id, 'activate', version, reason, key); }
  expireWarranty(id, version, reason, key) { return this.lifecycle('warranties', id, 'expire', version, reason, key); }

  licenses(assetId, options = {}) { return this.request(`/v1/itam/assets/${uuid(assetId, 'assetId')}/licenses${pageQuery(options)}`); }
  getLicense(id) { return this.request(`/v1/itam/licenses/${uuid(id, 'licenseId')}`); }
  createLicense(assetId, body, key) { rejectRawLicenseKeys(body); return this.request(`/v1/itam/assets/${uuid(assetId, 'assetId')}/licenses`, { method: 'POST', body: mutationBody(body), idempotencyKey: key }); }
  reviseLicense(id, version, body, key) { rejectRawLicenseKeys(body); return this.request(`/v1/itam/licenses/${uuid(id, 'licenseId')}`, { method: 'PATCH', body: mutationBody(body), version, idempotencyKey: key }); }
  activateLicense(id, version, reason, key) { return this.lifecycle('licenses', id, 'activate', version, reason, key); }
  expireLicense(id, version, reason, key) { return this.lifecycle('licenses', id, 'expire', version, reason, key); }

  supportCoverages(assetId, options = {}) { return this.request(`/v1/itam/assets/${uuid(assetId, 'assetId')}/support-coverages${pageQuery(options)}`); }
  getSupportCoverage(id) { return this.request(`/v1/itam/support-coverages/${uuid(id, 'coverageId')}`); }
  createSupportCoverage(assetId, body, key) { return this.request(`/v1/itam/assets/${uuid(assetId, 'assetId')}/support-coverages`, { method: 'POST', body: mutationBody(body), idempotencyKey: key }); }
  reviseSupportCoverage(id, version, body, key) { return this.request(`/v1/itam/support-coverages/${uuid(id, 'coverageId')}`, { method: 'PATCH', body: mutationBody(body), version, idempotencyKey: key }); }
  activateSupportCoverage(id, version, reason, key) { return this.lifecycle('support-coverages', id, 'activate', version, reason, key); }
  expireSupportCoverage(id, version, reason, key) { return this.lifecycle('support-coverages', id, 'expire', version, reason, key); }

  listSupportAuthorizations(organizationId) { return this.request(`/v1/itam/support-authorizations?organization_id=${encodeURIComponent(uuid(organizationId, 'organizationId'))}`); }
  createSupportAuthorization(body, key) { return this.request('/v1/itam/support-authorizations', { method: 'POST', body: mutationBody(body), idempotencyKey: key }); }
  supportAuthorization(id) { return this.request(`/v1/itam/support-authorizations/${uuid(id, 'authorizationId')}`); }
  activateSupportAuthorization(id, version, reason, key) { return this.lifecycle('support-authorizations', id, 'activate', version, reason, key); }
  suspendSupportAuthorization(id, version, reason, key) { return this.lifecycle('support-authorizations', id, 'suspend', version, reason, key); }

  warrantyTypes(organizationId) { return this.request(`/v1/itam/warranty-types?organization_id=${encodeURIComponent(uuid(organizationId, 'organizationId'))}`); }
  createWarrantyType(organizationId, code, displayName, reason, key) {
    return this.request('/v1/itam/warranty-types', { method: 'POST', body: { organizationId: uuid(organizationId, 'organizationId'), code: text(code, 'code', 2, 64), displayName: text(displayName, 'displayName', 2, 160), reason: validatedReason(reason) }, idempotencyKey: key });
  }
  alerts(assetId, { asOf, horizonDays = 180 } = {}) {
    const horizon = integer(horizonDays, 'horizonDays', 1, 3650);
    const query = new URLSearchParams({ horizon_days: String(horizon) });
    if (asOf !== undefined && asOf !== null && String(asOf).trim() !== '') query.set('as_of', isoDate(asOf, 'asOf'));
    return this.request(`/v1/itam/assets/${uuid(assetId, 'assetId')}/compliance-alerts?${query}`);
  }
  history(type, id, { afterVersion = 0, limit = 100 } = {}) {
    if (!['warranties', 'licenses', 'support-coverages'].includes(type)) throw new TypeError('unsupported compliance history type');
    const after = integer(afterVersion, 'afterVersion', 0, Number.MAX_SAFE_INTEGER);
    const bounded = integer(limit, 'limit', 1, 200);
    return this.request(`/v1/itam/${type}/${uuid(id, 'recordId')}/history?after_version=${after}&limit=${bounded}`);
  }

  lifecycle(resource, id, operation, version, reason, key) {
    return this.request(`/v1/itam/${resource}/${uuid(id, 'recordId')}/${operation}`, {
      method: 'POST', body: { reason: validatedReason(reason) }, version, idempotencyKey: key,
    });
  }

  /** Same-origin request with bounded latency, CSRF, idempotency and optimistic concurrency. */
  async request(path, { method = 'GET', body, version, idempotencyKey } = {}) {
    if (typeof path !== 'string' || !path.startsWith('/v1/itam/')) throw new TypeError('path is outside the ITAM Compliance boundary');
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
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS); timer.unref?.();
    try {
      const response = await this.fetchFunction(`${this.configuration.apiBaseUrl}${path}`, {
        method: verb, headers, credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (!response.ok) {
        const problem = await safeJson(response);
        throw new ItamComplianceApiError(response.status, problem?.code ?? problem?.title, problem?.detail ?? problem?.message);
      }
      const payload = response.status === 204 ? null : await response.json();
      return Object.freeze({ payload, etag: response.headers?.get?.('etag') ?? null });
    } catch (error) {
      if (error?.name === 'AbortError') throw new ItamComplianceApiError(0, 'ITAM_COMPLIANCE_TIMEOUT', 'ITAM Compliance request timed out');
      throw error;
    } finally { clearTimeout(timer); }
  }
}

function mutationBody(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new TypeError('request body must be an object');
  return { ...body, reason: validatedReason(body.reason) };
}
function rejectRawLicenseKeys(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new TypeError('request body must be an object');
  for (const field of SECRET_LICENSE_FIELDS) if (Object.prototype.hasOwnProperty.call(body, field)) throw new TypeError('raw software license keys are not accepted until Secret Service is available');
}
function pageQuery({ cursor, limit = 50 } = {}) {
  const query = new URLSearchParams({ limit: String(integer(limit, 'limit', 1, 200)) });
  if (cursor !== undefined && cursor !== null && String(cursor).trim() !== '') query.set('cursor', uuid(cursor, 'cursor'));
  return `?${query}`;
}
function uuid(value, field) { const normalized = String(value ?? '').trim(); if (!UUID_PATTERN.test(normalized)) throw new TypeError(`${field} must be a UUID`); return normalized.toLowerCase(); }
function positiveVersion(value) { const normalized = String(value ?? '').trim(); if (!VERSION_PATTERN.test(normalized)) throw new TypeError('version must be a positive integer'); return normalized; }
function validatedIdempotencyKey(value) { const normalized = String(value ?? '').trim(); if (!IDEMPOTENCY_KEY_PATTERN.test(normalized)) throw new TypeError('idempotencyKey must contain 8 to 200 safe characters'); return normalized; }
function validatedReason(value) { return text(value, 'reason', 2, 1024); }
function text(value, field, minimum, maximum) { const normalized = String(value ?? '').trim(); if (normalized.length < minimum || normalized.length > maximum || /[\u0000-\u001f\u007f]/.test(normalized)) throw new TypeError(`${field} is invalid`); return normalized; }
function isoDate(value, field) { const normalized = String(value ?? '').trim(); if (!/^\d{4}-\d{2}-\d{2}$/.test(normalized) || Number.isNaN(Date.parse(`${normalized}T00:00:00Z`))) throw new TypeError(`${field} must be an ISO date`); return normalized; }
function integer(value, field, minimum, maximum) { const parsed = Number(value); if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) throw new TypeError(`${field} must be between ${minimum} and ${maximum}`); return parsed; }
async function safeJson(response) { try { return await response.json(); } catch { return null; } }
