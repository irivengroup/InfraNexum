import http from 'node:http';

const SHELL_CONTENT_SECURITY_POLICY = "default-src 'self'; base-uri 'none'; connect-src 'self' https:; font-src 'self'; form-action 'self'; frame-ancestors 'none'; frame-src 'self'; img-src 'self' data:; object-src 'none'; script-src 'self' https://cdn.jsdelivr.net; style-src 'self' https://cdn.jsdelivr.net";
const REDOC_FRAME_CONTENT_SECURITY_POLICY = "default-src 'none'; base-uri 'none'; connect-src 'self'; font-src 'self' data:; form-action 'none'; frame-ancestors 'self'; frame-src 'none'; img-src 'self' data:; object-src 'none'; script-src 'self' https://cdn.redoc.ly https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline'";
const REDOC_FRAME_PATH = '/assets/redoc-frame.html';
const SECURITY_HEADERS = Object.freeze({
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Permissions-Policy': 'camera=(), geolocation=(), microphone=(), payment=(), usb=()',
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
});

/** Owns the Web process lifecycle, health state, and immutable asset HTTP boundary. */
export class WebApplication {
  #configuration;
  #assets;
  #logger;
  #server;
  #state = 'created';
  #address;

  constructor({ configuration, assets, logger }) {
    if (!configuration || typeof configuration.publicConfiguration !== 'function') {
      throw new TypeError('configuration is required');
    }
    if (!assets || typeof assets.initialize !== 'function' || typeof assets.read !== 'function') {
      throw new TypeError('asset store is required');
    }
    if (!logger || typeof logger.info !== 'function' || typeof logger.error !== 'function') {
      throw new TypeError('logger is required');
    }
    this.#configuration = configuration;
    this.#assets = assets;
    this.#logger = logger;
  }

  get state() { return this.#state; }
  get address() { return this.#address; }

  async start() {
    if (this.#state !== 'created') {
      throw new Error(`cannot start Web application from state ${this.#state}`);
    }
    this.#state = 'starting';
    try {
      await this.#assets.initialize();
      this.#server = http.createServer((request, response) => {
        this.#handle(request, response).catch((error) => {
          this.#logger.error('web request failed', { error, method: request.method, path: request.url });
          if (!response.headersSent) {
            this.#json(response, 500, { status: 'ERROR', code: 'WEB_INTERNAL_ERROR' });
          }
          // Only transport failures after a committed response can reach this branch.
          /* node:coverage ignore next 3 */
          else {
            response.destroy(error);
          }
        });
      });
      this.#server.requestTimeout = 15_000;
      this.#server.headersTimeout = 10_000;
      this.#server.keepAliveTimeout = 5_000;
      this.#server.maxHeadersCount = 100;
      await listen(this.#server, this.#configuration.listenPort, this.#configuration.listenHost);
      this.#address = normalizeTcpAddress(this.#server.address());
      this.#state = 'ready';
      this.#logger.info('web runtime started', { address: this.#address, environment: this.#configuration.environment });
      return this.#address;
    } catch (error) {
      this.#state = 'failed';
      if (this.#server && !this.#server.listening) {
        this.#server = undefined;
      }
      this.#logger.error('web runtime startup failed', { error });
      throw error;
    }
  }

  async stop() {
    if (['created', 'stopped'].includes(this.#state)) {
      this.#state = 'stopped';
      return;
    }
    if (!this.#server) {
      this.#state = 'stopped';
      return;
    }
    this.#state = 'stopping';
    const server = this.#server;
    await closeServerWithDeadline(server, this.#configuration.shutdownTimeoutMs);
    this.#state = 'stopped';
    this.#logger.info('web runtime stopped');
  }

  async #handle(request, response) {
    // Node's IncomingMessage always supplies method and URL for accepted HTTP requests.
    /* node:coverage ignore next */
    const method = request.method ?? 'GET';
    /* node:coverage ignore next */
    const requestUrl = new URL(request.url ?? '/', 'http://infranexum.invalid');
    const pathname = requestUrl.pathname;
    this.#securityHeaders(response, pathname);

    if (!['GET', 'HEAD'].includes(method)) {
      response.setHeader('Allow', 'GET, HEAD');
      this.#json(response, 405, { status: 'ERROR', code: 'METHOD_NOT_ALLOWED' }, method === 'HEAD');
      return;
    }
    if (pathname === '/health/live') {
      this.#json(response, 200, { status: 'UP' }, method === 'HEAD');
      return;
    }
    if (pathname === '/health/startup') {
      const probe = healthProbe(this.#state, 'startup');
      this.#json(response, probe.statusCode, { status: probe.status }, method === 'HEAD');
      return;
    }
    if (pathname === '/health/ready') {
      const probe = healthProbe(this.#state, 'readiness');
      this.#json(response, probe.statusCode, { status: probe.status }, method === 'HEAD');
      return;
    }
    if (pathname === '/runtime-config.json') {
      this.#json(response, 200, this.#configuration.publicConfiguration(), method === 'HEAD');
      return;
    }
    if (pathname === '/api/v1/system/build') {
      this.#json(response, 200, {
        product: 'InfraNexum',
        component: 'WEB',
        version: this.#configuration.version,
        architectureBaseline: this.#configuration.architectureBaseline,
        environment: this.#configuration.environment,
      }, method === 'HEAD');
      return;
    }

    let asset;
    try {
      asset = await this.#assets.read(pathname);
    } catch (error) {
      if (error?.code === 'EINVAL' || error?.code === 'EACCES') {
        this.#json(response, 400, { status: 'ERROR', code: 'INVALID_PATH' }, method === 'HEAD');
        return;
      }
      throw error;
    }
    if (!asset) {
      this.#json(response, 404, { status: 'ERROR', code: 'NOT_FOUND' }, method === 'HEAD');
      return;
    }
    response.statusCode = 200;
    response.setHeader('Content-Type', asset.contentType);
    response.setHeader('Cache-Control', asset.cacheControl);
    response.setHeader('Content-Length', String(asset.body.length));
    response.end(method === 'HEAD' ? undefined : asset.body);
  }

  #securityHeaders(response, pathname) {
    for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
      response.setHeader(name, value);
    }
    const redocFrame = pathname === REDOC_FRAME_PATH;
    response.setHeader(
      'Content-Security-Policy',
      redocFrame ? REDOC_FRAME_CONTENT_SECURITY_POLICY : SHELL_CONTENT_SECURITY_POLICY,
    );
    response.setHeader('X-Frame-Options', redocFrame ? 'SAMEORIGIN' : 'DENY');
  }

  #json(response, statusCode, payload, headOnly = false) {
    const body = Buffer.from(`${JSON.stringify(payload)}\n`, 'utf8');
    response.statusCode = statusCode;
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('Content-Length', String(body.length));
    response.end(headOnly ? undefined : body);
  }
}

function listen(server, port, host) {
  return new Promise((resolve, reject) => {
    const onError = (error) => {
      server.off('listening', onListening);
      reject(error);
    };
    const onListening = () => {
      server.off('error', onError);
      resolve();
    };
    server.once('error', onError);
    server.once('listening', onListening);
    server.listen({ host, port, exclusive: true });
  });
}

export function healthProbe(state, kind) {
  if (kind === 'startup') {
    const up = ['ready', 'stopping'].includes(state);
    return Object.freeze({ statusCode: up ? 200 : 503, status: up ? 'UP' : 'DOWN' });
  }
  if (kind === 'readiness') {
    const up = state === 'ready';
    return Object.freeze({ statusCode: up ? 200 : 503, status: up ? 'UP' : 'DOWN' });
  }
  throw new Error(`unsupported health probe ${kind}`);
}

export function normalizeTcpAddress(address) {
  if (!address || typeof address === 'string') {
    throw new Error('Web runtime did not expose a TCP address');
  }
  const host = address.family === 'IPv6' ? `[${address.address}]` : address.address;
  return `http://${host}:${address.port}`;
}

export function closeServerWithDeadline(server, timeoutMs) {
  return new Promise((resolve, reject) => {
    let completed = false;
    const timer = setTimeout(() => {
      if (completed) return;
      server.closeAllConnections?.();
      completed = true;
      reject(new Error(`Web runtime shutdown exceeded ${timeoutMs}ms`));
    }, timeoutMs);
    timer.unref();
    server.close((error) => {
      if (completed) return;
      completed = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve();
    });
    server.closeIdleConnections?.();
  });
}
