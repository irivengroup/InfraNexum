export const PREFERENCES_SCHEMA = 'infranexum.web-preferences/v1';
export const PREFERENCES_STORAGE_KEY = 'infranexum.preferences.v1';

const DENSITIES = new Set(['comfortable', 'compact']);
const NAVIGATION_MODES = new Set(['auto', 'expanded', 'compact']);
const REFRESH_INTERVALS = new Set([0, 30, 60, 300]);

export const DEFAULT_PREFERENCES = Object.freeze({
  schema: PREFERENCES_SCHEMA,
  density: 'comfortable',
  navigation: 'auto',
  refreshIntervalSeconds: 60,
});

export function validatePreferences(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return { ...DEFAULT_PREFERENCES };
  const density = DENSITIES.has(value.density) ? value.density : DEFAULT_PREFERENCES.density;
  const navigation = NAVIGATION_MODES.has(value.navigation) ? value.navigation : DEFAULT_PREFERENCES.navigation;
  const refreshIntervalSeconds = REFRESH_INTERVALS.has(value.refreshIntervalSeconds)
    ? value.refreshIntervalSeconds
    : DEFAULT_PREFERENCES.refreshIntervalSeconds;
  return { schema: PREFERENCES_SCHEMA, density, navigation, refreshIntervalSeconds };
}

export function loadPreferences(storageObject = globalThis.localStorage) {
  try {
    const raw = storageObject?.getItem(PREFERENCES_STORAGE_KEY);
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
  root?.setAttribute?.('data-refresh-seconds', String(validated.refreshIntervalSeconds));

  const density = documentObject?.getElementById?.('preference-density');
  const navigation = documentObject?.getElementById?.('preference-navigation');
  const refresh = documentObject?.getElementById?.('preference-refresh');
  if (density) density.value = validated.density;
  if (navigation) navigation.value = validated.navigation;
  if (refresh) refresh.value = String(validated.refreshIntervalSeconds);
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
  let current = applyPreferences(documentObject, loadPreferences(storageObject));

  const dispatch = () => dispatchPreferencesChange(documentObject, current);
  const open = () => {
    applyPreferences(documentObject, current);
    if (dialog && !dialog.open) dialog.showModal?.();
    trigger?.setAttribute?.('aria-expanded', 'true');
  };
  const close = () => {
    if (dialog?.open) dialog.close?.();
    trigger?.setAttribute?.('aria-expanded', 'false');
    trigger?.focus?.();
  };
  const save = () => {
    current = persistPreferences(storageObject, {
      schema: PREFERENCES_SCHEMA,
      density: documentObject?.getElementById?.('preference-density')?.value,
      navigation: documentObject?.getElementById?.('preference-navigation')?.value,
      refreshIntervalSeconds: Number(documentObject?.getElementById?.('preference-refresh')?.value),
    });
    applyPreferences(documentObject, current);
    dispatch();
    close();
  };
  const resetAll = () => {
    current = persistPreferences(storageObject, DEFAULT_PREFERENCES);
    applyPreferences(documentObject, current);
    dispatch();
  };

  trigger?.addEventListener?.('click', open);
  closer?.addEventListener?.('click', close);
  form?.addEventListener?.('submit', (event) => { event?.preventDefault?.(); save(); });
  reset?.addEventListener?.('click', resetAll);
  dialog?.addEventListener?.('click', (event) => { if (event.target === dialog) close(); });

  return Object.freeze({
    open,
    close,
    get: () => ({ ...current }),
    set: (preferences) => {
      current = persistPreferences(storageObject, preferences);
      applyPreferences(documentObject, current);
      dispatch();
      return { ...current };
    },
  });
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
