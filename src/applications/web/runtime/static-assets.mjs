import { realpath, readFile, stat } from 'node:fs/promises';
import path from 'node:path';

const MAX_ASSET_BYTES = 16 * 1024 * 1024;
const CONTENT_TYPES = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.ico', 'image/x-icon'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.map', 'application/json; charset=utf-8'],
  ['.mjs', 'text/javascript; charset=utf-8'],
  ['.png', 'image/png'],
  ['.svg', 'image/svg+xml'],
  ['.webp', 'image/webp'],
  ['.woff2', 'font/woff2'],
  ['.yaml', 'application/yaml; charset=utf-8'],
  ['.yml', 'application/yaml; charset=utf-8'],
]);

/** Secure, bounded reader for immutable Web assets. */
export class StaticAssetStore {
  #configuredRoot;
  #root;
  #index;

  constructor(configuredRoot) {
    if (typeof configuredRoot !== 'string' || configuredRoot.length === 0) {
      throw new TypeError('configuredRoot must be a non-empty string');
    }
    this.#configuredRoot = path.resolve(configuredRoot);
  }

  async initialize() {
    this.#root = await realpath(this.#configuredRoot);
    const index = await this.#secureFile(path.join(this.#root, 'index.html'));
    this.#index = index;
  }

  async read(requestPath) {
    if (!this.#root || !this.#index) {
      throw new Error('asset store is not initialized');
    }
    const pathname = decodePath(requestPath);
    const candidate = pathname === '/' ? this.#index : await this.#resolveCandidate(pathname);
    const selected = candidate ?? (path.extname(pathname) === '' ? this.#index : null);
    if (!selected) {
      return null;
    }
    const metadata = await stat(selected);
    if (!metadata.isFile() || metadata.size > MAX_ASSET_BYTES) {
      return null;
    }
    return Object.freeze({
      body: await readFile(selected),
      contentType: CONTENT_TYPES.get(path.extname(selected).toLowerCase()) ?? 'application/octet-stream',
      cacheControl: cachePolicy(path.basename(selected)),
    });
  }

  async #resolveCandidate(pathname) {
    const candidate = path.resolve(this.#root, `.${pathname}`);
    // decodePath() rejects traversal before resolution; this remains defense in depth.
    /* node:coverage ignore next 3 */
    if (!isInside(this.#root, candidate)) {
      return null;
    }
    try {
      return await this.#secureFile(candidate);
    } catch (error) {
      if (error && ['ENOENT', 'ENOTDIR'].includes(error.code)) {
        return null;
      }
      throw error;
    }
  }

  async #secureFile(candidate) {
    const resolved = await realpath(candidate);
    if (!isInside(this.#root, resolved)) {
      const error = new Error('asset path escapes configured root');
      error.code = 'EACCES';
      throw error;
    }
    return resolved;
  }
}

function decodePath(requestPath) {
  if (typeof requestPath !== 'string' || requestPath.length === 0 || requestPath.includes('\0') || requestPath.includes('\\')) {
    const error = new Error('invalid request path');
    error.code = 'EINVAL';
    throw error;
  }
  try {
    const decoded = decodeURIComponent(requestPath);
    if (!decoded.startsWith('/') || decoded.split('/').includes('..')) {
      throw new URIError('unsafe path');
    }
    return decoded;
  } catch {
    const error = new Error('invalid encoded request path');
    error.code = 'EINVAL';
    throw error;
  }
}

function isInside(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function cachePolicy(filename) {
  if (/\.[0-9a-f]{8,}\./i.test(filename)) {
    return 'public, max-age=31536000, immutable';
  }
  return 'no-cache';
}
