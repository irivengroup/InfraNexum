import assert from 'node:assert/strict';
import test from 'node:test';

import { createIamEntityDirectory } from '../../src/applications/web/public/assets/entity-selects.mjs';

const ORG_A = '019a0000-0000-7000-8000-000000000001';
const ORG_B = '019a0000-0000-7000-8000-000000000002';
const SUB_A = '019a0000-0000-7000-8000-000000000011';
const SUB_B = '019a0000-0000-7000-8000-000000000012';
const USER_A = '019a0000-0000-7000-8000-000000000021';
const GROUP_A = '019a0000-0000-7000-8000-000000000031';
const ROLE_A = '019a0000-0000-7000-8000-000000000041';
const ASSIGN_A = '019a0000-0000-7000-8000-000000000051';
const POLICY_A = '019a0000-0000-7000-8000-000000000061';

class FakeNode extends EventTarget {
  constructor(tagName, attributes = {}) {
    super();
    this.tagName = tagName.toUpperCase();
    this.attributes = new Map(Object.entries(attributes));
    this.children = [];
    this.options = this.children;
    this.ownerDocument = null;
    this.disabled = false;
    this.required = Object.hasOwn(attributes, 'required');
    this._value = attributes.value ?? '';
  }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  hasAttribute(name) { return name === 'required' ? this.required : this.attributes.has(name); }
  removeAttribute(name) { this.attributes.delete(name); if (name === 'required') this.required = false; }
  replaceChildren(...nodes) { this.children = nodes; this.options = this.children; }
  get value() { return this._value; }
  set value(value) { this._value = String(value ?? ''); }
}

class FakeDocument extends EventTarget {
  constructor(nodes) {
    super();
    this.nodes = nodes;
    this.documentElement = { getAttribute: (name) => name === 'lang' ? 'en' : null };
    this.defaultView = { CustomEvent: class extends Event { constructor(type) { super(type); } } };
    for (const node of nodes) node.ownerDocument = this;
  }
  querySelectorAll(selector) {
    if (selector === 'select[data-inx-entity]') return this.nodes.filter((node) => node.tagName === 'SELECT' && node.getAttribute('data-inx-entity'));
    return [];
  }
  getElementById(id) { return this.nodes.find((node) => node.getAttribute('id') === id) ?? null; }
  createElement(tagName) { const node = new FakeNode(tagName); node.ownerDocument = this; return node; }
}

function select(attributes) { return new FakeNode('select', attributes); }
function optionValues(node) { return node.options.map((item) => item.value).filter(Boolean); }
function nextTurn() { return new Promise((resolve) => setTimeout(resolve, 0)); }

function apiMock(calls) {
  return async (path) => {
    calls.push(path);
    if (path.includes(`${ORG_A}/subdivisions`)) return [{ id: SUB_A, code: 'PAR', displayName: 'Paris' }];
    if (path.includes(`${ORG_B}/subdivisions`)) return [{ id: SUB_B, code: 'LYO', displayName: 'Lyon' }];
    if (path.includes(`/roles/${ROLE_A}/assignments`)) return [{ id: ASSIGN_A, actorType: 'USER', actorId: USER_A, scopeKind: 'ORGANIZATION' }];
    if (path.startsWith('/v1/iam/policies?organizationId=')) return [];
    if (path === '/v1/iam/policies?limit=200') return [{ id: POLICY_A, code: 'system.rbac-bridge', version: 1, state: 'ACTIVE' }];
    throw new Error(`unexpected API path: ${path}`);
  };
}

test('entity directory filters subdivisions by their selected parent organization', async () => {
  const workspace = select({ id: 'workspace-org', value: ORG_A });
  const policyOrg = select({ id: 'policy-org', value: ORG_A });
  const subdivision = select({ 'data-inx-entity': 'subdivision', 'data-inx-organization-source': 'policy-org', required: '' });
  const documentObject = new FakeDocument([workspace, policyOrg, subdivision]);
  const calls = [];
  const directory = createIamEntityDirectory(documentObject, apiMock(calls), workspace);
  directory.setOrganizations([{ id: ORG_A, code: 'ORG-A', displayName: 'Alpha' }, { id: ORG_B, code: 'ORG-B', displayName: 'Beta' }]);

  await directory.setOrganization(ORG_A);
  await nextTurn();
  assert.deepEqual(optionValues(subdivision), [SUB_A]);

  policyOrg.value = ORG_B;
  policyOrg.dispatchEvent(new Event('change'));
  await nextTurn();
  assert.deepEqual(optionValues(subdivision), [SUB_B]);
  assert.ok(calls.some((path) => path.includes(`${ORG_A}/subdivisions`)));
  assert.ok(calls.some((path) => path.includes(`${ORG_B}/subdivisions`)));
});

test('entity directory filters actor choices by USER/GROUP and never asks operators for a raw UUID', async () => {
  const workspace = select({ id: 'workspace-org', value: ORG_A });
  const actorType = select({ id: 'actor-type', value: 'USER' });
  const actor = select({ 'data-inx-entity': 'actor', 'data-inx-type-source': 'actor-type', required: '' });
  const documentObject = new FakeDocument([workspace, actorType, actor]);
  const directory = createIamEntityDirectory(documentObject, apiMock([]), workspace);
  directory.setCatalog('user', [{ id: USER_A, login: 'alice', displayName: 'Alice' }]);
  directory.setCatalog('group', [{ id: GROUP_A, code: 'ops', displayName: 'Operations' }]);

  assert.deepEqual(optionValues(actor), [USER_A]);
  actorType.value = 'GROUP';
  actorType.dispatchEvent(new Event('change'));
  assert.deepEqual(optionValues(actor), [GROUP_A]);
});

test('entity directory filters assignment choices by role and exposes policy catalog values', async () => {
  const workspace = select({ id: 'workspace-org', value: ORG_A });
  const role = select({ id: 'revoke-role', value: ROLE_A });
  const assignment = select({ 'data-inx-entity': 'assignment', 'data-inx-role-source': 'revoke-role', required: '' });
  const policy = select({ 'data-inx-entity': 'policy', required: '' });
  const documentObject = new FakeDocument([workspace, role, assignment, policy]);
  const calls = [];
  const directory = createIamEntityDirectory(documentObject, apiMock(calls), workspace);

  await directory.setOrganization(ORG_A);
  await directory.ensureAssignments(ROLE_A);
  await nextTurn();
  assert.deepEqual(optionValues(assignment), [ASSIGN_A]);
  assert.deepEqual(optionValues(policy), [POLICY_A]);
});
