import { csrfToken } from './auth.mjs';

const REQUEST_TIMEOUT_MS = 15_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const VERSION_PATTERN = /^[1-9][0-9]*$/;
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,199}$/;
const RESOURCES = new Set(['sites', 'buildings', 'floors', 'rooms', 'zones']);

/** Safe browser-boundary error for PGM-07-E04 DCIM facilities. */
export class DcimFacilityApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `DCIM request failed with HTTP ${status}`);
    this.name = 'DcimFacilityApiError'; this.status = status; this.code = code || 'DCIM_FACILITY_HTTP_ERROR';
  }
}

/** Capability-gated browser client for the physical facility hierarchy. */
export class DcimFacilityClient {
  constructor(configuration, { fetchFunction = fetch, cookieProvider = () => globalThis.document?.cookie ?? '' } = {}) {
    if (!configuration?.apiBaseUrl) throw new TypeError('apiBaseUrl is required');
    if (configuration.dcimFacilitiesEnabled !== true) throw new Error('DCIM Facility Web capability is disabled');
    this.configuration = Object.freeze({ apiBaseUrl: configuration.apiBaseUrl });
    this.fetchFunction = fetchFunction; this.cookieProvider = cookieProvider;
  }

  list(resource, filters = {}) {
    const normalized=validatedResource(resource);
    const allowed=['organization_id','subdivision_id','parent_id','status','cursor','limit'];
    if(normalized==='sites') allowed.push('country_code');
    return this.request(`/${normalized}${query(filters, allowed)}`);
  }
  get(resource, id) { return this.request(`/${validatedResource(resource)}/${uuid(id,'facilityId')}`); }
  create(resource, body, idempotencyKey) {
    const reason = validatedReason(body?.reason);
    return this.request(`/${validatedResource(resource)}`, { method:'POST', body:{ ...body, reason }, idempotencyKey, justification:reason });
  }
  update(resource, id, version, body, idempotencyKey) {
    const reason=validatedReason(body?.reason);
    return this.request(`/${validatedResource(resource)}/${uuid(id,'facilityId')}`, { method:'PATCH', body:{ ...body, reason }, version, idempotencyKey, justification:reason });
  }
  changeStatus(resource, id, version, targetStatus, reason, idempotencyKey) {
    const normalizedReason=validatedReason(reason);
    return this.request(`/${validatedResource(resource)}/${uuid(id,'facilityId')}/status`, { method:'POST', body:{ targetStatus:String(targetStatus??'').trim().toLowerCase(), reason:normalizedReason }, version, idempotencyKey, justification:normalizedReason });
  }

  async request(path, { method='GET', body, version, idempotencyKey, justification } = {}) {
    if (typeof path !== 'string' || !path.startsWith('/')) throw new TypeError('path is outside the DCIM boundary');
    const verb=String(method).toUpperCase(); const headers={ Accept:'application/json' };
    if(body!==undefined) headers['Content-Type']='application/json';
    if(version!==undefined) headers['If-Match']=`"ver-${positiveVersion(version)}"`;
    if(!['GET','HEAD'].includes(verb)) {
      const csrf=csrfToken(this.cookieProvider()); if(!csrf) throw new Error('CSRF token is unavailable');
      headers['X-CSRF-Token']=csrf; headers['Idempotency-Key']=validatedIdempotencyKey(idempotencyKey);
      if(justification) headers['X-InfraNexum-Justification']=justification.slice(0,500);
    }
    const controller=new AbortController(); const timer=setTimeout(()=>controller.abort(),REQUEST_TIMEOUT_MS); timer.unref?.();
    try {
      const response=await this.fetchFunction(`${this.configuration.apiBaseUrl}/v1/dcim${path}`, { method:verb, headers, credentials:'same-origin', cache:'no-store', signal:controller.signal, ...(body===undefined?{}:{body:JSON.stringify(body)}) });
      if(!response.ok) { const problem=await safeJson(response); throw new DcimFacilityApiError(response.status,problem?.code??problem?.title,problem?.detail??problem?.message); }
      const payload=response.status===204?null:await response.json(); return Object.freeze({ payload, etag:response.headers?.get?.('etag')??null });
    } catch(error) {
      if(error?.name==='AbortError') throw new DcimFacilityApiError(0,'DCIM_FACILITY_TIMEOUT','DCIM facility request timed out');
      throw error;
    } finally { clearTimeout(timer); }
  }
}

function validatedResource(value){const v=String(value??'').trim().toLowerCase();if(!RESOURCES.has(v))throw new TypeError('unsupported DCIM facility resource');return v;}
function uuid(value,field){const v=String(value??'').trim();if(!UUID_PATTERN.test(v))throw new TypeError(`${field} must be a UUID`);return v.toLowerCase();}
function positiveVersion(value){const v=String(value??'').trim();if(!VERSION_PATTERN.test(v))throw new TypeError('version must be a positive integer');return v;}
function validatedIdempotencyKey(value){const v=String(value??'').trim();if(!IDEMPOTENCY_KEY_PATTERN.test(v))throw new TypeError('idempotencyKey must contain 8 to 200 safe characters');return v;}
function validatedReason(value){const v=String(value??'').trim();if(v.length<2||v.length>1024||/[\u0000-\u001f\u007f]/.test(v))throw new TypeError('reason must contain 2 to 1024 printable characters');return v;}
function query(values,allowed){const p=new URLSearchParams();for(const key of allowed){const v=values?.[key];if(v!==undefined&&v!==null&&String(v).trim()!=='')p.set(key,String(v));}const encoded=p.toString();return encoded?`?${encoded}`:'';}
async function safeJson(response){try{return await response.json();}catch{return null;}}
