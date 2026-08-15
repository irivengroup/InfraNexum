import assert from 'node:assert/strict';
import test from 'node:test';

import {
  FALLBACK_LOCALE,
  LOCALE_STORAGE_KEY,
  SUPPORTED_LOCALES,
  applyTranslations,
  initializeLocalization,
  initializeLanguageSwitcher,
  normalizeLocale,
  resolveLocale,
  setLocalizedElementText,
  translate,
} from '../../src/applications/web/public/assets/i18n.mjs';

class Element {
  constructor(attributes = {}) {
    this.attributes = { ...attributes };
    this.textContent = '';
    this.value = '';
    this.listeners = new Map();
    this.hidden = true;
    this.focused = false;
  }
  getAttribute(name) { return this.attributes[name] ?? null; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  change() { this.listeners.get('change')?.({ target: this }); }
  click() { this.listeners.get('click')?.({ target: this }); }
  keydown(key) { this.listeners.get('keydown')?.({ key, target: this, preventDefault() {} }); }
  focus() { this.focused = true; }
}

function localizationDocument() {
  const root = new Element({ lang: 'en' });
  const staticLabel = new Element({ 'data-i18n': 'nav.organizations' });
  const aria = new Element({ 'data-i18n-aria-label': 'command.open' });
  const placeholder = new Element({ 'data-i18n-placeholder': 'command.placeholder' });
  const dynamic = new Element();
  const selector = new Element();
  const groups = new Map([
    ['[data-i18n]', [staticLabel]],
    ['[data-i18n-aria-label]', [aria]],
    ['[data-i18n-placeholder]', [placeholder]],
    ['[data-i18n-dynamic]', [dynamic]],
    ['[data-i18n-aria-dynamic]', []],
  ]);
  return {
    documentElement: root,
    getElementById: (id) => id === 'language-select' ? selector : null,
    querySelectorAll: (selectorText) => groups.get(selectorText) ?? [],
    dispatchEvent() {},
    staticLabel,
    aria,
    placeholder,
    dynamic,
    selector,
  };
}

test('i18n supports exactly DE EN ES FR IT and normalizes browser locale variants', () => {
  assert.deepEqual(SUPPORTED_LOCALES, ['de', 'en', 'es', 'fr', 'it']);
  assert.equal(normalizeLocale('fr-FR'), 'fr');
  assert.equal(normalizeLocale('DE_de'), 'de');
  assert.equal(normalizeLocale('es-MX'), 'es');
  assert.equal(normalizeLocale('pt-BR'), null);
  assert.equal(normalizeLocale(''), null);
});

test('locale resolution prefers persisted choice, then browser preferences, then English fallback', () => {
  assert.equal(resolveLocale({
    storageObject: { getItem: () => 'it' },
    navigatorObject: { languages: ['fr-FR'] },
  }), 'it');
  assert.equal(resolveLocale({
    storageObject: { getItem: () => null },
    navigatorObject: { languages: ['pt-BR', 'es-ES'] },
  }), 'es');
  assert.equal(resolveLocale({
    storageObject: { getItem() { throw new Error('blocked'); } },
    navigatorObject: { language: 'pt-BR' },
  }), FALLBACK_LOCALE);
});

test('all supported locales translate navigation and interpolate safe runtime values', () => {
  for (const locale of SUPPORTED_LOCALES) {
    assert.notEqual(translate(locale, 'nav.organizations'), 'nav.organizations');
    assert.notEqual(translate(locale, 'notification.title'), 'notification.title');
    assert.notEqual(translate(locale, 'preference.title'), 'preference.title');
    assert.notEqual(translate(locale, 'platform.title'), 'platform.title');
    assert.match(translate(locale, 'common.version', { value: '2.0.0-alpha.0.85' }), /2\.0\.0-alpha\.0\.85/);
  }
  assert.equal(translate('pt', 'nav.overview'), 'Overview');
  assert.equal(translate('en', 'missing.translation.key'), 'missing.translation.key');
});

test('localization applies static and dynamic translations and persists explicit language choice', () => {
  const documentObject = localizationDocument();
  const values = new Map();
  const storage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };

  initializeLocalization(documentObject, storage, { languages: ['fr-FR', 'en-US'] });
  assert.equal(documentObject.documentElement.getAttribute('lang'), 'fr');
  assert.equal(documentObject.staticLabel.textContent, 'Organisations');
  assert.equal(documentObject.selector.value, 'fr');
  setLocalizedElementText(documentObject, documentObject.dynamic, 'organization.count', { count: 7 });
  assert.equal(documentObject.dynamic.textContent, '7 organisation(s)');

  documentObject.selector.value = 'de';
  documentObject.selector.change();
  assert.equal(values.get(LOCALE_STORAGE_KEY), 'de');
  assert.equal(documentObject.documentElement.getAttribute('lang'), 'de');
  assert.equal(documentObject.staticLabel.textContent, 'Organisationen');
  assert.equal(documentObject.dynamic.textContent, '7 Organisation(en)');
  assert.match(documentObject.placeholder.getAttribute('placeholder'), /Befehl/i);
});


test('stable language switcher stays open across background translation refreshes and closes only on explicit interaction', () => {
  const root = new Element({ lang: 'en' });
  const switcher = new Element();
  const trigger = new Element();
  const menu = new Element();
  const current = new Element();
  const options = SUPPORTED_LOCALES.map((locale) => new Element({ 'data-locale': locale }));
  const byId = new Map([
    ['language-switcher', switcher],
    ['language-trigger', trigger],
    ['language-menu', menu],
    ['language-current', current],
  ]);
  switcher.contains = (target) => target === trigger || target === menu || options.includes(target);
  const documentListeners = new Map();
  const documentObject = {
    documentElement: root,
    getElementById: (id) => byId.get(id) ?? null,
    querySelectorAll: (query) => query === '[data-locale]' ? options : [],
    addEventListener: (name, listener) => documentListeners.set(name, listener),
  };
  const selected = [];
  const commitLocale = (locale) => {
    selected.push(locale);
    applyTranslations(documentObject, locale);
  };

  const controller = initializeLanguageSwitcher(documentObject, commitLocale);
  trigger.click();
  assert.equal(controller.isOpen(), true);
  assert.equal(menu.hidden, false);
  assert.equal(trigger.getAttribute('aria-expanded'), 'true');

  // Runtime/capability refreshes may re-apply translated text. They must not collapse
  // an operator's in-progress language choice.
  applyTranslations(documentObject, 'en');
  assert.equal(controller.isOpen(), true);
  assert.equal(menu.hidden, false);

  options[3].click();
  assert.deepEqual(selected, ['fr']);
  assert.equal(root.getAttribute('lang'), 'fr');
  assert.equal(current.textContent, 'FR');
  assert.equal(options[3].getAttribute('aria-selected'), 'true');
  assert.equal(menu.hidden, true);
  assert.equal(trigger.getAttribute('aria-expanded'), 'false');

  trigger.click();
  documentListeners.get('pointerdown')?.({ target: {} });
  assert.equal(menu.hidden, true);
});

test('applyTranslations fails safe on malformed dynamic parameter metadata', () => {
  const documentObject = localizationDocument();
  documentObject.dynamic.setAttribute('data-i18n-dynamic', 'common.version');
  documentObject.dynamic.setAttribute('data-i18n-params', '{broken-json');
  assert.doesNotThrow(() => applyTranslations(documentObject, 'it'));
  assert.equal(documentObject.dynamic.textContent, 'Versione {value}');
});
