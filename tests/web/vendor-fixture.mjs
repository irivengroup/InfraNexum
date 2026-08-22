import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

import {
  REDOC_VENDOR_BUNDLE_SIZE,
  REDOC_VENDOR_COMMIT,
  REDOC_VENDOR_RELATIVE_DIRECTORY,
  REDOC_VENDOR_VERSION,
} from '../../src/applications/web/runtime/vendor-assets.mjs';

/** Creates a deterministic synthetic vendor tree for runtime integrity tests only. */
export async function writeSyntheticRedocVendor(staticRoot, overrides = {}) {
  const directory = path.join(staticRoot, REDOC_VENDOR_RELATIVE_DIRECTORY);
  await mkdir(directory, { recursive: true });

  const prefix = Buffer.from(`/*! ReDoc Version: ${REDOC_VENDOR_VERSION}\n * Commit: ${REDOC_VENDOR_COMMIT}\n */\nwindow.Redoc = {};\n`, 'utf8');
  const bundle = Buffer.alloc(REDOC_VENDOR_BUNDLE_SIZE, 0x20);
  prefix.copy(bundle, 0);
  const license = Buffer.from('MIT License\n\nPermission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files.\n', 'utf8');
  const notice = Buffer.from('ReDoc third-party license notices.\n'.repeat(8), 'utf8');

  const files = {
    'redoc.standalone.js': overrides.bundle ?? bundle,
    LICENSE: overrides.license ?? license,
    'redoc.standalone.js.LICENSE.txt': overrides.notice ?? notice,
  };
  for (const [filename, bytes] of Object.entries(files)) {
    await writeFile(path.join(directory, filename), bytes);
  }

  const manifest = {
    schema: 'infranexum.vendor.redoc/v1',
    component: 'redoc',
    version: REDOC_VENDOR_VERSION,
    upstreamCommit: REDOC_VENDOR_COMMIT,
    source: {
      npmPackage: 'redoc',
      npmVersion: REDOC_VENDOR_VERSION,
      runtimeNetworkRequired: false,
    },
    files: Object.entries(files).map(([filename, bytes]) => ({
      path: filename,
      size: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    })),
  };
  const effectiveManifest = overrides.manifest ? overrides.manifest(manifest) : manifest;
  await writeFile(path.join(directory, 'manifest.json'), `${JSON.stringify(effectiveManifest, null, 2)}\n`, 'utf8');
  return { directory, files, manifest: effectiveManifest };
}
