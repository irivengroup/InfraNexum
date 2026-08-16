export const PREFERENCES_SCHEMA = 'infranexum.web-preferences/v2';
export const PREFERENCES_STORAGE_KEY = 'infranexum.preferences.v2';
export const LEGACY_PREFERENCES_STORAGE_KEY = 'infranexum.preferences.v1';

const DENSITIES = new Set(['comfortable', 'compact']);
const NAVIGATION_MODES = new Set(['auto', 'expanded', 'compact']);
const LAYOUT_MODES = new Set(['page', 'fluid']);
const THEMES = new Set(['light', 'dark']);
const REFRESH_INTERVALS = new Set([0, 30, 60, 300]);

export const DEFAULT_PREFERENCES = Object.freeze({
  schema: PREFERENCES_SCHEMA,
  density: 'comfortable',
  navigation: 'auto',
  layout: 'page',
  refreshIntervalSeconds: 60,
});

export function validatePreferences(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return { ...DEFAULT_PREFERENCES };
  const density = DENSITIES.has(value.density) ? value.density : DEFAULT_PREFERENCES.density;
  const navigation = NAVIGATION_MODES.has(value.navigation) ? value.navigation : DEFAULT_PREFERENCES.navigation;
  const layout = LAYOUT_MODES.has(value.layout) ? value.layout : DEFAULT_PREFERENCES.layout;
  const refreshIntervalSeconds = REFRESH_INTERVALS.has(value.refreshIntervalSeconds)
    ? value.refreshIntervalSeconds
    : DEFAULT_PREFERENCES.refreshIntervalSeconds;
  return { schema: PREFERENCES_SCHEMA, density, navigation, layout, refreshIntervalSeconds };
}

export function loadPreferences(storageObject = globalThis.localStorage) {
  try {
    const raw = storageObject?.getItem(PREFERENCES_STORAGE_KEY)
      ?? storageObject?.getItem(LEGACY_PREFERENCES_STORAGE_KEY);
    if (!raw) return { ...DEFAULT_PREFERENCES };
    return validatePreferences(JSON.parse(raw));
  } catch {
    return { ...DEFAULT_PREFERENCES };
  }
}

export function persistPreferences(storageObject, preferences) {
  const validated = validatePreferences(preferences);
  try {
    storageObject?.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify(validated));
  } catch {
    // Hardened/private browser contexts may deny persistence. The current session still applies the choice.
  }
  return validated;
}

export function applyPreferences(documentObject, preferences) {
  const validated = validatePreferences(preferences);
  const root = documentObject?.documentElement;
  root?.setAttribute?.('data-density', validated.density);
  root?.setAttribute?.('data-navigation', validated.navigation);
  root?.setAttribute?.('data-layout', validated.layout);
  root?.setAttribute?.('data-refresh-seconds', String(validated.refreshIntervalSeconds));

  const fields = {
    'preference-density': validated.density,
    'preference-navigation': validated.navigation,
    'preference-layout': validated.layout,
    'preference-refresh': String(validated.refreshIntervalSeconds),
  };
  for (const [id, value] of Object.entries(fields)) {
    const element = documentObject?.getElementById?.(id);
    if (element) {
      element.value = value;
      dispatchElementSync(documentObject, element);
    }
  }
  synchronizeThemeField(documentObject);
  return validated;
}

export function initializePreferences(
  documentObject = document,
  storageObject = globalThis.localStorage,
) {
  const dialog = documentObject?.getElementById?.('preferences-dialog');
  const trigger = documentObject?.getElementById?.('preferences-trigger');
  const closer = documentObject?.getElementById?.('preferences-close');
  const form = documentObject?.getElementById?.('preferences-form');
  const reset = documentObject?.getElementById?.('preferences-reset');
  const saveButton = documentObject?.getElementById?.('preferences-save');
  let saving = false;
  let current = applyPreferences(documentObject, loadPreferences(storageObject));

  const dispatch = () => dispatchPreferencesChange(documentObject, current);
  const open = () => {
    applyPreferences(documentObject, current);
    synchronizeThemeField(documentObject);
    dialog?.setAttribute?.('data-preferences-state', 'editing');
    if (dialog && !dialog.open) dialog.showModal?.();
    trigger?.setAttribute?.('aria-expanded', 'true');
  };
  const close = (returnValue = 'cancel') => {
    if (dialog?.open) dialog.close?.(returnValue);
    trigger?.setAttribute?.('aria-expanded', 'false');
    trigger?.focus?.();
  };
  const save = () => {
    if (saving) return Object.freeze({ preferences: { ...current }, theme: currentTheme(documentObject) });
    saving = true;
    saveButton?.setAttribute?.('aria-busy', 'true');
    try {
      const draft = readPreferenceDraft(documentObject);
      current = persistPreferences(storageObject, draft.preferences);
      current = applyPreferences(documentObject, current);
      requestTheme(documentObject, draft.theme);
      dispatch();
      dialog?.setAttribute?.('data-preferences-state', 'saved');
      close('saved');
      return Object.freeze({ preferences: { ...current }, theme: draft.theme });
    } finally {
      saving = false;
      saveButton?.removeAttribute?.('aria-busy');
    }
  };
  const resetAll = () => {
    current = persistPreferences(storageObject, DEFAULT_PREFERENCES);
    applyPreferences(documentObject, current);
    requestTheme(documentObject, 'light');
    dispatch();
  };

  trigger?.addEventListener?.('click', open);
  closer?.addEventListener?.('click', () => close());
  saveButton?.addEventListener?.('click', (event) => { event?.preventDefault?.(); save(); });
  form?.addEventListener?.('submit', (event) => { event?.preventDefault?.(); save(); });
  reset?.addEventListener?.('click', resetAll);
  dialog?.addEventListener?.('click', (event) => { if (event.target === dialog) close(); });
  dialog?.addEventListener?.('cancel', (event) => { event?.preventDefault?.(); close(); });
  documentObject?.addEventListener?.('infranexum:theme-change', () => synchronizeThemeField(documentObject));

  return Object.freeze({
    open,
    close,
    get: () => ({ ...current }),
    save,
    set: (preferences) => {
      current = persistPreferences(storageObject, preferences);
      applyPreferences(documentObject, current);
      dispatch();
      return { ...current };
    },
  });
}

function readPreferenceDraft(documentObject) {
  const themeValue = documentObject?.getElementById?.('preference-theme')?.value;
  return Object.freeze({
    theme: THEMES.has(themeValue) ? themeValue : 'light',
    preferences: Object.freeze({
      schema: PREFERENCES_SCHEMA,
      density: documentObject?.getElementById?.('preference-density')?.value,
      navigation: documentObject?.getElementById?.('preference-navigation')?.value,
      layout: documentObject?.getElementById?.('preference-layout')?.value,
      refreshIntervalSeconds: Number(documentObject?.getElementById?.('preference-refresh')?.value),
    }),
  });
}

function dispatchElementSync(documentObject, element) {
  if (!element?.dispatchEvent) return;
  const EventConstructor = documentObject?.defaultView?.Event ?? globalThis.Event;
  try {
    element.dispatchEvent(new EventConstructor('infranexum:entity-sync'));
  } catch {
    element.dispatchEvent({ type: 'infranexum:entity-sync' });
  }
}

function currentTheme(documentObject) {
  const value = documentObject?.documentElement?.getAttribute?.('data-bs-theme');
  return THEMES.has(value) ? value : 'light';
}

function synchronizeThemeField(documentObject) {
  const field = documentObject?.getElementById?.('preference-theme');
  if (!field) return;
  const theme = documentObject?.documentElement?.getAttribute?.('data-bs-theme');
  field.value = THEMES.has(theme) ? theme : 'light';
}

function requestTheme(documentObject, value) {
  const theme = THEMES.has(value) ? value : 'light';
  if (!documentObject?.dispatchEvent) return theme;
  let event;
  try {
    event = new CustomEvent('infranexum:theme-request', { detail: { theme } });
  } catch {
    event = { type: 'infranexum:theme-request', detail: { theme } };
  }
  documentObject.dispatchEvent(event);
  return theme;
}

function dispatchPreferencesChange(documentObject, preferences) {
  if (!documentObject?.dispatchEvent) return;
  let event;
  try {
    event = new CustomEvent('infranexum:preferences-change', { detail: { ...preferences } });
  } catch {
    event = { type: 'infranexum:preferences-change', detail: { ...preferences } };
  }
  documentObject.dispatchEvent(event);
}
