import type { Snapshot, AgentId } from '../src/types.js';
const get = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const names = { commerce: 'Commerce', merch: 'Merch', domain: 'Domain' };
const details = { commerce: 'Store settings', merch: 'Products & handoff', domain: 'Connection check' };
let token = '', current: Snapshot | undefined, selected: AgentId = 'commerce', busy = false, pendingRequests = 0, lastRevision = -1;
function el(tag: string, text = '', className = '') { const node = document.createElement(tag); node.textContent = text; node.className = className; return node; }
function notice(text: string, error = false) { get('notice').textContent = text; get('notice').classList.toggle('error',error); }
async function command(command: string, extras: Record<string,unknown> = {}) {
  if(busy&&command!=='pause')return;pendingRequests++;busy=true;document.body.classList.add('busy');
  try {
    const response=await fetch('/api/command',{method:'POST',headers:{'Content-Type':'application/json','x-diyalamp-token':token},body:JSON.stringify({command,...extras})});
    const result=await response.json();if(!response.ok)throw new Error(result.error);
    if(!current||result.run.id!==current.run.id||result.run.revision>=current.run.revision){current=result;render();}notice(command==='recover'&&Object.values(current!.run.tasks).some(task=>task.status==='uncertain')?'Some outcomes could not be verified. They remain blocked; no action was repeated.':Object.values(current!.run.tasks).every(task=>task.status==='done')?'All three fixture tasks are verified. No real account was changed.':'Recorded. The workspace reflects the durable coordinator state.');
  } catch(error) { notice(error instanceof Error?error.message:'Request failed.',true);await refresh(); }
  finally {pendingRequests--;busy=pendingRequests>0;document.body.classList.toggle('busy',busy);}
}
function screenshot(){const img=get<HTMLImageElement>('browser-image');img.src=`/api/screenshot/${selected}?v=${current?.run.revision ?? 0}`;get<HTMLAnchorElement>('full-capture').href=img.src;get('address').textContent=current?.tabs.find(t=>t.id===selected)?.url ?? '';}
function leaseStatus(){
  if(!current)return;const lease=current.run.leases[`tab:${selected}`];
  get('tab-state').textContent=lease?.owner&&lease.expires>Date.now()?`${names[selected]} owns this tab · generation ${lease.generation}`:'Tab available · No current lease';
}
function render() {
  if(!current)return;const {run}=current;
  const team=get('agents');team.replaceChildren();
  for(const id of ['commerce','merch','domain'] as AgentId[]){const task=run.tasks[id],button=el('button','','agent');button.classList.toggle('selected',id===selected);button.setAttribute('aria-pressed',String(id===selected));const avatar=el('span',names[id][0],'avatar');const text=el('span');text.append(el('strong',names[id]),el('span',details[id],'detail'),el('span',task.status==='pending'?(id==='merch'&&run.tasks.commerce.status!=='done'?'Waiting for Commerce':id==='domain'&&run.tasks.merch.status!=='done'?'Waiting for Merch':id==='domain'&&!run.fixture.signedIn?'Needs your handoff':'Ready to plan'):task.status,'status'));button.append(avatar,text);button.onclick=()=>{selected=id;render();};team.append(button);}
  get<HTMLSelectElement>('currency').value=run.decision.currency;get('decision-version').textContent=`Revision ${run.decision.revision}`;
  get('pause').textContent=run.paused?'Resume':'Pause';get('plan').toggleAttribute('disabled',run.paused);
  const tabs=get('tabs');tabs.replaceChildren();
  for(const id of ['commerce','merch','domain'] as AgentId[]){const button=el('button',names[id]);button.setAttribute('aria-pressed',String(id===selected));button.onclick=()=>{selected=id;render();};tabs.append(button);}
  leaseStatus();
  const approvals=get('approvals');approvals.replaceChildren();let count=0;
  for(const id of ['commerce','merch','domain'] as AgentId[]){const task=run.tasks[id];if(task.proposal){count++;const card=el('article','','approval');card.append(el('strong',names[id]),el('p',task.proposal.summary),el('p',`Decision revision ${task.proposal.decisionRevision} · ${task.status}`,'detail'));if(task.status==='proposed'){const button=el('button','Approve this action');(button as HTMLButtonElement).disabled=run.paused;const proposalId=task.proposal.id;button.onclick=()=>command('approve',{id:proposalId,interrupt:get<HTMLInputElement>('interrupt').checked}).then(()=>{get<HTMLInputElement>('interrupt').checked=false;});card.append(button);}if(task.status==='uncertain')card.append(el('p','No replay. Check the fixture result before continuing.'));approvals.append(card);}}
  if(!count)approvals.append(el('p',Object.values(run.tasks).every(t=>t.status==='done')?'All three fixture tasks verified.':'Plan the next eligible step. Dependencies determine which worker can propose an action.'));
  get('handoff').hidden=run.tasks.merch.status!=='done'||run.fixture.signedIn;
  const events=get('events');events.replaceChildren();for(const event of [...current.events].reverse()){const li=el('li');li.append(el('strong',`${event.seq} · ${event.actor}`),el('span',event.kind,'detail'),el('p',event.detail));events.append(li);}
  if(lastRevision!==run.revision){screenshot();lastRevision=run.revision;}else screenshot();
}
async function refresh(){try{const response=await fetch('/api/state');const result=await response.json();if(!response.ok)throw new Error(result.error);const first=!current;if(!current||result.run.id!==current.run.id||result.run.revision>current.run.revision){current=result;render();}leaseStatus();if(first)notice('Local fixture ready. Plan a step, inspect the proposal, then approve it.');}catch(error){notice(error instanceof Error?error.message:'Coordinator unavailable.',true);}}
get('plan').onclick=()=>command('plan');get('pause').onclick=()=>command(current?.run.paused?'resume':'pause');get('recover').onclick=()=>command('recover');get('handoff').onclick=()=>command('handoff');get<HTMLSelectElement>('currency').onchange=event=>command('currency',{currency:(event.target as HTMLSelectElement).value});
try{const bootstrap=await(await fetch('/api/bootstrap')).json();token=bootstrap.token;await refresh();if(!current)notice('Browser workers are starting. The workspace will appear shortly.');}catch{notice('Could not connect. Reload when the coordinator is running.',true);}
setInterval(()=>{if(!busy)void refresh();},1500);
