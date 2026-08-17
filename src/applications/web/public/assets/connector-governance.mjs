import { csrfToken } from './auth.mjs';
import { paginationMetadata } from './http-pagination.mjs';

const TIMEOUT_MS = 15_000;
const CONNECTOR_KEY = /^[a-z0-9][a-z0-9._-]{2,79}$/;
const DIRECTIONS = new Set(['FEDERATED_READ','INBOUND','OUTBOUND','BIDIRECTIONAL']);
const FIELD = /^[a-z][a-z0-9_.-]{0,127}$/;

export class ConnectorGovernanceApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `Connector governance request failed with HTTP ${status}`);
    this.name = 'ConnectorGovernanceApiError';
    this.status = status;
    this.code = code || 'CONNECTOR_GOVERNANCE_HTTP_ERROR';
  }
}

/** Read-only/dry-run browser boundary for connector authority, sync direction and rollback policy. */
export class ConnectorGovernanceClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '' } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.integrationsConnectorsEnabled !== true) throw new Error('Integrations Web capability is disabled');
    this.base = `${configuration.apiBaseUrl}/v1/integrations/governance`;
    this.fetchFunction = fetchFunction;
    this.cookieProvider = cookieProvider;
  }

  policies({ offset = 0, limit = 50 } = {}) {
    return this.request(`?offset=${integer(offset,0,1_000_000,'offset')}&limit=${integer(limit,1,200,'limit')}`);
  }

  policy(connectorKey) { return this.request(`/${key(connectorKey)}`); }

  plan(connectorKey, { direction, fields = [], propagateDeletions = false } = {}) {
    const normalizedDirection = String(direction ?? '').trim().toUpperCase();
    if (!DIRECTIONS.has(normalizedDirection)) throw new TypeError('direction is invalid');
    if (!Array.isArray(fields) || fields.length > 512) throw new TypeError('fields must be an array with at most 512 entries');
    const normalizedFields = fields.map(value => String(value ?? '').trim()).filter(Boolean);
    if (new Set(normalizedFields).size !== normalizedFields.length || normalizedFields.some(value => !FIELD.test(value))) {
      throw new TypeError('fields contain invalid or duplicate values');
    }
    if (typeof propagateDeletions !== 'boolean') throw new TypeError('propagateDeletions must be boolean');
    return this.request(`/${key(connectorKey)}/sync-plan`, {
      method: 'POST', body: { direction: normalizedDirection, fields: normalizedFields, propagateDeletions },
    });
  }

  async request(path, { method = 'GET', body } = {}) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (!['GET','HEAD'].includes(method)) {
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
        throw new ConnectorGovernanceApiError(response.status, problem?.code ?? problem?.title, problem?.detail ?? problem?.message);
      }
      return Object.freeze({ payload: response.status === 204 ? null : await response.json(), pagination: paginationMetadata(response.headers) });
    } catch (error) {
      if (error?.name === 'AbortError') throw new ConnectorGovernanceApiError(0,'CONNECTOR_GOVERNANCE_TIMEOUT','Connector governance request timed out');
      throw error;
    } finally { clearTimeout(timer); }
  }
}

function key(value) {
  const normalized = String(value ?? '').trim().toLowerCase();
  if (!CONNECTOR_KEY.test(normalized)) throw new TypeError('connectorKey is invalid');
  return encodeURIComponent(normalized);
}
function integer(value,min,max,name){ const n=Number(value); if(!Number.isSafeInteger(n)||n<min||n>max) throw new TypeError(`${name} is out of bounds`); return n; }
async function safeProblem(response){ try{return await response.json();}catch{return null;} }
