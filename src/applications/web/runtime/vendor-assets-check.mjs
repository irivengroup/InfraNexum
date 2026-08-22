import { fileURLToPath } from 'node:url';
import path from 'node:path';
import process from 'node:process';

import { VendorAssetIntegrityVerifier } from './vendor-assets.mjs';

/** Runs the same fail-closed vendor validation used by the Web startup path. */
export async function checkVendorAssets(staticRoot) {
  const verifier = new VendorAssetIntegrityVerifier(staticRoot);
  return verifier.verify();
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  const staticRoot = process.argv[2];
  if (!staticRoot) {
    console.error('usage: node runtime/vendor-assets-check.mjs <static-root>');
    process.exitCode = 2;
  } else {
    checkVendorAssets(staticRoot)
      .then((result) => console.log(JSON.stringify({ status: 'PASS', ...result })))
      .catch((error) => {
        console.error(JSON.stringify({ status: 'ERROR', code: error.code ?? 'WEB_VENDOR_REDOC_CHECK_FAILED', message: error.message }));
        process.exitCode = 1;
      });
  }
}
