import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildDecisionPayload,
  buildPolicyCreatePayload,
  parsePolicyRules,
  parseSodConstraints,
  policyAuthorizationRequest,
} from '../../src/applications/web/public/assets/policy-authorization.mjs';

const USER_ID = '01900000-0000-7000-8000-000000000001';
const ORG_ID = '01900000-0000-7000-8000-000000000002';
const SUBDIVISION_ID = '01900000-0000-7000-8000-000000000003';
const ROLE_A = '01900000-0000-7000-8000-000000000004';
const ROLE_B = '01900000-0000-7000-8000-000000000005';
const configuration = Object.freeze({ apiBaseUrl: '/api', identityAccessEnabled: true, advancedAuthorizationEnabled: true });

function jsonResponse(status, payload) {
  return { ok: status >= 200 && status < 300, status, async json() { return payload; } };
}

function values(entries) {
  const form = new FormData();
  for (const [key, value] of Object.entries(entries)) form.append(key, value);
  return form;
}

test('policy browser request is capability gated, namespace bounded, CSRF protected and carries a validated justification', async () => {
  let observed;
  const response = await policyAuthorizationRequest(configuration, '/v1/iam/authorization/decisions', {
    method: 'POST',
    body: { subjectId: USER_ID },
    justification: 'Approved operational emergency',
    cookieString: 'INX_XSRF=csrf-token',
    fetchFunction: async (url, options) => {
      observed = { url, options };
      return jsonResponse(200, { decision: 'permit' });
    },
  });
  assert.equal(response.decision, 'permit');
  assert.equal(observed.url, '/api/v1/iam/authorization/decisions');
  assert.equal(observed.options.headers['X-CSRF-Token'], 'csrf-token');
  assert.equal(observed.options.headers['X-InfraNexum-Justification'], 'Approved operational emergency');

  await assert.rejects(policyAuthorizationRequest({ ...configuration, advancedAuthorizationEnabled: false }, '/v1/iam/policies'), /capability is disabled/);
  await assert.rejects(policyAuthorizationRequest(configuration, '/v1/iam/users'), /outside the policy boundary/);
  await assert.rejects(policyAuthorizationRequest(configuration, '/v1/iam/authorization/decisions', {
    method: 'POST', body: {}, justification: 'short', cookieString: 'INX_XSRF=csrf-token', fetchFunction: async () => jsonResponse(200, {}),
  }), /8 to 500 printable/);
});

test('closed declarative policy parser accepts supported rules and rejects executable or unknown syntax', () => {
  const rules = parsePolicyRules(JSON.stringify([{
    effect: 'DENY', action: 'iam.user.assign_role', resourceType: 'iam.user',
    conditions: [{ source: 'AUTHENTICATION', attribute: 'method', operator: 'NOT_EQUALS', expectedValue: 'MFA' }],
    obligations: ['REQUIRE_JUSTIFICATION'], advice: 'Use MFA.',
  }]));
  assert.equal(rules.length, 1);
  assert.deepEqual(rules[0].obligations, ['REQUIRE_JUSTIFICATION']);

  assert.throws(() => parsePolicyRules('{bad'), /valid JSON/);
  assert.throws(() => parsePolicyRules('[]'), /between 1 and 256/);
  assert.throws(() => parsePolicyRules(JSON.stringify([{ effect: 'DENY', action: '*', resourceType: '*', conditions: [], script: 'return true' }])), /script is not supported/);
  assert.throws(() => parsePolicyRules(JSON.stringify([{ effect: 'EXECUTE', action: '*', resourceType: '*', conditions: [{}] }])), /effect is invalid/);
  assert.throws(() => parsePolicyRules(JSON.stringify([{ effect: 'DENY', action: '*', resourceType: '*', conditions: [{ source: 'RBAC', attribute: 'permitted', operator: 'EQUALS', expectedValue: 'true' }], obligations: ['MASK_FIELDS', 'MASK_FIELDS'] }])), /duplicates/);
});

test('SoD parser validates closed role pairs and rejects self-conflicts', () => {
  assert.deepEqual(parseSodConstraints(''), []);
  assert.deepEqual(parseSodConstraints(JSON.stringify([{ firstRoleId: ROLE_A, secondRoleId: ROLE_B, reason: 'Maker/checker separation' }])), [{ firstRoleId: ROLE_A, secondRoleId: ROLE_B, reason: 'Maker/checker separation' }]);
  assert.throws(() => parseSodConstraints(JSON.stringify([{ firstRoleId: ROLE_A, secondRoleId: ROLE_A, reason: 'invalid' }])), /distinct/);
  assert.throws(() => parseSodConstraints(JSON.stringify([{ firstRoleId: ROLE_A, secondRoleId: ROLE_B, reason: 'ok', dynamic: true }])), /dynamic is not supported/);
});

test('policy create payload preserves calendar local date-times for Server-side timezone resolution', () => {
  const payload = buildPolicyCreatePayload(values({
    code: 'operations.server-change', purpose: 'Restrict high-risk server changes', priority: '100',
    scopeKind: 'SUBDIVISION', organizationId: ORG_ID, subdivisionId: SUBDIVISION_ID,
    effectiveFrom: '2026-08-14T14:00',
    rules: JSON.stringify([{ effect: 'PERMIT', action: 'iam.user.read', resourceType: 'iam.user', conditions: [{ source: 'RBAC', attribute: 'permitted', operator: 'EQUALS', expectedValue: 'true' }] }]),
    sodConstraints: JSON.stringify([{ firstRoleId: ROLE_A, secondRoleId: ROLE_B, reason: 'Maker/checker separation' }]),
    reason: 'Create governed authorization policy',
  }));
  assert.equal(payload.scopeKind, 'SUBDIVISION');
  assert.equal(payload.organizationId, ORG_ID);
  assert.equal(payload.subdivisionId, SUBDIVISION_ID);
  assert.equal(payload.rules[0].effect, 'PERMIT');
  assert.equal(payload.effectiveFrom, '2026-08-14T14:00');

  const explicit = buildPolicyCreatePayload(values({
    code: 'operations.offset', purpose: 'Offset compatibility', priority: '1', scopeKind: 'PLATFORM',
    effectiveFrom: '2026-08-14T12:00:00Z',
    rules: JSON.stringify([{ effect: 'PERMIT', action: 'iam.user.read', resourceType: 'iam.user', conditions: [{ source: 'RBAC', attribute: 'permitted', operator: 'EQUALS', expectedValue: 'true' }] }]),
    reason: 'Validate explicit offset compatibility',
  }));
  assert.equal(explicit.effectiveFrom, '2026-08-14T12:00:00Z');

  assert.throws(() => buildPolicyCreatePayload(values({ code: 'system.override', purpose: 'x', priority: '0', scopeKind: 'PLATFORM', rules: '[{}]', reason: 'x' })), /system/);
  assert.throws(() => buildPolicyCreatePayload(values({ code: 'operations.change', purpose: 'x', priority: '10001', scopeKind: 'PLATFORM', rules: '[{}]', reason: 'x' })), /priority/);
  assert.throws(() => buildPolicyCreatePayload(values({ code: 'operations.change', purpose: 'x', priority: '1', scopeKind: 'PLATFORM', organizationId: ORG_ID, rules: '[{}]', reason: 'x' })), /must not declare organizationId/);
  assert.throws(() => buildPolicyCreatePayload(values({ code: 'operations.change', purpose: 'x', priority: '1', scopeKind: 'ORGANIZATION', rules: '[{}]', reason: 'x' })), /organizationId/);
});

test('decision payload enforces scope ownership and bounded subject/resource values', () => {
  const payload = buildDecisionPayload(values({
    subjectId: USER_ID, action: 'iam.user.read', resourceType: 'iam.user', resourceId: USER_ID,
    scopeKind: 'ORGANIZATION', organizationId: ORG_ID, requestedPolicyVersion: 'operations.server-change@2',
  }));
  assert.equal(payload.subjectId, USER_ID);
  assert.equal(payload.organizationId, ORG_ID);
  assert.equal(payload.subdivisionId, null);
  assert.equal(payload.requestedPolicyVersion, 'operations.server-change@2');

  assert.throws(() => buildDecisionPayload(values({ subjectId: 'not-a-uuid', action: 'x', resourceType: 'x', resourceId: 'x', scopeKind: 'PLATFORM' })), /subjectId must be a UUID/);
  assert.throws(() => buildDecisionPayload(values({ subjectId: USER_ID, action: 'x', resourceType: 'x', resourceId: 'x', scopeKind: 'SUBDIVISION', organizationId: ORG_ID })), /subdivisionId/);
});

import { synchronizePolicyWorkspaceVisibility } from '../../src/applications/web/public/assets/policy-authorization.mjs';

test('advanced policy workspace never renders beside the active users panel', () => {
  const classes = new Set();
  const workspace = { hidden: false, attrs: new Map(), classList: { toggle(name, active) { active ? classes.add(name) : classes.delete(name); } }, setAttribute(name, value) { this.attrs.set(name, value); } };
  const tab = { hidden: false, selected: 'false', getAttribute(name) { return name === 'aria-selected' ? this.selected : null; } };

  assert.equal(synchronizePolicyWorkspaceVisibility(tab, workspace, true), false);
  assert.equal(workspace.hidden, true);
  assert.equal(workspace.attrs.get('aria-hidden'), 'true');
  assert.equal(classes.has('active'), false);

  tab.selected = 'true';
  assert.equal(synchronizePolicyWorkspaceVisibility(tab, workspace, true), true);
  assert.equal(workspace.hidden, false);
  assert.equal(workspace.attrs.get('aria-hidden'), 'false');
  assert.equal(classes.has('active'), true);

  assert.equal(synchronizePolicyWorkspaceVisibility(tab, workspace, false), false);
  assert.equal(tab.hidden, true);
  assert.equal(workspace.hidden, true);
});
