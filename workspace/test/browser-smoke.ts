import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn, type ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import type { Snapshot } from '../src/types.js';

const path=await mkdtemp(join(tmpdir(),'diyalamp-browser-'));
let child:ChildProcess|undefined;
async function launch(){
 child=spawn(process.execPath,['--import','tsx','src/server.ts'],{cwd:resolve('.'),env:{...process.env,DIYALAMP_DATA_DIR:path,DIYALAMP_PORT:'0',DIYALAMP_ENABLE_TEST_CRASH:'1'},stdio:['ignore','pipe','pipe']});
 const running=child;let output='';let errors='';running.stderr!.on('data',data=>{errors+=String(data).slice(0,1000);});
 const origin=await new Promise<string>((resolve,reject)=>{const timeout=setTimeout(()=>reject(new Error('App startup timed out. '+errors)),30000);running.once('exit',code=>{clearTimeout(timeout);reject(new Error('App exited during startup: '+code+' '+errors));});running.stdout!.on('data',data=>{output+=String(data);const line=output.split('\n').find(line=>line.startsWith('{"ready":true'));if(line){clearTimeout(timeout);resolve(JSON.parse(line).url);}});});
 const {token}=await(await fetch(origin+'/api/bootstrap')).json() as {token:string};return {origin,token};
}
async function stop(){if(child&&child.exitCode===null&&child.signalCode===null){const ended=once(child,'exit');child.kill('SIGTERM');await ended;}child=undefined;}
let api: {origin:string;token:string};
async function state(){return await(await fetch(api.origin+'/api/state')).json() as Snapshot;}
async function command(command:string,extra:Record<string,unknown>={},expected=200){const response=await fetch(api.origin+'/api/command',{method:'POST',headers:{'Content-Type':'application/json','x-diyalamp-token':api.token},body:JSON.stringify({command,...extra})});const value=await response.json();assert.equal(response.status,expected,JSON.stringify(value));return value as Snapshot;}
try{
 api=await launch();
 let snapshot=await state();assert.equal(snapshot.tabs.length,3);
 assert.equal((await fetch(api.origin+'/api/screenshot/commerce')).headers.get('content-type'),'image/png');
 const forbidden=await fetch(api.origin+'/api/command',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"command":"plan"}'});assert.equal(forbidden.status,403);
 const cross=await fetch(api.origin+'/api/command',{method:'POST',headers:{'Content-Type':'application/json','x-diyalamp-token':api.token,Origin:'https://untrusted.invalid'},body:'{"command":"plan"}'});assert.equal(cross.status,403);
 snapshot=await command('plan');assert.equal(snapshot.run.tasks.commerce.status,'proposed');assert.equal(snapshot.run.tasks.merch.status,'pending');
 const stale=snapshot.run.tasks.commerce.proposal!.id;
 snapshot=await command('currency',{currency:'EUR'});assert.equal(snapshot.run.tasks.commerce.proposal,null);await command('approve',{id:stale},409);
 snapshot=await command('plan');const paused=snapshot.run.tasks.commerce.proposal!.id;await command('pause');await command('approve',{id:paused},409);await command('resume');
 snapshot=await command('plan');snapshot=await command('approve',{id:snapshot.run.tasks.commerce.proposal!.id});assert.equal(snapshot.run.fixture.currency,'EUR');assert.equal(snapshot.run.tasks.commerce.status,'done');
 snapshot=await command('plan');assert.equal(snapshot.run.tasks.merch.status,'proposed');
 const interrupted=snapshot.run.tasks.merch.proposal!.id;
 snapshot=await command('approve',{id:interrupted,interrupt:true});assert.equal(snapshot.run.tasks.merch.status,'uncertain');assert.equal(snapshot.run.fixture.products.length,2);
 const receipts=Object.keys(snapshot.run.fixture.receipts).length;
 await command('approve',{id:interrupted},409);await command('plan',{},409);
 const oldOrigin=api.origin;await stop();api=await launch();assert.notEqual(api.origin,oldOrigin);
 snapshot=await state();assert.equal(snapshot.run.tasks.commerce.status,'done');assert.equal(snapshot.run.tasks.merch.status,'uncertain');
 snapshot=await command('recover');assert.equal(snapshot.run.tasks.merch.status,'done');assert.equal(Object.keys(snapshot.run.fixture.receipts).length,receipts,'recovery must not replay the sync');
 snapshot=await command('plan');assert.equal(snapshot.run.tasks.domain.status,'pending');assert.ok(snapshot.events.some(event=>event.kind==='human.requested'));
 await command('handoff');snapshot=await command('plan');
 const crashedAction=snapshot.run.tasks.domain.proposal!.id;
 const crashed=once(child!,'exit');
 await assert.rejects(command('approve',{id:crashedAction,interrupt:'crash'}));
 await crashed;child=undefined;api=await launch();
 snapshot=await state();assert.equal(snapshot.run.tasks.domain.status,'uncertain');
 assert.equal(snapshot.run.fixture.domainReady,true,'browser effect committed before actual process crash');
 snapshot=await command('recover');
 assert.equal(snapshot.run.fixture.domainReady,true);assert.ok(Object.values(snapshot.run.tasks).every(task=>task.status==='done'));
 assert.equal(Object.keys(snapshot.run.fixture.receipts).length,3,'exactly three fixture effects across actual app/browser restart');
 assert.ok(snapshot.events.some(event=>event.kind==='task.reconciled'));
 console.log('PASS: three actual browser tabs; dependencies, stale approval rejection, pause fencing, controlled human handoff, app/browser restart, forced process crash after a real browser effect, and receipt-based recovery without replay.');
}finally{await stop();await rm(path,{recursive:true,force:true});}
