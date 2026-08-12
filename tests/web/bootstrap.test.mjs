import assert from 'node:assert/strict';
import test from 'node:test';

import {
  bootstrap,
  renderRuntimeConfiguration,
  renderRuntimeFailure,
  validatePublicConfiguration,
} from '../../src/applications/web/public/assets/bootstrap.mjs';

class Element {
  textContent = '';
  attributes = {};
  setAttribute(name, value) { this.attributes[name] = value; }
}

function documentFixture() {
  const elements = new Map([
    ['runtime-version', new Element()],
    ['runtime-environment', new Element()],
    ['runtime-api', new Element()],
    ['runtime-message', new Element()],
  ]);
  return { elements, getElementById: (id) => elements.get(id) };
}

const valid = {
  schema: 'infranexum.web-runtime-config/v1',
  product: 'InfraNexum',
  component: 'web',
  version: '2.0.0-alpha.0.42',
  architectureBaseline: '2.0.0-draft.21',
  environment: 'test',
  apiBaseUrl: '/api',
};

test('browser bootstrap validates and renders public configuration', async () => {
  assert.equal(Object.isFrozen(validatePublicConfiguration(valid)), true);
  const documentObject = documentFixture();
  renderRuntimeConfiguration(documentObject, valid);
  assert.equal(documentObject.elements.get('runtime-version').textContent, valid.version);
  assert.equal(documentObject.elements.get('runtime-environment').textContent, 'test');
  assert.equal(documentObject.elements.get('runtime-api').textContent, '/api');
  assert.match(documentObject.elements.get('runtime-message').textContent, /loaded/);

  await bootstrap({
    documentObject,
    fetchFunction: async (_url, options) => {
      assert.equal(options.cache, 'no-store');
      assert.equal(options.credentials, 'same-origin');
      return { ok: true, async json() { return valid; } };
    },
  });
  assert.equal(documentObject.elements.get('runtime-version').textContent, valid.version);
});

test('browser bootstrap rejects malformed configuration and renders a safe failure', async () => {
  for (const value of [null, [], {}, { ...valid, schema: 'wrong' }, { ...valid, version: '' }]) {
    assert.throws(() => validatePublicConfiguration(value), /configuration/i);
  }
  const documentObject = documentFixture();
  renderRuntimeFailure(documentObject);
  assert.match(documentObject.elements.get('runtime-message').textContent, /could not be loaded/);
  assert.equal(documentObject.elements.get('runtime-message').attributes['data-state'], 'error');

  await bootstrap({
    documentObject,
    fetchFunction: async () => ({ ok: false, status: 503 }),
  });
  assert.equal(documentObject.elements.get('runtime-message').attributes['data-state'], 'error');

  await bootstrap({
    documentObject,
    fetchFunction: async () => { throw new Error('offline'); },
  });
  assert.equal(documentObject.elements.get('runtime-message').attributes['data-state'], 'error');
});
