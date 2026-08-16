import { wireAsyncForm } from './form-controller.mjs';
import { applyTranslations, localeFromDocument, translate } from './i18n.mjs';
import { ItamAssetClient } from './itam-assets.mjs';
import { ItamComplianceClient } from './itam-compliance.mjs';
import { ItamPartnerClient } from './itam-partners.mjs';
import { RsotCanonicalObjectClient } from './rsot-canonical-objects.mjs';
import { initializeStableSelects } from './stable-select.mjs';
import { initializeCountrySelects } from './country-catalog.mjs';
import { initializeTemporalPickers } from './temporal-picker.mjs';
import {
  bindTabSet,
  checkedValues,
  clean,
  csv,
  field,
  fillSelect,
  idempotencyKey,
  lines,
  listItems,
  nullable,
  organizationDirectory,
  parseJsonArray,
  replaceRows,
  selectedValues,
  setWorkspaceStatus,
  subdivisionDirectory,
  userDirectory,
} from './web-workspace-utils.mjs';

const PARTNER_ROLES = Object.freeze([
  'manufacturer', 'software_publisher', 'supplier', 'third_party_support_provider', 'integrator', 'recycler',
]);

/**
 * Full Web parity workspace for PGM-07-E01/E02/E03.
 *
 * Entity identifiers are always selected from governed catalogues. No raw UUID
 * input is exposed for organization, subdivision, RSOT, Partner, Asset or actor.
 */
export async function initializeItamWorkspace(documentObject = document, configuration, fetchFunction = fetch) {
  const workspace = documentObject?.getElementById?.('itam-workspace');
  if (!workspace) return Object.freeze({ enabled: false });
  const enabled = configuration?.itamPartnersEnabled === true || configuration?.itamAssetsEnabled === true || configuration?.itamComplianceEnabled === true;
  workspace.setAttribute('data-capability-enabled', String(enabled));
  if (!enabled) return Object.freeze({ enabled: false });

  workspace.innerHTML = itamWorkspaceTemplate(configuration);
  applyTranslations(documentObject, localeFromDocument(documentObject));
  initializeCountrySelects(documentObject, localeFromDocument(documentObject));
  initializeStableSelects(documentObject);
  initializeTemporalPickers(documentObject);

  const clients = {
    partners: configuration.itamPartnersEnabled ? new ItamPartnerClient(configuration, { fetchFunction }) : null,
    assets: configuration.itamAssetsEnabled ? new ItamAssetClient(configuration, { fetchFunction }) : null,
    compliance: configuration.itamComplianceEnabled ? new ItamComplianceClient(configuration, { fetchFunction }) : null,
    rsot: configuration.rsotCoreEnabled ? new RsotCanonicalObjectClient(configuration, { fetchFunction }) : null,
  };
  const state = {
    organizations: [], subdivisions: [], users: [], partners: [], assets: [], rsotObjects: [], warrantyTypes: [], authorizations: [],
    selectedPartner: null, selectedAsset: null, selectedCompliance: null,
  };

  gateTabs(documentObject, configuration);
  bindTabSet(documentObject, '[data-itam-tab]', '[data-itam-panel]', 'data-itam-tab');
  bindTabSet(documentObject, '[data-compliance-tab]', '[data-compliance-panel]', 'data-compliance-tab');

  wireContext(documentObject, configuration, fetchFunction, clients, state);
  wirePartner(documentObject, clients.partners, state);
  wireAsset(documentObject, configuration, fetchFunction, clients, state);
  wireCompliance(documentObject, clients.compliance, state);
  documentObject.getElementById('itam-refresh')?.addEventListener('click', () => void refreshContext(documentObject, configuration, fetchFunction, clients, state));
  documentObject.addEventListener?.('infranexum:locale-change', () => { initializeCountrySelects(documentObject, localeFromDocument(documentObject)); initializeStableSelects(documentObject).sync(); rerender(documentObject, state); });

  await loadDirectories(documentObject, configuration, fetchFunction, state);
  await refreshContext(documentObject, configuration, fetchFunction, clients, state);
  return Object.freeze({ enabled: true, refresh: () => refreshContext(documentObject, configuration, fetchFunction, clients, state) });
}

export function itamWorkspaceTemplate(configuration = {}) {
  const partnerEnabled = configuration.itamPartnersEnabled === true;
  const assetEnabled = configuration.itamAssetsEnabled === true;
  const complianceEnabled = configuration.itamComplianceEnabled === true;
  return `
    <header class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-4"><div><p class="small text-uppercase fw-bold text-primary mb-1" data-i18n="itam.eyebrow">IT asset governance</p><h2 id="itam-workspace-title" data-i18n="itam.title">ITAM</h2><p data-i18n="itam.description">Partners, patrimonial assets and contractual compliance.</p></div><button id="itam-refresh" class="btn btn-outline-primary" type="button" data-i18n="common.refresh">Refresh</button></header>
    <p id="itam-status" class="alert alert-info py-2" role="status" aria-live="polite" data-state="info" data-i18n="workspace.ready">Ready.</p>
    <section class="card card-body bg-body-tertiary border mb-4 row g-3" aria-label="ITAM governance scope">
      <div class="col-lg-6"><label class="form-label" for="itam-organization" data-i18n="common.organization">Organization</label><select id="itam-organization" class="form-select" required></select></div>
      <div class="col-lg-6"><label class="form-label" for="itam-subdivision" data-i18n="common.subdivision">Subdivision</label><select id="itam-subdivision" class="form-select"></select></div>
    </section>
    <div class="nav nav-underline gap-3 mb-4 border-bottom" role="tablist" aria-label="ITAM">
      <button class="nav-link active" role="tab" type="button" data-itam-tab="partners" aria-selected="true" ${partnerEnabled ? '' : 'hidden'} data-i18n="itam.partners">Partners</button>
      <button class="nav-link" role="tab" type="button" data-itam-tab="assets" aria-selected="false" ${assetEnabled ? '' : 'hidden'} data-i18n="itam.assets">Assets</button>
      <button class="nav-link" role="tab" type="button" data-itam-tab="compliance" aria-selected="false" ${complianceEnabled ? '' : 'hidden'} data-i18n="itam.compliance">Compliance</button>
    </div>
    ${partnerPanel()}
    ${assetPanel()}
    ${compliancePanel()}`;
}

function partnerPanel() {
  return `<section class="tab-pane" role="tabpanel" data-itam-panel="partners">
    <div class="d-grid gap-3">
      <div>
        <form id="itam-partner-filter" class="card card-body bg-body-tertiary border mb-4 row g-3">
          <div class="col-md-5"><label class="form-label" for="itam-partner-role-filter" data-i18n="itam.role">Role</label><select id="itam-partner-role-filter" name="role" class="form-select"><option value="" data-i18n="common.all">All</option>${PARTNER_ROLES.map((r) => `<option value="${r}" data-i18n="itam.role.${r}">${r}</option>`).join('')}</select></div>
          <div class="col-md-4"><label class="form-label" for="itam-partner-status-filter" data-i18n="common.status">Status</label><select id="itam-partner-status-filter" name="authorization_status" class="form-select"><option value="" data-i18n="common.all">All</option><option>draft</option><option>pending_approval</option><option>active</option><option>suspended</option><option>retired</option></select></div>
          <div class="col-md-3 d-flex align-items-end"><button class="btn btn-outline-primary w-100" type="submit" data-i18n="common.filter">Filter</button></div>
        </form>
        ${table('itam-partner-table-body', [['itam.code','Code'],['itam.displayName','Display name'],['itam.roles','Roles'],['common.status','Status'],['common.version','Version']])}
        <section id="itam-partner-detail" class="inx-record-detail card border-0 shadow-sm mb-4" tabindex="0" aria-live="polite"><div class="card-body text-body-secondary" data-i18n="itam.noPartnerSelected">Select a partner to display its governed profile.</div></section>
      </div>
      <div class="d-grid gap-3">
        <form id="itam-partner-create" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3">
          <h3 data-i18n="itam.partnerCreate">Create partner</h3>
          <div class="col-md-4"><label class="form-label" for="itam-partner-code" data-i18n="itam.code">Code</label><input id="itam-partner-code" name="code" class="form-control" maxlength="64" required /></div>
          <div class="col-md-8"><label class="form-label" for="itam-partner-display" data-i18n="itam.displayName">Display name</label><input id="itam-partner-display" name="displayName" class="form-control" maxlength="255" required /></div>
          <div class="col-12"><label class="form-label" for="itam-partner-legal" data-i18n="itam.legalName">Legal name</label><input id="itam-partner-legal" name="legalName" class="form-control" maxlength="255" required /></div>
          <div class="col-md-4"><label class="form-label" for="itam-partner-country" data-i18n="itam.country">Country</label><select id="itam-partner-country" name="countryCode" class="form-select" data-inx-country-select required></select></div>
          <div class="col-md-8"><fieldset><legend class="form-label" data-i18n="itam.roles">Roles</legend><div class="row row-cols-1 row-cols-md-2 g-3">${PARTNER_ROLES.map((r) => `<label><input type="checkbox" name="roles" value="${r}" /> <span data-i18n="itam.role.${r}">${r}</span></label>`).join('')}</div></fieldset></div>
          <div class="col-md-6"><label class="form-label" for="itam-partner-valid-from" data-i18n="common.validFrom">Valid from</label><input id="itam-partner-valid-from" name="validFrom" type="date" data-inx-temporal="date" class="form-control" required /></div>
          <div class="col-md-6"><label class="form-label" for="itam-partner-valid-until" data-i18n="common.validUntil">Valid until</label><input id="itam-partner-valid-until" name="validUntil" type="date" data-inx-temporal="date" class="form-control" /></div>
          <div class="col-md-6"><label class="form-label" for="itam-partner-website" data-i18n="itam.website">Official website</label><input id="itam-partner-website" name="officialWebsite" type="url" class="form-control" maxlength="2048" /></div>
          <div class="col-md-6"><label class="form-label" for="itam-partner-support" data-i18n="itam.supportPortal">Support portal</label><input id="itam-partner-support" name="supportPortal" type="url" class="form-control" maxlength="2048" /></div>
          <div class="col-12"><label class="form-label" for="itam-partner-aliases" data-i18n="itam.aliases">Aliases (one per line)</label><textarea id="itam-partner-aliases" name="aliases" class="form-control" rows="3"></textarea></div>
          ${jsonField('itam-partner-external-ids','externalIds','itam.externalIds','[]',3)}
          ${jsonField('itam-partner-accreditations','accreditations','itam.accreditations','[]',4)}
          ${partnerContactsEditor()}
          <div class="col-12"><label class="form-label" for="itam-partner-reason" data-i18n="common.reason">Reason</label><textarea id="itam-partner-reason" name="reason" class="form-control" minlength="2" maxlength="1024" required></textarea></div>
          <div class="col-12"><button class="btn btn-primary" type="submit" data-i18n="common.create">Create</button></div>
        </form>
        <form id="itam-partner-lifecycle" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><h3 data-i18n="itam.partnerLifecycle">Selected partner</h3><div class="col-12"><label class="form-label" for="itam-partner-transition-reason" data-i18n="common.reason">Reason</label><textarea id="itam-partner-transition-reason" name="reason" class="form-control" minlength="2" maxlength="1024" required></textarea></div><div class="col-12 d-flex flex-wrap gap-2"><button class="btn btn-outline-primary" type="submit" value="submit" data-i18n="itam.submitApproval">Submit approval</button><button class="btn btn-outline-success" type="submit" value="authorize" data-i18n="itam.authorize">Authorize</button><button class="btn btn-outline-warning" type="submit" value="suspend" data-i18n="common.suspend">Suspend</button></div></form>
      </div>
    </div>
  </section>`;
}

function assetPanel() {
  return `<section class="tab-pane" role="tabpanel" data-itam-panel="assets" hidden aria-hidden="true"><div class="d-grid gap-3"><div>
    <form id="itam-asset-filter" class="card card-body bg-body-tertiary border mb-4 row g-3"><div class="col-md-5"><label class="form-label" for="itam-asset-type-filter" data-i18n="itam.assetType">Asset type</label><select id="itam-asset-type-filter" name="asset_type" class="form-select"><option value="" data-i18n="common.all">All</option><option value="hardware" data-i18n="itam.hardware">Hardware</option><option value="software" data-i18n="itam.software">Software</option></select></div><div class="col-md-4"><label class="form-label" for="itam-asset-status-filter" data-i18n="common.status">Status</label><select id="itam-asset-status-filter" name="lifecycle_status" class="form-select"><option value="" data-i18n="common.all">All</option>${['acquired','received','in_stock','assigned','deployed','maintenance','retired','disposed'].map((x)=>`<option value="${x}">${x}</option>`).join('')}</select></div><div class="col-md-3 d-flex align-items-end"><button class="btn btn-outline-primary w-100" type="submit" data-i18n="common.filter">Filter</button></div></form>
    ${table('itam-asset-table-body', [['rsot.id','ID'],['itam.assetType','Type'],['itam.rsotObject','RSOT object'],['common.status','Status'],['itam.producer','Producer'],['common.version','Version']])}
    <pre id="itam-asset-detail" class="p-3 rounded-3 border bg-body-tertiary mb-4 overflow-auto small" tabindex="0">—</pre>
    <h3 class="h5 mt-4 mb-3" data-i18n="itam.custody">Custody chain</h3>${table('itam-custody-table-body', [['itam.sequence','Sequence'],['itam.event','Event'],['common.status','Status'],['itam.custodian','Custodian'],['common.reason','Reason'],['common.updated','Occurred']])}
  </div><div class="d-grid gap-3">
    <form id="itam-asset-create" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><h3 data-i18n="itam.assetCreate">Acquire asset</h3>
      <div class="col-12"><label class="form-label" for="itam-asset-rsot" data-i18n="itam.rsotObject">RSOT object</label><select id="itam-asset-rsot" name="rsotObjectId" class="form-select" required></select></div>
      <div class="col-md-6"><label class="form-label" for="itam-asset-type" data-i18n="itam.assetType">Asset type</label><select id="itam-asset-type" name="assetType" class="form-select" required><option value="hardware" data-i18n="itam.hardware">Hardware</option><option value="software" data-i18n="itam.software">Software</option></select></div>
      <div class="col-md-6"><label class="form-label" for="itam-asset-date" data-i18n="itam.acquisitionDate">Acquisition date</label><input id="itam-asset-date" name="acquisitionDate" class="form-control" type="date" data-inx-temporal="date" required /></div>
      <div class="col-md-6"><label class="form-label" for="itam-asset-value" data-i18n="itam.acquisitionValue">Acquisition value</label><input id="itam-asset-value" name="acquisitionValue" class="form-control" type="number" min="0" step="0.0001" required /></div>
      <div class="col-md-6"><label class="form-label" for="itam-asset-currency" data-i18n="itam.currency">Currency</label><input id="itam-asset-currency" name="currencyCode" class="form-control text-uppercase" value="EUR" minlength="3" maxlength="3" pattern="[A-Za-z]{3}" required /></div>
      <div class="col-md-6"><label class="form-label" for="itam-asset-supplier" data-i18n="itam.acquiredFrom">Acquired from</label><select id="itam-asset-supplier" name="acquiredFromPartnerId" class="form-select"></select></div>
      <div class="col-md-6"><label class="form-label" for="itam-asset-producer" data-i18n="itam.producer">Manufacturer / publisher</label><select id="itam-asset-producer" name="producerPartnerId" class="form-select" required></select></div>
      <div class="col-12"><label class="form-label" for="itam-asset-create-reason" data-i18n="common.reason">Reason</label><textarea id="itam-asset-create-reason" name="reason" class="form-control" minlength="2" maxlength="1024" required></textarea></div><div class="col-12"><button class="btn btn-primary" type="submit" data-i18n="itam.acquire">Acquire</button></div>
    </form>
    <form id="itam-asset-lifecycle" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><h3 data-i18n="itam.assetLifecycle">Selected asset lifecycle</h3>
      <div class="col-md-5"><label class="form-label" for="itam-custodian-kind" data-i18n="itam.custodianKind">Custodian kind</label><select id="itam-custodian-kind" name="custodianKind" class="form-select"><option value="organization" data-i18n="common.organization">Organization</option><option value="subdivision" data-i18n="common.subdivision">Subdivision</option><option value="actor" data-i18n="itam.actor">Actor</option><option value="partner" data-i18n="itam.partner">Partner</option></select></div>
      <div class="col-md-7"><label class="form-label" for="itam-custodian-id" data-i18n="itam.custodian">Custodian</label><select id="itam-custodian-id" name="custodianId" class="form-select"></select></div>
      <div class="col-12"><label class="form-label" for="itam-asset-transition-reason" data-i18n="common.reason">Reason</label><textarea id="itam-asset-transition-reason" name="reason" class="form-control" minlength="2" maxlength="1024" required></textarea></div>
      <div class="col-12"><label class="form-label" for="itam-asset-evidence" data-i18n="itam.evidence">Disposition evidence</label><input id="itam-asset-evidence" name="evidenceReference" class="form-control" maxlength="240" /></div>
      <div class="col-12 d-flex flex-wrap gap-2">${[['receive','itam.receive'],['stock','itam.stock'],['assign','itam.assign'],['deploy','itam.deploy'],['transfer','itam.transfer'],['maintenance/start','itam.maintenanceStart'],['maintenance/return','itam.maintenanceReturn'],['retire','itam.retire'],['dispose','itam.dispose']].map(([v,k])=>`<button class="btn btn-outline-primary" type="submit" value="${v}" data-i18n="${k}">${v}</button>`).join('')}</div>
      <hr /><div class="col-md-8"><label class="form-label" for="itam-asset-new-producer" data-i18n="itam.producer">Manufacturer / publisher</label><select id="itam-asset-new-producer" name="producerPartnerId" class="form-select"></select></div><div class="col-md-4 d-flex align-items-end"><button class="btn btn-outline-secondary w-100" type="submit" value="set-producer" data-i18n="itam.setProducer">Set producer</button></div>
    </form>
  </div></div></section>`;
}

function compliancePanel() {
  return `<section class="tab-pane" role="tabpanel" data-itam-panel="compliance" hidden aria-hidden="true">
    <div class="card card-body bg-body-tertiary border mb-4 row g-3"><div class="col-lg-8"><label class="form-label" for="itam-compliance-asset" data-i18n="itam.asset">Asset</label><select id="itam-compliance-asset" class="form-select"></select></div><div class="col-lg-4 d-flex align-items-end"><button id="itam-compliance-refresh" class="btn btn-outline-primary w-100" type="button" data-i18n="common.refresh">Refresh</button></div></div>
    <div class="nav nav-underline gap-3 mb-4 border-bottom" role="tablist"><button class="nav-link active" role="tab" type="button" data-compliance-tab="warranties" aria-selected="true" data-i18n="itam.warranties">Warranties</button><button class="nav-link" role="tab" type="button" data-compliance-tab="licenses" aria-selected="false" data-i18n="itam.licenses">Licenses</button><button class="nav-link" role="tab" type="button" data-compliance-tab="coverage" aria-selected="false" data-i18n="itam.supportCoverage">Support coverage</button><button class="nav-link" role="tab" type="button" data-compliance-tab="catalog" aria-selected="false" data-i18n="itam.supportCatalog">Support catalog</button><button class="nav-link" role="tab" type="button" data-compliance-tab="alerts" aria-selected="false" data-i18n="itam.alerts">Alerts / history</button></div>
    ${warrantyPanel()}${licensePanel()}${coveragePanel()}${catalogPanel()}${alertPanel()}
  </section>`;
}

function warrantyPanel() { return `<section class="tab-pane" data-compliance-panel="warranties"><div class="d-grid gap-3"><div>${table('itam-warranty-table-body', [['rsot.id','ID'],['itam.manufacturer','Manufacturer'],['itam.warrantyEnd','Warranty end'],['itam.supportEnd','Support end'],['common.status','Status'],['common.version','Version']])}<pre id="itam-warranty-detail" class="p-3 rounded-3 border bg-body-tertiary mb-4 overflow-auto small">—</pre></div><form id="itam-warranty-form" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><h3 data-i18n="itam.warrantyManage">Warranty</h3><input name="recordId" type="hidden" /><input name="version" type="hidden" />
  <div class="col-md-6"><label class="form-label" for="itam-warranty-manufacturer" data-i18n="itam.manufacturer">Manufacturer</label><select id="itam-warranty-manufacturer" name="manufacturerPartnerId" class="form-select" required></select></div><div class="col-md-6"><label class="form-label" for="itam-warranty-type" data-i18n="itam.warrantyType">Warranty type</label><select id="itam-warranty-type" name="warrantyTypeId" class="form-select" required></select></div>
  <div class="col-12"><label class="form-label" for="itam-warranty-level" data-i18n="itam.coverageLevel">Coverage level</label><input id="itam-warranty-level" name="coverageLevel" class="form-control" maxlength="120" required /></div>
  ${dateField('itam-warranty-start','warrantyStartDate','common.validFrom',true)}${dateField('itam-warranty-end','warrantyEndDate','itam.warrantyEnd',true)}${dateField('itam-warranty-support-end','manufacturerSupportEndDate','itam.supportEnd',true)}
  <div class="col-md-6"><label class="form-label" for="itam-warranty-certificate" data-i18n="itam.contractNumber">Certificate / contract</label><input id="itam-warranty-certificate" name="contractOrCertificateNumber" class="form-control" maxlength="160" /></div><div class="col-md-6"><label class="form-label" for="itam-warranty-source" data-i18n="itam.source">Source</label>${sourceSelect('itam-warranty-source','source')}</div>
  <div class="col-12"><label class="form-label" for="itam-warranty-proof" data-i18n="itam.proof">Proof reference</label><input id="itam-warranty-proof" name="proofReference" class="form-control" maxlength="240" required /></div>${reasonField('itam-warranty-reason')}
  <div class="col-12 d-flex gap-2 flex-wrap"><button class="btn btn-primary" type="submit" value="save" data-i18n="common.save">Save</button><button class="btn btn-outline-success" type="submit" value="activate" data-i18n="common.activate">Activate</button><button class="btn btn-outline-warning" type="submit" value="expire" data-i18n="itam.expire">Expire</button><button class="btn btn-outline-secondary" type="button" id="itam-warranty-clear" data-i18n="common.clear">Clear selection</button></div></form></div></section>`; }

function licensePanel() { return `<section class="tab-pane" data-compliance-panel="licenses" hidden aria-hidden="true"><div class="d-grid gap-3"><div>${table('itam-license-table-body', [['rsot.id','ID'],['itam.publisher','Publisher'],['itam.contractNumber','Contract'],['itam.supportEnd','Support end'],['common.status','Status'],['common.version','Version']])}<pre id="itam-license-detail" class="p-3 rounded-3 border bg-body-tertiary mb-4 overflow-auto small">—</pre></div><form id="itam-license-form" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><h3 data-i18n="itam.licenseManage">Software license</h3><input name="recordId" type="hidden" /><input name="version" type="hidden" />
  <div class="col-md-6"><label class="form-label" for="itam-license-publisher" data-i18n="itam.publisher">Publisher</label><select id="itam-license-publisher" name="publisherPartnerId" class="form-select" required></select></div><div class="col-md-6"><label class="form-label" for="itam-license-contract" data-i18n="itam.contractNumber">Contract number</label><input id="itam-license-contract" name="contractNumber" class="form-control" maxlength="160" required /></div>
  <div class="col-md-6"><label class="form-label" for="itam-license-model" data-i18n="itam.licenseModel">License model</label><input id="itam-license-model" name="licenseModel" class="form-control" maxlength="120" required /></div><div class="col-md-6"><label class="form-label" for="itam-license-quantity" data-i18n="itam.entitlementQuantity">Entitlement quantity</label><input id="itam-license-quantity" name="entitlementQuantity" class="form-control" type="number" min="1" step="1" value="1" required /></div>
  <div class="col-12"><label class="form-label" for="itam-license-rights" data-i18n="itam.usageRights">Usage rights</label><textarea id="itam-license-rights" name="usageRights" class="form-control" maxlength="2000" required></textarea></div>
  ${dateField('itam-license-start','startsOn','common.validFrom',true)}${dateField('itam-license-end','endsOn','common.validUntil',false)}${dateField('itam-license-support-end','publisherSupportEndDate','itam.supportEnd',true)}
  <div class="col-md-6"><label class="form-label" for="itam-license-proof" data-i18n="itam.proof">Proof reference</label><input id="itam-license-proof" name="proofReference" class="form-control" maxlength="240" required /></div><div class="col-md-6"><label class="form-label" for="itam-license-source" data-i18n="itam.source">Source</label>${sourceSelect('itam-license-source','source')}</div>${reasonField('itam-license-reason')}
  <div class="col-12 alert alert-info" data-i18n="itam.licenseSecretNotice">License/product keys are intentionally not accepted until Secret Service/PKI/KMS is available.</div><div class="col-12 d-flex gap-2 flex-wrap"><button class="btn btn-primary" type="submit" value="save" data-i18n="common.save">Save</button><button class="btn btn-outline-success" type="submit" value="activate" data-i18n="common.activate">Activate</button><button class="btn btn-outline-warning" type="submit" value="expire" data-i18n="itam.expire">Expire</button><button class="btn btn-outline-secondary" type="button" id="itam-license-clear" data-i18n="common.clear">Clear selection</button></div></form></div></section>`; }

function coveragePanel() { return `<section class="tab-pane" data-compliance-panel="coverage" hidden aria-hidden="true"><div class="d-grid gap-3"><div>${table('itam-coverage-table-body', [['rsot.id','ID'],['itam.supportProvider','Provider'],['itam.serviceLevel','Service level'],['common.validUntil','Ends'],['common.status','Status'],['common.version','Version']])}<pre id="itam-coverage-detail" class="p-3 rounded-3 border bg-body-tertiary mb-4 overflow-auto small">—</pre></div><form id="itam-coverage-form" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><h3 data-i18n="itam.coverageManage">Support coverage</h3><input name="recordId" type="hidden" /><input name="version" type="hidden" />
  <div class="col-md-6"><label class="form-label" for="itam-coverage-provider" data-i18n="itam.supportProvider">Support provider</label><select id="itam-coverage-provider" name="providerPartnerId" class="form-select" required></select></div><div class="col-md-6"><label class="form-label" for="itam-coverage-authorization" data-i18n="itam.authorization">Authorization</label><select id="itam-coverage-authorization" name="authorizationId" class="form-select" required></select></div>
  <div class="col-md-6"><label class="form-label" for="itam-coverage-contract" data-i18n="itam.contractNumber">Contract reference</label><input id="itam-coverage-contract" name="contractReference" class="form-control" maxlength="160" /></div><div class="col-md-6"><label class="form-label" for="itam-coverage-type" data-i18n="itam.coverageType">Coverage type</label><input id="itam-coverage-type" name="coverageType" class="form-control" maxlength="120" required /></div>
  <div class="col-12"><label class="form-label" for="itam-coverage-service" data-i18n="itam.serviceLevel">Service level</label><input id="itam-coverage-service" name="serviceLevel" class="form-control" maxlength="160" required /></div>${dateField('itam-coverage-start','startsOn','common.validFrom',true)}${dateField('itam-coverage-end','endsOn','common.validUntil',true)}
  <div class="col-12"><label class="form-label" for="itam-coverage-proof" data-i18n="itam.proof">Proof reference</label><input id="itam-coverage-proof" name="proofReference" class="form-control" maxlength="240" required /></div>${reasonField('itam-coverage-reason')}
  <div class="col-12 d-flex gap-2 flex-wrap"><button class="btn btn-primary" type="submit" value="save" data-i18n="common.save">Save</button><button class="btn btn-outline-success" type="submit" value="activate" data-i18n="common.activate">Activate</button><button class="btn btn-outline-warning" type="submit" value="expire" data-i18n="itam.expire">Expire</button><button class="btn btn-outline-secondary" type="button" id="itam-coverage-clear" data-i18n="common.clear">Clear selection</button></div></form></div></section>`; }

function catalogPanel() { return `<section class="tab-pane" data-compliance-panel="catalog" hidden aria-hidden="true"><div class="d-grid gap-3"><div><h3 class="h5 mt-4 mb-3" data-i18n="itam.supportAuthorizations">Support authorizations</h3>${table('itam-authorization-table-body', [['rsot.id','ID'],['itam.supportProvider','Provider'],['common.status','Status'],['common.validFrom','Valid from'],['common.validUntil','Valid until'],['common.version','Version']])}<form id="itam-authorization-lifecycle" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><div class="col-12"><label class="form-label" for="itam-auth-lifecycle-reason" data-i18n="common.reason">Reason</label><textarea id="itam-auth-lifecycle-reason" name="reason" class="form-control" minlength="2" maxlength="1024" required></textarea></div><div class="col-12 d-flex gap-2"><button class="btn btn-outline-success" type="submit" value="activate" data-i18n="common.activate">Activate</button><button class="btn btn-outline-warning" type="submit" value="suspend" data-i18n="common.suspend">Suspend</button></div></form><h3 class="h5 mt-4 mb-3" data-i18n="itam.warrantyTypes">Warranty types</h3>${table('itam-warranty-type-table-body', [['rsot.id','ID'],['itam.code','Code'],['itam.displayName','Display name'],['common.status','Status']])}</div><div class="d-grid gap-3">
  <form id="itam-authorization-create" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><h3 data-i18n="itam.authorizationCreate">Create support authorization</h3><div class="col-12"><label class="form-label" for="itam-auth-provider" data-i18n="itam.supportProvider">Provider</label><select id="itam-auth-provider" name="providerPartnerId" class="form-select" required></select></div><div class="col-12"><label class="form-label" for="itam-auth-manufacturers" data-i18n="itam.manufacturers">Supported manufacturers</label><select id="itam-auth-manufacturers" name="supportedManufacturerIds" class="form-select" multiple size="6" required></select></div><div class="col-12"><label class="form-label" for="itam-auth-object-types" data-i18n="itam.objectTypes">Supported RSOT object types (comma-separated)</label><input id="itam-auth-object-types" name="supportedObjectTypes" class="form-control" required /></div><div class="col-12"><label class="form-label" for="itam-auth-subdivisions" data-i18n="itam.subdivisionScopes">Subdivision scopes</label><select id="itam-auth-subdivisions" name="subdivisionScopes" class="form-select" multiple size="5"></select></div><div class="col-md-6"><label class="form-label" for="itam-auth-hours" data-i18n="itam.serviceHours">Service hours</label><input id="itam-auth-hours" name="serviceHours" class="form-control" maxlength="240" required /></div><div class="col-md-6"><label class="form-label" for="itam-auth-timezone" data-i18n="itam.timeZone">Timezone</label><input id="itam-auth-timezone" name="timeZoneId" class="form-control" value="Europe/Paris" maxlength="80" required /></div><div class="col-md-6"><label class="form-label" for="itam-auth-levels" data-i18n="itam.serviceLevels">Service levels (comma-separated)</label><input id="itam-auth-levels" name="serviceLevels" class="form-control" required /></div><div class="col-md-6"><label class="form-label" for="itam-auth-escalations" data-i18n="itam.escalationTypes">Escalation contact types</label><input id="itam-auth-escalations" name="escalationContactTypes" class="form-control" required /></div>${dateField('itam-auth-from','validFrom','common.validFrom',true)}${dateField('itam-auth-until','validUntil','common.validUntil',false)}${reasonField('itam-auth-reason')}<div class="col-12"><button class="btn btn-primary" type="submit" data-i18n="common.create">Create</button></div></form>
  <form id="itam-warranty-type-create" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><h3 data-i18n="itam.warrantyTypeCreate">Create warranty type</h3><div class="col-md-5"><label class="form-label" for="itam-warranty-type-code" data-i18n="itam.code">Code</label><input id="itam-warranty-type-code" name="code" class="form-control" maxlength="64" required /></div><div class="col-md-7"><label class="form-label" for="itam-warranty-type-name" data-i18n="itam.displayName">Display name</label><input id="itam-warranty-type-name" name="displayName" class="form-control" maxlength="160" required /></div>${reasonField('itam-warranty-type-reason')}<div class="col-12"><button class="btn btn-primary" type="submit" data-i18n="common.create">Create</button></div></form>
  </div></div></section>`; }

function alertPanel() { return `<section class="tab-pane" data-compliance-panel="alerts" hidden aria-hidden="true"><form id="itam-alert-filter" class="card card-body bg-body-tertiary border mb-4 row g-3"><div class="col-md-4">${dateFieldInner('itam-alert-asof','asOf','itam.asOf',false)}</div><div class="col-md-4"><label class="form-label" for="itam-alert-horizon" data-i18n="itam.horizon">Horizon (days)</label><input id="itam-alert-horizon" name="horizonDays" type="number" min="1" max="3650" value="180" class="form-control" /></div><div class="col-md-4 d-flex align-items-end"><button class="btn btn-outline-primary w-100" type="submit" data-i18n="common.filter">Filter</button></div></form>${table('itam-alert-table-body', [['itam.alertKind','Kind'],['rsot.id','Record'],['itam.dueDate','Due date'],['itam.daysRemaining','Days remaining'],['itam.threshold','Threshold']])}<div class="d-grid gap-3"><form id="itam-history-filter" class="border rounded-3 p-3 p-xl-4 bg-body-tertiary row g-3"><h3 data-i18n="itam.history">Contract history</h3><div class="col-md-6"><label class="form-label" for="itam-history-type" data-i18n="itam.recordType">Record type</label><select id="itam-history-type" name="type" class="form-select"><option value="warranties" data-i18n="itam.warranties">Warranties</option><option value="licenses" data-i18n="itam.licenses">Licenses</option><option value="support-coverages" data-i18n="itam.supportCoverage">Support coverage</option></select></div><div class="col-md-6"><label class="form-label" for="itam-history-record" data-i18n="itam.record">Record</label><select id="itam-history-record" name="recordId" class="form-select"></select></div><div class="col-12"><button class="btn btn-outline-primary" type="submit" data-i18n="common.refresh">Refresh</button></div></form><pre id="itam-history-detail" class="p-3 rounded-3 border bg-body-tertiary mb-4 overflow-auto small">—</pre></div></section>`; }

async function loadDirectories(documentObject, configuration, fetchFunction, state) {
  try { state.organizations = await organizationDirectory(configuration, fetchFunction); } catch { state.organizations = []; }
  try { state.users = await userDirectory(configuration, fetchFunction); } catch { state.users = []; }
  fillSelect(documentObject, documentObject.getElementById('itam-organization'), state.organizations, { label: (x) => `${x.code ?? ''} — ${x.displayName ?? x.id}`, preserve: false, selectFirst: true });
}

function wireContext(documentObject, configuration, fetchFunction, clients, state) {
  documentObject.getElementById('itam-organization')?.addEventListener('change', () => void refreshContext(documentObject, configuration, fetchFunction, clients, state));
  documentObject.getElementById('itam-subdivision')?.addEventListener('change', () => syncCustodianOptions(documentObject, state));
}

async function refreshContext(documentObject, configuration, fetchFunction, clients, state) {
  const organizationId = contextOrganization(documentObject);
  if (!organizationId) { setWorkspaceStatus(documentObject, 'itam-status', 'workspace.selectOrganization', 'warning'); return; }
  setWorkspaceStatus(documentObject, 'itam-status', 'workspace.loading');
  const failures = [];
  state.subdivisions = await subdivisionDirectory(configuration, organizationId, fetchFunction).catch((error) => { failures.push(error); return []; });
  fillSelect(documentObject, documentObject.getElementById('itam-subdivision'), state.subdivisions, { label: (x) => `${x.code ?? ''} — ${x.displayName ?? x.id}` });
  for (const task of [
    clients.partners ? () => loadPartners(documentObject, clients.partners, state) : null,
    clients.rsot ? () => loadRsotObjects(documentObject, clients.rsot, state) : null,
    clients.assets ? () => loadAssets(documentObject, clients.assets, state) : null,
    clients.compliance ? () => loadComplianceCatalogs(documentObject, clients.compliance, state) : null,
  ].filter(Boolean)) {
    try { await task(); } catch (error) { failures.push(error); }
  }
  refillEntitySelectors(documentObject, state);
  if (failures.length === 0) setWorkspaceStatus(documentObject, 'itam-status', 'workspace.ready', 'success');
  else if (failures.every((error) => error?.status === 403)) setWorkspaceStatus(documentObject, 'itam-status', 'workspace.restricted', 'warning');
  else showError(documentObject, failures[0]);
}

function wirePartner(documentObject, client, state) {
  if (!client) return;
  wireFilterForm(documentObject, 'itam-partner-filter', () => loadPartners(documentObject, client, state));
  wirePartnerContacts(documentObject);
  wireAsyncForm(documentObject.getElementById('itam-partner-create'), {
    execute: async (form) => {
      const organizationId = contextOrganization(documentObject); const roles = checkedValues(form, 'roles'); if (!roles.length) throw new Error(translate(localeFromDocument(documentObject), 'itam.roleRequired'));
      await client.create({ governingOrganizationId: organizationId, governingSubdivisionId: nullable(documentObject.getElementById('itam-subdivision')?.value), code: field(form,'code'), legalName: field(form,'legalName'), displayName: field(form,'displayName'), countryCode: field(form,'countryCode').toUpperCase(), roles, validFrom: field(form,'validFrom'), validUntil: nullable(field(form,'validUntil')), officialWebsite: nullable(field(form,'officialWebsite')), supportPortal: nullable(field(form,'supportPortal')), aliases: lines(field(form,'aliases')), externalIds: parseJsonArray(field(form,'externalIds'),'externalIds'), accreditations: parseJsonArray(field(form,'accreditations'),'accreditations'), contacts: serializePartnerContacts(form), reason: field(form,'reason') }, idempotencyKey('partner'));
      await loadPartners(documentObject, client, state); refillEntitySelectors(documentObject, state);
    }, onWorking: () => working(documentObject), onSuccess: () => saved(documentObject), onError: (e) => showError(documentObject,e),
  });
  wireAsyncForm(documentObject.getElementById('itam-partner-lifecycle'), {
    execute: async (form, submitter) => { const p=state.selectedPartner;if(!p) throw selectError(documentObject); const key=idempotencyKey('partner'); const reason=field(form,'reason'); if(submitter?.value==='submit') await client.submitApproval(p.id,p.version,reason,key); else if(submitter?.value==='authorize') await client.authorize(p.id,p.version,reason,key); else if(submitter?.value==='suspend') await client.suspend(p.id,p.version,reason,key); await loadPartners(documentObject,client,state); refillEntitySelectors(documentObject,state); },
    onWorking:()=>working(documentObject),onSuccess:()=>saved(documentObject),onError:(e)=>showError(documentObject,e),
  });
}

async function loadPartners(documentObject, client, state) {
  const filter=documentObject.getElementById('itam-partner-filter'); const result=await client.list({ organization_id:contextOrganization(documentObject), role:nullable(field(filter,'role')), authorization_status:nullable(field(filter,'authorization_status')), limit:200 }); state.partners=listItems(result.payload); state.selectedPartner=null; renderPartners(documentObject,state);
}
function renderPartners(documentObject,state){replaceRows(documentObject,documentObject.getElementById('itam-partner-table-body'),state.partners,[(x)=>x.code,(x)=>x.displayName,(x)=>(x.roles??[]).join(', '),(x)=>x.authorizationStatus,(x)=>x.version],(item)=>{state.selectedPartner=item;renderPartnerDetail(documentObject,item);});}

function renderPartnerDetail(documentObject,item){
  const host=documentObject.getElementById('itam-partner-detail');
  if(!host||!item)return;
  const create=(tag,className,text)=>{const node=documentObject.createElement(tag);if(className)node.className=className;if(text!==undefined&&text!==null)node.textContent=String(text);return node;};
  const body=create('div','card-body p-3 p-xl-4');
  const head=create('div','d-flex flex-wrap justify-content-between align-items-start gap-3 mb-4');
  const identity=create('div'); identity.appendChild(create('p','small text-uppercase fw-bold text-primary mb-1',item.code)); identity.appendChild(create('h3','h5 mb-1',item.displayName??item.legalName??item.code)); identity.appendChild(create('p','text-body-secondary mb-0',item.legalName??''));
  const status=create('span',`badge rounded-pill ${item.authorizationStatus==='active'?'text-bg-success':item.authorizationStatus==='suspended'?'text-bg-warning':'text-bg-secondary'}`,item.authorizationStatus??'—');
  head.appendChild(identity); head.appendChild(status); body.appendChild(head);
  const grid=create('dl','row g-3 mb-0 inx-record-detail-grid');
  const add=(label,value)=>{const wrap=create('div','col-sm-6 col-xl-4');wrap.appendChild(create('dt','small text-uppercase text-body-secondary mb-1',label));wrap.appendChild(create('dd','mb-0 fw-semibold',value||'—'));grid.appendChild(wrap);};
  add(translate(localeFromDocument(documentObject),'itam.country'),item.countryCode);
  add(translate(localeFromDocument(documentObject),'itam.roles'),(item.roles??[]).join(', '));
  add(translate(localeFromDocument(documentObject),'itam.validity'),`${item.validFrom??'—'} → ${item.validUntil??'∞'}`);
  add(translate(localeFromDocument(documentObject),'itam.website'),item.officialWebsite);
  add(translate(localeFromDocument(documentObject),'itam.supportPortal'),item.supportPortal);
  add(translate(localeFromDocument(documentObject),'common.version'),item.version);
  body.appendChild(grid);
  if(Array.isArray(item.contacts)&&item.contacts.length){const section=create('section','mt-4 pt-4 border-top');section.appendChild(create('h4','h6 mb-3',translate(localeFromDocument(documentObject),'itam.contacts')));const contacts=create('div','row g-3');for(const contact of item.contacts){const card=create('div','col-md-6 col-xl-4');const surface=create('div','inx-partner-contact h-100');surface.appendChild(create('div','fw-semibold mb-1',contact.name??contact.type));surface.appendChild(create('div','small text-body-secondary mb-2',contact.type));for(const value of [contact.email,contact.phone,contact.uri])if(value)surface.appendChild(create('div','small text-break',value));card.appendChild(surface);contacts.appendChild(card);}section.appendChild(contacts);body.appendChild(section);}
  host.replaceChildren(body);
}


function wireAsset(documentObject, configuration, fetchFunction, clients, state) {
  const client=clients.assets;if(!client)return; state.assetClient=client;
  wireFilterForm(documentObject, 'itam-asset-filter', () => loadAssets(documentObject, client, state));
  documentObject.getElementById('itam-asset-type')?.addEventListener('change',()=>refillProducerSelectors(documentObject,state));
  documentObject.getElementById('itam-custodian-kind')?.addEventListener('change',()=>syncCustodianOptions(documentObject,state));
  wireAsyncForm(documentObject.getElementById('itam-asset-create'),{execute:async(form)=>{await client.create({rsotObjectId:field(form,'rsotObjectId'),assetType:field(form,'assetType'),owningOrganizationId:contextOrganization(documentObject),owningSubdivisionId:nullable(documentObject.getElementById('itam-subdivision')?.value),acquisitionDate:field(form,'acquisitionDate'),acquisitionValue:field(form,'acquisitionValue'),currencyCode:field(form,'currencyCode').toUpperCase(),acquiredFromPartnerId:nullable(field(form,'acquiredFromPartnerId')),producerPartnerId:field(form,'producerPartnerId'),reason:field(form,'reason')},idempotencyKey('asset'));await loadAssets(documentObject,client,state);refillEntitySelectors(documentObject,state);},onWorking:()=>working(documentObject),onSuccess:()=>saved(documentObject),onError:(e)=>showError(documentObject,e)});
  wireAsyncForm(documentObject.getElementById('itam-asset-lifecycle'),{execute:async(form,submitter)=>{const a=state.selectedAsset;if(!a)throw selectError(documentObject);const op=submitter?.value;const reason=field(form,'reason');const key=idempotencyKey('asset');if(op==='set-producer')await client.setProducer(a.id,a.version,field(form,'producerPartnerId'),reason,key);else if(op==='retire')await client.retire(a.id,a.version,reason,key);else if(op==='dispose')await client.dispose(a.id,a.version,reason,field(form,'evidenceReference'),key);else {const custodian={custodianKind:field(form,'custodianKind'),custodianId:field(form,'custodianId')};await client.transition(a.id,op,a.version,{...custodian,reason},key);}await loadAssets(documentObject,client,state);},onWorking:()=>working(documentObject),onSuccess:()=>saved(documentObject),onError:(e)=>showError(documentObject,e)});
}

async function loadRsotObjects(documentObject,client,state){const result=await client.list({organizationId:contextOrganization(documentObject),offset:0,limit:200});state.rsotObjects=listItems(result.payload);}
async function loadAssets(documentObject,client,state){const filter=documentObject.getElementById('itam-asset-filter');const result=await client.list({organization_id:contextOrganization(documentObject),asset_type:nullable(field(filter,'asset_type')),lifecycle_status:nullable(field(filter,'lifecycle_status')),limit:200});state.assets=listItems(result.payload);state.selectedAsset=null;renderAssets(documentObject,state);refillAssetSelectors(documentObject,state);}
function renderAssets(documentObject,state){replaceRows(documentObject,documentObject.getElementById('itam-asset-table-body'),state.assets,[(x)=>x.id,(x)=>x.assetType,(x)=>x.rsotObjectId,(x)=>x.lifecycleStatus,(x)=>partnerLabel(state,x.producerPartnerId),(x)=>x.version],(item)=>void selectAsset(documentObject,state,item));}
async function selectAsset(documentObject,state,item){
  state.selectedAsset=item;
  documentObject.getElementById('itam-asset-detail').textContent=JSON.stringify(item,null,2);
  refillProducerSelectors(documentObject,state,item.assetType);
  syncCustodianOptions(documentObject,state);
  try {
    const result=await state.assetClient?.custody(item.id,{afterSequence:0,limit:200});
    const events=listItems(result?.payload);
    replaceRows(documentObject,documentObject.getElementById('itam-custody-table-body'),events,[(x)=>x.sequence,(x)=>x.eventType,(x)=>x.toStatus,(x)=>`${x.custodianKind}: ${x.custodianId??'—'}`,(x)=>x.reason,(x)=>x.occurredAt]);
  } catch (error) { showError(documentObject,error); }
}

function wireCompliance(documentObject,client,state){if(!client)return;documentObject.getElementById('itam-compliance-asset')?.addEventListener('change',()=>void refreshComplianceRecords(documentObject,client,state));documentObject.getElementById('itam-compliance-refresh')?.addEventListener('click',()=>void refreshComplianceRecords(documentObject,client,state));
  wireRecordForm(documentObject,client,state,'warranty');wireRecordForm(documentObject,client,state,'license');wireRecordForm(documentObject,client,state,'coverage');
  wireAsyncForm(documentObject.getElementById('itam-authorization-create'),{execute:async(form)=>{await client.createSupportAuthorization({providerPartnerId:field(form,'providerPartnerId'),organizationId:contextOrganization(documentObject),supportedManufacturerIds:selectedValues(documentObject.getElementById('itam-auth-manufacturers')),supportedObjectTypes:csv(field(form,'supportedObjectTypes')),subdivisionScopes:selectedValues(documentObject.getElementById('itam-auth-subdivisions')),serviceHours:field(form,'serviceHours'),timeZoneId:field(form,'timeZoneId'),serviceLevels:csv(field(form,'serviceLevels')),escalationContactTypes:csv(field(form,'escalationContactTypes')),validFrom:field(form,'validFrom'),validUntil:nullable(field(form,'validUntil')),reason:field(form,'reason')},idempotencyKey('support-auth'));await loadComplianceCatalogs(documentObject,client,state);refillEntitySelectors(documentObject,state);},onWorking:()=>working(documentObject),onSuccess:()=>saved(documentObject),onError:(e)=>showError(documentObject,e)});
  wireAsyncForm(documentObject.getElementById('itam-authorization-lifecycle'),{execute:async(form,submitter)=>{const a=state.selectedAuthorization;if(!a)throw selectError(documentObject);const key=idempotencyKey('support-auth'),reason=field(form,'reason');if(submitter?.value==='activate')await client.activateSupportAuthorization(a.id,a.version,reason,key);else await client.suspendSupportAuthorization(a.id,a.version,reason,key);await loadComplianceCatalogs(documentObject,client,state);},onWorking:()=>working(documentObject),onSuccess:()=>saved(documentObject),onError:(e)=>showError(documentObject,e)});
  wireAsyncForm(documentObject.getElementById('itam-warranty-type-create'),{execute:async(form)=>{await client.createWarrantyType(contextOrganization(documentObject),field(form,'code'),field(form,'displayName'),field(form,'reason'),idempotencyKey('warranty-type'));await loadComplianceCatalogs(documentObject,client,state);refillEntitySelectors(documentObject,state);},onWorking:()=>working(documentObject),onSuccess:()=>saved(documentObject),onError:(e)=>showError(documentObject,e)});
  wireFilterForm(documentObject, 'itam-alert-filter', () => loadAlerts(documentObject, client, state));
  wireFilterForm(documentObject, 'itam-history-filter', () => loadHistory(documentObject, client, state));
  documentObject.getElementById('itam-history-type')?.addEventListener('change',()=>refillHistoryRecords(documentObject,state));
}

function wireFilterForm(documentObject, formId, execute) {
  const form = documentObject.getElementById(formId);
  if (!form) return null;
  return wireAsyncForm(form, {
    execute: async () => execute(),
    onWorking: () => working(documentObject),
    onSuccess: () => setWorkspaceStatus(documentObject, 'itam-status', 'workspace.ready', 'success'),
    onError: (error) => showError(documentObject, error),
  });
}

function wireRecordForm(documentObject,client,state,kind){const id=`itam-${kind}-form`;const form=documentObject.getElementById(id);wireAsyncForm(form,{execute:async(f,submitter)=>{const assetId=complianceAssetId(documentObject);if(!assetId)throw new Error(translate(localeFromDocument(documentObject),'workspace.selectAsset'));const selected=state.selectedCompliance?.kind===kind?state.selectedCompliance.record:null;const op=submitter?.value;const key=idempotencyKey(kind);const reason=field(f,'reason');if(op==='activate'||op==='expire'){if(!selected)throw selectError(documentObject);if(kind==='warranty')await client[op==='activate'?'activateWarranty':'expireWarranty'](selected.id,selected.version,reason,key);if(kind==='license')await client[op==='activate'?'activateLicense':'expireLicense'](selected.id,selected.version,reason,key);if(kind==='coverage')await client[op==='activate'?'activateSupportCoverage':'expireSupportCoverage'](selected.id,selected.version,reason,key);}else {const body=recordBody(f,kind);if(selected){if(kind==='warranty')await client.reviseWarranty(selected.id,selected.version,body,key);if(kind==='license')await client.reviseLicense(selected.id,selected.version,body,key);if(kind==='coverage')await client.reviseSupportCoverage(selected.id,selected.version,body,key);}else{if(kind==='warranty')await client.createWarranty(assetId,body,key);if(kind==='license')await client.createLicense(assetId,body,key);if(kind==='coverage')await client.createSupportCoverage(assetId,body,key);}}await refreshComplianceRecords(documentObject,client,state);},onWorking:()=>working(documentObject),onSuccess:()=>saved(documentObject),onError:(e)=>showError(documentObject,e)});documentObject.getElementById(`itam-${kind}-clear`)?.addEventListener('click',()=>clearRecordSelection(documentObject,state,kind));}

function recordBody(form,kind){if(kind==='warranty')return{manufacturerPartnerId:field(form,'manufacturerPartnerId'),warrantyTypeId:field(form,'warrantyTypeId'),coverageLevel:field(form,'coverageLevel'),warrantyStartDate:field(form,'warrantyStartDate'),warrantyEndDate:field(form,'warrantyEndDate'),manufacturerSupportEndDate:field(form,'manufacturerSupportEndDate'),contractOrCertificateNumber:nullable(field(form,'contractOrCertificateNumber')),proofReference:field(form,'proofReference'),source:field(form,'source'),reason:field(form,'reason')};if(kind==='license')return{publisherPartnerId:field(form,'publisherPartnerId'),contractNumber:field(form,'contractNumber'),licenseModel:field(form,'licenseModel'),usageRights:field(form,'usageRights'),entitlementQuantity:Number(field(form,'entitlementQuantity')),startsOn:field(form,'startsOn'),endsOn:nullable(field(form,'endsOn')),publisherSupportEndDate:field(form,'publisherSupportEndDate'),proofReference:field(form,'proofReference'),source:field(form,'source'),reason:field(form,'reason')};return{providerPartnerId:field(form,'providerPartnerId'),authorizationId:field(form,'authorizationId'),contractReference:nullable(field(form,'contractReference')),coverageType:field(form,'coverageType'),serviceLevel:field(form,'serviceLevel'),startsOn:field(form,'startsOn'),endsOn:field(form,'endsOn'),proofReference:field(form,'proofReference'),reason:field(form,'reason')};}

async function loadComplianceCatalogs(documentObject,client,state){
  const org=contextOrganization(documentObject);
  const [types,auths]=await Promise.allSettled([client.warrantyTypes(org),client.listSupportAuthorizations(org)]);
  state.warrantyTypes=types.status==='fulfilled'?listItems(types.value.payload):[];
  state.authorizations=auths.status==='fulfilled'?listItems(auths.value.payload):[];
  renderComplianceCatalogs(documentObject,state);
  const rejected=[types,auths].filter((result)=>result.status==='rejected');
  if(rejected.length===2) throw rejected[0].reason;
}
function renderComplianceCatalogs(documentObject,state){replaceRows(documentObject,documentObject.getElementById('itam-authorization-table-body'),state.authorizations,[(x)=>x.id,(x)=>partnerLabel(state,x.providerPartnerId),(x)=>x.status,(x)=>x.validFrom,(x)=>x.validUntil,(x)=>x.version],(item)=>{state.selectedAuthorization=item;});replaceRows(documentObject,documentObject.getElementById('itam-warranty-type-table-body'),state.warrantyTypes,[(x)=>x.id,(x)=>x.code,(x)=>x.displayName,(x)=>x.active?'active':'inactive']);}

async function refreshComplianceRecords(documentObject,client,state){const assetId=complianceAssetId(documentObject);if(!assetId){renderComplianceRows(documentObject,state,[],[],[]);return;}const [w,l,c]=await Promise.all([client.warranties(assetId,{limit:200}),client.licenses(assetId,{limit:200}),client.supportCoverages(assetId,{limit:200})]);state.warranties=listItems(w.payload);state.licenses=listItems(l.payload);state.coverages=listItems(c.payload);state.selectedCompliance=null;renderComplianceRows(documentObject,state,state.warranties,state.licenses,state.coverages);refillHistoryRecords(documentObject,state);await loadAlerts(documentObject,client,state);}
function renderComplianceRows(documentObject,state,warranties=state.warranties??[],licenses=state.licenses??[],coverages=state.coverages??[]){replaceRows(documentObject,documentObject.getElementById('itam-warranty-table-body'),warranties,[(x)=>x.id,(x)=>partnerLabel(state,x.manufacturerPartnerId),(x)=>x.warrantyEndDate,(x)=>x.manufacturerSupportEndDate,(x)=>x.status,(x)=>x.version],(x)=>selectCompliance(documentObject,state,'warranty',x));replaceRows(documentObject,documentObject.getElementById('itam-license-table-body'),licenses,[(x)=>x.id,(x)=>partnerLabel(state,x.publisherPartnerId),(x)=>x.contractNumber,(x)=>x.publisherSupportEndDate,(x)=>x.status,(x)=>x.version],(x)=>selectCompliance(documentObject,state,'license',x));replaceRows(documentObject,documentObject.getElementById('itam-coverage-table-body'),coverages,[(x)=>x.id,(x)=>partnerLabel(state,x.providerPartnerId),(x)=>x.serviceLevel,(x)=>x.endsOn,(x)=>x.status,(x)=>x.version],(x)=>selectCompliance(documentObject,state,'coverage',x));}
function selectCompliance(documentObject,state,kind,record){state.selectedCompliance={kind,record};documentObject.getElementById(`itam-${kind}-detail`).textContent=JSON.stringify(record,null,2);fillRecordForm(documentObject,kind,record);}
function fillRecordForm(documentObject,kind,r){const f=documentObject.getElementById(`itam-${kind}-form`);if(!f)return;const fields=kind==='warranty'?['manufacturerPartnerId','warrantyTypeId','coverageLevel','warrantyStartDate','warrantyEndDate','manufacturerSupportEndDate','contractOrCertificateNumber','proofReference','source']:kind==='license'?['publisherPartnerId','contractNumber','licenseModel','usageRights','entitlementQuantity','startsOn','endsOn','publisherSupportEndDate','proofReference','source']:['providerPartnerId','authorizationId','contractReference','coverageType','serviceLevel','startsOn','endsOn','proofReference'];for(const name of fields){const el=f.elements.namedItem(name);if(el){el.value=r[name]??'';if(String(el.tagName??'').toUpperCase()==='SELECT'){const EventConstructor=documentObject?.defaultView?.Event??globalThis.Event;if(typeof EventConstructor==='function')el.dispatchEvent?.(new EventConstructor('change',{bubbles:true}));}}}f.elements.namedItem('recordId').value=r.id;f.elements.namedItem('version').value=r.version;}
function clearRecordSelection(documentObject,state,kind){if(state.selectedCompliance?.kind===kind)state.selectedCompliance=null;const form=documentObject.getElementById(`itam-${kind}-form`);form?.reset?.();const id=form?.elements?.namedItem?.('recordId');if(id)id.value='';const version=form?.elements?.namedItem?.('version');if(version)version.value='';}

async function loadAlerts(documentObject,client,state){const assetId=complianceAssetId(documentObject);if(!assetId)return;const form=documentObject.getElementById('itam-alert-filter');const result=await client.alerts(assetId,{asOf:nullable(field(form,'asOf')),horizonDays:Number(field(form,'horizonDays')||180)});state.alerts=listItems(result.payload);replaceRows(documentObject,documentObject.getElementById('itam-alert-table-body'),state.alerts,[(x)=>x.kind,(x)=>x.recordId,(x)=>x.dueDate,(x)=>x.daysRemaining,(x)=>x.thresholdDays]);}
async function loadHistory(documentObject,client,state){const form=documentObject.getElementById('itam-history-filter');const type=field(form,'type'),recordId=field(form,'recordId');if(!recordId){documentObject.getElementById('itam-history-detail').textContent='—';return;}const result=await client.history(type,recordId,{limit:200});documentObject.getElementById('itam-history-detail').textContent=JSON.stringify(result.payload,null,2);}

function refillEntitySelectors(documentObject,state){refillPartnerSelectors(documentObject,state);refillAssetSelectors(documentObject,state);fillSelect(documentObject,documentObject.getElementById('itam-asset-rsot'),state.rsotObjects,{label:(x)=>`${x.objectType} — ${x.id}`});fillSelect(documentObject,documentObject.getElementById('itam-auth-subdivisions'),state.subdivisions,{label:(x)=>`${x.code??''} — ${x.displayName??x.id}`});fillSelect(documentObject,documentObject.getElementById('itam-warranty-type'),state.warrantyTypes,{label:(x)=>`${x.code} — ${x.displayName}`});fillSelect(documentObject,documentObject.getElementById('itam-coverage-authorization'),state.authorizations.filter((x)=>x.status==='active'),{label:(x)=>`${partnerLabel(state,x.providerPartnerId)} — ${x.id}`});syncCustodianOptions(documentObject,state);refillHistoryRecords(documentObject,state);}
function refillPartnerSelectors(documentObject,state){const by=(role)=>state.partners.filter((x)=>(x.roles??[]).includes(role)&&x.authorizationStatus==='active');const manufacturers=by('manufacturer'),publishers=by('software_publisher'),suppliers=by('supplier'),providers=by('third_party_support_provider');for(const id of ['itam-warranty-manufacturer'])fillSelect(documentObject,documentObject.getElementById(id),manufacturers,{label:(x)=>x.displayName});for(const id of ['itam-license-publisher'])fillSelect(documentObject,documentObject.getElementById(id),publishers,{label:(x)=>x.displayName});for(const id of ['itam-asset-supplier'])fillSelect(documentObject,documentObject.getElementById(id),suppliers,{label:(x)=>x.displayName});for(const id of ['itam-coverage-provider','itam-auth-provider'])fillSelect(documentObject,documentObject.getElementById(id),providers,{label:(x)=>x.displayName});fillSelect(documentObject,documentObject.getElementById('itam-auth-manufacturers'),manufacturers,{label:(x)=>x.displayName});refillProducerSelectors(documentObject,state);}
function refillProducerSelectors(documentObject,state,type=null){const assetType=type??documentObject.getElementById('itam-asset-type')?.value??'hardware';const role=assetType==='software'?'software_publisher':'manufacturer';const items=state.partners.filter((x)=>(x.roles??[]).includes(role)&&x.authorizationStatus==='active');for(const id of ['itam-asset-producer','itam-asset-new-producer'])fillSelect(documentObject,documentObject.getElementById(id),items,{label:(x)=>x.displayName});}
function refillAssetSelectors(documentObject,state){fillSelect(documentObject,documentObject.getElementById('itam-compliance-asset'),state.assets,{label:(x)=>`${x.assetType} · ${x.lifecycleStatus} · ${x.id}`});}
function refillHistoryRecords(documentObject,state){const type=documentObject.getElementById('itam-history-type')?.value??'warranties';const items=type==='licenses'?(state.licenses??[]):type==='support-coverages'?(state.coverages??[]):(state.warranties??[]);fillSelect(documentObject,documentObject.getElementById('itam-history-record'),items,{label:(x)=>`${x.status} · ${x.id}`});}
function syncCustodianOptions(documentObject,state){const kind=documentObject.getElementById('itam-custodian-kind')?.value;let items=[];let label=(x)=>x.displayName??x.code??x.id;if(kind==='organization')items=state.organizations.filter((x)=>x.id===contextOrganization(documentObject));else if(kind==='subdivision')items=state.subdivisions;else if(kind==='actor')items=state.users;else if(kind==='partner')items=state.partners.filter((x)=>x.authorizationStatus==='active');fillSelect(documentObject,documentObject.getElementById('itam-custodian-id'),items,{label});}

function rerender(documentObject,state){renderPartners(documentObject,state);renderAssets(documentObject,state);renderComplianceCatalogs(documentObject,state);renderComplianceRows(documentObject,state);}
function contextOrganization(documentObject){return clean(documentObject.getElementById('itam-organization')?.value);}
function complianceAssetId(documentObject){return clean(documentObject.getElementById('itam-compliance-asset')?.value);}
function partnerLabel(state,id){return state.partners.find((x)=>x.id===id)?.displayName??id??'—';}
function gateTabs(documentObject,configuration){for(const [value,enabled] of [['partners',configuration.itamPartnersEnabled],['assets',configuration.itamAssetsEnabled],['compliance',configuration.itamComplianceEnabled]]){const tab=documentObject.querySelector?.(`[data-itam-tab="${value}"]`);if(tab)tab.hidden=enabled!==true;const panel=documentObject.querySelector?.(`[data-itam-panel="${value}"]`);if(panel)panel.setAttribute('data-capability-enabled',String(enabled===true));}}
function working(documentObject){setWorkspaceStatus(documentObject,'itam-status','workspace.saving');}
function saved(documentObject){setWorkspaceStatus(documentObject,'itam-status','workspace.saved','success');}
function selectError(documentObject){return new Error(translate(localeFromDocument(documentObject),'workspace.selectRecord'));}
function showError(documentObject,error){const key=error?.status===403?'workspace.restricted':error?.status===409?'workspace.conflict':'workspace.error';setWorkspaceStatus(documentObject,'itam-status',key,'error',{message:String(error?.message??error)});}
function table(tbodyId,headings){return `<div class="border rounded-3 overflow-hidden mb-4 bg-body shadow-sm"><div class="table-responsive"><table class="table table-hover align-middle mb-0"><thead><tr>${headings.map(([k,f])=>`<th scope="col" data-i18n="${k}">${f}</th>`).join('')}</tr></thead><tbody id="${tbodyId}"></tbody></table></div></div>`;}

function partnerContactsEditor(){return `<section class="col-12 inx-partner-contacts" aria-labelledby="itam-partner-contacts-title"><div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2"><div><h4 class="h6 mb-1" id="itam-partner-contacts-title" data-i18n="itam.contacts">Contacts</h4><p class="small text-body-secondary mb-0" data-i18n="itam.contactsHint">Add named business, support or escalation contacts. At least one email, phone or URL is required per contact.</p></div><button id="itam-partner-contact-add" class="btn btn-sm btn-outline-primary" type="button" data-i18n="itam.contactAdd">Add contact</button></div><div id="itam-partner-contact-list" class="d-grid gap-3"></div></section>`;}
function wirePartnerContacts(documentObject){const list=documentObject.getElementById('itam-partner-contact-list');const add=documentObject.getElementById('itam-partner-contact-add');if(!list||!add)return;const addRow=()=>{const index=list.children?.length??0;const wrapper=documentObject.createElement('fieldset');wrapper.className='inx-partner-contact card card-body border';wrapper.setAttribute('data-inx-partner-contact','true');wrapper.innerHTML=`<div class="d-flex justify-content-between align-items-center gap-2 mb-3"><legend class="h6 mb-0" data-i18n="itam.contact">Contact</legend><button class="btn btn-sm btn-outline-danger" type="button" data-inx-remove-contact data-i18n-aria-label="itam.contactRemove" aria-label="Remove contact">×</button></div><div class="row g-3"><div class="col-md-3"><label class="form-label" data-i18n="itam.contactType">Type</label><input name="contactType" class="form-control" maxlength="32" pattern="[a-z][a-z0-9_-]{1,31}" value="business" required /></div><div class="col-md-9"><label class="form-label" data-i18n="itam.contactName">Name</label><input name="contactName" class="form-control" maxlength="160" required /></div><div class="col-md-4"><label class="form-label" data-i18n="itam.contactEmail">Email</label><input name="contactEmail" type="email" class="form-control" maxlength="320" autocomplete="email" /></div><div class="col-md-4"><label class="form-label" data-i18n="itam.contactPhone">Phone</label><input name="contactPhone" type="tel" class="form-control" maxlength="64" autocomplete="tel" /></div><div class="col-md-4"><label class="form-label" data-i18n="itam.contactUrl">URL</label><input name="contactUri" type="url" class="form-control" maxlength="2048" autocomplete="url" /></div></div>`;wrapper.querySelector?.('[data-inx-remove-contact]')?.addEventListener?.('click',()=>wrapper.remove?.());list.appendChild(wrapper);applyTranslations(documentObject,localeFromDocument(documentObject));};add.addEventListener?.('click',addRow);}
export function serializePartnerContacts(form){const contacts=[];for(const row of form.querySelectorAll?.('[data-inx-partner-contact]')??[]){const value=(name)=>String(row.querySelector?.(`[name="${name}"]`)?.value??'').trim();const type=value('contactType'),name=value('contactName'),email=value('contactEmail'),phone=value('contactPhone'),uri=value('contactUri');if(!type&&!name&&!email&&!phone&&!uri)continue;if(!type||!name)throw new Error('Contact type and name are required');if(!email&&!phone&&!uri)throw new Error('Each contact requires an email, phone or URL');contacts.push({type,name,email:nullable(email),phone:nullable(phone),uri:nullable(uri)});}return contacts;}

function jsonField(id,name,key,value,rows){return `<div class="col-12"><label class="form-label" for="${id}" data-i18n="${key}">${key}</label><textarea id="${id}" name="${name}" class="form-control font-monospace" rows="${rows}" spellcheck="false">${value}</textarea><div class="form-text" data-i18n="itam.declarativeJsonHint">Declarative JSON array; no executable content.</div></div>`;}
function dateField(id,name,key,required){return `<div class="col-md-6">${dateFieldInner(id,name,key,required)}</div>`;}
function dateFieldInner(id,name,key,required){return `<label class="form-label" for="${id}" data-i18n="${key}">${key}</label><input id="${id}" name="${name}" class="form-control" type="date" data-inx-temporal="date" ${required?'required':''}/>`;}
function reasonField(id){return `<div class="col-12"><label class="form-label" for="${id}" data-i18n="common.reason">Reason</label><textarea id="${id}" name="reason" class="form-control" minlength="2" maxlength="1024" required></textarea></div>`;}
function sourceSelect(id,name){return `<select id="${id}" name="${name}" class="form-select" required><option value="manual" data-i18n="itam.source.manual">Manual</option><option value="import" data-i18n="itam.source.import">Import</option><option value="integration" data-i18n="itam.source.integration">Integration</option><option value="migration" data-i18n="itam.source.migration">Migration</option></select>`;}
