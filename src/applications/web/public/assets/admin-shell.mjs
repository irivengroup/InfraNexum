import { localeFromDocument, setLocalizedText, translate } from './i18n.mjs';

const ROUTES = Object.freeze({
  overview: Object.freeze({ viewId: 'overview-view', labelKey: 'nav.overview', titleKey: 'topbar.dashboard' }),
  organizations: Object.freeze({ viewId: 'organization-workspace', labelKey: 'nav.organizations', titleKey: 'topbar.organizations' }),
  access: Object.freeze({ viewId: 'identity-access-workspace', labelKey: 'nav.access', titleKey: 'topbar.access' }),
  rsot: Object.freeze({ viewId: 'rsot-workspace', labelKey: 'nav.rsot', titleKey: 'topbar.rsot' }),
  itam: Object.freeze({ viewId: 'itam-workspace', labelKey: 'nav.itam', titleKey: 'topbar.itam' }),
  dcim: Object.freeze({ viewId: 'dcim-workspace', labelKey: 'nav.dcim', titleKey: 'topbar.dcim' }),
  ddi: Object.freeze({ viewId: 'ddi-workspace', labelKey: 'nav.ddi', titleKey: 'topbar.ddi' }),
  swagger: Object.freeze({ viewId: 'swagger-workspace', labelKey: 'nav.swagger', titleKey: 'topbar.swagger' }),
  redoc: Object.freeze({ viewId: 'redoc-workspace', labelKey: 'nav.redoc', titleKey: 'topbar.redoc' }),
});

export function normalizeRoute(value) {
  if (typeof value !== 'string') return 'overview';
  const normalized = value.replace(/^#\/?/, '').trim().toLowerCase();
  return Object.hasOwn(ROUTES, normalized) ? normalized : 'overview';
}

export function routeForHash(hash) {
  return normalizeRoute(hash);
}

export function filterCommands(commands, query, locale = 'en') {
  const terms = searchable(query).split(/\s+/).filter(Boolean);
  if (terms.length === 0) return commands.slice();
  return commands.filter((command) => {
    const haystack = searchable([
      translate(locale, command.titleKey),
      translate(locale, command.descriptionKey),
      translate(locale, command.categoryKey),
    ].join(' '));
    return terms.every((term) => haystack.includes(term));
  });
}

export function setOrganizationAvailability(documentObject, enabled, windowObject = globalThis.window) {
  const available = enabled === true;
  const link = documentObject?.getElementById?.('nav-organizations');
  if (link) {
    link.hidden = !available;
    link.setAttribute?.('aria-disabled', available ? 'false' : 'true');
    link.setAttribute?.('data-capability-enabled', String(available));
  }
  const workspace = documentObject?.getElementById?.('organization-workspace');
  workspace?.setAttribute?.('data-capability-enabled', String(available));
  const heroAction = documentObject?.getElementById?.('hero-organization-action');
  if (heroAction) {
    heroAction.hidden = !available;
    heroAction.setAttribute?.('aria-disabled', available ? 'false' : 'true');
  }
  if (!available && currentRoute(documentObject) === 'organizations') {
    applyRoute(documentObject, 'overview', windowObject, { replaceHash: true });
  } else {
    applyRoute(documentObject, currentRoute(documentObject), windowObject, { replaceHash: false });
  }
}


export function setRsotAvailability(documentObject, enabled, windowObject = globalThis.window) {
  setCapabilityRouteAvailability(documentObject, 'rsot', enabled, windowObject);
}

export function setItamAvailability(documentObject, enabled, windowObject = globalThis.window) {
  setCapabilityRouteAvailability(documentObject, 'itam', enabled, windowObject);
}

export function setDcimAvailability(documentObject, enabled, windowObject = globalThis.window) {
  setCapabilityRouteAvailability(documentObject, 'dcim', enabled, windowObject);
}

export function setDdiAvailability(documentObject, enabled, windowObject = globalThis.window) {
  setCapabilityRouteAvailability(documentObject, 'ddi', enabled, windowObject);
}

function setCapabilityRouteAvailability(documentObject, route, enabled, windowObject) {
  const available = enabled === true;
  const link = documentObject?.getElementById?.(`nav-${route}`);
  if (link) {
    link.hidden = !available;
    link.setAttribute?.('aria-disabled', available ? 'false' : 'true');
    link.setAttribute?.('data-capability-enabled', String(available));
  }
  const definition = ROUTES[route];
  const workspace = definition ? documentObject?.getElementById?.(definition.viewId) : null;
  workspace?.setAttribute?.('data-capability-enabled', String(available));
  if (!available && currentRoute(documentObject) === route) {
    applyRoute(documentObject, 'overview', windowObject, { replaceHash: true });
  } else {
    applyRoute(documentObject, currentRoute(documentObject), windowObject, { replaceHash: false });
  }
}

export function setIdentityAccessAvailability(documentObject, enabled, windowObject = globalThis.window) {
  const available = enabled === true;
  const link = documentObject?.getElementById?.('nav-access');
  if (link) {
    link.hidden = !available;
    link.setAttribute?.('aria-disabled', available ? 'false' : 'true');
    link.setAttribute?.('data-capability-enabled', String(available));
  }
  const workspace = documentObject?.getElementById?.('identity-access-workspace');
  workspace?.setAttribute?.('data-capability-enabled', String(available));
  if (!available && currentRoute(documentObject) === 'access') {
    applyRoute(documentObject, 'overview', windowObject, { replaceHash: true });
  } else {
    applyRoute(documentObject, currentRoute(documentObject), windowObject, { replaceHash: false });
  }
}

export function applyRoute(
  documentObject,
  requestedRoute,
  windowObject = globalThis.window,
  { replaceHash = false } = {},
) {
  let route = normalizeRoute(requestedRoute);
  if (route === 'organizations' && !organizationsAvailable(documentObject)) route = 'overview';
  if (route === 'access' && !identityAccessAvailable(documentObject)) route = 'overview';
  if (route === 'rsot' && !capabilityRouteAvailable(documentObject, 'rsot')) route = 'overview';
  if (route === 'itam' && !capabilityRouteAvailable(documentObject, 'itam')) route = 'overview';
  if (route === 'dcim' && !capabilityRouteAvailable(documentObject, 'dcim')) route = 'overview';
  if (route === 'ddi' && !capabilityRouteAvailable(documentObject, 'ddi')) route = 'overview';

  for (const [name, definition] of Object.entries(ROUTES)) {
    const view = documentObject?.getElementById?.(definition.viewId);
    if (view) view.hidden = name !== route;
  }
  for (const link of documentObject?.querySelectorAll?.('[data-route]') ?? []) {
    const selected = link.getAttribute?.('data-route') === route;
    link.classList?.toggle?.('active', selected);
    if (selected) link.setAttribute?.('aria-current', 'page');
    else link.removeAttribute?.('aria-current');
  }

  documentObject?.documentElement?.setAttribute?.('data-route', route);
  const definition = ROUTES[route];
  setLocalizedText(documentObject, 'breadcrumb-current', definition.labelKey);
  setLocalizedText(documentObject, 'topbar-page-title', definition.titleKey);
  if (documentObject) {
    documentObject.title = `InfraNexum — ${translate(localeFromDocument(documentObject), definition.titleKey)}`;
  }

  const expectedHash = `#/${route}`;
  if (windowObject?.location && windowObject.location.hash !== expectedHash) {
    if (replaceHash && windowObject.history?.replaceState) {
      windowObject.history.replaceState(null, '', expectedHash);
    } else if (windowObject.history?.pushState) {
      windowObject.history.pushState(null, '', expectedHash);
    }
  }
  dispatchRouteChange(documentObject, route);
  return route;
}

function dispatchRouteChange(documentObject, route) {
  try {
    const EventConstructor = documentObject?.defaultView?.CustomEvent ?? globalThis.CustomEvent;
    if (typeof EventConstructor === 'function') {
      documentObject?.dispatchEvent?.(new EventConstructor('infranexum:route-change', { detail: { route } }));
    } else {
      documentObject?.dispatchEvent?.({ type: 'infranexum:route-change', detail: { route } });
    }
  } catch {
    // Route application is authoritative; documentation lazy-loading is best-effort.
  }
}

export function initializeAdminShell(documentObject = document, windowObject = globalThis.window) {
  const navigation = initializeResponsiveNavigation(documentObject, windowObject);
  const initialRoute = routeForHash(windowObject?.location?.hash ?? '');
  applyRoute(documentObject, initialRoute, windowObject, { replaceHash: initialRoute === 'overview' && Boolean(windowObject?.location?.hash) });

  for (const link of documentObject?.querySelectorAll?.('[data-route]') ?? []) {
    link.addEventListener?.('click', (event) => {
      if (link.getAttribute?.('aria-disabled') === 'true') return;
      event?.preventDefault?.();
      applyRoute(documentObject, link.getAttribute?.('data-route'), windowObject);
      navigation.close();
      documentObject?.getElementById?.('main')?.focus?.({ preventScroll: true });
    });
  }

  windowObject?.addEventListener?.('hashchange', () => {
    applyRoute(documentObject, routeForHash(windowObject.location.hash), windowObject, { replaceHash: true });
  });

  const palette = initializeCommandPalette(documentObject, windowObject);
  documentObject?.addEventListener?.('infranexum:locale-change', () => {
    applyRoute(documentObject, currentRoute(documentObject), windowObject, { replaceHash: false });
    palette.refresh();
  });
  return Object.freeze({
    navigate: (route) => applyRoute(documentObject, route, windowObject),
    refresh: () => applyRoute(documentObject, currentRoute(documentObject), windowObject, { replaceHash: false }),
    openCommandPalette: palette.open,
  });
}

function initializeResponsiveNavigation(documentObject, windowObject) {
  const sidebar = documentObject?.getElementById?.('primary-sidebar');
  const toggle = documentObject?.getElementById?.('sidebar-toggle');
  const closeButton = documentObject?.getElementById?.('sidebar-close');
  const backdrop = documentObject?.getElementById?.('sidebar-backdrop');
  const isDesktop = () => windowObject?.matchMedia?.('(min-width: 992px)')?.matches === true;
  const setOpen = (open) => {
    if (!sidebar) return;
    const visible = open === true || isDesktop();
    sidebar.classList?.toggle?.('d-none', !visible);
    sidebar.setAttribute?.('aria-hidden', visible ? 'false' : 'true');
    toggle?.setAttribute?.('aria-expanded', open && !isDesktop() ? 'true' : 'false');
    backdrop?.classList?.toggle?.('d-none', !(open && !isDesktop()));
  };
  const close = () => setOpen(false);
  toggle?.addEventListener?.('click', () => setOpen(toggle.getAttribute?.('aria-expanded') !== 'true'));
  closeButton?.addEventListener?.('click', close);
  backdrop?.addEventListener?.('click', close);
  windowObject?.addEventListener?.('resize', () => setOpen(false));
  setOpen(false);
  return Object.freeze({ open: () => setOpen(true), close });
}

export function buildCommands(documentObject, windowObject = globalThis.window) {
  const commands = [
    command('overview', 'command.category.workspace', 'command.overview.title', 'command.overview.description', () => {
      applyRoute(documentObject, 'overview', windowObject);
    }),
    command('runtime', 'command.category.workspace', 'command.runtime.title', 'command.runtime.description', () => {
      applyRoute(documentObject, 'overview', windowObject);
      documentObject?.getElementById?.('runtime-title')?.scrollIntoView?.({ behavior: reducedMotion(windowObject) ? 'auto' : 'smooth', block: 'center' });
    }),
    command('theme', 'command.category.appearance', 'command.theme.title', 'command.theme.description', () => {
      documentObject?.getElementById?.('theme-toggle')?.click?.();
    }),
    command('preferences', 'command.category.appearance', 'command.preferences.title', 'command.preferences.description', () => {
      documentObject?.getElementById?.('preferences-trigger')?.click?.();
    }),
    command('notifications', 'command.category.system', 'command.notifications.title', 'command.notifications.description', () => {
      documentObject?.getElementById?.('notification-trigger')?.click?.();
    }),
    command('swagger', 'command.category.documentation', 'command.swagger.title', 'command.swagger.description', () => {
      applyRoute(documentObject, 'swagger', windowObject);
    }),
    command('redoc', 'command.category.documentation', 'command.redoc.title', 'command.redoc.description', () => {
      applyRoute(documentObject, 'redoc', windowObject);
    }),
  ];
  if (capabilityRouteAvailable(documentObject, 'itam')) {
    commands.splice(1, 0, command('itam', 'command.category.workspace', 'command.itam.title', 'command.itam.description', () => {
      applyRoute(documentObject, 'itam', windowObject);
    }));
  }
  if (capabilityRouteAvailable(documentObject, 'ddi')) {
    commands.splice(1, 0, command('ddi', 'command.category.workspace', 'command.ddi.title', 'command.ddi.description', () => {
      applyRoute(documentObject, 'ddi', windowObject);
    }));
  }
  if (capabilityRouteAvailable(documentObject, 'dcim')) {
    commands.splice(1, 0, command('dcim', 'command.category.workspace', 'command.dcim.title', 'command.dcim.description', () => {
      applyRoute(documentObject, 'dcim', windowObject);
    }));
  }
  if (capabilityRouteAvailable(documentObject, 'rsot')) {
    commands.splice(1, 0, command('rsot', 'command.category.workspace', 'command.rsot.title', 'command.rsot.description', () => {
      applyRoute(documentObject, 'rsot', windowObject);
    }));
  }
  if (organizationsAvailable(documentObject)) {
    commands.splice(1, 0, command('organizations', 'command.category.workspace', 'command.organizations.title', 'command.organizations.description', () => {
      applyRoute(documentObject, 'organizations', windowObject);
    }));
  }
  if (identityAccessAvailable(documentObject)) {
    commands.splice(1, 0, command('access', 'command.category.workspace', 'command.access.title', 'command.access.description', () => {
      applyRoute(documentObject, 'access', windowObject);
    }));
  }
  return commands;
}

function initializeCommandPalette(documentObject, windowObject) {
  const dialog = documentObject?.getElementById?.('command-palette');
  const trigger = documentObject?.getElementById?.('command-palette-trigger');
  const closer = documentObject?.getElementById?.('command-palette-close');
  const input = documentObject?.getElementById?.('command-search');
  const results = documentObject?.getElementById?.('command-results');
  if (!dialog || !trigger || !closer || !input || !results) {
    return Object.freeze({ open() {}, refresh() {} });
  }

  let rendered = [];
  let activeIndex = 0;

  const close = () => {
    if (dialog.open) dialog.close?.();
    trigger.setAttribute?.('aria-expanded', 'false');
    input.setAttribute?.('aria-expanded', 'false');
    trigger.focus?.();
  };
  const execute = (index) => {
    const selected = rendered[index];
    if (!selected) return;
    close();
    selected.run();
  };
  const render = () => {
    const locale = localeFromDocument(documentObject);
    rendered = filterCommands(buildCommands(documentObject, windowObject), input.value ?? '', locale);
    activeIndex = rendered.length === 0 ? -1 : Math.min(Math.max(activeIndex, 0), rendered.length - 1);
    results.replaceChildren?.(...rendered.map((item, index) => commandElement(documentObject, item, index, locale, () => execute(index), index === activeIndex)));
    if (rendered.length === 0) {
      const empty = documentObject.createElement?.('div');
      if (empty) {
        empty.className = 'list-group-item text-body-secondary text-center py-4';
        empty.textContent = translate(locale, 'command.empty');
        results.replaceChildren?.(empty);
      }
    }
    syncActiveDescendant(input, rendered, activeIndex);
  };
  const open = () => {
    input.value = '';
    activeIndex = 0;
    render();
    if (!dialog.open) dialog.showModal?.();
    trigger.setAttribute?.('aria-expanded', 'true');
    input.setAttribute?.('aria-expanded', 'true');
    input.focus?.();
  };

  trigger.addEventListener?.('click', open);
  closer.addEventListener?.('click', close);
  input.addEventListener?.('input', () => { activeIndex = 0; render(); });
  input.addEventListener?.('keydown', (event) => {
    if (event.key === 'ArrowDown' && rendered.length > 0) {
      event.preventDefault?.();
      activeIndex = (activeIndex + 1) % rendered.length;
      render();
    } else if (event.key === 'ArrowUp' && rendered.length > 0) {
      event.preventDefault?.();
      activeIndex = (activeIndex - 1 + rendered.length) % rendered.length;
      render();
    } else if (event.key === 'Enter' && activeIndex >= 0) {
      event.preventDefault?.();
      execute(activeIndex);
    } else if (event.key === 'Escape') {
      event.preventDefault?.();
      close();
    }
  });
  documentObject?.addEventListener?.('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === 'k') {
      event.preventDefault?.();
      open();
    }
  });
  dialog.addEventListener?.('click', (event) => {
    if (event.target === dialog) close();
  });

  return Object.freeze({ open, refresh: render });
}

function command(id, categoryKey, titleKey, descriptionKey, run) {
  return Object.freeze({ id, categoryKey, titleKey, descriptionKey, run });
}

function commandElement(documentObject, item, index, locale, onClick, active) {
  const button = documentObject.createElement('button');
  button.type = 'button';
  button.id = `command-option-${index}`;
  button.className = `list-group-item list-group-item-action d-flex align-items-center gap-3${active ? ' active' : ''}`;
  button.setAttribute('role', 'option');
  button.setAttribute('aria-selected', active ? 'true' : 'false');

  const icon = documentObject.createElement('span');
  icon.className = 'badge text-bg-primary';
  icon.setAttribute('aria-hidden', 'true');
  icon.textContent = item.id === 'theme' ? '◐' : item.id === 'organizations' ? '◎' : item.id === 'access' ? '⚿' : item.id === 'runtime' ? '◇' : '◫';

  const copy = documentObject.createElement('span');
  copy.className = 'd-flex flex-column flex-grow-1';
  const title = documentObject.createElement('strong');
  title.textContent = translate(locale, item.titleKey);
  const description = documentObject.createElement('span');
  description.textContent = translate(locale, item.descriptionKey);
  copy.append(title, description);

  const category = documentObject.createElement('span');
  category.className = 'badge text-bg-light text-body-secondary';
  category.textContent = translate(locale, item.categoryKey);

  button.append(icon, copy, category);
  button.addEventListener('click', onClick);
  return button;
}

function syncActiveDescendant(input, commands, activeIndex) {
  if (activeIndex >= 0 && commands[activeIndex]) input.setAttribute?.('aria-activedescendant', `command-option-${activeIndex}`);
  else input.removeAttribute?.('aria-activedescendant');
}

function searchable(value) {
  return String(value ?? '')
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .trim();
}

function capabilityRouteAvailable(documentObject, route) {
  return documentObject?.getElementById?.(`nav-${route}`)?.getAttribute?.('data-capability-enabled') === 'true';
}

function organizationsAvailable(documentObject) {
  const workspace = documentObject?.getElementById?.('organization-workspace');
  return workspace?.getAttribute?.('data-capability-enabled') === 'true';
}

function identityAccessAvailable(documentObject) {
  const workspace = documentObject?.getElementById?.('identity-access-workspace');
  return workspace?.getAttribute?.('data-capability-enabled') === 'true';
}

function currentRoute(documentObject) {
  return normalizeRoute(documentObject?.documentElement?.getAttribute?.('data-route') ?? 'overview');
}

function reducedMotion(windowObject) {
  try {
    return Boolean(windowObject?.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
  } catch {
    return true;
  }
}
