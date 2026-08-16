import assert from 'node:assert/strict';
import test from 'node:test';

import {
  loadOrganizations,
  loadSubdivisions,
  validatePublicConfiguration,
} from '../../src/applications/web/public/assets/bootstrap.mjs';
import { WebRuntimeConfiguration } from '../../src/applications/web/runtime/config.mjs';

class Element {
  constructor(tagName = 'div') {
    this.tagName = tagName;
    this.textContent = '';
    this.hidden = true;
    this.children = [];
    this.attributes = {};
    this.listeners = new Map();
    this.className = '';
    this.type = '';
  }

  appendChild(child) {
    this.children.push(child);
    return child;
  }

  replaceChildren(...children) {
    this.children = children;
  }

  setAttribute(name, value) {
    this.attributes[name] = value;
  }

  addEventListener(name, listener) {
    this.listeners.set(name, listener);
  }

  click() {
    this.listeners.get('click')?.();
  }
}

function organizationDocument() {
  const elements = new Map([
    ['organization-rows', new Element('tbody')],
    ['organization-status', new Element('span')],
    ['subdivision-panel', new Element('section')],
    ['subdivision-title', new Element('h3')],
    ['subdivision-status', new Element('span')],
    ['subdivision-rows', new Element('tbody')],
  ]);
  return {
    elements,
    getElementById: (id) => elements.get(id),
    createElement: (tagName) => new Element(tagName),
  };
}

const configuration = { apiBaseUrl: '/api' };
const organization = {
  id: '018bcfe5-6800-7001-8000-000000000057',
  code: 'LAB-FR',
  displayName: '<InfraNexum Lab>',
  countryCode: 'FR',
  status: 'active',
  version: 1,
};

const subdivision = {
  id: '018bcfe5-6800-7002-8000-000000000057',
  code: 'OPS',
  displayName: '<Operations>',
  type: 'department',
  status: 'active',
  version: 1,
};

test('organization foundation is production-capable now that IAM enforcement is installed', () => {
  const configuration = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ENVIRONMENT: 'production',
    INFRANEXUM_WEB_ORGANIZATION_FOUNDATION_ENABLED: 'true',
  }, { version: '2.0.0-alpha.0.101' });
  assert.equal(configuration.organizationFoundationEnabled, true);
  assert.equal(configuration.identityAccessEnabled, false);
});

test('organization foundation configuration accepts explicit true and false only', () => {
  const enabled = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ENVIRONMENT: 'local',
    INFRANEXUM_WEB_ORGANIZATION_FOUNDATION_ENABLED: 'true',
  }, { version: '2.0.0-alpha.0.101' });
  assert.equal(enabled.organizationFoundationEnabled, true);
  assert.equal(enabled.publicConfiguration().organizationFoundationEnabled, true);
  assert.equal(validatePublicConfiguration(enabled.publicConfiguration()).organizationFoundationEnabled, true);

  const disabled = WebRuntimeConfiguration.fromEnvironment({
    INFRANEXUM_WEB_ENVIRONMENT: 'local',
    INFRANEXUM_WEB_ORGANIZATION_FOUNDATION_ENABLED: 'false',
  }, { version: '2.0.0-alpha.0.101' });
  assert.equal(disabled.organizationFoundationEnabled, false);

  assert.throws(
    () => WebRuntimeConfiguration.fromEnvironment({
      INFRANEXUM_WEB_ENVIRONMENT: 'local',
      INFRANEXUM_WEB_ORGANIZATION_FOUNDATION_ENABLED: 'yes',
    }, { version: '2.0.0-alpha.0.101' }),
    /must be true or false/,
  );
  assert.throws(
    () => validatePublicConfiguration({
      ...enabled.publicConfiguration(),
      organizationFoundationEnabled: 'true',
    }),
    /organizationFoundationEnabled/,
  );
});

test('organization and subdivision tables use same-origin API and text-only DOM', async () => {
  const documentObject = organizationDocument();
  const requests = [];
  const fetchFunction = async (url, options) => {
    requests.push({ url, options });
    if (url.endsWith('/organizations?limit=50')) {
      return { ok: true, async json() { return [organization]; } };
    }
    return { ok: true, async json() { return [subdivision]; } };
  };

  await loadOrganizations(documentObject, configuration, fetchFunction);
  assert.equal(requests[0].url, '/api/v1/iam/organizations?limit=50');
  assert.equal(requests[0].options.cache, 'no-store');
  assert.equal(documentObject.elements.get('organization-status').textContent, '1 organisation(s)');

  const row = documentObject.elements.get('organization-rows').children[0];
  assert.equal(row.children[1].textContent, '<InfraNexum Lab>');
  assert.equal(row.children[5].children[0].textContent, 'View hierarchy');
  row.children[5].children[0].click();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(
    requests[1].url,
    `/api/v1/iam/organizations/${organization.id}/subdivisions?limit=50`,
  );
  assert.equal(documentObject.elements.get('subdivision-panel').hidden, false);
  assert.equal(documentObject.elements.get('subdivision-title').textContent, 'Subdivisions — LAB-FR');
  assert.equal(documentObject.elements.get('subdivision-status').textContent, '1 subdivision(s)');
  assert.equal(documentObject.elements.get('subdivision-rows').children[0].children[1].textContent, '<Operations>');
});

test('organization foundation UI reports API failures without throwing', async () => {
  const documentObject = organizationDocument();
  await loadOrganizations(
    documentObject,
    configuration,
    async () => ({ ok: false, status: 503 }),
  );
  assert.equal(documentObject.elements.get('organization-status').textContent, 'Organisation data unavailable');

  await loadSubdivisions(
    documentObject,
    configuration,
    organization,
    async () => { throw new Error('offline'); },
  );
  assert.equal(documentObject.elements.get('subdivision-status').textContent, 'Subdivision data unavailable');
  assert.equal(documentObject.elements.get('subdivision-rows').children.length, 0);

  await loadOrganizations({ getElementById: () => undefined }, configuration, async () => {
    throw new Error('must not execute');
  });
  await loadSubdivisions({ getElementById: () => undefined }, configuration, organization, async () => {
    throw new Error('must not execute');
  });
});
