/**
 * Reliable asynchronous form and action wiring for InfraNexum administration.
 *
 * Native HTML validation remains authoritative. The controller adds Bootstrap's
 * validation state, prevents duplicate mutations, preserves intentional disabled
 * states and guarantees that pointer and keyboard activation reach the same
 * execution path. Errors are returned to the caller instead of becoming
 * unhandled promise rejections.
 */
export function wireAsyncForm(form, {
  execute,
  onWorking = () => {},
  onSuccess = () => {},
  onError = () => {},
  focusInvalid = true,
} = {}) {
  if (!form || typeof form.addEventListener !== 'function') throw new TypeError('form must support DOM events');
  if (typeof execute !== 'function') throw new TypeError('execute callback is required');

  const submitButtons = [...(form.querySelectorAll?.('button[type="submit"],input[type="submit"]') ?? [])];
  let busy = false;
  let busySnapshot = new Map();

  form.classList?.add?.('needs-validation');
  form.setAttribute?.('novalidate', '');

  const setBusy = (value) => {
    busy = value;
    form.setAttribute?.('aria-busy', value ? 'true' : 'false');
    if (value) busySnapshot = new Map(submitButtons.map((button) => [button, button.disabled === true]));
    for (const button of submitButtons) {
      button.disabled = value ? true : (busySnapshot.get(button) ?? false);
      button.setAttribute?.('aria-disabled', button.disabled ? 'true' : 'false');
    }
  };

  const validate = () => {
    const valid = typeof form.checkValidity === 'function' ? form.checkValidity() :
      (typeof form.reportValidity === 'function' ? form.reportValidity() : true);
    form.classList?.toggle?.('was-validated', !valid);
    if (!valid && typeof form.reportValidity === 'function') form.reportValidity();
    if (!valid && focusInvalid) {
      form.querySelector?.(':invalid')?.focus?.({ preventScroll: false });
    }
    return valid;
  };

  const run = async (submitter = null) => {
    if (busy || !validate()) return false;
    setBusy(true);
    onWorking(form, submitter);
    try {
      await execute(form, submitter);
      form.classList?.remove?.('was-validated');
      onSuccess(form, submitter);
      return true;
    } catch (error) {
      onError(error, form, submitter);
      return false;
    } finally {
      setBusy(false);
    }
  };

  const onSubmit = (event) => {
    event?.preventDefault?.();
    void run(event?.submitter ?? null);
  };
  form.addEventListener('submit', onSubmit);

  // Some embedded browser stacks have historically failed to dispatch submit
  // reliably from pointer activation. The click fallback is idempotent because
  // the in-flight guard rejects the duplicate submit event when both are emitted.
  const buttonListeners = new Map();
  for (const button of submitButtons) {
    const onClick = (event) => {
      if (button.disabled) return;
      event?.preventDefault?.();
      void run(button);
    };
    button.addEventListener?.('click', onClick);
    button.setAttribute?.('data-inx-form-wired', 'true');
    button.setAttribute?.('data-iam-wired', 'true');
    button.setAttribute?.('aria-disabled', button.disabled ? 'true' : 'false');
    buttonListeners.set(button, onClick);
  }
  form.setAttribute?.('data-inx-form-wired', 'true');
  form.setAttribute?.('data-iam-wired', 'true');

  const onInput = () => form.classList?.remove?.('was-validated');
  form.addEventListener?.('input', onInput);
  form.addEventListener?.('change', onInput);

  return Object.freeze({
    run,
    isBusy: () => busy,
    destroy() {
      form.removeEventListener?.('submit', onSubmit);
      form.removeEventListener?.('input', onInput);
      form.removeEventListener?.('change', onInput);
      for (const [button, listener] of buttonListeners) button.removeEventListener?.('click', listener);
      form.removeAttribute?.('data-inx-form-wired');
      form.removeAttribute?.('data-iam-wired');
      form.removeAttribute?.('aria-busy');
    },
  });
}

/**
 * Wires a non-submit action button with the same busy/error semantics as forms.
 * This is used for lifecycle/update commands that intentionally live outside a
 * submit boundary while still needing deterministic failure feedback.
 */
export function wireAsyncAction(button, {
  execute,
  onWorking = () => {},
  onSuccess = () => {},
  onError = () => {},
} = {}) {
  if (!button || typeof button.addEventListener !== 'function') throw new TypeError('button must support DOM events');
  if (typeof execute !== 'function') throw new TypeError('execute callback is required');
  let busy = false;

  const run = async () => {
    if (busy || button.disabled) return false;
    busy = true;
    const previousDisabled = button.disabled === true;
    button.disabled = true;
    button.setAttribute?.('aria-disabled', 'true');
    button.setAttribute?.('aria-busy', 'true');
    onWorking(button);
    try {
      await execute(button);
      onSuccess(button);
      return true;
    } catch (error) {
      onError(error, button);
      return false;
    } finally {
      busy = false;
      button.disabled = previousDisabled;
      button.setAttribute?.('aria-disabled', button.disabled ? 'true' : 'false');
      button.removeAttribute?.('aria-busy');
    }
  };

  const listener = (event) => { event?.preventDefault?.(); void run(); };
  button.addEventListener('click', listener);
  button.setAttribute?.('data-inx-action-wired', 'true');
  return Object.freeze({ run, isBusy: () => busy, destroy: () => button.removeEventListener?.('click', listener) });
}
