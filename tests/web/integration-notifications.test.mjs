import assert from 'node:assert/strict';
import test from 'node:test';
import { NotificationApiError, NotificationClient } from '../../src/applications/web/public/assets/integration-notifications.mjs';

const config={apiBaseUrl:'/api',integrationsConnectorsEnabled:true};
const headers=(values={})=>({get:name=>values[name]??values[name.toLowerCase()]??null});
const response=(payload,{status=200,headerValues={}}={})=>({ok:status>=200&&status<300,status,headers:headers(headerValues),async json(){if(payload instanceof Error)throw payload;return payload;}});
const ID='01980000-0000-7001-8000-000000000001';

test('notification client publishes with CSRF and idempotency while keeping endpoint secrets server-side',async()=>{
  const calls=[];const client=new NotificationClient(config,{cookieProvider:()=> 'INX_XSRF=csrf-token',idempotencyKeyProvider:()=> 'notification-test-0001',fetchFunction:async(url,options)=>{calls.push({url,options});return response([{deliveryId:ID,status:'ADMITTED'}],{status:202});}});
  await client.publish('evt-20260817-1001','infrastructure.health.changed',['OPS-WEBHOOK'],{state:'DOWN'});
  assert.equal(calls[0].url,'/api/v1/integrations/notifications/events');
  assert.equal(calls[0].options.method,'POST');
  assert.equal(calls[0].options.headers['X-CSRF-Token'],'csrf-token');
  assert.equal(calls[0].options.headers['Idempotency-Key'],'notification-test-0001');
  assert.equal(calls[0].options.headers.Authorization,undefined);
  assert.deepEqual(JSON.parse(calls[0].options.body),{eventId:'evt-20260817-1001',eventType:'infrastructure.health.changed',endpointKeys:['ops-webhook'],payload:{state:'DOWN'}});
});

test('notification endpoints, runtime and DLQ are bounded and pagination-aware',async()=>{
  const calls=[];const client=new NotificationClient(config,{cookieProvider:()=> 'INX_XSRF=x',idempotencyKeyProvider:()=> 'notification-test-0002',fetchFunction:async(url,options)=>{calls.push({url,options});return response([],{headerValues:{'X-Page-Limit':'50','X-Next-Offset':'50'}});}});
  const page=await client.endpoints({offset:0,limit:50}); assert.equal(page.pagination.nextOffset,50);
  await client.deadLetters({endpointKey:'ops-webhook',offset:50,limit:50});
  await client.runtime('ops-webhook');
  await client.replay(ID,'operator replay');
  await client.resume('ops-webhook','endpoint recovered');
  assert.match(calls[1].url,/endpointKey=ops-webhook/);
  assert.equal(calls[3].options.headers['Idempotency-Key'],'notification-test-0002');
  assert.equal(calls[4].options.headers['Idempotency-Key'],'notification-test-0002');
  for(const args of [{offset:-1,limit:50},{offset:0,limit:201}]) assert.throws(()=>client.endpoints(args),/bounds/);
});

test('notification client rejects malformed contracts before network access',async()=>{
  assert.throws(()=>new NotificationClient({apiBaseUrl:'/api',integrationsConnectorsEnabled:false}),/disabled/);
  assert.throws(()=>new NotificationClient({integrationsConnectorsEnabled:true}),/apiBaseUrl/);
  const client=new NotificationClient(config,{cookieProvider:()=>'',fetchFunction:async()=>response([]),idempotencyKeyProvider:()=> 'short'});
  assert.throws(()=>client.publish('bad','a.b',['ops-webhook'],{}),/eventId/);
  assert.throws(()=>client.publish('evt-20260817-1001','BAD',['ops-webhook'],{}),/eventType/);
  assert.throws(()=>client.publish('evt-20260817-1001','a.b',[],{}),/endpointKeys/);
  assert.throws(()=>client.publish('evt-20260817-1001','a.b',['ops-webhook','OPS-WEBHOOK'],{}),/unique/);
  assert.throws(()=>client.publish('evt-20260817-1001','a.b',['ops-webhook'],'text'),/payload/);
  await assert.rejects(client.publish('evt-20260817-1001','a.b',['ops-webhook'],{}),/CSRF/);
  assert.throws(()=>client.runtime('!bad'),/endpointKey/);
  assert.throws(()=>client.replay('not-a-uuid'),/UUIDv7/);
  assert.throws(()=>client.resume('ops-webhook','x'),/reason/);
});

test('notification provider problems and aborts become stable browser errors',async()=>{
  const problem=new NotificationClient(config,{fetchFunction:async()=>response({code:'INFRANEXUM_NOTIFICATION_UNAVAILABLE',detail:'unavailable'},{status:503})});
  await assert.rejects(problem.endpoints(),e=>e instanceof NotificationApiError&&e.status===503&&e.code==='INFRANEXUM_NOTIFICATION_UNAVAILABLE');
  const malformed=new NotificationClient(config,{fetchFunction:async()=>response(new Error('bad-json'),{status:500})});
  await assert.rejects(malformed.endpoints(),e=>e instanceof NotificationApiError&&e.status===500);
  const aborted=new NotificationClient(config,{fetchFunction:async()=>{const e=new Error('aborted');e.name='AbortError';throw e;}});
  await assert.rejects(aborted.endpoints(),e=>e instanceof NotificationApiError&&e.code==='NOTIFICATION_TIMEOUT');
});
