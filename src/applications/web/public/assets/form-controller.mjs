/**
 * Reliable async form wiring for browser administration surfaces.
 *
 * Submit buttons are wired explicitly in addition to the form submit event. This
 * protects mouse/touch activation even when browser/library behaviour suppresses
 * the synthetic submit event, while keyboard/Enter submission remains supported.
 * A single in-flight guard prevents duplicate mutations from click + submit races.
 */
export function wireAsyncForm(form, {
  execute,
  onWorking = () => {},
  onSuccess = () => {},
  onError = () => {},
} = {}) {
  if (!form || typeof form.addEventListener !== 'function') throw new TypeError('form must support DOM events');
  if (typeof execute !== 'function') throw new TypeError('execute callback is required');

  const submitButtons = [...(form.querySelectorAll?.('button[type="submit"],input[type="submit"]') ?? [])];
  let busy = false;

  const setBusy = (value) => {
    busy = value;
    form.setAttribute?.('aria-busy', value ? 'true' : 'false');
    for (const button of submitButtons) {
      button.disabled = value;
      button.setAttribute?.('aria-disabled', value ? 'true' : 'false');
    }
  };

  const run = async (submitter = null) => {
    if (busy) return false;
    if (typeof form.reportValidity === 'function' && !form.reportValidity()) return false;
    setBusy(true);
    onWorking(form, submitter);
    try {
      await execute(form, submitter);
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

  const buttonListeners = new Map();
  for (const button of submitButtons) {
    const onClick = (event) => {
      // Handle the click directly instead of relying on the browser to dispatch a
      // second submit event. This is the deterministic mutation path for pointer use.
      event?.preventDefault?.();
      void run(button);
    };
    button.addEventListener?.('click', onClick);
    button.setAttribute?.('data-iam-wired', 'true');
    button.setAttribute?.('aria-disabled', 'false');
    button.disabled = false;
    buttonListeners.set(button, onClick);
  }
  form.setAttribute?.('data-iam-wired', 'true');

  return Object.freeze({
    run,
    isBusy: () => busy,
    destroy() {
      form.removeEventListener?.('submit', onSubmit);
      for (const [button, listener] of buttonListeners) button.removeEventListener?.('click', listener);
      form.removeAttribute?.('data-iam-wired');
    },
  });
}
