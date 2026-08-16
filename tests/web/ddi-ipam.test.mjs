import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { DdiIpamClient } from '../../src/applications/web/public/assets/ddi-ipam.mjs';

const ORG='019ffbda-8301-7111-8101-000000000001';
const ID='019ffbda-8301-7111-8101-000000000002';
function response(payload,status=200,headers={}){return {ok:status>=200&&status<300,status,headers:{get:n=>headers[String(n).toLowerCase()]??null},async json(){return payload;}};}

test('DDI/IPAM client is capability gated and exposes the governed collections',async()=>{
  assert.throws(()=>new DdiIpamClient({apiBaseUrl:'/api',ddiIpamEnabled:false}),/disabled/);
  const calls=[];const c=new DdiIpamClient({apiBaseUrl:'/api',ddiIpamEnabled:true},{fetchFunction:async(url,opts)=>{calls.push([url,opts]);return response([]);}});
  await c.vrfs(ORG);await c.vlans(ORG);await c.networks(ORG);await c.pools(ID);await c.addresses(ORG);
  assert.deepEqual(calls.map(x=>x[0]),[
    `/api/v1/ddi/ipam/vrfs?organization_id=${ORG}&limit=200`,
    `/api/v1/ddi/ipam/vlans?organization_id=${ORG}&limit=200`,
    `/api/v1/ddi/ipam/networks?organization_id=${ORG}&limit=200`,
    `/api/v1/ddi/ipam/networks/${ID}/pools?limit=200`,
    `/api/v1/ddi/ipam/addresses?organization_id=${ORG}&limit=200`,
  ]);
});

test('DDI/IPAM mutations enforce CSRF idempotency justification and optimistic version',async()=>{
  let request;const c=new DdiIpamClient({apiBaseUrl:'/api',ddiIpamEnabled:true},{cookieProvider:()=> 'INX_XSRF=token',fetchFunction:async(url,opts)=>{request={url,opts};return response({id:ID});}});
  await c.release(ID,3,'address retired after decommission','ddi-release-0001');
  assert.equal(request.opts.headers['X-CSRF-Token'],'token');
  assert.equal(request.opts.headers['Idempotency-Key'],'ddi-release-0001');
  assert.equal(request.opts.headers['If-Match'],'"ver-3"');
  assert.equal(request.opts.headers['X-InfraNexum-Justification'],'address retired after decommission');
});

test('DDI/IPAM workspace is a real five-catalogue UI and uses governed entity selectors',async()=>{
  const source=await readFile(new URL('../../src/applications/web/public/assets/ddi-ipam-workspace.mjs',import.meta.url),'utf8');
  for(const tab of ['vrfs','vlans','networks','pools','addresses']) assert.match(source,new RegExp(`data-ddi-tab="${tab}"`));
  for(const id of ['ddi-org','ddi-subdivision','ddi-site','ddi-network-vrf','ddi-network-vlan','ddi-network-parent','ddi-pool-network','ddi-address-vrf','ddi-address-network','ddi-address-pool','ddi-address-rsot','ddi-address-equipment']) assert.match(source,new RegExp(`id="${id}"`));
  for(const action of ['createVrf','createVlan','createNetwork','createPool','allocate','release','updateNetwork']) assert.match(source,new RegExp(`client\\.${action}|client\\[.*${action}`));
  assert.doesNotMatch(source,/<input[^>]+name=["'](?:vrfId|parentNetworkId|networkId|poolId|rsotObjectId|dcimEquipmentId)["']/);
  assert.match(source,/wireAsyncForm/);
  assert.match(source,/wireAsyncAction/);
  assert.match(source,/class="nav-link/);
  assert.match(source,/selectFirst:true/);
});

test('DDI/IPAM client accepts cursor continuation and exposes response pagination metadata',async()=>{let url;const c=new DdiIpamClient({apiBaseUrl:'/api',ddiIpamEnabled:true},{fetchFunction:async(value)=>{url=value;return response([{id:ID}],200,{'x-page-limit':'1','x-next-cursor':ID});}});const result=await c.vrfs(ORG,1,ID);assert.equal(url,`/api/v1/ddi/ipam/vrfs?organization_id=${ORG}&limit=1&cursor=${ID}`);assert.deepEqual(result.pagination,{limit:1,nextCursor:ID,nextOffset:null,hasNext:true});});
