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

test('Web shell vendors Bootstrap 5 locally and loads only the adapted predecessor theme after it', async () => {
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
  for (const legacySelector of ['class=\"masthead\"', 'class=\"status-card\"', 'class=\"eyebrow\"']) {
    assert.ok(!index.includes(legacySelector), `legacy predecessor template selector must not be imported: ${legacySelector}`);
  }
  assert.doesNotMatch(index, /\/assets\/bootstrap\.css/, 'legacy theme filename must not masquerade as Bootstrap');
  assert.match(theme, /Only the theme layer is retained here/i);
});

test('adapted theme preserves the complete visual token set from the archived predecessor theme', async () => {
  const theme = await read('assets/infranexum-theme.css');
  const requiredTokens = [
    '#003d8f', // masthead / primary
    '#172033', // text
    '#f5f7fb', // page
    '#d8dfeb', // card border
    '#e7ebf2', // divider
    '#006f75', // eyebrow / accent
    '#00a6a6', // focus
    '#9b1c1c', // error
    '#111827', // dark page
    '#1f2937', // dark surface
    '#445066', // dark border
    '#edf2fa', // dark text
  ];

  for (const token of requiredTokens) {
    assert.match(theme, new RegExp(token, 'i'), `missing archived predecessor theme token ${token}`);
  }
  assert.match(theme, /font-family:\s*Inter,\s*ui-sans-serif,\s*system-ui/i);
  assert.match(theme, /line-height:\s*1\.5/);
  assert.match(theme, /:focus-visible/);
  assert.match(theme, /outline:\s*3px\s+solid\s+var\(--inx-focus\)/);
  assert.match(theme, /prefers-color-scheme:\s*dark/);
  assert.match(theme, /max-width:\s*36rem/);
});

test('InfraNexum markup uses Bootstrap responsive primitives while the adapted theme owns only visual styling', async () => {
  const index = await read('index.html');
  for (const token of ['navbar', 'navbar-brand', 'card', 'row', 'col-sm-4', 'col-sm-8']) {
    assert.match(index, new RegExp(`class="[^"]*\\b${token}\\b`));
  }
  assert.doesNotMatch(index, /bootstrap(?:\.bundle)?(?:\.min)?\.js/);
  assert.match(index, /class="skip-link"/);
  assert.match(index, /aria-live="polite"/);
});

test('vendored Bootstrap license notice is shipped beside the framework asset', async () => {
  const license = await read('assets/vendor/BOOTSTRAP-LICENSE.txt');
  assert.match(license, /The MIT License \(MIT\)/);
  assert.match(license, /The Bootstrap Authors/);
  assert.match(license, /included in\s+all copies or substantial portions/);
});
