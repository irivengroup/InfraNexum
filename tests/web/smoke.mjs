import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import path from 'node:path';
import process from 'node:process';

const applicationRoot = path.resolve(import.meta.dirname, '..', '..', 'src', 'applications', 'web');
const port = 18_080 + Math.floor(Math.random() * 1_000);
const child = spawn(process.execPath, ['runtime/main.mjs'], {
  cwd: applicationRoot,
  env: {
    ...process.env,
    INFRANEXUM_WEB_LISTEN_ADDRESS: `127.0.0.1:${port}`,
    INFRANEXUM_WEB_STATIC_ROOT: './public',
    INFRANEXUM_WEB_ENVIRONMENT: 'test',
    INFRANEXUM_WEB_API_BASE_URL: 'http://127.0.0.1:9090/api',
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});

let output = '';
child.stdout.on('data', (chunk) => { output += chunk; });
child.stderr.on('data', (chunk) => { output += chunk; });

try {
  let response;
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      response = await fetch(`http://127.0.0.1:${port}/health/ready`);
      if (response.ok) break;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 40));
    }
  }
  assert.ok(response?.ok, `Web runtime did not become ready: ${output}`);
  assert.deepEqual(await response.json(), { status: 'UP' });
  const runtime = await fetch(`http://127.0.0.1:${port}/runtime-config.json`);
  assert.equal((await runtime.json()).version, '2.0.0-alpha.0.100');
  const page = await fetch(`http://127.0.0.1:${port}/`);
  assert.match(await page.text(), /Infrastructure Control &amp; Governance Platform/);
  child.kill('SIGTERM');
  const [code, signal] = await once(child, 'exit');
  assert.equal(signal, null);
  assert.equal(code, 0, output);
  process.stdout.write(`${JSON.stringify({ schema: 'infranexum.web-smoke/v1', status: 'passed', port })}\n`);
} finally {
  if (child.exitCode === null && child.signalCode === null) {
    child.kill('SIGKILL');
  }
}
