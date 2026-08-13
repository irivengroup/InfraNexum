import assert from 'node:assert/strict';
import test from 'node:test';

import {
  IdentityAccessApiError,
  identityAccessRequest,
} from '../../src/applications/web/public/assets/identity-access.mjs';

const configuration = Object.freeze({ apiBaseUrl: '/api', identityAccessEnabled: true });

function jsonResponse(status, payload) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() { return payload; },
  };
}

test('IAM GET uses the authenticated same-origin channel without CSRF mutation headers', async () => {
  let observed;
  const result = await identityAccessRequest(configuration, '/v1/iam/users?limit=10', {
    fetchFunction: async (url, options) => {
      observed = { url, options };
      return jsonResponse(200, [{ id: '01900000-0000-7000-8000-000000000001' }]);
    },
    cookieString: 'INX_XSRF=ignored-on-get',
  });
  assert.equal(observed.url, '/api/v1/iam/users?limit=10');
  assert.equal(observed.options.method, 'GET');
  assert.equal(observed.options.credentials, 'same-origin');
  assert.equal(observed.options.cache, 'no-store');
  assert.equal(observed.options.headers['X-CSRF-Token'], undefined);
  assert.equal(observed.options.body, undefined);
  assert.equal(result.length, 1);
});

test('IAM mutations require and forward the decoded CSRF token', async () => {
  let observed;
  await identityAccessRequest(configuration, '/v1/iam/users', {
    method: 'POST',
    body: { login: 'operator' },
    cookieString: 'other=x; INX_XSRF=csrf%20token',
    fetchFunction: async (url, options) => {
      observed = { url, options };
      return jsonResponse(201, { id: '01900000-0000-7000-8000-000000000001' });
    },
  });
  assert.equal(observed.url, '/api/v1/iam/users');
  assert.equal(observed.options.headers['X-CSRF-Token'], 'csrf token');
  assert.equal(observed.options.headers['Content-Type'], 'application/json');
  assert.equal(observed.options.body, JSON.stringify({ login: 'operator' }));

  await assert.rejects(
    identityAccessRequest(configuration, '/v1/iam/users', {
      method: 'POST', body: {}, cookieString: '', fetchFunction: async () => jsonResponse(500, {}),
    }),
    /CSRF token is unavailable/,
  );
});

test('IAM problem responses preserve HTTP status, stable problem code and safe detail', async () => {
  await assert.rejects(
    identityAccessRequest(configuration, '/v1/iam/users', {
      fetchFunction: async () => jsonResponse(403, {
        code: 'IAM_AUTHORIZATION_DENIED', detail: 'Permission is not effective for this scope',
      }),
    }),
    (error) => error instanceof IdentityAccessApiError
      && error.status === 403
      && error.code === 'IAM_AUTHORIZATION_DENIED'
      && error.message === 'Permission is not effective for this scope',
  );
});

test('IAM browser adapter is capability-gated and rejects paths outside its API namespace', async () => {
  await assert.rejects(
    identityAccessRequest({ apiBaseUrl: '/api', identityAccessEnabled: false }, '/v1/iam/users'),
    /capability is disabled/,
  );
  await assert.rejects(
    identityAccessRequest(configuration, '/system/build'),
    /path must start with \/v1\//,
  );
});
