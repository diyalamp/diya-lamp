import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.js';
import { keysFor, type Proposal } from '../src/types.js';
function fixture() { const path=mkdtempSync(join(tmpdir(),'diyalamp-store-'));const store=new Store(path);return {store,path,close(){store.close();rmSync(path,{recursive:true,force:true});}}; }
test('atomic multi-resource acquisition and generation fencing across connections',()=>{
 const f=fixture(), second=new Store(f.path);
 try {
  const first=f.store.acquire(['tab:commerce','resource:store-catalog'],'worker-a',100,10);
  assert.throws(()=>second.acquire(['tab:merch','resource:store-catalog'],'worker-b',105,10),/busy/);
  assert.equal(second.read().leases['tab:merch'],undefined,'no partial lease');
  const next=second.acquire(['tab:commerce','resource:store-catalog'],'worker-b',111,10);
  assert.ok(next.every((generation,i)=>generation>first[i]));
  const old:Proposal={id:'old',agent:'commerce',owner:'worker-a',keys:keysFor('commerce'),generations:first,decisionRevision:1,observation:'old',summary:'old'};
  assert.throws(()=>f.store.assertLease(f.store.read(),old,112),/stale/);
 } finally {second.close();f.close();}
});
test('task and event updates roll back together',()=>{
 const f=fixture();try{const before=f.store.events().length;assert.throws(()=>f.store.update('test','fail','not committed',run=>{run.decision.currency='EUR';throw Error('rollback');}));assert.equal(f.store.read().decision.currency,'USD');assert.equal(f.store.events().length,before);}finally{f.close();}
});
test('pause and restart invalidate approvals and fence previous workers',()=>{
 const f=fixture();try{
  const generations=f.store.acquire(keysFor('commerce'),'worker');const p:Proposal={id:'pending',agent:'commerce',owner:'worker',keys:keysFor('commerce'),generations,decisionRevision:1,observation:'view',summary:'change'};
  f.store.update('test','proposal','pending',run=>{run.tasks.commerce={status:'proposed',proposal:p,checkpoint:''};});
  f.store.pause();assert.equal(f.store.read().tasks.commerce.status,'pending');assert.equal(f.store.read().tasks.commerce.proposal,null);assert.throws(()=>f.store.assertLease(f.store.read(),p));
  f.store.update('test','inflight','fixture',run=>{run.tasks.commerce={status:'dispatching',proposal:p,checkpoint:''};});f.store.restart();assert.equal(f.store.read().tasks.commerce.status,'uncertain');assert.equal(f.store.read().tasks.commerce.proposal?.id,'pending');
 }finally{f.close();}
});
test('resource bindings cannot omit the shared-resource lease',()=>{
 const f=fixture();try{
  assert.throws(()=>f.store.acquire(['tab:commerce','tab:commerce'],'worker'),/Invalid/);
  assert.throws(()=>f.store.acquire([],'worker'),/Invalid/);
  const generations=f.store.acquire(['tab:commerce'],'worker');
  assert.throws(()=>f.store.assertLease(f.store.read(),{id:'bad',agent:'commerce',owner:'worker',keys:['tab:commerce'],generations,decisionRevision:1,observation:'view',summary:'bad'}),/binding/);
 }finally{f.close();}
});
