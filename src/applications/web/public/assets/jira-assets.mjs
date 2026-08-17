import { csrfToken } from './auth.mjs';
import { paginationMetadata } from './http-pagination.mjs';

const TIMEOUT_MS = 15_000;
const CONNECTOR_KEY = /^[a-z0-9][a-z0-9._-]{2,79}$/;

/** Safe browser error for the Jira Assets federated-read boundary. */
export class JiraAssetsApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `Jira Assets request failed with HTTP ${status}`);
    this.name = 'JiraAssetsApiError';
    this.status = status;
    this.code = code || 'JIRA_ASSETS_HTTP_ERROR';
  }
}

/** Capability-gated Jira Assets API client. No provider credential ever reaches the browser. */
export class JiraAssetsClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '' } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.integrationsConnectorsEnabled !== true) throw new Error('Integrations Web capability is disabled');
    this.base = `${configuration.apiBaseUrl}/v1/integrations/providers/jira-assets`;
    this.fetchFunction = fetchFunction;
    this.cookieProvider = cookieProvider;
  }

  connectors() { return this.request(''); }
  health(connectorKey) { return this.request(`/${key(connectorKey)}/health`); }

  search(connectorKey, aql, { offset = 0, limit = 50 } = {}) {
    const query = String(aql ?? '').trim();
    if (query.length < 1 || query.length > 4096 || /[\u0000-\u001f\u007f]/.test(query)) {
      throw new TypeError('AQL must contain 1 to 4096 printable characters');
    }
    const normalizedOffset = integer(offset, 0, 1_000_000, 'offset');
    const normalizedLimit = integer(limit, 1, 200, 'limit');
    return this.request(`/${key(connectorKey)}/objects/search?offset=${normalizedOffset}&limit=${normalizedLimit}`, {
      method: 'POST', body: { aql: query },
    });
  }

  async request(path, { method = 'GET', body } = {}) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (!['GET', 'HEAD'].includes(method)) {
      const token = csrfToken(this.cookieProvider());
      if (!token) throw new Error('CSRF token is unavailable');
      headers['X-CSRF-Token'] = token;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS); timer.unref?.();
    try {
      const response = await this.fetchFunction(`${this.base}${path}`, {
        method, headers, credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (!response.ok) {
        const problem = await safeProblem(response);
        throw new JiraAssetsApiError(response.status, problem?.code ?? problem?.title, problem?.detail ?? problem?.message);
      }
      return Object.freeze({
        payload: response.status === 204 ? null : await response.json(),
        pagination: paginationMetadata(response.headers),
      });
    } catch (error) {
      if (error?.name === 'AbortError') throw new JiraAssetsApiError(0, 'JIRA_ASSETS_TIMEOUT', 'Jira Assets request timed out');
      throw error;
    } finally { clearTimeout(timer); }
  }
}

function key(value) {
  const normalized = String(value ?? '').trim().toLowerCase();
  if (!CONNECTOR_KEY.test(normalized)) throw new TypeError('connectorKey is invalid');
  return encodeURIComponent(normalized);
}
function integer(value, minimum, maximum, name) {
  const normalized = Number(value);
  if (!Number.isSafeInteger(normalized) || normalized < minimum || normalized > maximum) throw new TypeError(`${name} is out of bounds`);
  return normalized;
}
async function safeProblem(response) { try { return await response.json(); } catch { return null; } }
