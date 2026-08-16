import assert from 'node:assert/strict';
import test from 'node:test';

import { wireAsyncAction, wireAsyncForm } from '../../src/applications/web/public/assets/form-controller.mjs';

class FakeElement {
  constructor() { this.listeners = new Map(); this.attributes = new Map(); this.disabled = false; this.hidden = false; }
  addEventListener(type, listener) { if (!this.listeners.has(type)) this.listeners.set(type, []); this.listeners.get(type).push(listener); }
  removeEventListener(type, listener) { this.listeners.set(type, (this.listeners.get(type) ?? []).filter((item) => item !== listener)); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  removeAttribute(name) { this.attributes.delete(name); }
  dispatch(type, extra = {}) { for (const listener of this.listeners.get(type) ?? []) listener({ preventDefault() {}, ...extra }); }
}
class FakeForm extends FakeElement {
  constructor(buttons) { super(); this.buttons = buttons; this.valid = true; }
  querySelectorAll() { return this.buttons; }
  reportValidity() { return this.valid; }
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

test('async form controller executes pointer clicks even without a browser submit event', async () => {
  const button = new FakeElement();
  const form = new FakeForm([button]);
  let executions = 0;
  let success = 0;
  wireAsyncForm(form, { execute: async (_form, submitter) => { assert.equal(submitter, button); executions += 1; }, onSuccess: () => { success += 1; } });
  assert.equal(form.attributes.get('data-iam-wired'), 'true');
  assert.equal(button.attributes.get('data-iam-wired'), 'true');
  button.dispatch('click');
  await tick();
  assert.equal(executions, 1);
  assert.equal(success, 1);
  assert.equal(button.disabled, false);
});

test('async form controller supports keyboard submit and prevents duplicate in-flight mutations', async () => {
  const button = new FakeElement();
  const form = new FakeForm([button]);
  let release;
  let executions = 0;
  const pending = new Promise((resolve) => { release = resolve; });
  wireAsyncForm(form, { execute: async () => { executions += 1; await pending; } });
  form.dispatch('submit', { submitter: button });
  button.dispatch('click');
  await tick();
  assert.equal(executions, 1);
  assert.equal(form.attributes.get('aria-busy'), 'true');
  assert.equal(button.disabled, true);
  release();
  await tick();
  assert.equal(form.attributes.get('aria-busy'), 'false');
  assert.equal(button.disabled, false);
});

test('async form controller does not execute an invalid native form and surfaces execution failures', async () => {
  const button = new FakeElement();
  const form = new FakeForm([button]);
  form.valid = false;
  let executions = 0;
  let errors = 0;
  const controller = wireAsyncForm(form, { execute: async () => { executions += 1; }, onError: () => { errors += 1; } });
  assert.equal(await controller.run(button), false);
  assert.equal(executions, 0);
  form.valid = true;
  const failing = new FakeForm([new FakeElement()]);
  const failureController = wireAsyncForm(failing, { execute: async () => { throw new Error('boom'); }, onError: (error) => { assert.equal(error.message, 'boom'); errors += 1; } });
  assert.equal(await failureController.run(failing.buttons[0]), false);
  assert.equal(errors, 1);
});


test('async form controller preserves buttons that were intentionally disabled before a mutation', async () => {
  const primary = new FakeElement();
  const forbidden = new FakeElement(); forbidden.disabled = true;
  const form = new FakeForm([primary, forbidden]);
  const controller = wireAsyncForm(form, { execute: async () => {} });
  assert.equal(await controller.run(primary), true);
  assert.equal(primary.disabled, false);
  assert.equal(forbidden.disabled, true);
  assert.equal(forbidden.attributes.get('aria-disabled'), 'true');
});

test('async action controller converts rejected mutations into deterministic error feedback', async () => {
  const button = new FakeElement();
  let errors = 0;
  const controller = wireAsyncAction(button, { execute: async () => { throw new Error('network failed'); }, onError: (error) => { assert.equal(error.message, 'network failed'); errors += 1; } });
  assert.equal(await controller.run(), false);
  assert.equal(errors, 1);
  assert.equal(button.disabled, false);
  assert.equal(button.attributes.has('aria-busy'), false);
});
