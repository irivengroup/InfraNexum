import { wireAsyncAction, wireAsyncForm } from './form-controller.mjs';
import { applyTranslations, localeFromDocument, translate } from './i18n.mjs';
import { DcimFacilityClient } from './dcim-facilities.mjs';
import { initializeDcimPhysicalWorkspace } from './dcim-physical-workspace.mjs';
import { initializeStableSelects } from './stable-select.mjs';
import { initializeCountrySelects } from './country-catalog.mjs';
import { initializeEnterpriseDataTables, openCrudEditor, wireCrudPanel } from './enterprise-crud.mjs';
import {
  bindTabSet,
  field,
  fillSelect,
  idempotencyKey,
  listItems,
  nullable,
  organizationDirectory,
  replaceRows,
  setWorkspaceStatus,
  subdivisionDirectory,
} from './web-workspace-utils.mjs';

const RESOURCES = Object.freeze(['sites', 'buildings', 'floors', 'rooms', 'zones']);
const KIND_BY_RESOURCE = Object.freeze({ sites:'site', buildings:'building', floors:'floor', rooms:'room', zones:'zone' });
const TITLE_BY_RESOURCE = Object.freeze({ sites:'dcim.sites', buildings:'dcim.buildings', floors:'dcim.floors', rooms:'dcim.rooms', zones:'dcim.zones' });

/**
 * Mounts the complete PGM-07-E04 facilities UI.
 * Governed entity references are selected from the authoritative hierarchy; UUID entry is never exposed.
 */
export async function initializeDcimWorkspace(documentObject = document, configuration, fetchFunction = fetch, confirmFunction = globalThis.confirm) {
  const workspace=documentObject?.getElementById?.('dcim-workspace');
  if(!workspace) return Object.freeze({ enabled:false });
  const enabled=configuration?.dcimFacilitiesEnabled===true;
  workspace.setAttribute('data-capability-enabled',String(enabled));
  if(!enabled) return Object.freeze({ enabled:false });

  workspace.innerHTML=dcimWorkspaceTemplate(configuration?.dcimPhysicalEnabled===true);
  applyTranslations(documentObject,localeFromDocument(documentObject));
  initializeCountrySelects(documentObject,localeFromDocument(documentObject));
  initializeStableSelects(documentObject);
  const client=new DcimFacilityClient(configuration,{fetchFunction});
  const physicalPromise=initializeDcimPhysicalWorkspace(documentObject,configuration,fetchFunction);
  const state={ organizations:[], subdivisions:[], sites:[], buildings:[], floors:[], rooms:[], zones:[], selected:new Map(), activeResource:'sites' };

  bindTabSet(documentObject,'[data-dcim-tab]','[data-dcim-panel]','data-dcim-tab');
  bindContext(documentObject,configuration,fetchFunction,client,state);
  for(const resource of RESOURCES) bindResource(documentObject,client,state,resource,confirmFunction);
  documentObject.getElementById('dcim-refresh')?.addEventListener('click',()=>void refreshHierarchy(documentObject,client,state));
  documentObject.addEventListener?.('infranexum:locale-change',()=>{initializeCountrySelects(documentObject,localeFromDocument(documentObject));initializeStableSelects(documentObject).sync();renderAll(documentObject,state);});

  try {
    setWorkspaceStatus(documentObject,'dcim-status','workspace.loading');
    state.organizations=await organizationDirectory(configuration,fetchFunction);
    fillSelect(documentObject,documentObject.getElementById('dcim-organization'),state.organizations,{label:(item)=>`${item.code??''} — ${item.displayName??item.legalName??item.id}`,selectFirst:true});
    const initialOrganization=documentObject.getElementById('dcim-organization')?.value;
    if(initialOrganization){
      state.subdivisions=await subdivisionDirectory(configuration,initialOrganization,fetchFunction);
      fillSelect(documentObject,documentObject.getElementById('dcim-subdivision'),state.subdivisions,{label:(x)=>`${x.code??''} — ${x.displayName??x.id}`,selectFirst:true});
      if(documentObject.getElementById('dcim-subdivision')?.value) await loadSites(documentObject,client,state);
    }
    setWorkspaceStatus(documentObject,'dcim-status','workspace.ready','success');
  } catch(error) {
    setWorkspaceStatus(documentObject,'dcim-status','workspace.directoryUnavailable','error');
  }
  await physicalPromise;
  return Object.freeze({ enabled:true, refresh:()=>refreshHierarchy(documentObject,client,state) });
}

export function dcimWorkspaceTemplate(physicalEnabled=false){
  const infrastructureTabs=physicalEnabled
    ? `${tab('models','dcim.physical.models')}${tab('racks','dcim.physical.racks')}${tab('equipment','dcim.physical.equipment')}${tab('cables','dcim.physical.cables')}`
    : '';
  return `
    <header class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-4">
      <div><p class="small text-uppercase fw-bold text-primary mb-1" data-i18n="dcim.eyebrow">Physical infrastructure</p><h2 id="dcim-workspace-title" data-i18n="dcim.title">Facilities</h2><p data-i18n="dcim.description">Sites, buildings, floors, rooms and technical zones governed as one physical hierarchy.</p></div>
      <button id="dcim-refresh" class="btn btn-outline-primary" type="button" data-i18n="common.refresh">Refresh</button>
    </header>
    <p id="dcim-status" class="alert alert-info py-2" role="status" aria-live="polite" data-state="info" data-i18n="workspace.ready">Ready.</p>
    <section class="inx-filter-bar inx-filter-context mb-4" aria-labelledby="dcim-context-title">
      <h3 id="dcim-context-title" class="visually-hidden" data-i18n="dcim.context">Physical hierarchy context</h3>
      <div class="inx-filter-field inx-filter-field-wide"><label class="form-label" for="dcim-organization" data-i18n="common.organization">Organization</label><select id="dcim-organization" class="form-select" required></select></div>
      <div class="inx-filter-field inx-filter-field-wide"><label class="form-label" for="dcim-subdivision" data-i18n="common.subdivision">Subdivision</label><select id="dcim-subdivision" class="form-select" required disabled></select></div>
      <div class="inx-filter-field"><label class="form-label" for="dcim-site-context" data-i18n="dcim.site">Site</label><select id="dcim-site-context" class="form-select" disabled></select></div>
      <div class="inx-filter-field"><label class="form-label" for="dcim-building-context" data-i18n="dcim.building">Building</label><select id="dcim-building-context" class="form-select" disabled></select></div>
      <div class="inx-filter-field"><label class="form-label" for="dcim-floor-context" data-i18n="dcim.floor">Floor</label><select id="dcim-floor-context" class="form-select" disabled></select></div>
      <div class="inx-filter-field"><label class="form-label" for="dcim-room-context" data-i18n="dcim.room">Room</label><select id="dcim-room-context" class="form-select" disabled></select></div>
    </section>
    <div class="row g-4">
      <aside class="col-lg-3" aria-label="DCIM navigation" data-i18n-aria-label="dcim.navigation">
        <nav class="nav nav-pills flex-column gap-1" role="tablist" aria-orientation="vertical">
          <div class="mb-3" data-dcim-nav-group="location"><p class="small text-uppercase fw-bold text-body-secondary px-2 mb-1" data-i18n="dcim.nav.location">Location</p>
            ${tab('sites','dcim.sites',true)}${tab('buildings','dcim.buildings')}${tab('floors','dcim.floors')}${tab('rooms','dcim.rooms')}${tab('zones','dcim.zones')}
          </div>
          ${physicalEnabled?`<div class="mb-3" data-dcim-nav-group="infrastructure"><p class="small text-uppercase fw-bold text-body-secondary px-2 mb-1" data-i18n="dcim.nav.infrastructure">Infrastructure</p>${infrastructureTabs}</div>`:''}
        </nav>
      </aside>
      <div class="col-lg-9 inx-min-w-0">
        ${panel('sites',siteFields())}
        ${panel('buildings',buildingFields())}
        ${panel('floors',floorFields())}
        ${panel('rooms',roomFields())}
        ${panel('zones',zoneFields())}
        <div id="dcim-physical-extension"></div>
      </div>
    </div>
  `;
}

function tab(resource,key,active=false){return `<button class="nav-link text-start${active?' active':''}" type="button" role="tab" aria-selected="${active}" tabindex="${active?'0':'-1'}" data-dcim-tab="${resource}" data-i18n="${key}">${resource}</button>`;}
function panel(resource,fields){const titleKey=TITLE_BY_RESOURCE[resource]??'dcim.title';return `
  <section class="tab-pane inx-crud-panel" role="tabpanel" data-inx-crud-panel="dcim-${resource}" data-dcim-panel="${resource}"${resource==='sites'?'':' hidden aria-hidden="true"'}>
    <div class="inx-crud-list-view" data-inx-crud-list>
      <header class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-3 inx-dcim-context-header" data-dcim-context="location">
        <div><p class="small text-uppercase fw-bold text-primary mb-1" data-i18n="dcim.nav.location">Location</p><h3 class="h5 mb-1" data-i18n="${titleKey}">${resource}</h3></div>
      </header>
      <div class="inx-filter-bar mb-4">
        <div class="inx-filter-field inx-filter-field-wide"><label class="form-label" for="dcim-${resource}-status-filter" data-i18n="common.status">Status</label><select id="dcim-${resource}-status-filter" class="form-select"><option value="" data-i18n="common.all">All</option>${statusOptions()}</select></div>
        ${resource==='sites'?'<div class="inx-filter-field"><label class="form-label" for="dcim-sites-country-filter" data-i18n="dcim.countryFilter">Country</label><select id="dcim-sites-country-filter" class="form-select" data-inx-country-select></select></div>':''}
        <div class="inx-filter-actions"><button id="dcim-${resource}-list-refresh" class="btn btn-outline-primary" type="button" data-i18n="common.refresh">Refresh</button><button class="btn btn-primary" type="button" data-inx-crud-new="record" data-inx-crud-editor-mode="create"><span aria-hidden="true">＋</span> <span data-i18n="common.new">New</span></button></div>
      </div>
      <div class="border rounded-3 overflow-hidden mb-4 bg-body shadow-sm"><div class="table-responsive"><table class="table table-hover align-middle mb-0"><thead><tr><th data-i18n="dcim.code">Code</th><th data-i18n="dcim.name">Name</th><th data-i18n="common.status">Status</th><th data-i18n="common.version">Version</th><th data-i18n="dcim.parent">Parent</th></tr></thead><tbody id="dcim-${resource}-rows"></tbody></table></div></div>
    </div>
    <section class="inx-crud-editor-view" data-inx-crud-editor hidden aria-hidden="true">
      <div class="inx-crud-editor-header"><div><p class="small text-uppercase fw-bold text-primary mb-1" data-i18n="dcim.nav.location">Location</p><h3 class="h5 mb-1" data-i18n="${titleKey}">${resource}</h3><p class="small text-body-secondary mb-0" data-inx-crud-editor-title data-i18n="dcim.manage">Create / edit</p></div><button class="btn btn-outline-secondary btn-sm" type="button" data-inx-crud-back data-i18n="common.backToList">Back to list</button></div>
      <div data-inx-crud-form="record" data-inx-crud-title-key="dcim.manage">
        <form id="dcim-${resource}-form" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3" autocomplete="off">
          <h3 data-i18n="dcim.manage">Create / edit</h3>
          ${fields}
          <div class="col-12"><label class="form-label" for="dcim-${resource}-reason" data-i18n="common.reason">Audit reason</label><textarea id="dcim-${resource}-reason" name="reason" class="form-control" minlength="2" maxlength="1024" required></textarea></div>
          <div class="col-12 d-flex flex-wrap gap-2"><button class="btn btn-primary" data-inx-only-mode="create" data-dcim-create="${resource}" type="submit" data-i18n="common.create">Create</button><button id="dcim-${resource}-update" data-inx-only-mode="edit" class="btn btn-outline-primary" type="button" disabled data-i18n="common.save">Save</button></div>
        </form>
      </div>
      <div data-inx-crud-form="lifecycle" data-inx-crud-title-key="dcim.lifecycle" hidden>
        <form id="dcim-${resource}-status-form" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3">
          <h3 data-i18n="dcim.lifecycle">Lifecycle</h3>
          <div class="col-12"><label class="form-label" for="dcim-${resource}-target" data-i18n="dcim.targetStatus">Target status</label><select id="dcim-${resource}-target" name="targetStatus" class="form-select" disabled></select></div>
          <div class="col-12"><label class="form-label" for="dcim-${resource}-status-reason" data-i18n="common.reason">Audit reason</label><textarea id="dcim-${resource}-status-reason" name="reason" class="form-control" minlength="2" maxlength="1024" required></textarea></div>
          <div class="col-12"><button class="btn btn-outline-danger" type="submit" disabled data-dcim-status-action="${resource}" data-i18n="dcim.applyStatus">Apply status</button></div>
        </form>
      </div>
    </section>
  </section>`;}
function commonFields(){return `<div class="col-12"><label class="form-label" data-i18n="dcim.name">Display name</label><input name="displayName" class="form-control" minlength="3" maxlength="128" required /></div>`;}
function siteFields(){return `${commonFields()}<div class="col-12"><label class="form-label" data-i18n="dcim.addressLine1">Address line 1</label><input name="addressLine1" class="form-control" maxlength="128" required /></div><div class="col-12"><label class="form-label" data-i18n="dcim.addressLine2">Address line 2</label><input name="addressLine2" class="form-control" maxlength="128" /></div><div class="col-md-4"><label class="form-label" data-i18n="dcim.postalCode">Postal code</label><input name="postalCode" class="form-control" maxlength="16" required /></div><div class="col-md-8"><label class="form-label" data-i18n="dcim.city">City</label><input name="city" class="form-control" maxlength="64" required /></div><div class="col-md-4"><label class="form-label" data-i18n="dcim.country">Country code</label><select name="countryCode" class="form-select" data-inx-country-select required></select></div><div class="col-md-8"><label class="form-label" data-i18n="dcim.timezone">Timezone</label><input name="timezone" class="form-control" maxlength="64" placeholder="Europe/Paris" required /></div><div class="col-md-6"><label class="form-label" data-i18n="dcim.latitude">Latitude</label><input name="latitude" type="number" step="0.0000001" min="-90" max="90" class="form-control" /></div><div class="col-md-6"><label class="form-label" data-i18n="dcim.longitude">Longitude</label><input name="longitude" type="number" step="0.0000001" min="-180" max="180" class="form-control" /></div><div class="col-12"><label class="form-label" data-i18n="dcim.descriptionField">Description</label><textarea name="description" class="form-control" maxlength="2000"></textarea></div>`;}
function buildingFields(){return `${commonFields()}<div class="col-md-4"><label class="form-label" data-i18n="dcim.floorCount">Floor count</label><input name="floorCount" type="number" min="1" step="1" class="form-control" required /></div><div class="col-md-4"><label class="form-label" data-i18n="dcim.area">Area m²</label><input name="areaM2" type="number" min="0" step="any" class="form-control" /></div><div class="col-md-4"><label class="form-label" data-i18n="dcim.latitude">Latitude</label><input name="latitude" type="number" step="0.0000001" min="-90" max="90" class="form-control" /></div><div class="col-md-4"><label class="form-label" data-i18n="dcim.longitude">Longitude</label><input name="longitude" type="number" step="0.0000001" min="-180" max="180" class="form-control" /></div><div class="col-12"><label class="form-label" data-i18n="dcim.descriptionField">Description</label><textarea name="description" class="form-control" maxlength="4000"></textarea></div>`;}
function floorFields(){return `${commonFields()}<div class="col-md-4"><label class="form-label" data-i18n="dcim.levelNumber">Level number</label><input name="levelNumber" type="number" step="1" class="form-control" required /></div><div class="col-md-4"><label class="form-label" data-i18n="dcim.levelHeight">Level height m</label><input name="levelHeightM" type="number" min="0" step="any" class="form-control" /></div><div class="col-md-4"><label class="form-label" data-i18n="dcim.area">Area m²</label><input name="areaM2" type="number" min="0" step="any" class="form-control" /></div><div class="col-md-6"><label class="form-label" data-i18n="dcim.capacity">Capacity kW</label><input name="capacityKw" type="number" min="0" step="any" class="form-control" /></div><div class="col-12"><label class="form-label" data-i18n="dcim.descriptionField">Description</label><textarea name="description" class="form-control" maxlength="4000"></textarea></div>`;}
function roomFields(){return `${commonFields()}<div class="col-md-4"><label class="form-label" data-i18n="dcim.area">Area m²</label><input name="areaM2" type="number" min="0" step="any" class="form-control" required /></div><div class="col-md-4"><label class="form-label" data-i18n="dcim.access">Access</label><select name="accessRestriction" class="form-select"><option value="open">open</option><option value="restricted">restricted</option><option value="secure">secure</option></select></div><div class="col-md-4"><label class="form-label" data-i18n="dcim.capacity">Capacity kW</label><input name="capacityKw" type="number" min="0" step="any" class="form-control" /></div><div class="col-12"><label class="form-label" data-i18n="dcim.descriptionField">Description</label><textarea name="description" class="form-control" maxlength="4000"></textarea></div>`;}
function zoneFields(){return `<div class="col-md-4"><label class="form-label" for="dcim-zone-parent-kind" data-i18n="dcim.parentKind">Parent kind</label><select id="dcim-zone-parent-kind" name="parentKind" class="form-select"><option value="site">site</option><option value="building">building</option><option value="floor">floor</option><option value="room">room</option></select></div><div class="col-md-8"><label class="form-label" for="dcim-zone-parent" data-i18n="dcim.parent">Parent</label><select id="dcim-zone-parent" name="parentId" class="form-select" required></select></div>${commonFields()}<div class="col-md-6"><label class="form-label" data-i18n="dcim.zoneType">Zone type</label><select name="zoneType" class="form-select" required><option value="cooling">cooling</option><option value="power_distribution">power_distribution</option><option value="airflow">airflow</option><option value="security">security</option></select></div><div class="col-12"><label class="form-label" data-i18n="dcim.descriptionField">Description</label><textarea name="description" class="form-control" maxlength="4000"></textarea></div>`;}
function statusOptions(){return ['draft','active','suspended','maintenance','locked','inactive','archived','deleted'].map((v)=>`<option value="${v}">${v}</option>`).join('');}

function bindContext(documentObject,configuration,fetchFunction,client,state){
  const org=documentObject.getElementById('dcim-organization');const sub=documentObject.getElementById('dcim-subdivision');const site=documentObject.getElementById('dcim-site-context');const building=documentObject.getElementById('dcim-building-context');const floor=documentObject.getElementById('dcim-floor-context');const room=documentObject.getElementById('dcim-room-context');
  org?.addEventListener('change',async()=>{state.subdivisions=[];state.sites=[];state.buildings=[];state.floors=[];state.rooms=[];clearDescendantSelects(documentObject,'subdivision');if(!org.value)return;try{state.subdivisions=await subdivisionDirectory(configuration,org.value,fetchFunction);fillSelect(documentObject,sub,state.subdivisions,{label:(x)=>`${x.code??''} — ${x.displayName??x.id}`,selectFirst:true});if(sub?.value)await loadSites(documentObject,client,state);}catch{setWorkspaceStatus(documentObject,'dcim-status','workspace.directoryUnavailable','error');}});
  sub?.addEventListener('change',()=>void loadSites(documentObject,client,state));
  site?.addEventListener('change',()=>void loadBuildings(documentObject,client,state));
  building?.addEventListener('change',()=>void loadFloors(documentObject,client,state));
  floor?.addEventListener('change',()=>void loadRooms(documentObject,client,state));
  room?.addEventListener('change',()=>void refreshResource(documentObject,client,state,'zones'));
  documentObject.getElementById('dcim-zone-parent-kind')?.addEventListener('change',()=>syncZoneParent(documentObject,state));
  for(const tab of documentObject.querySelectorAll?.('[data-dcim-tab]')??[])tab.addEventListener?.('click',()=>{state.activeResource=tab.getAttribute('data-dcim-tab')||'sites';void refreshResource(documentObject,client,state,state.activeResource);});
}

async function refreshHierarchy(documentObject,client,state){
  if(!documentObject.getElementById('dcim-subdivision')?.value)return;
  setWorkspaceStatus(documentObject,'dcim-status','workspace.loading');
  try{await loadSites(documentObject,client,state);setWorkspaceStatus(documentObject,'dcim-status','workspace.ready','success');}
  catch(error){handleError(documentObject,error);}
}
async function loadSites(d,c,s){const org=d.getElementById('dcim-organization')?.value;const sub=d.getElementById('dcim-subdivision')?.value;if(!org||!sub){s.sites=[];return;}s.sites=listItems((await c.list('sites',{organization_id:org,subdivision_id:sub,limit:200})).payload);fillSelect(d,d.getElementById('dcim-site-context'),s.sites,{label:label,selectFirst:true});await loadBuildings(d,c,s);renderResource(d,s,'sites');syncZoneParent(d,s);}
async function loadBuildings(d,c,s){const org=d.getElementById('dcim-organization')?.value;const sub=d.getElementById('dcim-subdivision')?.value;const parent=d.getElementById('dcim-site-context')?.value;s.buildings=org&&sub&&parent?listItems((await c.list('buildings',{organization_id:org,subdivision_id:sub,parent_id:parent,limit:200})).payload):[];fillSelect(d,d.getElementById('dcim-building-context'),s.buildings,{label,selectFirst:true});await loadFloors(d,c,s);renderResource(d,s,'buildings');syncZoneParent(d,s);}
async function loadFloors(d,c,s){const org=d.getElementById('dcim-organization')?.value;const sub=d.getElementById('dcim-subdivision')?.value;const parent=d.getElementById('dcim-building-context')?.value;s.floors=org&&sub&&parent?listItems((await c.list('floors',{organization_id:org,subdivision_id:sub,parent_id:parent,limit:200})).payload):[];fillSelect(d,d.getElementById('dcim-floor-context'),s.floors,{label,selectFirst:true});await loadRooms(d,c,s);renderResource(d,s,'floors');syncZoneParent(d,s);}
async function loadRooms(d,c,s){const org=d.getElementById('dcim-organization')?.value;const sub=d.getElementById('dcim-subdivision')?.value;const parent=d.getElementById('dcim-floor-context')?.value;s.rooms=org&&sub&&parent?listItems((await c.list('rooms',{organization_id:org,subdivision_id:sub,parent_id:parent,limit:200})).payload):[];fillSelect(d,d.getElementById('dcim-room-context'),s.rooms,{label,selectFirst:true});renderResource(d,s,'rooms');syncZoneParent(d,s);}

async function refreshResource(d,c,s,resource){
  const org=d.getElementById('dcim-organization')?.value;const sub=d.getElementById('dcim-subdivision')?.value;if(!org||!sub)return;
  const filters={organization_id:org,subdivision_id:sub,limit:200};const parent=parentForResource(d,resource);if(resource!=='sites'&&resource!=='zones'&&!parent){s[resource]=[];renderResource(d,s,resource);return;}if(parent)filters.parent_id=parent;const status=d.getElementById(`dcim-${resource}-status-filter`)?.value;if(status)filters.status=status;if(resource==='sites'){const country=d.getElementById('dcim-sites-country-filter')?.value?.trim();if(country)filters.country_code=country.toUpperCase();}
  try{s[resource]=listItems((await c.list(resource,filters)).payload);renderResource(d,s,resource);syncZoneParent(d,s);}
  catch(error){handleError(d,error);}
}
function parentForResource(d,r){if(r==='buildings')return d.getElementById('dcim-site-context')?.value||'';if(r==='floors')return d.getElementById('dcim-building-context')?.value||'';if(r==='rooms')return d.getElementById('dcim-floor-context')?.value||'';if(r==='zones')return d.getElementById('dcim-zone-parent')?.value||'';return '';}

function bindResource(d,c,s,resource,confirmFunction){
  d.getElementById(`dcim-${resource}-list-refresh`)?.addEventListener('click',()=>void refreshResource(d,c,s,resource));
  d.getElementById(`dcim-${resource}-status-filter`)?.addEventListener('change',()=>void refreshResource(d,c,s,resource));
  if(resource==='sites') d.getElementById('dcim-sites-country-filter')?.addEventListener('change',()=>void refreshResource(d,c,s,resource));
  const form=d.getElementById(`dcim-${resource}-form`);
  if(form) wireAsyncForm(form,{execute:async()=>mutateCreate(d,c,s,resource,form)});
  const update=d.getElementById(`dcim-${resource}-update`);
  if(update) wireAsyncAction(update,{execute:async()=>mutateUpdate(d,c,s,resource,form)});
  d.getElementById(`dcim-${resource}-clear`)?.addEventListener('click',()=>clearSelection(d,s,resource));
  const statusForm=d.getElementById(`dcim-${resource}-status-form`);
  if(statusForm) wireAsyncForm(statusForm,{execute:async()=>mutateStatus(d,c,s,resource,statusForm,confirmFunction)});
}
async function mutateCreate(d,c,s,r,form){
  const org=d.getElementById('dcim-organization')?.value;const sub=d.getElementById('dcim-subdivision')?.value;if(!org||!sub){setWorkspaceStatus(d,'dcim-status','workspace.selectOrganization','error');return;}
  try{setWorkspaceStatus(d,'dcim-status','workspace.saving');const body=bodyFromForm(d,r,form,org,sub,true);await c.create(r,body,idempotencyKey(`dcim-${KIND_BY_RESOURCE[r]}-create`));await refreshHierarchy(d,c,s);await refreshResource(d,c,s,r);form.reset();setWorkspaceStatus(d,'dcim-status','workspace.saved','success');}
  catch(error){handleError(d,error);}
}
async function mutateUpdate(d,c,s,r,form){const selected=s.selected.get(r);if(!selected){setWorkspaceStatus(d,'dcim-status','workspace.selectRecord','error');return;}try{setWorkspaceStatus(d,'dcim-status','workspace.saving');const body=bodyFromForm(d,r,form,selected.organizationId,selected.subdivisionId,false);const result=await c.update(r,selected.id,selected.version,body,idempotencyKey(`dcim-${KIND_BY_RESOURCE[r]}-update`));selectRecord(d,s,r,result.payload);await refreshHierarchy(d,c,s);await refreshResource(d,c,s,r);setWorkspaceStatus(d,'dcim-status','workspace.saved','success');}catch(error){handleError(d,error);}}
async function mutateStatus(d,c,s,r,form,confirmFunction=globalThis.confirm){const selected=s.selected.get(r);if(!selected){setWorkspaceStatus(d,'dcim-status','workspace.selectRecord','error');return;}const targetStatus=field(form,'targetStatus');if(targetStatus==='deleted'&&typeof confirmFunction==='function'&&!confirmFunction(translate(localeFromDocument(d),'common.confirmDelete')))return;try{setWorkspaceStatus(d,'dcim-status','workspace.saving');const result=await c.changeStatus(r,selected.id,selected.version,targetStatus,field(form,'reason'),idempotencyKey(`dcim-${KIND_BY_RESOURCE[r]}-status`));selectRecord(d,s,r,result.payload);await refreshHierarchy(d,c,s);await refreshResource(d,c,s,r);setWorkspaceStatus(d,'dcim-status','workspace.saved','success');}catch(error){handleError(d,error);}}

function bodyFromForm(d,r,form,org,sub,create){const body={organizationId:org,subdivisionId:sub,displayName:field(form,'displayName'),addressLine1:nullable(field(form,'addressLine1')),addressLine2:nullable(field(form,'addressLine2')),postalCode:nullable(field(form,'postalCode')),city:nullable(field(form,'city')),countryCode:nullable(field(form,'countryCode')),timezone:nullable(field(form,'timezone')),latitude:numberOrNull(field(form,'latitude')),longitude:numberOrNull(field(form,'longitude')),floorCount:intOrNull(field(form,'floorCount')),levelNumber:intOrNull(field(form,'levelNumber')),areaM2:numberOrNull(field(form,'areaM2')),levelHeightM:numberOrNull(field(form,'levelHeightM')),capacityKw:numberOrNull(field(form,'capacityKw')),accessRestriction:nullable(field(form,'accessRestriction')),zoneType:nullable(field(form,'zoneType')),description:nullable(field(form,'description')),reason:field(form,'reason')};if(create)body.parentId=r==='zones'?field(form,'parentId'):nullable(parentForResource(d,r));else{delete body.organizationId;delete body.subdivisionId;delete body.code;delete body.parentId;}return body;}
function numberOrNull(v){return v===''||v===null||v===undefined?null:Number(v);}function intOrNull(v){return v===''||v===null||v===undefined?null:Number.parseInt(v,10);}

function renderAll(d,s){for(const r of RESOURCES)renderResource(d,s,r);syncZoneParent(d,s);}
function renderResource(d,s,r){const panel=d.querySelector?.(`[data-dcim-panel="${r}"]`);replaceRows(d,d.getElementById(`dcim-${r}-rows`),s[r]??[],[(x)=>x.code,(x)=>x.displayName,(x)=>translateStatus(d,x.status),(x)=>String(x.version??''),(x)=>parentLabel(s,x.parentId)],(row)=>selectRecord(d,s,r,row),{actions:()=>[{key:'edit',labelKey:'common.edit',className:'btn-outline-primary',onClick:()=>openCrudEditor(panel,'record',{mode:'edit'})},{key:'lifecycle',labelKey:'dcim.lifecycle',className:'btn-outline-secondary',onClick:()=>openCrudEditor(panel,'lifecycle',{mode:'edit'})}]});initializeEnterpriseDataTables(d);}
function selectRecord(d,s,r,row){s.selected.set(r,row);const detail=d.getElementById(`dcim-${r}-detail`);if(detail)detail.textContent=JSON.stringify(row,null,2);const form=d.getElementById(`dcim-${r}-form`);if(form)for(const name of ['displayName','addressLine1','addressLine2','postalCode','city','countryCode','timezone','latitude','longitude','floorCount','levelNumber','areaM2','levelHeightM','capacityKw','accessRestriction','zoneType','description']){const el=form.elements?.namedItem?.(name);if(el){el.value=row[name]??'';if(String(el.tagName??'').toUpperCase()==='SELECT'){const EventConstructor=d?.defaultView?.Event??globalThis.Event;if(typeof EventConstructor==='function')el.dispatchEvent?.(new EventConstructor('change',{bubbles:true}));}}}d.getElementById(`dcim-${r}-update`)?.removeAttribute('disabled');syncStatusActions(d,r,row);}
function clearSelection(d,s,r){s.selected.delete(r);const form=d.getElementById(`dcim-${r}-form`);form?.reset?.();const detail=d.getElementById(`dcim-${r}-detail`);if(detail)detail.textContent='—';d.getElementById(`dcim-${r}-update`)?.setAttribute('disabled','');const target=d.getElementById(`dcim-${r}-target`);target?.replaceChildren();target?.setAttribute('disabled','');d.querySelector?.(`[data-dcim-status-action="${r}"]`)?.setAttribute('disabled','');}
function syncStatusActions(d,r,row){const target=d.getElementById(`dcim-${r}-target`);if(!target)return;const options=allowedTransitions(KIND_BY_RESOURCE[r],row.status).map((status)=>{const option=d.createElement('option');option.value=status;option.textContent=translateStatus(d,status);return option;});target.replaceChildren(...options);target.disabled=options.length===0;const action=d.querySelector?.(`[data-dcim-status-action="${r}"]`);if(action){action.disabled=options.length===0;action.setAttribute?.('aria-disabled',action.disabled?'true':'false');}}
function allowedTransitions(kind,status){const k=`${kind}:${status}`;return ({'site:draft':['active'],'site:active':['suspended','archived'],'site:suspended':['active','archived'],'site:archived':['deleted'],'building:draft':['active'],'building:active':['maintenance','archived'],'building:maintenance':['active'],'building:archived':['deleted'],'floor:draft':['active'],'floor:active':['maintenance','archived'],'floor:maintenance':['active'],'floor:archived':['deleted'],'room:draft':['active'],'room:active':['maintenance','locked','archived'],'room:maintenance':['active'],'room:locked':['active'],'room:archived':['deleted'],'zone:draft':['active'],'zone:active':['maintenance','inactive'],'zone:maintenance':['active'],'zone:inactive':['archived'],'zone:archived':['deleted']}[k]??[]);}
function translateStatus(d,status){const key=`dcim.status.${String(status??'').toLowerCase()}`;const value=translate(localeFromDocument(d),key);return value===key?String(status??''):value;}
function parentLabel(s,id){if(!id)return '—';for(const r of RESOURCES)for(const x of s[r]??[])if(x.id===id)return `${x.code} — ${x.displayName}`;return String(id);}
function label(x){return `${x.code??''} — ${x.displayName??x.id}`;}
function syncZoneParent(d,s){const kind=d.getElementById('dcim-zone-parent-kind')?.value||'site';const source={site:s.sites,building:s.buildings,floor:s.floors,room:s.rooms}[kind]??[];fillSelect(d,d.getElementById('dcim-zone-parent'),source,{label});}
function clearDescendantSelects(d,from){const ids=from==='subdivision'?['dcim-subdivision','dcim-site-context','dcim-building-context','dcim-floor-context','dcim-room-context']:[];for(const id of ids){const select=d.getElementById(id);select?.replaceChildren();if(select)select.disabled=true;}}
function handleError(d,error){const code=error?.code??'';if(error?.status===403)setWorkspaceStatus(d,'dcim-status','workspace.restricted','error');else if(error?.status===409)setWorkspaceStatus(d,'dcim-status','workspace.conflict','error');else setWorkspaceStatus(d,'dcim-status','workspace.error','error',{message:error?.message??code??'unknown'});}
