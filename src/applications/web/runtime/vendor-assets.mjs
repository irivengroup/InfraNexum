import { createHash } from 'node:crypto';
import { lstat, readFile, realpath } from 'node:fs/promises';
import path from 'node:path';

export const REDOC_VENDOR_VERSION = '2.5.3';
export const REDOC_VENDOR_COMMIT = '1b2591e';
export const REDOC_VENDOR_BUNDLE_SIZE = 1_097_271;
export const REDOC_VENDOR_RELATIVE_DIRECTORY = path.join('assets', 'vendor', 'redoc', REDOC_VENDOR_VERSION);
export const REDOC_VENDOR_MANIFEST_NAME = 'manifest.json';

const EXPECTED_FILES = Object.freeze(new Map([
  ['redoc.standalone.js', Object.freeze({ exactSize: REDOC_VENDOR_BUNDLE_SIZE, role: 'bundle' })],
  ['LICENSE', Object.freeze({ minimumSize: 128, role: 'license' })],
  ['redoc.standalone.js.LICENSE.txt', Object.freeze({ minimumSize: 128, role: 'notice' })],
]));
const SHA256_PATTERN = /^[0-9a-f]{64}$/;

/**
 * Verifies the immutable, local ReDoc distribution before the Web listener opens.
 *
 * The runtime intentionally performs no network access. The build-time acquisition
 * process owns provenance validation; this boundary proves that the files on disk
 * still match the signed-off manifest and that the bundle is the expected ReDoc
 * version/commit rather than a stub or silently substituted asset.
 */
export class VendorAssetIntegrityVerifier {
  #staticRoot;

  constructor(staticRoot) {
    if (typeof staticRoot !== 'string' || staticRoot.length === 0) {
      throw new TypeError('staticRoot must be a non-empty string');
    }
    this.#staticRoot = path.resolve(staticRoot);
  }

  async verify() {
    const root = await realpath(this.#staticRoot);
    const vendorDirectory = path.join(root, REDOC_VENDOR_RELATIVE_DIRECTORY);
    const resolvedVendorDirectory = await this.#regularDirectory(vendorDirectory, root);
    const manifestPath = path.join(resolvedVendorDirectory, REDOC_VENDOR_MANIFEST_NAME);
    const manifest = await this.#manifest(manifestPath, resolvedVendorDirectory);

    for (const [filename, policy] of EXPECTED_FILES) {
      const entry = manifest.files.find((candidate) => candidate.path === filename);
      await this.#verifyFile(resolvedVendorDirectory, entry, policy);
    }
    return Object.freeze({
      component: manifest.component,
      version: manifest.version,
      upstreamCommit: manifest.upstreamCommit,
      directory: resolvedVendorDirectory,
    });
  }

  async #manifest(manifestPath, vendorDirectory) {
    const manifestBytes = await this.#readRegularFile(manifestPath, vendorDirectory, 64 * 1024);
    let document;
    try {
      document = JSON.parse(manifestBytes.toString('utf8'));
    } catch (error) {
      throw vendorError('WEB_VENDOR_REDOC_MANIFEST_INVALID', 'ReDoc vendor manifest is not valid JSON', error);
    }
    if (!document || typeof document !== 'object' || Array.isArray(document)) {
      throw vendorError('WEB_VENDOR_REDOC_MANIFEST_INVALID', 'ReDoc vendor manifest must be an object');
    }
    if (document.schema !== 'infranexum.vendor.redoc/v1'
      || document.component !== 'redoc'
      || document.version !== REDOC_VENDOR_VERSION
      || document.upstreamCommit !== REDOC_VENDOR_COMMIT
      || !Array.isArray(document.files)) {
      throw vendorError('WEB_VENDOR_REDOC_MANIFEST_INVALID', 'ReDoc vendor manifest identity is invalid');
    }
    if (document.files.length !== EXPECTED_FILES.size) {
      throw vendorError('WEB_VENDOR_REDOC_MANIFEST_INVALID', 'ReDoc vendor manifest must enumerate exactly the approved files');
    }
    const names = new Set();
    for (const entry of document.files) {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)
        || !EXPECTED_FILES.has(entry.path)
        || names.has(entry.path)
        || !Number.isSafeInteger(entry.size)
        || entry.size <= 0
        || !SHA256_PATTERN.test(entry.sha256 ?? '')) {
        throw vendorError('WEB_VENDOR_REDOC_MANIFEST_INVALID', 'ReDoc vendor manifest file entry is invalid');
      }
      names.add(entry.path);
    }
    return document;
  }

  async #verifyFile(vendorDirectory, entry, policy) {
    const candidate = path.join(vendorDirectory, entry.path);
    const bytes = await this.#readRegularFile(candidate, vendorDirectory, 16 * 1024 * 1024);
    if (bytes.length !== entry.size) {
      throw vendorError('WEB_VENDOR_REDOC_SIZE_MISMATCH', `ReDoc vendor file size mismatch: ${entry.path}`);
    }
    if (policy.exactSize !== undefined && bytes.length !== policy.exactSize) {
      throw vendorError('WEB_VENDOR_REDOC_SIZE_MISMATCH', `ReDoc bundle size is not the certified ${policy.exactSize} bytes`);
    }
    if (policy.minimumSize !== undefined && bytes.length < policy.minimumSize) {
      throw vendorError('WEB_VENDOR_REDOC_FILE_INVALID', `ReDoc vendor file is unexpectedly small: ${entry.path}`);
    }
    const digest = createHash('sha256').update(bytes).digest('hex');
    if (digest !== entry.sha256) {
      throw vendorError('WEB_VENDOR_REDOC_SHA256_MISMATCH', `ReDoc vendor SHA-256 mismatch: ${entry.path}`);
    }
    if (policy.role === 'bundle') {
      // The upstream ReDoc identity banner is emitted deep in the minified bundle,
      // not near the file header. The file is already strictly size-bounded above,
      // so scanning the complete certified bundle is deterministic and safe.
      const marker = bytes.toString('utf8');
      if (!marker.includes('ReDoc Version:')
        || !marker.includes(REDOC_VENDOR_VERSION)
        || !marker.includes('Commit:')
        || !marker.includes(REDOC_VENDOR_COMMIT)) {
        throw vendorError('WEB_VENDOR_REDOC_IDENTITY_MISMATCH', 'ReDoc bundle version/commit markers are invalid');
      }
    } else if (policy.role === 'license') {
      const text = bytes.toString('utf8');
      if (!/MIT License/i.test(text) || !/Permission is hereby granted/i.test(text)) {
        throw vendorError('WEB_VENDOR_REDOC_LICENSE_INVALID', 'ReDoc LICENSE does not contain the expected MIT grant');
      }
    }
  }

  async #regularDirectory(candidate, root) {
    let metadata;
    try {
      metadata = await lstat(candidate);
    } catch (error) {
      throw vendorError('WEB_VENDOR_REDOC_DIRECTORY_INVALID', 'ReDoc vendor directory is missing or inaccessible', error);
    }
    if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
      throw vendorError('WEB_VENDOR_REDOC_DIRECTORY_INVALID', 'ReDoc vendor directory must be a real directory');
    }
    const resolved = await realpath(candidate);
    if (!isInside(root, resolved)) {
      throw vendorError('WEB_VENDOR_REDOC_DIRECTORY_INVALID', 'ReDoc vendor directory escapes the static root');
    }
    return resolved;
  }

  async #readRegularFile(candidate, root, maximumBytes) {
    let metadata;
    try {
      metadata = await lstat(candidate);
    } catch (error) {
      throw vendorError('WEB_VENDOR_REDOC_FILE_INVALID', `ReDoc vendor file is missing or inaccessible: ${path.basename(candidate)}`, error);
    }
    if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size <= 0 || metadata.size > maximumBytes) {
      throw vendorError('WEB_VENDOR_REDOC_FILE_INVALID', `ReDoc vendor path is not an approved regular file: ${path.basename(candidate)}`);
    }
    const resolved = await realpath(candidate);
    return readFile(resolved);
  }
}

function isInside(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function vendorError(code, message, cause) {
  const error = new Error(message, cause ? { cause } : undefined);
  error.code = code;
  return error;
}
