import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { Store } from './store.js';
import { BrowserWorkers } from './browser.js';
import { Coordinator } from './coordinator.js';
import { agentIds, type AgentId } from './types.js';

const escape = (text: string) => text.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]!));
async function body(req: IncomingMessage): Promise<Record<string, unknown>> {
  let raw = ''; for await (const part of req) { raw += String(part); if (raw.length > 16_384) throw new Error('Request too large.'); }
  const parsed: unknown = JSON.parse(raw);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Expected a JSON object.');
  return parsed as Record<string, unknown>;
}
export async function startApp(directory: string, port = 4180) {
  const store = new Store(directory), token = randomBytes(32).toString('hex'), fixtureKey = randomBytes(32).toString('hex');
  let coordinator: Coordinator | undefined, workers: BrowserWorkers | undefined, origin = '';
  const server = createServer(async (req, res) => {
    res.setHeader('X-Content-Type-Options','nosniff'); res.setHeader('Cache-Control','no-store');
    res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'");
    const send = (status: number, value: unknown) => { res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(value)); };
    try {
      if (!origin || req.headers.host !== new URL(origin).host) return send(403,{error:'Unexpected host.'});
      const url = new URL(req.url ?? '/',origin);
      if (url.pathname.startsWith('/api') && req.headers['sec-fetch-site'] === 'cross-site') return send(403,{error:'Cross-site API access denied.'});
      if (req.method === 'POST') {
        if (req.headers['content-type']?.split(';')[0] !== 'application/json') return send(415,{error:'JSON required.'});
        if (req.headers.origin && req.headers.origin !== origin) return send(403,{error:'Cross-origin request denied.'});
      }
      if (url.pathname === '/api/bootstrap' && req.method === 'GET') return send(200,{token,ready:!!coordinator});
      if (url.pathname === '/api/state' && req.method === 'GET') return coordinator ? send(200,coordinator.snapshot()) : send(503,{error:'Browser workers starting.'});
      if (url.pathname === '/api/command' && req.method === 'POST') {
        if (req.headers['x-diyalamp-token'] !== token) return send(403,{error:'Workspace token required.'});
        if (!coordinator) return send(503,{error:'Browser workers starting.'});
        const input = await body(req);
        if (input.command === 'plan') await coordinator.plan();
        else if (input.command === 'approve' && typeof input.id === 'string') {
          if (input.interrupt === 'crash' && process.env.DIYALAMP_ENABLE_TEST_CRASH !== '1') throw new Error('Process crash injection is disabled.');
          const crash = input.interrupt === 'crash' ? () => { process.kill(process.pid, 'SIGKILL'); throw new Error('Unreachable after test crash'); } : undefined;
          await coordinator.approve(input.id, input.interrupt === true, crash);
        }
        else if (input.command === 'pause') store.pause();
        else if (input.command === 'resume') store.update('you','workspace.resumed','Resume from recorded checkpoints.',run=>{run.paused=false;});
        else if (input.command === 'recover') await coordinator.recover();
        else if (input.command === 'currency' && (input.currency === 'USD' || input.currency === 'EUR')) coordinator.setCurrency(input.currency);
        else if (input.command === 'handoff') store.update('you','human.fixture_handoff','Controlled fixture handoff completed. This is not real authentication.',run=>{run.fixture.signedIn=true;});
        else throw new Error('Unknown command.');
        return send(200,coordinator.snapshot());
      }
      const screenshot = /^\/api\/screenshot\/(commerce|merch|domain)$/.exec(url.pathname);
      if (screenshot && req.method === 'GET') {
        if (!workers) return send(503,{error:'Browser starting.'});
        const png = await workers.screenshot(screenshot[1] as AgentId); res.writeHead(200,{'Content-Type':'image/png'});res.end(png);return;
      }
      const fixture = /^\/fixture\/(commerce|merch|domain)$/.exec(url.pathname);
      if (fixture && req.method === 'GET') {
        const agent = fixture[1] as AgentId, state = store.read(), receipt = Object.entries(state.fixture.receipts).filter(([,r])=>r.agent===agent).at(-1)?.[0] ?? '';
        const detail = agent==='commerce' ? `Store currency: ${state.fixture.currency || 'Not set'}` : agent==='merch' ? `Products: ${state.fixture.products.join(', ') || 'Not synced'}` : `Access: ${state.fixture.signedIn?'Fixture user handoff complete':'Human handoff needed'} · Domain: ${state.fixture.domainReady?'Verified':'Not verified'}`;
        res.writeHead(200,{'Content-Type':'text/html'});res.end(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="fixture-key" content="${fixtureKey}"><title>${agent} · controlled fixture</title><link rel="stylesheet" href="/style.css"></head><body class="fixture"><header>🪔 diyalamp / controlled fixture</header><main><p class="eyebrow">${agent} responsibility</p><h1>${agent==='commerce'?'Store settings':agent==='merch'?'Product sync':'Domain connection'}</h1><p>Local test page. No real service or account is connected.</p><section class="fixture-card"><h2>Shared decision</h2><p id="decision">${state.decision.currency} · revision ${state.decision.revision}</p><h2>Current result</h2><p id="fixture-detail">${escape(detail)}</p><p id="result" data-receipt="${receipt}">${receipt?'Recorded action '+receipt:'No action recorded'}</p></section><label>Approved action record<input id="proposal" autocomplete="off" placeholder="Supplied by the browser worker"></label><button id="execute">${agent==='commerce'?'Save currency':agent==='merch'?'Sync product drafts':'Verify connection'}</button><p id="error" role="status"></p></main><script src="/fixture.js" defer></script></body></html>`);return;
      }
      if (url.pathname === '/fixture-action' && req.method === 'POST') {
        if(req.headers['x-fixture-key']!==fixtureKey)return send(403,{error:'Fixture key required.'});
        const input=await body(req);if(typeof input.id!=='string')throw new Error('Action identity required.');
        const proposal=Object.values(store.read().tasks).find(t=>t.proposal?.id===input.id)?.proposal;
        if(!proposal)throw new Error('No matching dispatch.');store.fixtureAction(proposal);return send(200,{receipt:proposal.id});
      }
      if (url.pathname === '/fixture.js' && req.method === 'GET') {
        res.writeHead(200,{'Content-Type':'text/javascript'});res.end(`document.getElementById('execute').addEventListener('click',async()=>{const id=document.getElementById('proposal').value;try{const response=await fetch('/fixture-action',{method:'POST',headers:{'Content-Type':'application/json','x-fixture-key':document.querySelector('meta[name="fixture-key"]').content},body:JSON.stringify({id})});const result=await response.json();if(!response.ok)throw new Error(result.error);const node=document.getElementById('result');node.textContent='Recorded action '+result.receipt;node.dataset.receipt=result.receipt;document.getElementById('error').textContent='Action recorded in the local fixture.';}catch(error){document.getElementById('error').textContent=error.message;}});`);return;
      }
      const assets: Record<string,[string,string]> = {'/':['index.html','text/html'],'/app.js':['app.js','text/javascript'],'/style.css':['style.css','text/css'],'/favicon.svg':['favicon.svg','image/svg+xml'],'/fonts/inter-latin.woff2':['fonts/inter-latin.woff2','font/woff2']};
      if(req.method==='GET' && assets[url.pathname]){const [file,type]=assets[url.pathname];const bytes=await readFile(new URL(`../public/${file}`,import.meta.url));res.writeHead(200,{'Content-Type':type});res.end(bytes);return;}
      send(404,{error:'Not found.'});
    } catch(error) { if(!res.headersSent)send(409,{error:error instanceof Error?error.message:'Action failed.'});else res.end(); }
  });
  await new Promise<void>((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',()=>resolve());});
  const address=server.address();if(!address || typeof address==='string')throw new Error('No local port.');origin=`http://127.0.0.1:${address.port}`;
  store.restart();workers=new BrowserWorkers(origin);
  try{await workers.start();coordinator=new Coordinator(store,workers);}catch(error){await workers.close();server.close();store.close();throw error;}
  return {origin,token,store,coordinator,close:async()=>{await workers!.close();await new Promise<void>((resolve,reject)=>server.close(error=>error?reject(error):resolve()));store.close();}};
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  process.umask(0o077);
  const app=await startApp(process.env.DIYALAMP_DATA_DIR ?? fileURLToPath(new URL('../../.diyalamp/workspace/',import.meta.url)),Number(process.env.DIYALAMP_PORT ?? '4180'));
  console.log(JSON.stringify({ready:true,url:app.origin}));
  let closing=false;const close=async()=>{if(closing)return;closing=true;await app.close();process.exit(0);};process.on('SIGINT',close);process.on('SIGTERM',close);
}
