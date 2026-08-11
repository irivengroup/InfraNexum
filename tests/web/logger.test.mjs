import assert from 'node:assert/strict';
import test from 'node:test';

import { JsonLogger } from '../../src/applications/web/runtime/logger.mjs';

class Sink {
  chunks = [];
  write(value) { this.chunks.push(value); }
}

test('logger emits deterministic structured records and redacts sensitive fields', () => {
  const sink = new Sink();
  const logger = new JsonLogger({ sink, clock: () => new Date('2026-08-03T12:00:00.000Z') });
  logger.info('started', { address: '127.0.0.1', token: 'value' });
  logger.error('failed', { error: new Error('boom'), passwordHash: 'hash' });
  assert.deepEqual(sink.chunks.map((line) => JSON.parse(line)), [
    {
      timestamp: '2026-08-03T12:00:00.000Z',
      level: 'INFO',
      component: 'web',
      message: 'started',
      address: '127.0.0.1',
      token: '[REDACTED]',
    },
    {
      timestamp: '2026-08-03T12:00:00.000Z',
      level: 'ERROR',
      component: 'web',
      message: 'failed',
      error: 'boom',
      passwordHash: '[REDACTED]',
    },
  ]);
});

test('logger validates injected dependencies and field shape', () => {
  assert.throws(() => new JsonLogger({ sink: {} }), /sink/);
  assert.throws(() => new JsonLogger({ clock: 1 }), /clock/);
  const logger = new JsonLogger({ sink: new Sink() });
  assert.throws(() => logger.info('bad', []), /fields/);
  assert.doesNotThrow(() => logger.info('default'));
});
