import { csrfToken } from './auth.mjs';

const REQUEST_TIMEOUT_MS = 15_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const VERSION_PATTERN = /^[1-9][0-9]*$/;
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,199}$/;

/** Safe Web boundary error for the PGM-07-E01 Partner catalogue. */
export class ItamPartnerApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `ITAM Partner request failed with HTTP ${status}`);
    this.name = 'ItamPartnerApiError';
    this.status = status;
    this.code = code || 'ITAM_PARTNER_HTTP_ERROR';
  }
}

/** Capability-gated browser client for the single governed Partner aggregate. */
export class ItamPartnerClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '' } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.itamPartnersEnabled !== true) throw new Error('ITAM Partner Web capability is disabled');
    this.configuration = Object.freeze({ apiBaseUrl: configuration.apiBaseUrl });
    this.fetchFunction = fetchFunction;
    this.cookieProvider = cookieProvider;
  }

  list(filters = {}) {
    return this.request(`/v1/itam/partners${query(filters, [
      'organization_id', 'role', 'authorization_status', 'country_code', 'accreditation', 'effective_on', 'cursor', 'limit',
    ])}`);
  }

  create(body, idempotencyKey) {
    return this.request('/v1/itam/partners', { method: 'POST', body, idempotencyKey });
  }

  submitApproval(id, version, reason, idempotencyKey) {
    return this.transition(id, 'submit-approval', version, reason, idempotencyKey);
  }

  authorize(id, version, reason, idempotencyKey) {
    return this.transition(id, 'authorize', version, reason, idempotencyKey);
  }

  suspend(id, version, reason, idempotencyKey) {
    return this.transition(id, 'suspend', version, reason, idempotencyKey);
  }

  transition(id, operation, version, reason, idempotencyKey) {
    const allowed = new Set(['submit-approval', 'authorize', 'suspend']);
    if (!allowed.has(operation)) throw new TypeError('unsupported ITAM Partner transition');
    const normalizedReason = String(reason ?? '').trim();
    if (normalizedReason.length < 2 || normalizedReason.length > 1024 || /[\u0000-\u001f\u007f]/.test(normalizedReason)) {
      throw new TypeError('reason must contain 2 to 1024 printable characters');
    }
    return this.request(`/v1/itam/partners/${uuid(id, 'partnerId')}/${operation}`, {
      method: 'POST', version, idempotencyKey, body: { reason: normalizedReason },
    });
  }

  /** Same-origin request with bounded latency, CSRF, idempotency and optimistic concurrency. */
  async request(path, { method = 'GET', body, version, idempotencyKey } = {}) {
    if (typeof path !== 'string' || !path.startsWith('/v1/itam/partners')) {
      throw new TypeError('path is outside the ITAM Partner boundary');
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
        method: verb,
        headers,
        credentials: 'same-origin',
        cache: 'no-store',
        signal: controller.signal,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (!response.ok) {
        const problem = await safeJson(response);
        throw new ItamPartnerApiError(
          response.status,
          problem?.code ?? problem?.title,
          problem?.detail ?? problem?.message,
        );
      }
      const etag = response.headers?.get?.('etag') ?? null;
      const payload = response.status === 204 ? null : await response.json();
      return Object.freeze({ payload, etag });
    } catch (error) {
      if (error?.name === 'AbortError') {
        throw new ItamPartnerApiError(0, 'ITAM_PARTNER_TIMEOUT', 'ITAM Partner request timed out');
      }
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }
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
  if (!IDEMPOTENCY_KEY_PATTERN.test(normalized)) {
    throw new TypeError('idempotencyKey must contain 8 to 200 safe characters');
  }
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
