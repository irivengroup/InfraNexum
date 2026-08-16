import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const publicRoot = path.resolve(import.meta.dirname, '..', '..', 'src', 'applications', 'web', 'public');

async function read(relativePath) { return readFile(path.join(publicRoot, relativePath), 'utf8'); }
async function readBytes(relativePath) { return readFile(path.join(publicRoot, relativePath)); }

test('Web shell vendors Bootstrap 5 locally and layers a bounded InfraNexum design system after it', async () => {
  const [index, bootstrap, theme] = await Promise.all([
    read('index.html'), read('assets/vendor/bootstrap-5.3.6.min.css'), read('assets/infranexum-theme.css'),
  ]);
  assert.match(bootstrap.slice(0, 300), /Bootstrap\s+v?5\.3\.6/i);
  const digest = createHash('sha256').update(await readBytes('assets/vendor/bootstrap-5.3.6.min.css')).digest('hex');
  assert.equal(digest, 'a31a97d0b41c95bbebb09b7c2329a4c672129f8369d96cb6ad8f638e84250c48');
  assert.doesNotMatch(index, /https?:\/\/[^"']+\.(?:css|js)/i);
  assert.ok(index.indexOf('/assets/infranexum-theme.css') > index.indexOf('/assets/vendor/bootstrap-5.3.6.min.css'));
  for (const token of ['--inx-midnight', '--inx-blue', '--inx-turquoise', '--inx-orange', '--inx-surface', '--inx-ink']) {
    assert.match(theme, new RegExp(`${token}\\s*:`));
  }
  for (const component of ['inx-sidebar', 'inx-nav-link', 'inx-topbar', 'inx-hero', 'inx-kpi-card', 'inx-workspace', 'inx-auth-card']) {
    assert.match(index, new RegExp(`\\b${component}\\b`));
    assert.match(theme, new RegExp(`\\.${component}\\b`));
  }
});

test('theme applies the IONOS palette with readable sidebar states and Bootstrap component integration', async () => {
  const theme = await read('assets/infranexum-theme.css');
  for (const token of ['#001b41', '#003d8f', '#11c7e6', '#ffaa00', '#ffffff']) {
    assert.match(theme, new RegExp(token, 'i'));
  }
  for (const selector of ['.btn-primary', '.btn-outline-primary', '.nav-pills', '.card', '.table', '.alert', '.form-control', '.form-select']) {
    assert.match(theme, new RegExp(selector.replace('.', '\\.')));
  }
  assert.match(theme, /prefers-reduced-motion:\s*reduce/);
  assert.match(theme, /data-bs-theme="dark"/);
  assert.match(theme, /\.inx-sidebar-nav \.inx-nav-link[\s\S]*color:\s*#d9e8f8\s*!important/);
  assert.match(theme, /\.inx-sidebar-nav \.inx-nav-link\.active[\s\S]*color:\s*#ffffff\s*!important/);
  assert.match(theme, /\.inx-sidebar-nav \.inx-nav-link\.active[\s\S]*#7fe8f5/);
  assert.doesNotMatch(theme, /\.inx-sidebar-nav \.inx-nav-link(?:\.active)?[^}]*color:\s*var\(--inx-blue\)/);
});

test('admin dashboard keeps Bootstrap structure while applying product-specific premium component classes', async () => {
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
  assert.match(index, /id="notification-center"[^>]*class="[^"]*inx-notification-panel/);
  assert.match(index, /id="notification-trigger"[\s\S]*?<svg class="inx-topbar-icon"/);
  assert.match(index, /id="preferences-dialog"[^>]*class="[^"]*inx-settings-panel/);
  assert.match(index, /id="preference-layout"/);
  assert.match(index, /id="preference-theme"/);
  assert.doesNotMatch(index, /id="preference-contrast"/);
  assert.match(index, /id="language-current"[^>]*>English EN<\/span>/);
  assert.match(index, /class="[^"]*\binx-sidebar\b/);
  assert.match(index, /class="[^"]*\binx-workspace\b/);
  assert.doesNotMatch(index, /<article class="col\s+card/);
  assert.doesNotMatch(index, /\sstyle="/);
  assert.match(index, /class="[^"]*col-12 col-lg-2[^\"]*inx-sidebar/);
  assert.doesNotMatch(index, /bootstrap(?:\.bundle)?(?:\.min)?\.js/);
});

test('vendored Bootstrap license notice is shipped beside the framework asset', async () => {
  const license = await read('assets/vendor/BOOTSTRAP-LICENSE.txt');
  assert.match(license, /The MIT License \(MIT\)/);
  assert.match(license, /The Bootstrap Authors/);
});

test('global shell controls are wired before asynchronous business bootstrap while authentication remains fail-safe', async () => {
  const bootstrapSource = await read('assets/bootstrap.mjs');
  const bootstrapCall = bootstrapSource.lastIndexOf('void bootstrap({ notificationCenter, preferenceController })');
  assert.ok(bootstrapCall > 0);
  for (const initializer of ['initializePreferences(document)', 'initializeStableSelects(document)', 'initializeNotificationCenter(document)', 'initializeAdminShell(document']) {
    const position = bootstrapSource.lastIndexOf(initializer);
    assert.ok(position > 0 && position < bootstrapCall, `${initializer} must be wired before asynchronous bootstrap`);
  }
  for (const initializer of ['initializeRsotWorkspace(document', 'initializeItamWorkspace(document', 'initializeDcimWorkspace(document', 'initializeDdiIpamWorkspace(document']) {
    assert.ok(bootstrapSource.lastIndexOf(initializer) > bootstrapCall, `${initializer} must remain after validated bootstrap`);
  }
  const html = await read('index.html');
  assert.match(html, /id="auth-login-submit"[^>]*data-auth-wired="false"[^>]*disabled/);
  assert.match(html, /id="preferences-save"[^>]*type="button"/);
});

test('admin shell ships five-locale i18n and Bootstrap dropdown state without external dependencies', async () => {
  const [i18n, shell, index] = await Promise.all([read('assets/i18n.mjs'), read('assets/admin-shell.mjs'), read('index.html')]);
  assert.match(i18n, /SUPPORTED_LOCALES[^\n]*\['de', 'en', 'es', 'fr', 'it'\]/);
  assert.match(i18n, /menu\.classList\?\.add\?\.\('show'\)/);
  assert.match(i18n, /menu\.classList\?\.remove\?\.\('show'\)/);
  assert.match(shell, /ctrlKey \|\| event\.metaKey/);
  assert.match(shell, /ArrowDown/);
  assert.match(index, /Ctrl K/);
  assert.match(index, />Deutsch<\/span><strong>DE<\/strong>/);
  assert.match(index, />Français<\/span><strong>FR<\/strong>/);
  assert.doesNotMatch(index, /🇫🇷|🇬🇧|🇩🇪|🇪🇸|🇮🇹/);
  assert.doesNotMatch(index, /aria-hidden="true">⌄<\/span>/);
  assert.doesNotMatch(index, /https?:\/\/[^"']+\.(?:css|js)/i);
});

test('premium administration shell keeps Bootstrap-native responsive navigation, tabs and coherent utility composition', async () => {
  const [index, shell, theme] = await Promise.all([read('index.html'), read('assets/admin-shell.mjs'), read('assets/infranexum-theme.css')]);
  assert.match(index, /id="primary-sidebar"[^>]*class="[^"]*d-none d-lg-flex/);
  assert.match(index, /id="sidebar-toggle"[^>]*class="[^"]*d-lg-none/);
  assert.match(index, /id="sidebar-backdrop"[^>]*class="[^"]*position-fixed/);
  assert.match(shell, /initializeResponsiveNavigation/);
  assert.match(theme, /\.inx-sidebar[\s\S]*position:\s*fixed/);
  assert.match(index, /nav nav-underline gap-3 mb-4 border-bottom/);
  assert.match(theme, /\.nav-underline \.nav-link\.active[\s\S]*var\(--inx-turquoise\)/);
  assert.match(theme, /\.table > thead[\s\S]*text-transform:\s*uppercase/);
  assert.match(theme, /\.form-control:focus,\.form-select:focus/);
  assert.match(theme, /\.inx-settings-panel[\s\S]*inset:\s*0 0 0 auto/);
  assert.match(theme, /data-layout="fluid"[\s\S]*max-width:\s*none/);
  assert.doesNotMatch(theme, /data-contrast=/);
  assert.match(theme, /\.inx-auth-title[\s\S]*color:\s*var\(--inx-midnight\)\s*!important/);
  assert.match(index, /inx-auth-story-title/);
  assert.doesNotMatch(index, /inx-auth-capabilities/);
  assert.match(theme, /\.inx-auth-story-title[\s\S]*font-size:\s*clamp\(2\.15rem/);
  assert.match(theme, /\.inx-notification-panel[\s\S]*inset:\s*0 0 0 auto/);
  assert.match(theme, /data-density="compact"/);
  assert.match(theme, /data-navigation="compact"/);
  assert.match(theme, /#identity-access-workspace > \.row > aside \[role="tablist"\] \.nav-link[\s\S]*width:\s*100%/);

  const conflictPatterns = [
    [/^gap-/, 'gap'], [/^align-items-/, 'align-items'], [/^justify-content-/, 'justify-content'],
    [/^h[1-6]$/, 'heading'], [/^shadow(?:-|$)/, 'shadow'],
  ];
  for (const match of index.matchAll(/class="([^"]+)"/g)) {
    const classes = match[1].split(/\s+/).filter(Boolean);
    for (const [pattern, label] of conflictPatterns) {
      const values = [...new Set(classes.filter((value) => pattern.test(value)))];
      assert.ok(values.length <= 1, `conflicting ${label} utilities in class="${match[1]}"`);
    }
  }
});
