import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import process from 'node:process';

import { WebRuntimeConfiguration } from './config.mjs';
import { JsonLogger } from './logger.mjs';
import { StaticAssetStore } from './static-assets.mjs';
import { VendorAssetIntegrityVerifier } from './vendor-assets.mjs';
import { WebApplication } from './web-application.mjs';

/** Composition root: validates configuration before constructing runtime dependencies. */
export async function createWebApplication({ environment = process.env, sink = process.stdout } = {}) {
  const applicationDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  // Runtime metadata is application-local so relocating the module cannot change
  // how the version is resolved. The release gates keep package.json and VERSION aligned.
  const packageDocument = JSON.parse(await readFile(path.join(applicationDirectory, 'package.json'), 'utf8'));
  const version = typeof packageDocument.version === 'string' ? packageDocument.version.trim() : '';
  if (!version) {
    throw new Error('web package version is required');
  }
  const configuration = WebRuntimeConfiguration.fromEnvironment(environment, {
    version,
    baseDirectory: applicationDirectory,
  });
  const logger = new JsonLogger({ sink });
  const assets = new StaticAssetStore(configuration.staticRoot);
  const vendorVerifier = new VendorAssetIntegrityVerifier(configuration.staticRoot);
  return new WebApplication({ configuration, assets, logger, vendorVerifier });
}

export async function run(environment = process.env) {
  const application = await createWebApplication({ environment });
  const shutdown = async (signal) => {
    try {
      await application.stop();
      process.exitCode = 0;
    } catch (error) {
      console.error(JSON.stringify({ level: 'ERROR', component: 'web', message: 'shutdown failed', signal, error: error.message }));
      process.exitCode = 1;
    }
  };
  process.once('SIGINT', () => void shutdown('SIGINT'));
  process.once('SIGTERM', () => void shutdown('SIGTERM'));
  await application.start();
  return application;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  run().catch((error) => {
    console.error(JSON.stringify({ level: 'ERROR', component: 'web', message: 'startup failed', error: error.message }));
    process.exitCode = 1;
  });
}
