import assert from 'node:assert/strict';
import test from 'node:test';

import { COUNTRY_CATALOG, groupedCountries } from '../../src/applications/web/public/assets/country-catalog.mjs';

test('country catalogue exposes the complete ISO 3166-1 alpha-2 set exactly once', () => {
  assert.equal(COUNTRY_CATALOG.length, 249);
  const codes = COUNTRY_CATALOG.map((country) => country.code);
  assert.equal(new Set(codes).size, 249);
  for (const code of codes) assert.match(code, /^[A-Z]{2}$/);
});

test('country catalogue is grouped by human continents and preserves alpha-2 values', () => {
  const groups = groupedCountries('fr');
  const names = new Set(groups.map((group) => group.continent));
  for (const expected of ['Africa', 'North America', 'South America', 'Asia', 'Europe', 'Oceania', 'Antarctica']) {
    assert.equal(names.has(expected), true, `missing ${expected}`);
  }
  const france = groups.flatMap((group) => group.countries).find((country) => country.code === 'FR');
  assert.equal(france?.name, 'France');
});

class CountryNode extends EventTarget {
  constructor(tagName) { super(); this.tagName = tagName.toUpperCase(); this.children = []; this.value = ''; this.textContent = ''; this.label = ''; this.disabled = false; this.attributes = new Map(); }
  appendChild(node) { this.children.push(node); node.parentElement = this; return node; }
  replaceChildren(...nodes) { this.children = []; for (const node of nodes) this.appendChild(node); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
}

test('country select renders grouped optgroups and uses alpha-2 as the submitted value', async () => {
  const { populateCountrySelect } = await import('../../src/applications/web/public/assets/country-catalog.mjs');
  const select = new CountryNode('select');
  const documentObject = { defaultView: { Event }, createElement: (tag) => new CountryNode(tag) };
  populateCountrySelect(documentObject, select, 'fr');
  const groups = select.children.filter((child) => child.tagName === 'OPTGROUP');
  assert.equal(groups.length, 7);
  const france = groups.flatMap((group) => group.children).find((option) => option.value === 'FR');
  assert.ok(france);
  assert.equal(france.textContent, 'France');
  assert.doesNotMatch(france.textContent, /FR/);
  assert.equal(france.value, 'FR');
  assert.equal(select.getAttribute('data-inx-country-ready'), 'true');
});
