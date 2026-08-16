import assert from 'node:assert/strict';
import test from 'node:test';

import { fillSelect } from '../../src/applications/web/public/assets/web-workspace-utils.mjs';

class FakeOption {
  constructor() { this.value=''; this.textContent=''; this.disabled=false; this.selected=false; }
}
class FakeSelect {
  constructor({ multiple=false, required=false, value='', disabled=false }={}) {
    this.multiple=multiple; this.required=required; this.value=value; this.disabled=disabled;
    this.options=[]; this.selectedOptions=[]; this.attributes=new Map(); this.events=[];
  }
  replaceChildren(...nodes) {
    this.options=nodes;
    const selected=nodes.filter((node)=>node.selected);
    this.selectedOptions=selected;
    if (!this.multiple && selected.length) this.value=selected[0].value;
  }
  setAttribute(name,value){this.attributes.set(name,String(value));}
  dispatchEvent(event){this.events.push(event.type);return true;}
}
const documentObject={
  defaultView:{Event:class { constructor(type,init={}){this.type=type;this.bubbles=init.bubbles;} }},
  documentElement:{getAttribute:(name)=>name==='lang'?'en':null},
  createElement:(tag)=>{assert.equal(tag,'option');return new FakeOption();},
};

test('empty governed select remains interactive and exposes an explicit empty state',()=>{
  const select=new FakeSelect({required:true,disabled:true});
  const selected=fillSelect(documentObject,select,[]);
  assert.equal(selected,'');
  assert.equal(select.disabled,false);
  assert.equal(select.options.length,1);
  assert.equal(select.options[0].value,'');
  assert.equal(select.options[0].disabled,false);
  assert.equal(select.attributes.get('data-inx-select-state'),'empty');
  assert.equal(select.attributes.get('aria-disabled'),'false');
  assert.deepEqual(select.events,['infranexum:entity-sync']);
});

test('selectFirst makes the first available business entity immediately usable',()=>{
  const select=new FakeSelect({required:true});
  const selected=fillSelect(documentObject,select,[{id:'org-a',displayName:'Alpha'},{id:'org-b',displayName:'Beta'}],{selectFirst:true});
  assert.equal(selected,'org-a');
  assert.equal(select.value,'org-a');
  assert.equal(select.disabled,false);
  assert.equal(select.attributes.get('data-inx-select-state'),'ready');
  assert.deepEqual(select.events,['infranexum:entity-sync']);
});

test('empty multi-select stays interactive without injecting a fake selectable value',()=>{
  const select=new FakeSelect({multiple:true,disabled:true});
  fillSelect(documentObject,select,[]);
  assert.equal(select.disabled,false);
  assert.equal(select.options.length,0);
  assert.equal(select.attributes.get('data-inx-select-state'),'empty');
});
