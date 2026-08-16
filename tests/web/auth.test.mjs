import assert from 'node:assert/strict';
import test from 'node:test';

import {
  AuthenticationError,
  PasswordPolicyError,
  changeLocalPassword,
  csrfToken,
  initializeLocalAuthentication,
  loginLocal,
  logoutLocal,
  readLocalSession,
  validateSession,
} from '../../src/applications/web/public/assets/auth.mjs';

const configuration = Object.freeze({ apiBaseUrl: '/api', localAuthEnabled: true });
const session = Object.freeze({
  sessionId: '018f22b2-7c00-7000-8000-000000000001',
  accountId: '018f22b2-7c00-7000-8000-000000000002',
  username: 'admin', displayName: 'Local Administrator', mustChange: false,
  idleExpiresAt: '2026-08-12T22:30:00Z', absoluteExpiresAt: '2026-08-13T10:00:00Z',
});

class ClassList {
  values = new Set();
  add(...values) { for (const value of values) this.values.add(value); }
  remove(...values) { for (const value of values) this.values.delete(value); }
  contains(value) { return this.values.has(value); }
}
class Element {
  constructor() { this.hidden = true; this.textContent = ''; this.dataset = {}; this.disabled = false; this.listeners = new Map(); this.attributes = new Map(); this.value = ''; }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  removeAttribute(name) { this.attributes.delete(name); if (name === 'hidden') this.hidden = false; }
  focus() {}
}
function authenticatedDocument(cookie = 'INX_XSRF=csrf-value') {
  const body = { classList: new ClassList() };
  const elements = new Map([
    ['auth-gate', new Element()], ['app-shell', new Element()], ['auth-login-form', new Element()], ['auth-password-form', new Element()],
    ['auth-login-view', new Element()], ['auth-password-view', new Element()], ['auth-login-submit', new Element()], ['auth-password-submit', new Element()],
    ['auth-username', new Element()], ['auth-password', new Element()], ['auth-message', new Element()],
    ['auth-service-state', new Element()], ['auth-service-state-text', new Element()],
    ['session-identity', new Element()], ['session-avatar', new Element()], ['session-logout', new Element()],
  ]);
  return { body, cookie, getElementById: (id) => elements.get(id) ?? null, elements };
}

function jsonResponse(status, body) {
  return { status, ok: status >= 200 && status < 300, async json() { return body; } };
}

test('CSRF cookie extraction is exact, URL-decoded and fail-closed', () => {
  assert.equal(csrfToken('other=x; INX_XSRF=a%2Bb%3D; x=y'), 'a+b=');
  assert.equal(csrfToken('INX_XSRF='), '');
  assert.equal(csrfToken(''), null);
  assert.equal(csrfToken('INX_XSRF2=wrong'), null);
});

test('session reader treats only 401 as unauthenticated and validates every required field', async () => {
  assert.equal(await readLocalSession(configuration, async () => jsonResponse(401, {})), null);
  assert.deepEqual(await readLocalSession(configuration, async (_url, options) => {
    assert.equal(options.credentials, 'same-origin'); assert.equal(options.cache, 'no-store');
    return jsonResponse(200, session);
  }), session);
  await assert.rejects(() => readLocalSession(configuration, async () => jsonResponse(503, {})), /HTTP 503/);
  assert.throws(() => validateSession({ ...session, mustChange: 'false' }), /mustChange/);
  assert.throws(() => validateSession({ ...session, accountId: '' }), /accountId/);
});

test('login posts credentials same-origin and maps 401 to a generic authentication error', async () => {
  const authenticated = await loginLocal(configuration, 'Admin', 'Secret!Aa1', async (url, options) => {
    assert.equal(url, '/api/v1/iam/local-auth/session');
    assert.equal(options.method, 'POST'); assert.equal(options.credentials, 'same-origin');
    assert.deepEqual(JSON.parse(options.body), { username: 'Admin', password: 'Secret!Aa1' });
    return jsonResponse(200, session);
  });
  assert.equal(authenticated.username, 'admin');
  await assert.rejects(() => loginLocal(configuration, 'admin', 'wrong', async () => jsonResponse(401, {})), AuthenticationError);
});

test('password mutation requires CSRF and preserves server policy violations without echoing secrets', async () => {
  await assert.rejects(() => changeLocalPassword(configuration, 'Current!Aa1', 'NextPassword!Aa1', async () => jsonResponse(200, session), ''), /CSRF/);
  await assert.rejects(
    () => changeLocalPassword(configuration, 'Current!Aa1', 'weak', async (_url, options) => {
      assert.equal(options.headers['X-CSRF-Token'], 'csrf-value');
      assert.equal(options.credentials, 'same-origin');
      return jsonResponse(400, { details: { violations: ['min_length', 'uppercase'] } });
    }, 'INX_XSRF=csrf-value'),
    (error) => error instanceof PasswordPolicyError && error.violations.join(',') === 'min_length,uppercase',
  );
});

test('logout is CSRF protected and accepts already-expired sessions', async () => {
  await logoutLocal(configuration, async (_url, options) => {
    assert.equal(options.method, 'DELETE'); assert.equal(options.headers['X-CSRF-Token'], 'csrf-value');
    return jsonResponse(204, null);
  }, 'INX_XSRF=csrf-value');
  await logoutLocal(configuration, async () => jsonResponse(401, null), 'INX_XSRF=csrf-value');
  await assert.rejects(() => logoutLocal(configuration, async () => jsonResponse(500, null), 'INX_XSRF=csrf-value'), /HTTP 500/);
});


test('login gate wires click and submit synchronously before the session probe resolves', async () => {
  const documentObject = authenticatedDocument();
  documentObject.elements.get('auth-username').value = 'admin';
  documentObject.elements.get('auth-password').value = 'Bootstrap!Aa1';
  let releaseProbe;
  const probe = new Promise((resolve) => { releaseProbe = resolve; });
  let loginCalls = 0;
  const fetchFunction = async (_url, options = {}) => {
    if (options.method === 'GET') return probe;
    loginCalls += 1;
    return jsonResponse(200, session);
  };

  const initialization = initializeLocalAuthentication(documentObject, configuration, fetchFunction);
  const form = documentObject.elements.get('auth-login-form');
  const submit = documentObject.elements.get('auth-login-submit');
  assert.equal(form.dataset.authWired, 'true');
  assert.equal(submit.dataset.authWired, 'true');
  assert.equal(submit.disabled, false);
  assert.equal(submit.attributes.get('aria-disabled'), 'false');
  assert.equal(documentObject.elements.get('auth-gate').hidden, false);

  const click = submit.listeners.get('click');
  assert.equal(typeof click, 'function');
  click({ preventDefault() {} });
  assert.equal(submit.disabled, true);
  assert.equal(submit.attributes.get('aria-busy'), 'true');
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(loginCalls, 1);
  const result = await initialization;
  assert.equal(result.session.username, 'admin');
  releaseProbe(jsonResponse(401, {}));
});

test('login gate exposes backend unavailability and remains retryable', async () => {
  const documentObject = authenticatedDocument();
  documentObject.elements.get('auth-username').value = 'admin';
  documentObject.elements.get('auth-password').value = 'Bootstrap!Aa1';
  let call = 0;
  const fetchFunction = async (_url, options = {}) => {
    call += 1;
    if (call === 1) return jsonResponse(503, {}); // initial session probe
    if (call === 2) return jsonResponse(503, {}); // first login attempt
    assert.equal(options.method, 'POST');
    return jsonResponse(200, session);
  };
  const initialization = initializeLocalAuthentication(documentObject, configuration, fetchFunction);
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(documentObject.elements.get('auth-message').attributes.get('data-i18n'), 'auth.login.unavailable');
  assert.equal(documentObject.elements.get('auth-service-state').hidden, false);

  const submit = documentObject.elements.get('auth-login-submit');
  const click = submit.listeners.get('click');
  click({ preventDefault() {} });
  assert.equal(submit.disabled, true);
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(submit.disabled, false);
  assert.equal(documentObject.elements.get('auth-message').attributes.get('data-i18n'), 'auth.login.unavailable');

  documentObject.elements.get('auth-password').value = 'Bootstrap!Aa1';
  click({ preventDefault() {} });
  const result = await initialization;
  assert.equal(result.session.username, 'admin');
  assert.equal(documentObject.elements.get('auth-service-state').hidden, true);
});

test('authentication gate releases immediately when disabled or when a valid session already exists', async () => {
  const disabled = authenticatedDocument();
  const disabledResult = await initializeLocalAuthentication(disabled, { ...configuration, localAuthEnabled: false }, async () => { throw new Error('must not fetch'); });
  assert.equal(disabledResult.enabled, false);
  assert.equal(disabled.elements.get('app-shell').hidden, false);

  const documentObject = authenticatedDocument();
  const result = await initializeLocalAuthentication(documentObject, configuration, async () => jsonResponse(200, session));
  assert.equal(result.enabled, true);
  assert.equal(result.session.username, 'admin');
  assert.equal(documentObject.elements.get('session-identity').textContent, 'Local Administrator');
  assert.equal(documentObject.elements.get('session-avatar').textContent, 'LA');
  assert.equal(documentObject.elements.get('session-logout').hidden, false);
  assert.equal(documentObject.elements.get('app-shell').hidden, false);
});


test('authentication requests carry a bounded AbortSignal when the platform supports it', async () => {
  let observedSignal = null;
  await readLocalSession(configuration, async (_url, options) => {
    observedSignal = options.signal;
    return jsonResponse(401, {});
  });
  assert.ok(observedSignal);
  assert.equal(typeof observedSignal.aborted, 'boolean');
});


test('login shell uses the secure-area card and hides healthy authentication service state', async () => {
  const fs = await import('node:fs/promises');
  const html = await fs.readFile(new URL('../../src/applications/web/public/index.html', import.meta.url), 'utf8');
  const login = html.slice(html.indexOf('<div id="auth-login-view">'), html.indexOf('<div id="auth-password-view"'));
  assert.match(login, /data-i18n="auth\.title">Secure Area<\/p>/);
  assert.match(login, /<h1 id="auth-title"[^>]*data-i18n="auth\.brandTitle">Sign in to InfraNexum<\/h1>/);
  assert.match(html, /class="[^"]*inx-auth-brand[^"]*"[\s\S]*InfraNexum[\s\S]*Infrastructure Control &amp; Governance Platform/);
  assert.match(html, /class="[^"]*inx-auth-story[^"]*"/);
  assert.match(login, /id="auth-service-state"[^>]*hidden/);
  assert.match(login, /id="auth-login-submit"[^>]*data-auth-wired="false"[^>]*disabled/);
  assert.match(html, /id="auth-password-submit"[^>]*data-auth-wired="false"[^>]*disabled/);
});
