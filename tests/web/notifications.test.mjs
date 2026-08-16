import assert from 'node:assert/strict';
import test from 'node:test';

import { initializeNotificationCenter, normalizeNotice } from '../../src/applications/web/public/assets/notifications.mjs';

class Element {
  constructor(attributes = {}) {
    this.attributes = { ...attributes };
    this.textContent = '';
    this.hidden = false;
    this.className = '';
    this.children = [];
    this.listeners = new Map();
    this.open = false;
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return this.attributes[name] ?? null; }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  appendChild(child) { this.children.push(child); }
  replaceChildren(...children) { this.children = children; }
  showModal() { this.open = true; }
  close() { this.open = false; }
  focus() { this.focused = true; }
  click() { this.listeners.get('click')?.({ target: this }); }
}

function documentFixture() {
  const root = new Element({ lang: 'en' });
  const ids = ['notification-center', 'notification-trigger', 'notification-close', 'notification-mark-read', 'notification-list', 'notification-count'];
  const elements = new Map(ids.map((id) => [id, new Element()]));
  return {
    documentElement: root,
    getElementById: (id) => elements.get(id),
    createElement: () => new Element(),
    addEventListener() {},
    elements,
  };
}

test('notification contract rejects malformed identity while normalizing severity', () => {
  assert.throws(() => normalizeNotice(null), /notification/i);
  assert.throws(() => normalizeNotice({ id: '', titleKey: 'a', bodyKey: 'b' }), /id/i);
  const notice = normalizeNotice({ id: 'runtime', severity: 'unknown', titleKey: 'notification.runtimeReady.title', bodyKey: 'notification.runtimeReady.body' }, () => 42);
  assert.equal(notice.severity, 'info');
  assert.equal(notice.observedAt, 42);
});

test('notification center counts only unread observed facts and marks them read on open', () => {
  const documentObject = documentFixture();
  const center = initializeNotificationCenter(documentObject, () => 100);
  center.upsert({ id: 'runtime', severity: 'success', titleKey: 'notification.runtimeReady.title', bodyKey: 'notification.runtimeReady.body', parameters: { version: 'x', environment: 'local' } });
  center.upsert({ id: 'platform', severity: 'error', titleKey: 'notification.platformUnavailable.title', bodyKey: 'notification.platformUnavailable.body' });
  assert.equal(documentObject.elements.get('notification-count').textContent, '2');
  assert.equal(documentObject.elements.get('notification-count').hidden, false);
  assert.equal(documentObject.elements.get('notification-list').children.length, 2);

  center.open();
  assert.equal(documentObject.elements.get('notification-center').open, true);
  assert.equal(documentObject.elements.get('notification-trigger').getAttribute('aria-expanded'), 'true');
  assert.equal(documentObject.elements.get('notification-count').hidden, true);
  assert.equal(center.snapshot().every((item) => item.read), true);
  center.close();
  assert.equal(documentObject.elements.get('notification-center').open, false);
  assert.equal(documentObject.elements.get('notification-trigger').getAttribute('aria-expanded'), 'false');
});

test('upserting the same observed fact does not create duplicates', () => {
  const documentObject = documentFixture();
  const center = initializeNotificationCenter(documentObject, () => 100);
  const notice = { id: 'platform', severity: 'success', titleKey: 'notification.platformReady.title', bodyKey: 'notification.platformReady.body', parameters: { profile: 'PRO', tier: 'STANDARD' } };
  center.upsert(notice);
  center.markAllRead();
  center.upsert(notice);
  assert.equal(center.snapshot().length, 1);
  assert.equal(center.snapshot()[0].read, true);
});
