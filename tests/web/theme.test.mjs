import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const publicRoot = path.resolve(import.meta.dirname, '..', '..', 'src', 'applications', 'web', 'public');

async function read(relativePath) { return readFile(path.join(publicRoot, relativePath), 'utf8'); }
async function readBytes(relativePath) { return readFile(path.join(publicRoot, relativePath)); }

test('Web shell vendors Bootstrap 5 locally and loads only a Bootstrap-native InfraNexum theme override after it', async () => {
  const [index, bootstrap, theme] = await Promise.all([
    read('index.html'), read('assets/vendor/bootstrap-5.3.6.min.css'), read('assets/infranexum-theme.css'),
  ]);
  assert.match(bootstrap.slice(0, 300), /Bootstrap\s+v?5\.3\.6/i);
  const digest = createHash('sha256').update(await readBytes('assets/vendor/bootstrap-5.3.6.min.css')).digest('hex');
  assert.equal(digest, 'a31a97d0b41c95bbebb09b7c2329a4c672129f8369d96cb6ad8f638e84250c48');
  assert.doesNotMatch(index, /https?:\/\/[^"']+\.(?:css|js)/i);
  assert.ok(index.indexOf('/assets/infranexum-theme.css') > index.indexOf('/assets/vendor/bootstrap-5.3.6.min.css'));
  assert.doesNotMatch(theme, /\.inx-[A-Za-z0-9_-]+/);
  assert.doesNotMatch(theme, /--inx-[A-Za-z0-9_-]+/);
  for (const token of theme.matchAll(/(--[A-Za-z0-9_-]+)\s*:/g)) assert.match(token[1], /^--bs-/);
});

test('theme preserves the approved InfraNexum/IONOS-inspired palette through Bootstrap variables and native components', async () => {
  const theme = await read('assets/infranexum-theme.css');
  for (const token of ['#003d8f', '#001b41', '#11', '#ffaa00', '#172033', '#f5f7fb', '#d8dfeb', '#006f75', '#00a6a6', '#9b1c1c', '#111827', '#edf2fa']) {
    assert.match(theme, new RegExp(token, 'i'));
  }
  for (const selector of ['.btn-primary', '.btn-outline-primary', '.nav-pills', '.card', '.table', '.alert', '.form-control', '.form-select']) {
    assert.match(theme, new RegExp(selector.replace('.', '\\.')));
  }
  assert.match(theme, /prefers-reduced-motion:\s*reduce/);
  assert.match(theme, /data-bs-theme="dark"/);
});

test('admin dashboard uses Bootstrap grid/components, semantic regions and native accessibility utilities', async () => {
  const index = await read('index.html');
  for (const token of ['container-fluid', 'row', 'col-lg-2', 'nav', 'nav-pills', 'card', 'alert', 'btn', 'badge', 'table', 'table-responsive', 'form-control', 'form-select']) {
    assert.match(index, new RegExp(`class="[^"]*\\b${token}\\b`));
  }
  assert.match(index, /<aside[^>]+aria-label="Primary navigation"/);
  assert.match(index, /<main id="main"/);
  assert.match(index, /id="app-shell"[^>]*hidden/);
  assert.match(index, /visually-hidden-focusable/);
  assert.match(index, /id="theme-toggle"/);
  assert.match(index, /id="language-menu"[^>]*class="[^"]*dropdown-menu/);
  assert.match(index, /id="notification-center"/);
  assert.doesNotMatch(index, /class="[^"]*\binx-/);
  assert.doesNotMatch(index, /bootstrap(?:\.bundle)?(?:\.min)?\.js/);
});

test('vendored Bootstrap license notice is shipped beside the framework asset', async () => {
  const license = await read('assets/vendor/BOOTSTRAP-LICENSE.txt');
  assert.match(license, /The MIT License \(MIT\)/);
  assert.match(license, /The Bootstrap Authors/);
});

test('authentication bootstrap remains the critical path before non-critical dashboard initializers', async () => {
  const bootstrapSource = await read('assets/bootstrap.mjs');
  const authCall = bootstrapSource.lastIndexOf('void bootstrap().then');
  assert.ok(authCall > 0);
  for (const initializer of ['initializePreferences(document)', 'initializeNotificationCenter(document)', 'initializeAdminShell(document']) {
    assert.ok(bootstrapSource.lastIndexOf(initializer) > authCall);
  }
  const html = await read('index.html');
  assert.match(html, /id="auth-login-submit"[^>]*data-auth-wired="false"[^>]*disabled/);
});

test('admin shell ships five-locale i18n and Bootstrap dropdown state without external dependencies', async () => {
  const [i18n, shell, index] = await Promise.all([read('assets/i18n.mjs'), read('assets/admin-shell.mjs'), read('index.html')]);
  assert.match(i18n, /SUPPORTED_LOCALES[^\n]*\['de', 'en', 'es', 'fr', 'it'\]/);
  assert.match(i18n, /menu\.classList\?\.add\?\.\('show'\)/);
  assert.match(i18n, /menu\.classList\?\.remove\?\.\('show'\)/);
  assert.match(shell, /ctrlKey \|\| event\.metaKey/);
  assert.match(shell, /ArrowDown/);
  assert.match(index, /Ctrl K/);
  assert.doesNotMatch(index, /https?:\/\/[^"']+\.(?:css|js)/i);
});
