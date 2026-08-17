import { setLocalizedElementText } from './i18n.mjs';

const SESSION_PATH = '/v1/iam/local-auth/session';
const PASSWORD_PATH = '/v1/iam/local-auth/password';
const CSRF_COOKIE = 'INX_XSRF';
const AUTH_REQUEST_TIMEOUT_MS = 15_000;

export function csrfToken(cookieString = globalThis.document?.cookie ?? '') {
  for (const pair of String(cookieString).split(';')) {
    const [name, ...rest] = pair.trim().split('=');
    if (name === CSRF_COOKIE) return decodeURIComponent(rest.join('='));
  }
  return null;
}


export function copyrightLabel(currentYear = new Date().getFullYear()) {
  const year = Number(currentYear);
  if (!Number.isInteger(year) || year < 2026 || year > 9999) {
    throw new RangeError('copyright year must be an integer from 2026 through 9999');
  }
  const period = year === 2026 ? '2026' : `2026 - ${year}`;
  return `copyright ${period} Iriven Group. All Right Reserved`;
}

export function renderCopyright(documentObject, currentYear) {
  const element = documentObject?.getElementById?.('auth-copyright');
  if (!element) return false;
  element.textContent = copyrightLabel(currentYear);
  return true;
}

export async function readLocalSession(configuration, fetchFunction = fetch) {
  const response = await requestWithTimeout(fetchFunction, `${configuration.apiBaseUrl}${SESSION_PATH}`, {
    method: 'GET', headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store',
  });
  if (response.status === 401) return null;
  if (!response.ok) throw new Error(`Local session returned HTTP ${response.status}`);
  return validateSession(await response.json());
}

export async function loginLocal(configuration, username, password, fetchFunction = fetch) {
  const response = await requestWithTimeout(fetchFunction, `${configuration.apiBaseUrl}${SESSION_PATH}`, {
    method: 'POST', credentials: 'same-origin', cache: 'no-store',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (response.status === 400 || response.status === 401) throw new AuthenticationError('invalid_credentials');
  if (!response.ok) throw new Error(`Local login returned HTTP ${response.status}`);
  return validateSession(await response.json());
}

export async function changeLocalPassword(configuration, currentPassword, newPassword, fetchFunction = fetch, cookieString) {
  const csrf = csrfToken(cookieString);
  if (!csrf) throw new Error('CSRF token is unavailable');
  const response = await requestWithTimeout(fetchFunction, `${configuration.apiBaseUrl}${PASSWORD_PATH}`, {
    method: 'POST', credentials: 'same-origin', cache: 'no-store',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
    body: JSON.stringify({ currentPassword, newPassword }),
  });
  if (response.status === 401) throw new AuthenticationError('invalid_credentials');
  if (response.status === 400) {
    const payload = await safeJson(response);
    throw new PasswordPolicyError(payload?.details?.violations ?? []);
  }
  if (!response.ok) throw new Error(`Local password change returned HTTP ${response.status}`);
  return validateSession(await response.json());
}

export async function logoutLocal(configuration, fetchFunction = fetch, cookieString) {
  const csrf = csrfToken(cookieString);
  const response = await requestWithTimeout(fetchFunction, `${configuration.apiBaseUrl}${SESSION_PATH}`, {
    method: 'DELETE', credentials: 'same-origin', cache: 'no-store',
    headers: csrf ? { 'X-CSRF-Token': csrf } : {},
  });
  if (![204, 401].includes(response.status)) throw new Error(`Local logout returned HTTP ${response.status}`);
}

export function validateSession(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Local session payload is invalid');
  for (const key of ['sessionId', 'accountId', 'username', 'displayName', 'idleExpiresAt', 'absoluteExpiresAt']) {
    if (typeof value[key] !== 'string' || value[key].length === 0) throw new Error(`Local session field ${key} is invalid`);
  }
  if (typeof value.mustChange !== 'boolean') throw new Error('Local session mustChange is invalid');
  return Object.freeze({ ...value });
}

/**
 * Initialize the local-auth gate as an independent critical path.
 *
 * Event listeners are wired synchronously before the first network await. This is
 * intentional: a slow/stalled session probe must never leave a visible login form
 * without an active submit/click handler. The explicit click handler is a defensive
 * browser path; keyboard/implicit form submission remains supported by the submit
 * listener. Both paths are serialized through one in-flight guard.
 */
export async function initializeLocalAuthentication(
  documentObject,
  configuration,
  fetchFunction = fetch,
) {
  renderCopyright(documentObject);
  const appShell = documentObject.getElementById('app-shell');
  if (!configuration.localAuthEnabled) {
    if (appShell) appShell.hidden = false;
    return Object.freeze({ enabled: false, session: null });
  }

  const gate = documentObject.getElementById('auth-gate');
  const loginForm = documentObject.getElementById('auth-login-form');
  const passwordForm = documentObject.getElementById('auth-password-form');
  const loginSubmit = documentObject.getElementById('auth-login-submit');
  const passwordSubmit = documentObject.getElementById('auth-password-submit');
  if (!gate || !loginForm || !passwordForm || !loginSubmit || !passwordSubmit) {
    throw new Error('Local authentication UI is incomplete');
  }

  const completion = deferred();
  let completed = false;
  let loginBusy = false;
  let passwordBusy = false;
  let interactionEpoch = 0;

  const finish = (authenticated) => {
    if (completed) return;
    completed = true;
    completion.resolve(authenticated);
  };

  const attemptLogin = async () => {
    if (completed || loginBusy) return;
    interactionEpoch += 1;
    const usernameInput = documentObject.getElementById('auth-username');
    const passwordInput = documentObject.getElementById('auth-password');
    const username = usernameInput?.value ?? '';
    const password = passwordInput?.value ?? '';
    if (!String(username).trim() || !String(password)) {
      setAuthMessage(documentObject, 'auth.login.required', true);
      return;
    }

    loginBusy = true;
    setLoginBusy(documentObject, loginSubmit, true);
    setAuthMessage(documentObject, 'auth.login.working', false);
    try {
      const authenticated = await loginLocal(configuration, username, password, fetchFunction);
      setAuthServiceState(documentObject, 'ready');
      if (passwordInput) passwordInput.value = '';
      if (authenticated.mustChange) {
        showPasswordChange(documentObject, authenticated);
        return;
      }
      finish(authenticated);
    } catch (error) {
      if (passwordInput) passwordInput.value = '';
      if (error instanceof AuthenticationError) setAuthServiceState(documentObject, 'ready');
      else setAuthServiceState(documentObject, 'unavailable');
      setAuthMessage(documentObject, error instanceof AuthenticationError ? 'auth.login.failed' : 'auth.login.unavailable', true);
    } finally {
      loginBusy = false;
      setLoginBusy(documentObject, loginSubmit, false);
    }
  };

  const attemptPasswordChange = async () => {
    if (completed || passwordBusy) return;
    interactionEpoch += 1;
    const currentInput = documentObject.getElementById('auth-current-password');
    const newInput = documentObject.getElementById('auth-new-password');
    const confirmInput = documentObject.getElementById('auth-confirm-password');
    const current = currentInput?.value ?? '';
    const next = newInput?.value ?? '';
    const confirmation = confirmInput?.value ?? '';
    if (!current || !next || !confirmation) {
      setAuthMessage(documentObject, 'auth.password.required', true);
      return;
    }
    if (next !== confirmation) {
      setAuthMessage(documentObject, 'auth.password.mismatch', true);
      return;
    }

    passwordBusy = true;
    setPasswordBusy(documentObject, passwordSubmit, true);
    setAuthMessage(documentObject, 'auth.password.working', false);
    try {
      const authenticated = await changeLocalPassword(configuration, current, next, fetchFunction, documentObject.cookie);
      setAuthServiceState(documentObject, 'ready');
      for (const input of [currentInput, newInput, confirmInput]) if (input) input.value = '';
      finish(authenticated);
    } catch (error) {
      for (const input of [currentInput, newInput, confirmInput]) if (input) input.value = '';
      if (!(error instanceof PasswordPolicyError) && !(error instanceof AuthenticationError)) setAuthServiceState(documentObject, 'unavailable');
      else setAuthServiceState(documentObject, 'ready');
      setAuthMessage(documentObject, error instanceof PasswordPolicyError ? 'auth.password.policyFailed' : 'auth.password.failed', true);
    } finally {
      passwordBusy = false;
      setPasswordBusy(documentObject, passwordSubmit, false);
    }
  };

  const onLoginSubmit = (event) => { event?.preventDefault?.(); void attemptLogin(); };
  const onLoginClick = (event) => { event?.preventDefault?.(); void attemptLogin(); };
  const onPasswordSubmit = (event) => { event?.preventDefault?.(); void attemptPasswordChange(); };
  const onPasswordClick = (event) => { event?.preventDefault?.(); void attemptPasswordChange(); };

  loginForm.addEventListener('submit', onLoginSubmit);
  loginSubmit.addEventListener('click', onLoginClick);
  passwordForm.addEventListener('submit', onPasswordSubmit);
  passwordSubmit.addEventListener('click', onPasswordClick);
  markAuthWired(loginForm, loginSubmit, passwordForm, passwordSubmit);

  // The gate is made interactive only after all critical listeners exist.
  if (appShell) appShell.hidden = true;
  gate.hidden = false;
  showLogin(documentObject);
  setAuthServiceState(documentObject, 'checking');

  const probeEpoch = interactionEpoch;
  void readLocalSession(configuration, fetchFunction).then((existing) => {
    if (completed || interactionEpoch !== probeEpoch) return;
    setAuthServiceState(documentObject, 'ready');
    if (!existing) return;
    if (existing.mustChange) showPasswordChange(documentObject, existing);
    else finish(existing);
  }).catch(() => {
    if (completed || interactionEpoch !== probeEpoch) return;
    // Session discovery is advisory: a transient GET failure must not contradict a
    // still-usable login POST path. Only an actual login/password mutation failure
    // marks the authentication service unavailable.
    setAuthServiceState(documentObject, 'ready');
    setAuthMessage(documentObject, 'auth.login.prompt', false);
  });

  const session = await completion.promise;
  removeAuthListeners(loginForm, loginSubmit, passwordForm, passwordSubmit, {
    onLoginSubmit, onLoginClick, onPasswordSubmit, onPasswordClick,
  });
  renderAuthenticated(documentObject, session);
  gate.hidden = true;
  if (appShell) appShell.hidden = false;
  wireLogout(documentObject, configuration, fetchFunction);
  return Object.freeze({ enabled: true, session });
}

function deferred() {
  let resolve;
  const promise = new Promise((resolver) => { resolve = resolver; });
  return Object.freeze({ promise, resolve });
}

function markAuthWired(...elements) {
  for (const element of elements) {
    element.dataset.authWired = 'true';
    element.setAttribute?.('data-auth-wired', 'true');
    if ('disabled' in element) element.disabled = false;
    element.setAttribute?.('aria-disabled', 'false');
  }
}

function removeAuthListeners(loginForm, loginSubmit, passwordForm, passwordSubmit, listeners) {
  loginForm.removeEventListener?.('submit', listeners.onLoginSubmit);
  loginSubmit.removeEventListener?.('click', listeners.onLoginClick);
  passwordForm.removeEventListener?.('submit', listeners.onPasswordSubmit);
  passwordSubmit.removeEventListener?.('click', listeners.onPasswordClick);
}

function setLoginBusy(documentObject, button, busy) {
  if (!button) return;
  button.disabled = busy;
  button.setAttribute('aria-busy', busy ? 'true' : 'false');
  button.setAttribute('data-busy', busy ? 'true' : 'false');
  setLocalizedElementText(documentObject, button, busy ? 'auth.login.working' : 'auth.signIn');
}

function setPasswordBusy(documentObject, button, busy) {
  if (!button) return;
  button.disabled = busy;
  button.setAttribute('aria-busy', busy ? 'true' : 'false');
  button.setAttribute('data-busy', busy ? 'true' : 'false');
  setLocalizedElementText(documentObject, button, busy ? 'auth.password.working' : 'auth.changePassword');
}

function wireLogout(documentObject, configuration, fetchFunction) {
  const menu = documentObject.getElementById('session-menu');
  const trigger = documentObject.getElementById('session-menu-trigger');
  const dropdown = documentObject.getElementById('session-menu-dropdown');
  const button = documentObject.getElementById('session-logout');
  if (!button || !menu || !trigger || !dropdown || button.dataset.authWired === 'true') return;
  button.dataset.authWired = 'true';
  menu.hidden = false;
  button.hidden = false;

  const setOpen = (open, restoreFocus = false) => {
    // Bootstrap's .dropdown-menu remains display:none until .show is present.
    // Keep the native hidden state and Bootstrap visual state synchronized so
    // accessibility semantics cannot diverge from what the operator sees.
    dropdown.hidden = !open;
    dropdown.classList?.toggle?.('show', open);
    menu.classList?.toggle?.('show', open);
    trigger.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (open) button.focus?.();
    else if (restoreFocus) trigger.focus?.();
  };
  const close = (restoreFocus = false) => setOpen(false, restoreFocus);
  const toggle = () => setOpen(dropdown.hidden || !dropdown.classList?.contains?.('show'));
  trigger.addEventListener('click', (event) => { event.preventDefault?.(); event.stopPropagation?.(); toggle(); });
  dropdown.addEventListener?.('keydown', (event) => { if (event.key === 'Escape') { event.preventDefault?.(); close(true); } });
  documentObject.addEventListener?.('click', (event) => { if (!menu.contains?.(event.target)) close(false); });

  button.addEventListener('click', async () => {
    button.disabled = true;
    try { await logoutLocal(configuration, fetchFunction, documentObject.cookie); }
    finally { globalThis.location?.reload?.(); }
  });
}

function renderAuthenticated(documentObject, session) {
  const identity = documentObject.getElementById('session-identity');
  if (identity) identity.textContent = session.displayName;
  const avatar = documentObject.getElementById('session-avatar');
  if (avatar) avatar.textContent = initials(session.displayName);
}

function initials(displayName) {
  const words = String(displayName).trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return 'AD';
  return words.slice(0, 2).map((word) => word[0]?.toUpperCase() ?? '').join('') || 'AD';
}

function showLogin(documentObject) {
  documentObject.getElementById('auth-login-view')?.removeAttribute('hidden');
  documentObject.getElementById('auth-password-view')?.setAttribute('hidden', '');
  setAuthMessage(documentObject, 'auth.login.prompt', false);
  globalThis.setTimeout?.(() => documentObject.getElementById('auth-username')?.focus?.(), 0);
}

function showPasswordChange(documentObject, session) {
  documentObject.getElementById('auth-login-view')?.setAttribute('hidden', '');
  documentObject.getElementById('auth-password-view')?.removeAttribute('hidden');
  const account = documentObject.getElementById('auth-account-name');
  if (account) account.textContent = session.displayName;
  setAuthMessage(documentObject, 'auth.password.prompt', false);
  globalThis.setTimeout?.(() => documentObject.getElementById('auth-current-password')?.focus?.(), 0);
}

function setAuthMessage(documentObject, key, error) {
  const element = documentObject.getElementById('auth-message');
  if (!element) return;
  element.setAttribute('data-i18n', key);
  element.className = `alert ${error ? 'alert-danger' : 'alert-info'} mt-3 mb-2`;
  element.setAttribute('data-state', error ? 'error' : 'info');
  setLocalizedElementText(documentObject, element, key);
}

function setAuthServiceState(documentObject, state) {
  const container = documentObject.getElementById('auth-service-state');
  const text = documentObject.getElementById('auth-service-state-text');
  if (!container || !text) return;
  const normalized = ['checking', 'ready', 'unavailable'].includes(state) ? state : 'unavailable';
  const contextual = normalized === 'unavailable' ? 'danger' : normalized === 'ready' ? 'success' : 'secondary';
  container.className = `alert alert-${contextual} d-flex align-items-center gap-2 py-2`;
  container.setAttribute('data-state', normalized);
  setLocalizedElementText(documentObject, text, `auth.service.${normalized}`);
  const visible = normalized === 'unavailable';
  container.hidden = !visible;
  container.setAttribute('aria-hidden', visible ? 'false' : 'true');
}

async function requestWithTimeout(fetchFunction, url, options) {
  if (typeof globalThis.AbortController !== 'function' || typeof globalThis.setTimeout !== 'function') {
    return fetchFunction(url, options);
  }
  const controller = new globalThis.AbortController();
  const timer = globalThis.setTimeout(() => controller.abort(), AUTH_REQUEST_TIMEOUT_MS);
  try {
    return await fetchFunction(url, { ...options, signal: controller.signal });
  } finally {
    globalThis.clearTimeout?.(timer);
  }
}

async function safeJson(response) { try { return await response.json(); } catch { return null; } }

export class AuthenticationError extends Error {}
export class PasswordPolicyError extends Error {
  constructor(violations) { super('password policy rejected'); this.violations = Object.freeze([...violations]); }
}
