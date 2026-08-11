/** Structured JSON logger used by the Web runtime boundary. */
export class JsonLogger {
  #sink;
  #clock;

  constructor({ sink = process.stdout, clock = () => new Date() } = {}) {
    if (!sink || typeof sink.write !== 'function') {
      throw new TypeError('logger sink must provide write()');
    }
    if (typeof clock !== 'function') {
      throw new TypeError('logger clock must be a function');
    }
    this.#sink = sink;
    this.#clock = clock;
  }

  info(message, fields = {}) { this.#write('INFO', message, fields); }
  error(message, fields = {}) { this.#write('ERROR', message, fields); }

  #write(level, message, fields) {
    const record = {
      timestamp: this.#clock().toISOString(),
      level,
      component: 'web',
      message,
      ...sanitizeFields(fields),
    };
    this.#sink.write(`${JSON.stringify(record)}\n`);
  }
}

function sanitizeFields(fields) {
  if (!fields || typeof fields !== 'object' || Array.isArray(fields)) {
    throw new TypeError('log fields must be an object');
  }
  const safe = {};
  for (const [key, value] of Object.entries(fields)) {
    if (/password|secret|token|credential|authorization/i.test(key)) {
      safe[key] = '[REDACTED]';
    } else if (value instanceof Error) {
      safe[key] = value.message;
    } else {
      safe[key] = value;
    }
  }
  return safe;
}
