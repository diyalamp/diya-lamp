import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { Store } from '../src/store.js';
import { BrowserWorkers } from '../src/browser.js';
import { Coordinator } from '../src/coordinator.js';
function fixture(){const path=mkdtempSync(join(tmpdir(),'diyalamp-coordinator-'));const store=new Store(path);const browser=new BrowserWorkers('http://fixture.invalid');browser.observe=async()=> 'first-view';const coordinator=new Coordinator(store,browser);return {store,browser,coordinator,close(){store.close();rmSync(path,{recursive:true,force:true});}};}
test('expired proposals acquire a fresh generation and approval identity',async()=>{
 const f=fixture();try{await f.coordinator.plan();const old=f.store.read().tasks.commerce.proposal!;f.store.update('test','expire','fixture clock',run=>{for(const lease of Object.values(run.leases))lease.expires=0;});await f.coordinator.plan();const fresh=f.store.read().tasks.commerce.proposal!;assert.notEqual(fresh.id,old.id);assert.ok(fresh.generations.every((n,i)=>n>old.generations[i]));await assert.rejects(f.coordinator.approve(old.id),/pending/);}finally{f.close();}
});
test('failed observations release resources rather than deadlocking the next plan',async()=>{
 const f=fixture();try{f.browser.observe=async()=>{throw Error('Fixture observation failed');};await assert.rejects(f.coordinator.plan());assert.ok(Object.values(f.store.read().leases).every(lease=>lease.owner===null));f.browser.observe=async()=> 'recovered-view';await f.coordinator.plan();assert.equal(f.store.read().tasks.commerce.status,'proposed');}finally{f.close();}
});
test('a changed page invalidates approval before any browser command',async()=>{
 const f=fixture();try{await f.coordinator.plan();const id=f.store.read().tasks.commerce.proposal!.id;let calls=0;f.browser.execute=async()=>{calls++;return 'must not execute';};f.browser.observe=async()=> 'different-view';await assert.rejects(f.coordinator.approve(id),/Page changed/);assert.equal(calls,0);assert.equal(f.store.read().tasks.commerce.status,'pending');assert.ok(Object.values(f.store.read().leases).every(lease=>lease.owner===null));}finally{f.close();}
});
test('pause fences an in-flight fixture effect and leaves its intent uncertain',async()=>{
 const f=fixture();try{
  await f.coordinator.plan();const proposal=f.store.read().tasks.commerce.proposal!;
  let release!:()=>void,started!:()=>void;const barrier=new Promise<void>(resolve=>{release=resolve;});const dispatched=new Promise<void>(resolve=>{started=resolve;});
  f.browser.execute=async action=>{started();await barrier;f.store.fixtureAction(action);return 'recorded';};
  f.browser.receiptVisible=async()=>false;
  const inFlight=f.coordinator.approve(proposal.id);await dispatched;f.store.pause();release();await inFlight;
  assert.equal(f.store.read().tasks.commerce.status,'uncertain');assert.equal(Object.keys(f.store.read().fixture.receipts).length,0);assert.equal(f.store.read().fixture.currency,'');
 }finally{f.close();}
});
