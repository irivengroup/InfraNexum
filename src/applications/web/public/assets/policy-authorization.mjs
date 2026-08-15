import { identityAccessRequest } from './identity-access.mjs';
import { localeFromDocument, setLocalizedElementText, translate } from './i18n.mjs';
import { wireAsyncForm } from './form-controller.mjs';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CODE_PATTERN = /^(?!system\.)(?:[a-z][a-z0-9-]*)(?:\.[a-z][a-z0-9-]*){1,7}$/;
const SOURCES = new Set(['SUBJECT', 'RESOURCE', 'ORGANIZATION', 'SUBDIVISION', 'ENVIRONMENT', 'AUTHENTICATION', 'CAPABILITY', 'RBAC']);
const OPERATORS = new Set(['EQUALS', 'NOT_EQUALS', 'CONTAINS', 'EXISTS']);
const EFFECTS = new Set(['PERMIT', 'DENY']);
const OBLIGATIONS = new Set(['REQUIRE_JUSTIFICATION', 'STEP_UP_MFA', 'REQUIRE_APPROVAL', 'MASK_FIELDS', 'LIMIT_FIELDS']);
const SCOPES = new Set(['PLATFORM', 'ORGANIZATION', 'SUBDIVISION']);

/** Performs one advanced-authorization request through the authenticated IAM browser boundary. */
export async function policyAuthorizationRequest(configuration, path, options = {}) {
  if (!configuration?.advancedAuthorizationEnabled) throw new Error('Advanced-authorization Web capability is disabled');
  if (!/^\/v1\/iam\/(?:policies(?:\/|$)|authorization\/)/.test(path)) {
    throw new TypeError('Advanced-authorization API path is outside the policy boundary');
  }
  return identityAccessRequest(configuration, path, options);
}

/** Parses the closed policy-rule JSON syntax accepted by the E04 Web administration form. */
export function parsePolicyRules(raw) {
  const value = parseJsonArray(raw, 'rules', 1, 256);
  return value.map((rule, index) => {
    assertClosedObject(rule, ['effect', 'action', 'resourceType', 'conditions', 'obligations', 'advice'], `rules[${index}]`);
    const effect = enumValue(rule.effect, EFFECTS, `rules[${index}].effect`);
    const action = boundedText(rule.action, `rules[${index}].action`, 1, 128);
    const resourceType = boundedText(rule.resourceType, `rules[${index}].resourceType`, 1, 80);
    const conditions = requireArray(rule.conditions, `rules[${index}].conditions`, 1, 32).map((condition, conditionIndex) => {
      const label = `rules[${index}].conditions[${conditionIndex}]`;
      assertClosedObject(condition, ['source', 'attribute', 'operator', 'expectedValue'], label);
      return {
        source: enumValue(condition.source, SOURCES, `${label}.source`),
        attribute: boundedText(condition.attribute, `${label}.attribute`, 1, 64),
        operator: enumValue(condition.operator, OPERATORS, `${label}.operator`),
        expectedValue: boundedText(condition.expectedValue, `${label}.expectedValue`, 1, 256),
      };
    });
    const obligations = rule.obligations === undefined
      ? []
      : requireArray(rule.obligations, `rules[${index}].obligations`, 0, 8).map((item, obligationIndex) =>
          enumValue(item, OBLIGATIONS, `rules[${index}].obligations[${obligationIndex}]`));
    if (new Set(obligations).size !== obligations.length) throw new Error(`rules[${index}].obligations contains duplicates`);
    return {
      effect,
      action,
      resourceType,
      conditions,
      obligations,
      advice: rule.advice === undefined ? '' : boundedText(rule.advice, `rules[${index}].advice`, 0, 500),
    };
  });
}

/** Parses optional static SoD constraints without allowing arbitrary fields. */
export function parseSodConstraints(raw) {
  if (typeof raw !== 'string' || raw.trim() === '') return [];
  return parseJsonArray(raw, 'sodConstraints', 0, 128).map((constraint, index) => {
    const label = `sodConstraints[${index}]`;
    assertClosedObject(constraint, ['firstRoleId', 'secondRoleId', 'reason'], label);
    const firstRoleId = uuid(constraint.firstRoleId, `${label}.firstRoleId`);
    const secondRoleId = uuid(constraint.secondRoleId, `${label}.secondRoleId`);
    if (firstRoleId === secondRoleId) throw new Error(`${label} roles must be distinct`);
    return { firstRoleId, secondRoleId, reason: boundedText(constraint.reason, `${label}.reason`, 1, 500) };
  });
}

/** Builds a closed CreatePolicy request from browser form data. */
export function buildPolicyCreatePayload(values) {
  const scopeKind = enumValue(field(values, 'scopeKind'), SCOPES, 'scopeKind');
  const scope = scopeFields(scopeKind, optionalField(values, 'organizationId'), optionalField(values, 'subdivisionId'));
  const priorityText = field(values, 'priority');
  if (!/^\d{1,5}$/.test(priorityText)) throw new Error('priority must be an integer between 0 and 10000');
  const priority = Number(priorityText);
  if (priority > 10_000) throw new Error('priority must be an integer between 0 and 10000');
  const code = boundedText(field(values, 'code'), 'code', 3, 128);
  if (!CODE_PATTERN.test(code)) throw new Error('code must be a namespaced lower-case policy code and must not use system.*');
  return {
    ...scope,
    code,
    purpose: boundedText(field(values, 'purpose'), 'purpose', 1, 500),
    priority,
    scopeKind,
    effectiveFrom: optionalInstant(optionalField(values, 'effectiveFrom')),
    rules: parsePolicyRules(field(values, 'rules')),
    sodConstraints: parseSodConstraints(optionalField(values, 'sodConstraints')),
    reason: boundedText(field(values, 'reason'), 'reason', 1, 1024),
  };
}

/** Builds a closed PDP simulation/explanation request. */
export function buildDecisionPayload(values) {
  const scopeKind = enumValue(field(values, 'scopeKind'), SCOPES, 'scopeKind');
  const scope = scopeFields(scopeKind, optionalField(values, 'organizationId'), optionalField(values, 'subdivisionId'));
  return {
    subjectId: uuid(field(values, 'subjectId'), 'subjectId'),
    action: boundedText(field(values, 'action'), 'action', 1, 128),
    resourceType: boundedText(field(values, 'resourceType'), 'resourceType', 1, 80),
    resourceId: boundedText(field(values, 'resourceId'), 'resourceId', 1, 512),
    scopeKind,
    ...scope,
    requestedPolicyVersion: nullableText(optionalField(values, 'requestedPolicyVersion'), 'requestedPolicyVersion', 128),
  };
}

/** Wires the Pro/Enterprise E04 PAP/PDP administration surface without duplicating policy evaluation in the browser. */
export async function initializePolicyAuthorization(documentObject, configuration, fetchFunction = fetch) {
  const workspace = documentObject?.getElementById?.('iam-policy-workspace');
  const tab = documentObject?.getElementById?.('iam-tab-policies');
  const available = configuration?.advancedAuthorizationEnabled === true;
  if (workspace) workspace.hidden = !available;
  if (tab) tab.hidden = !available;
  if (!available) return Object.freeze({ enabled: false });
  if (!configuration.identityAccessEnabled) throw new Error('Advanced authorization requires identity-access Web capability');

  const status = requiredElement(documentObject, 'iam-policy-status');
  const result = requiredElement(documentObject, 'iam-policy-result');
  const api = (path, options = {}) => policyAuthorizationRequest(configuration, path, {
    ...options,
    fetchFunction,
    cookieString: documentObject.cookie,
  });

  bindCreateForm(documentObject, status, result, api);
  bindLifecycleForm(documentObject, status, result, api);
  bindDecisionForm(documentObject, status, result, api);
  setStatus(documentObject, status, 'policy.ready', false);
  return Object.freeze({ enabled: true });
}

function bindCreateForm(documentObject, status, result, api) {
  const form = requiredElement(documentObject, 'iam-policy-create-form');
  wireAsyncForm(form, {
    execute: async () => {
      const response = await api('/v1/iam/policies', {
        method: 'POST',
        body: buildPolicyCreatePayload(new FormData(form)),
      });
      renderResult(result, response);
      const policyId = documentObject.getElementById('iam-policy-lifecycle-id');
      if (policyId && response?.id) { policyId.value = response.id; dispatchChange(documentObject, policyId); }
      dispatchPolicyChanged(documentObject);
    },
    onWorking: () => setStatus(documentObject, status, 'policy.working', false),
    onSuccess: () => setStatus(documentObject, status, 'policy.created', false),
    onError: (error) => setPolicyError(documentObject, status, error),
  });
}

function bindLifecycleForm(documentObject, status, result, api) {
  const form = requiredElement(documentObject, 'iam-policy-lifecycle-form');
  wireAsyncForm(form, {
    execute: async (_form, submitter) => {
      const values = new FormData(form);
      const policyId = uuid(field(values, 'policyId'), 'policyId');
      const action = enumValue(submitter?.value, new Set(['validate', 'approve', 'activate']), 'lifecycle action');
      const response = await api(`/v1/iam/policies/${policyId}/${action}`, {
        method: 'POST',
        body: { reason: boundedText(field(values, 'reason'), 'reason', 1, 1024) },
      });
      renderResult(result, response);
      form.dataset.lastAction = action;
      dispatchPolicyChanged(documentObject);
    },
    onWorking: () => setStatus(documentObject, status, 'policy.working', false),
    onSuccess: () => {
      const action = form.dataset.lastAction;
      setStatus(documentObject, status, action === 'validate' ? 'policy.validated' : action === 'approve' ? 'policy.approved' : 'policy.activated', false);
    },
    onError: (error) => setPolicyError(documentObject, status, error),
  });
}

function bindDecisionForm(documentObject, status, result, api) {
  const form = requiredElement(documentObject, 'iam-policy-decision-form');
  wireAsyncForm(form, {
    execute: async (_form, submitter) => {
      const values = new FormData(form);
      const mode = enumValue(submitter?.value, new Set(['decide', 'explain']), 'decision action');
      const justification = optionalField(values, 'justification');
      const response = await api(`/v1/iam/authorization/${mode === 'decide' ? 'decisions' : 'explain'}`, {
        method: 'POST',
        body: buildDecisionPayload(values),
        justification: justification || undefined,
      });
      renderResult(result, response);
      form.dataset.lastAction = mode;
    },
    onWorking: () => setStatus(documentObject, status, 'policy.working', false),
    onSuccess: () => setStatus(documentObject, status, form.dataset.lastAction === 'decide' ? 'policy.decided' : 'policy.explained', false),
    onError: (error) => setPolicyError(documentObject, status, error),
  });
}

function dispatchPolicyChanged(documentObject) {
  const EventConstructor = documentObject?.defaultView?.CustomEvent ?? globalThis.CustomEvent;
  if (typeof EventConstructor === 'function') documentObject.dispatchEvent?.(new EventConstructor('infranexum:policy-changed'));
}

function dispatchChange(documentObject, field) {
  const EventConstructor = documentObject?.defaultView?.Event ?? globalThis.Event;
  if (typeof EventConstructor === 'function') field.dispatchEvent?.(new EventConstructor('change', { bubbles: true }));
}

function setPolicyError(documentObject, status, error) {
  const locale = localeFromDocument(documentObject);
  const message = typeof error?.message === 'string' && error.message.trim() ? error.message.trim() : translate(locale, 'policy.error');
  status.textContent = message.length <= 500 && !/[\u0000-\u001F\u007F]/.test(message) ? message : translate(locale, 'policy.error');
  status.className = 'alert alert-danger mb-3';
  status.hidden = false;
}

function renderResult(element, value) {
  element.textContent = JSON.stringify(value, null, 2);
}

function setStatus(documentObject, element, key, error) {
  setLocalizedElementText(documentObject, element, key);
  element.className = `alert ${error ? 'alert-danger' : 'alert-secondary'} mb-3`;
  element.hidden = false;
}

function parseJsonArray(raw, name, minimum, maximum) {
  let value;
  try { value = JSON.parse(raw); } catch { throw new Error(`${name} must contain valid JSON`); }
  return requireArray(value, name, minimum, maximum);
}

function requireArray(value, name, minimum, maximum) {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) {
    throw new Error(`${name} must contain between ${minimum} and ${maximum} item(s)`);
  }
  return value;
}

function assertClosedObject(value, allowed, name) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${name} must be a JSON object`);
  const accepted = new Set(allowed);
  for (const key of Object.keys(value)) if (!accepted.has(key)) throw new Error(`${name}.${key} is not supported`);
}

function enumValue(value, accepted, name) {
  if (typeof value !== 'string' || !accepted.has(value)) throw new Error(`${name} is invalid`);
  return value;
}

function boundedText(value, name, minimum, maximum) {
  if (typeof value !== 'string') throw new Error(`${name} must be a string`);
  const normalized = value.trim();
  if (normalized.length < minimum || normalized.length > maximum || normalized.includes('\0') || /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(normalized)) {
    throw new Error(`${name} length or characters are invalid`);
  }
  return normalized;
}

function nullableText(value, name, maximum) {
  return value ? boundedText(value, name, 1, maximum) : null;
}

function uuid(value, name) {
  const normalized = boundedText(value, name, 1, 64).toLowerCase();
  if (!UUID_PATTERN.test(normalized)) throw new Error(`${name} must be a UUID`);
  return normalized;
}

function nullableUuid(value, name) { return value ? uuid(value, name) : null; }

function scopeFields(scopeKind, organizationValue, subdivisionValue) {
  if (scopeKind === 'PLATFORM') {
    if (organizationValue || subdivisionValue) throw new Error('PLATFORM scope must not declare organizationId or subdivisionId');
    return { organizationId: null, subdivisionId: null };
  }
  const organizationId = uuid(organizationValue, 'organizationId');
  if (scopeKind === 'ORGANIZATION') {
    if (subdivisionValue) throw new Error('ORGANIZATION scope must not declare subdivisionId');
    return { organizationId, subdivisionId: null };
  }
  return { organizationId, subdivisionId: uuid(subdivisionValue, 'subdivisionId') };
}

function optionalInstant(value) {
  if (!value) return null;
  const normalized = boundedText(value, 'effectiveFrom', 16, 80);
  const localDateTime = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/.test(normalized);
  const explicitInstant = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(normalized);
  if (!localDateTime && (!explicitInstant || Number.isNaN(Date.parse(normalized)))) {
    throw new Error('effectiveFrom must be an ISO-8601 local date-time or offset date-time');
  }
  return normalized;
}

function field(values, name) {
  const value = values.get(name);
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`${name} is required`);
  return value.trim();
}

function optionalField(values, name) {
  const value = values.get(name);
  return typeof value === 'string' ? value.trim() : '';
}

function requiredElement(documentObject, id) {
  const element = documentObject?.getElementById?.(id);
  if (!element) throw new Error(`Required advanced-authorization element is missing: ${id}`);
  return element;
}
