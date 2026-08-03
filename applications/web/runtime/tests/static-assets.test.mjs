import assert from 'node:assert/strict';
import { mkdtemp, mkdir, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { StaticAssetStore } from '../static-assets.mjs';

async function fixture() {
  const root = await mkdtemp(path.join(os.tmpdir(), 'infranexum-web-assets-'));
  await mkdir(path.join(root, 'assets'));
  await writeFile(path.join(root, 'index.html'), '<!doctype html><title>InfraNexum</title>');
  await writeFile(path.join(root, 'assets', 'app.12345678.js'), 'export const ready = true;');
  await writeFile(path.join(root, 'assets', 'plain.css'), 'body{}');
  await writeFile(path.join(root, 'assets', 'unknown.bin'), Buffer.from([1, 2, 3]));
  return root;
}

test('asset store requires initialization and a valid root', async () => {
  assert.throws(() => new StaticAssetStore(''), /configuredRoot/);
  const root = await fixture();
  const store = new StaticAssetStore(root);
  await assert.rejects(() => store.read('/'), /not initialized/);
  await store.initialize();
  assert.match((await store.read('/')).body.toString(), /InfraNexum/);
});

test('asset store serves content types, immutable assets, and SPA fallback', async () => {
  const root = await fixture();
  const store = new StaticAssetStore(root);
  await store.initialize();

  const script = await store.read('/assets/app.12345678.js');
  assert.equal(script.contentType, 'text/javascript; charset=utf-8');
  assert.equal(script.cacheControl, 'public, max-age=31536000, immutable');
  assert.match(script.body.toString(), /ready/);

  const css = await store.read('/assets/plain.css');
  assert.equal(css.contentType, 'text/css; charset=utf-8');
  assert.equal(css.cacheControl, 'no-cache');

  const binary = await store.read('/assets/unknown.bin');
  assert.equal(binary.contentType, 'application/octet-stream');
  assert.deepEqual([...binary.body], [1, 2, 3]);

  assert.match((await store.read('/sites/overview')).body.toString(), /InfraNexum/);
  assert.equal(await store.read('/missing.png'), null);
});

test('asset store rejects malformed and escaping paths', async () => {
  const root = await fixture();
  const store = new StaticAssetStore(root);
  await store.initialize();
  for (const value of ['', 'relative', '/bad\\path', '/bad\0path', '/%E0%A4%A', '/%2e%2e/secret']) {
    await assert.rejects(() => store.read(value), /invalid/i, value);
  }
});

test('asset store blocks symlinks escaping the configured root', async () => {
  const root = await fixture();
  const outside = await mkdtemp(path.join(os.tmpdir(), 'infranexum-outside-'));
  await writeFile(path.join(outside, 'secret.txt'), 'secret');
  await symlink(path.join(outside, 'secret.txt'), path.join(root, 'assets', 'link.txt'));
  const store = new StaticAssetStore(root);
  await store.initialize();
  await assert.rejects(() => store.read('/assets/link.txt'), /escapes configured root/);
});

test('asset store returns null for directories and oversized assets', async () => {
  const root = await fixture();
  await mkdir(path.join(root, 'assets', 'folder'));
  await writeFile(path.join(root, 'assets', 'large.js'), Buffer.alloc(16 * 1024 * 1024 + 1));
  const store = new StaticAssetStore(root);
  await store.initialize();
  assert.equal(await store.read('/assets/folder'), null);
  assert.equal(await store.read('/assets/large.js'), null);
});
