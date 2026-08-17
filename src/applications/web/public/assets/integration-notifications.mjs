import { csrfToken } from './auth.mjs';
import { paginationMetadata } from './http-pagination.mjs';

const TIMEOUT_MS = 15_000;
const CONNECTOR_KEY = /^[a-z0-9][a-z0-9._-]{2,79}$/;
const EVENT_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,199}$/;
const EVENT_TYPE = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,15}$/;
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** Safe browser error for durable outbound notification operations. */
export class NotificationApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `Notification request failed with HTTP ${status}`);
    this.name = 'NotificationApiError'; this.status = status; this.code = code || 'NOTIFICATION_HTTP_ERROR';
  }
}

/** Capability-gated notification operations client. Endpoint destinations and secrets remain Server-side. */
export class NotificationClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '', idempotencyKeyProvider = defaultIdempotencyKey } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.integrationsConnectorsEnabled !== true) throw new Error('Integrations Web capability is disabled');
    if (typeof idempotencyKeyProvider !== 'function') throw new TypeError('idempotencyKeyProvider must be a function');
    this.base = `${configuration.apiBaseUrl}/v1/integrations/notifications`;
    this.fetchFunction = fetchFunction; this.cookieProvider = cookieProvider; this.idempotencyKeyProvider = idempotencyKeyProvider;
  }

  endpoints({ offset = 0, limit = 50 } = {}) { return this.request(`/endpoints?offset=${integer(offset,0,1_000_000,'offset')}&limit=${integer(limit,1,200,'limit')}`); }
  publish(eventId, eventType, endpointKeys, payload) {
    const normalizedId = String(eventId ?? '').trim(); const normalizedType = String(eventType ?? '').trim();
    if (!EVENT_ID.test(normalizedId)) throw new TypeError('eventId is invalid');
    if (!EVENT_TYPE.test(normalizedType)) throw new TypeError('eventType is invalid');
    if (!Array.isArray(endpointKeys) || endpointKeys.length < 1 || endpointKeys.length > 64) throw new TypeError('endpointKeys must contain 1 to 64 entries');
    const keys = endpointKeys.map(key); if (new Set(keys).size !== keys.length) throw new TypeError('endpointKeys must be unique');
    if (payload === null || typeof payload !== 'object' || (!Array.isArray(payload) && Object.getPrototypeOf(payload) !== Object.prototype)) throw new TypeError('payload must be a JSON object or array');
    return this.request('/events', { method: 'POST', idempotent: true, body: { eventId: normalizedId, eventType: normalizedType, endpointKeys: keys, payload } });
  }
  deadLetters({ endpointKey = null, offset = 0, limit = 50 } = {}) {
    const query = new URLSearchParams({ offset: String(integer(offset,0,1_000_000,'offset')), limit: String(integer(limit,1,200,'limit')) });
    if (endpointKey !== null && String(endpointKey).trim() !== '') query.set('endpointKey', key(endpointKey));
    return this.request(`/dlq?${query}`);
  }
  replay(deliveryId, reason = null) { return this.request(`/dlq/${uuid(deliveryId)}/replay`, { method: 'POST', idempotent: true, body: reasonBody(reason) }); }
  runtime(endpointKey) { return this.request(`/endpoints/${key(endpointKey)}/runtime`); }
  resume(endpointKey, reason = null) { return this.request(`/endpoints/${key(endpointKey)}/resume`, { method: 'POST', idempotent: true, body: reasonBody(reason) }); }

  async request(path, { method = 'GET', body, idempotent = false } = {}) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (!['GET','HEAD'].includes(method)) {
      const token = csrfToken(this.cookieProvider()); if (!token) throw new Error('CSRF token is unavailable'); headers['X-CSRF-Token'] = token;
      if (idempotent) headers['Idempotency-Key'] = idempotency(this.idempotencyKeyProvider());
    }
    const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), TIMEOUT_MS); timer.unref?.();
    try {
      const response = await this.fetchFunction(`${this.base}${path}`, { method, headers, credentials: 'same-origin', cache: 'no-store', signal: controller.signal, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
      if (!response.ok) { const problem = await safeProblem(response); throw new NotificationApiError(response.status, problem?.code ?? problem?.title, problem?.detail ?? problem?.message); }
      return Object.freeze({ payload: response.status === 204 ? null : await response.json(), pagination: paginationMetadata(response.headers) });
    } catch (error) { if (error?.name === 'AbortError') throw new NotificationApiError(0,'NOTIFICATION_TIMEOUT','Notification request timed out'); throw error; }
    finally { clearTimeout(timer); }
  }
}

function defaultIdempotencyKey() { return `notification-${globalThis.crypto.randomUUID()}`; }
function idempotency(value) { const normalized=String(value??'').trim(); if(!/^[A-Za-z0-9._:-]{8,200}$/.test(normalized)) throw new TypeError('Idempotency-Key is invalid'); return normalized; }
function key(value){ const normalized=String(value??'').trim().toLowerCase(); if(!CONNECTOR_KEY.test(normalized)) throw new TypeError('endpointKey is invalid'); return normalized; }
function uuid(value){ const normalized=String(value??'').trim().toLowerCase(); if(!UUID_V7.test(normalized)) throw new TypeError('deliveryId must be a UUIDv7'); return normalized; }
function integer(value,min,max,name){ const n=Number(value); if(!Number.isSafeInteger(n)||n<min||n>max) throw new TypeError(`${name} is out of bounds`); return n; }
function reasonBody(reason){ if(reason==null||String(reason).trim()==='') return {}; const normalized=String(reason).trim(); if(normalized.length<2||normalized.length>512) throw new TypeError('reason must contain 2 to 512 characters'); return {reason:normalized}; }
async function safeProblem(response){ try{return await response.json();}catch{return null;} }
