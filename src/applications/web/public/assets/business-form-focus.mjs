/**
 * Keeps pointer focus on editable business-form controls inside the authenticated shell.
 *
 * Some hardened Chromium/Windows combinations may leave focus on the route container
 * after a CRUD editor is revealed. The next printable key then targets <main> instead
 * of the clicked field. This guard repairs that browser-focus boundary without
 * synthesizing text, intercepting keyboard input, or touching login/search controls.
 */
const states = new WeakMap();

export function initializeBusinessFormFocus(documentObject = document) {
  const existing = states.get(documentObject);
  if (existing) return existing;

  const shell = documentObject?.getElementById?.('app-shell');
  if (!shell) return Object.freeze({ enabled: false, destroy() {} });

  const focusPointerTarget = (event) => {
    const field = editableBusinessField(event?.target, shell);
    if (!field || documentObject.activeElement === field) return;
    field.focus?.({ preventScroll: true });
  };

  // Capture runs before CRUD/table handlers that may update route focus. We do not
  // prevent the default pointer action, so the browser still positions the caret and
  // preserves native text selection semantics.
  documentObject.addEventListener?.('pointerdown', focusPointerTarget, true);
  documentObject.addEventListener?.('click', focusPointerTarget, true);

  const controller = Object.freeze({
    enabled: true,
    destroy() {
      documentObject.removeEventListener?.('pointerdown', focusPointerTarget, true);
      documentObject.removeEventListener?.('click', focusPointerTarget, true);
      states.delete(documentObject);
    },
  });
  states.set(documentObject, controller);
  return controller;
}

export function editableBusinessField(target, shell) {
  if (!target || !shell?.contains?.(target)) return null;
  const tag = String(target.tagName ?? '').toUpperCase();
  if (tag !== 'INPUT' && tag !== 'TEXTAREA') return null;
  if (!target.closest?.('form')) return null;
  if (target.disabled === true || target.readOnly === true) return null;
  if (target.matches?.('[data-inx-temporal], .inx-select-native')) return null;

  const type = String(target.type ?? '').toLowerCase();
  if (['search', 'hidden', 'button', 'submit', 'reset', 'checkbox', 'radio', 'file', 'color', 'range'].includes(type)) return null;

  // Authentication and search are intentionally outside this corrective boundary.
  if (target.closest?.('#auth-gate, .inx-auth-gate, [data-inx-auth]')) return null;
  if (target.matches?.('[role="search"] *, [data-inx-search], [data-inx-table-search]')) return null;
  if (target.closest?.('[role="search"]')) return null;
  return target;
}
