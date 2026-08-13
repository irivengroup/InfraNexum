import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const publicRoot = path.resolve(import.meta.dirname, '..', '..', 'src', 'applications', 'web', 'public');

async function read(relativePath) {
  return readFile(path.join(publicRoot, relativePath), 'utf8');
}

async function readBytes(relativePath) {
  return readFile(path.join(publicRoot, relativePath));
}

test('Web shell vendors Bootstrap 5 locally and loads the InfraNexum theme after it', async () => {
  const [index, bootstrap, theme] = await Promise.all([
    read('index.html'),
    read('assets/vendor/bootstrap-5.3.6.min.css'),
    read('assets/infranexum-theme.css'),
  ]);

  assert.match(bootstrap.slice(0, 300), /Bootstrap\s+v?5\.3\.6/i);
  const bootstrapDigest = createHash('sha256').update(await readBytes('assets/vendor/bootstrap-5.3.6.min.css')).digest('hex');
  assert.equal(bootstrapDigest, 'a31a97d0b41c95bbebb09b7c2329a4c672129f8369d96cb6ad8f638e84250c48');
  assert.doesNotMatch(index, /https?:\/\/[^"']+\.(?:css|js)/i);
  const bootstrapPosition = index.indexOf('/assets/vendor/bootstrap-5.3.6.min.css');
  const themePosition = index.indexOf('/assets/infranexum-theme.css');
  assert.ok(bootstrapPosition >= 0, 'Bootstrap stylesheet must be loaded');
  assert.ok(themePosition > bootstrapPosition, 'InfraNexum theme must override Bootstrap after the framework stylesheet');
  assert.doesNotMatch(index, /\/assets\/bootstrap\.css/, 'legacy theme filename must not masquerade as Bootstrap');
  assert.match(theme, /predecessor palette remains the visual baseline/i);
});

test('theme preserves the predecessor token set while extending it into a professional admin shell', async () => {
  const theme = await read('assets/infranexum-theme.css');
  const requiredTokens = [
    '#003d8f', '#172033', '#f5f7fb', '#d8dfeb', '#e7ebf2', '#006f75',
    '#00a6a6', '#9b1c1c', '#111827', '#1f2937', '#445066', '#edf2fa',
  ];
  for (const token of requiredTokens) {
    assert.match(theme, new RegExp(token, 'i'), `missing predecessor visual token ${token}`);
  }
  for (const selector of ['.inx-app-shell', '.inx-sidebar', '.inx-topbar', '.inx-hero', '.inx-kpi-grid', '.inx-dashboard-grid', '.inx-data-table', '.inx-platform-section', '.inx-side-dialog', '.inx-language-switcher', '.inx-language-menu']) {
    assert.match(theme, new RegExp(selector.replace('.', '\\.')));
  }
  assert.match(theme, /font-family:\s*Inter,\s*ui-sans-serif,\s*system-ui/i);
  assert.match(theme, /:focus-visible/);
  assert.match(theme, /prefers-reduced-motion:\s*reduce/);
  assert.match(theme, /data-bs-theme="dark"/);
});

test('admin dashboard uses semantic regions, responsive Bootstrap primitives and no Bootstrap JavaScript dependency', async () => {
  const index = await read('index.html');
  for (const token of ['btn', 'badge', 'rounded-pill', 'table', 'table-responsive', 'align-middle']) {
    assert.match(index, new RegExp(`class="[^"]*\\b${token}\\b`));
  }
  for (const marker of ['inx-sidebar', 'inx-topbar', 'inx-kpi-grid', 'inx-dashboard-grid', 'organization-workspace']) {
    assert.match(index, new RegExp(marker));
  }
  assert.match(index, /<aside[^>]+aria-label="Primary navigation"/);
  assert.match(index, /<main id="main"/);
  assert.match(index, /aria-live="polite"/);
  assert.match(index, /id="theme-toggle"/);
  assert.match(index, /id="command-palette"/);
  assert.match(index, /id="language-trigger"/);
  assert.match(index, /id="language-menu"/);
  assert.doesNotMatch(index, /id="language-select"/, "native select must not be used for the persistent language switcher");
  assert.match(index, /id="notification-center"/);
  assert.match(index, /id="preferences-dialog"/);
  assert.match(index, /id="platform-insights-title"/);
  assert.match(index, /data-route="overview"/);
  assert.match(index, /data-route="organizations"/);
  assert.doesNotMatch(index, /bootstrap(?:\.bundle)?(?:\.min)?\.js/);
  assert.match(index, /class="skip-link"/);
});

test('vendored Bootstrap license notice is shipped beside the framework asset', async () => {
  const license = await read('assets/vendor/BOOTSTRAP-LICENSE.txt');
  assert.match(license, /The MIT License \(MIT\)/);
  assert.match(license, /The Bootstrap Authors/);
  assert.match(license, /included in\s+all copies or substantial portions/);
});


test('authentication bootstrap is invoked before non-critical dashboard initializers', async () => {
  const bootstrapSource = await read('assets/bootstrap.mjs');
  const authCall = bootstrapSource.lastIndexOf('void bootstrap().then');
  assert.ok(authCall > 0);
  for (const initializer of ['initializePreferences(document)', 'initializeNotificationCenter(document)', 'initializeAdminShell(document']) {
    const position = bootstrapSource.lastIndexOf(initializer);
    assert.ok(position > authCall, `${initializer} must run only after authentication bootstrap`);
  }
  const html = await read('index.html');
  assert.match(html, /id="auth-login-submit"[^>]*data-auth-wired="false"[^>]*disabled/);
});

test('admin shell ships five-locale i18n and keyboard command palette without external dependencies', async () => {
  const [i18n, shell, index] = await Promise.all([read('assets/i18n.mjs'), read('assets/admin-shell.mjs'), read('index.html')]);
  assert.match(i18n, /SUPPORTED_LOCALES[^\n]*\['de', 'en', 'es', 'fr', 'it'\]/);
  assert.match(i18n, /infranexum\.locale/);
  assert.match(shell, /ctrlKey \|\| event\.metaKey/);
  assert.match(shell, /ArrowDown/);
  assert.match(shell, /ArrowUp/);
  assert.match(shell, /aria-activedescendant/);
  assert.match(index, /Ctrl K/);
  assert.doesNotMatch(index, /https?:\/\/[^"']+\.(?:css|js)/i);
});
