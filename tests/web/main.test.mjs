import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { createWebApplication, run } from '../../src/applications/web/runtime/main.mjs';

class Sink { chunks = []; write(value) { this.chunks.push(value); } }

async function assets() {
  const root = await mkdtemp(path.join(os.tmpdir(), 'infranexum-web-main-'));
  await mkdir(path.join(root, 'assets'));
  await writeFile(path.join(root, 'index.html'), '<!doctype html><title>InfraNexum</title>');
  return root;
}

test('composition root loads application-local version and injects runtime dependencies', async () => {
  const root = await assets();
  const sink = new Sink();
  const application = await createWebApplication({
    environment: {
      INFRANEXUM_WEB_LISTEN_ADDRESS: '127.0.0.1:0',
      INFRANEXUM_WEB_STATIC_ROOT: root,
      INFRANEXUM_WEB_ENVIRONMENT: 'test',
    },
    sink,
  });
  assert.equal(application.state, 'created');
  const base = await application.start();
  assert.equal((await (await fetch(`${base}/api/v1/system/build`)).json()).version, '2.0.0-alpha.0.23');
  await application.stop();
  assert.match(sink.chunks.join(''), /runtime stopped/);
});

test('run starts the process-level application and can be stopped explicitly', async () => {
  const root = await assets();
  const application = await run({
    INFRANEXUM_WEB_LISTEN_ADDRESS: '127.0.0.1:0',
    INFRANEXUM_WEB_STATIC_ROOT: root,
    INFRANEXUM_WEB_ENVIRONMENT: 'test',
  });
  assert.equal(application.state, 'ready');
  await application.stop();
});
