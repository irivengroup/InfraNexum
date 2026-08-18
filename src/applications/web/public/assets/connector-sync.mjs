import { csrfToken } from './auth.mjs';
import { paginationMetadata } from './http-pagination.mjs';

const TIMEOUT_MS = 15_000;
const CONNECTOR_KEY = /^[a-z0-9][a-z0-9._-]{2,79}$/;
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DIRECTIONS = new Set(['INBOUND','OUTBOUND','BIDIRECTIONAL']);
const FIELD = /^[a-z][a-z0-9_.-]{0,127}$/;

export class ConnectorSyncApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `Connector synchronization request failed with HTTP ${status}`);
    this.name = 'ConnectorSyncApiError'; this.status = status; this.code = code || 'CONNECTOR_SYNC_HTTP_ERROR';
  }
}

/** Browser boundary for durable sync history and explicitly governed operator mutations. */
export class ConnectorSyncClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '' } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.integrationsConnectorsEnabled !== true) throw new Error('Integrations Web capability is disabled');
    this.base = `${configuration.apiBaseUrl}/v1/integrations/sync`; this.fetchFunction = fetchFunction; this.cookieProvider = cookieProvider;
  }

  runs({ connectorKey, offset = 0, limit = 50 } = {}) {
    const query = new URLSearchParams({ offset: String(integer(offset,0,1_000_000,'offset')), limit: String(integer(limit,1,200,'limit')) });
    if (connectorKey != null && String(connectorKey).trim() !== '') query.set('connectorKey', key(connectorKey));
    return this.request(`/runs?${query}`);
  }
  checkpoints(connectorKey, { offset = 0, limit = 50 } = {}) {
    return this.request(`/${key(connectorKey)}/checkpoints?offset=${integer(offset,0,1_000_000,'offset')}&limit=${integer(limit,1,200,'limit')}`);
  }
  execute(connectorKey, { direction, fields = [], propagateDeletions = false, maxBatches = 10, reason, idempotencyKey } = {}) {
    const d = String(direction ?? '').trim().toUpperCase(); if (!DIRECTIONS.has(d)) throw new TypeError('mutating synchronization direction is invalid');
    const normalizedFields = fieldList(fields); if (typeof propagateDeletions !== 'boolean') throw new TypeError('propagateDeletions must be boolean');
    return this.mutate(`/${key(connectorKey)}/execute`, { direction:d, fields:normalizedFields, propagateDeletions, maxBatches:integer(maxBatches,1,100,'maxBatches'), reason:operatorReason(reason) }, idempotencyKey);
  }
  resume(syncRunId, reason, idempotencyKey) { return this.mutate(`/runs/${runId(syncRunId)}/resume`, { reason:operatorReason(reason) }, idempotencyKey); }
  compensate(syncRunId, reason, idempotencyKey) { return this.mutate(`/runs/${runId(syncRunId)}/compensate`, { reason:operatorReason(reason) }, idempotencyKey); }
  mutate(path, body, idempotencyKey) { const idem=String(idempotencyKey??'').trim(); if(idem.length<8||idem.length>200)throw new TypeError('idempotencyKey must contain 8..200 characters'); return this.request(path,{method:'POST',body,extraHeaders:{'Idempotency-Key':idem}}); }

  async request(path, { method = 'GET', body, extraHeaders = {} } = {}) {
    const headers = { Accept:'application/json', ...extraHeaders }; if(body!==undefined)headers['Content-Type']='application/json';
    if(!['GET','HEAD'].includes(method)){const token=csrfToken(this.cookieProvider());if(!token)throw new Error('CSRF token is unavailable');headers['X-CSRF-Token']=token;}
    const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),TIMEOUT_MS);timer.unref?.();
    try { const response=await this.fetchFunction(`${this.base}${path}`,{method,headers,credentials:'same-origin',cache:'no-store',signal:controller.signal,...(body===undefined?{}:{body:JSON.stringify(body)})});
      if(!response.ok){const problem=await safeProblem(response);throw new ConnectorSyncApiError(response.status,problem?.code??problem?.title,problem?.detail??problem?.message);}
      return Object.freeze({payload:response.status===204?null:await response.json(),pagination:paginationMetadata(response.headers)});
    } catch(error){if(error?.name==='AbortError')throw new ConnectorSyncApiError(0,'CONNECTOR_SYNC_TIMEOUT','Connector synchronization request timed out');throw error;} finally{clearTimeout(timer);}
  }
}
function key(value){const v=String(value??'').trim().toLowerCase();if(!CONNECTOR_KEY.test(v))throw new TypeError('connectorKey is invalid');return encodeURIComponent(v);}
function runId(value){const v=String(value??'').trim().toLowerCase();if(!UUID_V7.test(v))throw new TypeError('syncRunId is invalid');return encodeURIComponent(v);}
function fieldList(values){if(!Array.isArray(values)||values.length>512)throw new TypeError('fields must be an array with at most 512 entries');const out=values.map(v=>String(v??'').trim()).filter(Boolean);if(new Set(out).size!==out.length||out.some(v=>!FIELD.test(v)))throw new TypeError('fields contain invalid or duplicate values');return out;}
function operatorReason(value){const v=String(value??'').trim();if(v.length<3||v.length>500)throw new TypeError('reason must contain 3..500 characters');return v;}
function integer(value,min,max,name){const n=Number(value);if(!Number.isSafeInteger(n)||n<min||n>max)throw new TypeError(`${name} is out of bounds`);return n;}
async function safeProblem(response){try{return await response.json();}catch{return null;}}
