const REQUEST_TIMEOUT_MS = 15_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** Read-only browser adapter for canonical RSOT identities used by governance and ITAM forms. */
export class RsotCanonicalObjectClient {
  constructor(configuration, { fetchFunction = fetch } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.rsotCoreEnabled !== true) throw new Error('RSOT core Web capability is disabled');
    this.apiBaseUrl = configuration.apiBaseUrl;
    this.fetchFunction = fetchFunction;
  }

  list({ organizationId, offset = 0, limit = 100 } = {}) {
    const parameters = new URLSearchParams();
    parameters.set('organization_id', uuid(organizationId, 'organizationId'));
    const normalizedOffset = integer(offset, 'offset', 0, Number.MAX_SAFE_INTEGER);
    const normalizedLimit = integer(limit, 'limit', 1, 200);
    parameters.set('offset', String(normalizedOffset));
    parameters.set('limit', String(normalizedLimit));
    return this.request(`/v1/rsot/canonical-objects?${parameters}`);
  }

  get(id) { return this.request(`/v1/rsot/canonical-objects/${uuid(id, 'canonicalId')}`); }

  async request(path) {
    if (!String(path).startsWith('/v1/rsot/canonical-objects')) throw new TypeError('path is outside the canonical RSOT boundary');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    timer.unref?.();
    try {
      const response = await this.fetchFunction(`${this.apiBaseUrl}${path}`, {
        method: 'GET', headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
      });
      if (!response.ok) {
        const problem = await safeJson(response);
        const error = new Error(problem?.detail ?? `RSOT canonical object request failed with HTTP ${response.status}`);
        error.name = 'RsotCanonicalObjectApiError'; error.status = response.status; error.code = problem?.code ?? 'RSOT_OBJECT_HTTP_ERROR';
        throw error;
      }
      return Object.freeze({ payload: await response.json(), etag: response.headers?.get?.('etag') ?? null });
    } catch (error) {
      if (error?.name === 'AbortError') throw new Error('RSOT canonical object request timed out');
      throw error;
    } finally { clearTimeout(timer); }
  }
}

function uuid(value, field) {
  const normalized = String(value ?? '').trim();
  if (!UUID_PATTERN.test(normalized)) throw new TypeError(`${field} must be a UUID`);
  return normalized.toLowerCase();
}
function integer(value, field, min, max) {
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number < min || number > max) throw new TypeError(`${field} is outside the supported range`);
  return number;
}
async function safeJson(response) { try { return await response.json(); } catch { return null; } }
