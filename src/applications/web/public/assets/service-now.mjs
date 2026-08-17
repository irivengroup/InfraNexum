import { csrfToken } from './auth.mjs';
import { paginationMetadata } from './http-pagination.mjs';

const TIMEOUT_MS = 15_000;
const CONNECTOR_KEY = /^[a-z0-9][a-z0-9._-]{2,79}$/;

/** Safe browser error for the ServiceNow CMDB federated-read boundary. */
export class ServiceNowApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `ServiceNow request failed with HTTP ${status}`);
    this.name = 'ServiceNowApiError'; this.status = status; this.code = code || 'SERVICE_NOW_HTTP_ERROR';
  }
}

/** Capability-gated ServiceNow API client. Provider credentials remain server-side. */
export class ServiceNowClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '' } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.integrationsConnectorsEnabled !== true) throw new Error('Integrations Web capability is disabled');
    this.base = `${configuration.apiBaseUrl}/v1/integrations/providers/service-now`;
    this.fetchFunction = fetchFunction; this.cookieProvider = cookieProvider;
  }
  connectors() { return this.request(''); }
  health(connectorKey) { return this.request(`/${key(connectorKey)}/health`); }
  search(connectorKey, query, { offset = 0, limit = 50 } = {}) {
    const term = String(query ?? '').trim();
    if (term.length < 1 || term.length > 256 || !/^[A-Za-z0-9 _./:-]+$/.test(term)) throw new TypeError('ServiceNow query must contain 1 to 256 safe characters');
    return this.request(`/${key(connectorKey)}/configuration-items/search?offset=${integer(offset,0,1_000_000,'offset')}&limit=${integer(limit,1,200,'limit')}`, { method: 'POST', body: { query: term } });
  }
  async request(path, { method = 'GET', body } = {}) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (!['GET','HEAD'].includes(method)) { const token = csrfToken(this.cookieProvider()); if (!token) throw new Error('CSRF token is unavailable'); headers['X-CSRF-Token'] = token; }
    const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), TIMEOUT_MS); timer.unref?.();
    try {
      const response = await this.fetchFunction(`${this.base}${path}`, { method, headers, credentials: 'same-origin', cache: 'no-store', signal: controller.signal, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
      if (!response.ok) { const problem = await safeProblem(response); throw new ServiceNowApiError(response.status, problem?.code ?? problem?.title, problem?.detail ?? problem?.message); }
      return Object.freeze({ payload: response.status === 204 ? null : await response.json(), pagination: paginationMetadata(response.headers) });
    } catch (error) { if (error?.name === 'AbortError') throw new ServiceNowApiError(0,'SERVICE_NOW_TIMEOUT','ServiceNow request timed out'); throw error; }
    finally { clearTimeout(timer); }
  }
}
function key(value){ const normalized=String(value??'').trim().toLowerCase(); if(!CONNECTOR_KEY.test(normalized)) throw new TypeError('connectorKey is invalid'); return encodeURIComponent(normalized); }
function integer(value,min,max,name){ const n=Number(value); if(!Number.isSafeInteger(n)||n<min||n>max) throw new TypeError(`${name} is out of bounds`); return n; }
async function safeProblem(response){ try{return await response.json();}catch{return null;} }
