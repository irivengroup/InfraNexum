const REQUIRED_SCHEMA = 'infranexum.web-runtime-config/v1';

export function validatePublicConfiguration(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Runtime configuration payload is invalid');
  }
  const required = ['schema', 'product', 'component', 'version', 'architectureBaseline', 'environment', 'apiBaseUrl'];
  for (const key of required) {
    if (typeof value[key] !== 'string' || value[key].length === 0) {
      throw new Error(`Runtime configuration field ${key} is invalid`);
    }
  }
  if (value.schema !== REQUIRED_SCHEMA || value.product !== 'InfraNexum' || value.component !== 'web') {
    throw new Error('Runtime configuration identity is invalid');
  }
  return Object.freeze({ ...value });
}

export function renderRuntimeConfiguration(documentObject, configuration) {
  documentObject.getElementById('runtime-version').textContent = configuration.version;
  documentObject.getElementById('runtime-environment').textContent = configuration.environment;
  documentObject.getElementById('runtime-api').textContent = configuration.apiBaseUrl;
  documentObject.getElementById('runtime-message').textContent = 'Runtime configuration loaded. The capability-driven React shell is not enabled in this foundation increment.';
}

export function renderRuntimeFailure(documentObject) {
  documentObject.getElementById('runtime-message').textContent = 'The Web runtime configuration could not be loaded. Contact an InfraNexum administrator.';
  documentObject.getElementById('runtime-message').setAttribute('data-state', 'error');
}

export async function bootstrap({ fetchFunction = fetch, documentObject = document } = {}) {
  try {
    const response = await fetchFunction('/runtime-config.json', {
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) {
      throw new Error(`Runtime configuration returned HTTP ${response.status}`);
    }
    renderRuntimeConfiguration(documentObject, validatePublicConfiguration(await response.json()));
  } catch {
    renderRuntimeFailure(documentObject);
  }
}

if (typeof document !== 'undefined') {
  void bootstrap();
}
