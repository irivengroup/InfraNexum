import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import process from 'node:process';

import { WebRuntimeConfiguration } from './config.mjs';
import { JsonLogger } from './logger.mjs';
import { StaticAssetStore } from './static-assets.mjs';
import { WebApplication } from './web-application.mjs';

/** Composition root: validates configuration before constructing runtime dependencies. */
export async function createWebApplication({ environment = process.env, sink = process.stdout } = {}) {
  const applicationDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const repositoryDirectory = path.resolve(applicationDirectory, '..', '..', '..');
  const version = (await readFile(path.join(repositoryDirectory, 'VERSION'), 'utf8')).trim();
  const configuration = WebRuntimeConfiguration.fromEnvironment(environment, {
    version,
    baseDirectory: applicationDirectory,
  });
  const logger = new JsonLogger({ sink });
  const assets = new StaticAssetStore(configuration.staticRoot);
  return new WebApplication({ configuration, assets, logger });
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
