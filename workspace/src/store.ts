import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, chmodSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { agentIds, dependsOn, keysFor, type RunState, type Event, type AgentId, type Proposal } from './types.js';

export class Store {
  readonly db: DatabaseSync;
  constructor(directory: string) {
    const path = resolve(directory); mkdirSync(path, { recursive: true, mode: 0o700 }); chmodSync(path, 0o700);
    this.db = new DatabaseSync(join(path, 'workspace.sqlite'));
    chmodSync(join(path, 'workspace.sqlite'), 0o600);
    this.db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=3000; CREATE TABLE IF NOT EXISTS run (id INTEGER PRIMARY KEY CHECK(id=1), body TEXT NOT NULL); CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL, actor TEXT NOT NULL, kind TEXT NOT NULL, detail TEXT NOT NULL);');
    if (!this.db.prepare('SELECT id FROM run WHERE id=1').get()) {
      const task = () => ({ status: 'pending' as const, proposal: null, checkpoint: '' });
      const state: RunState = { schema: 1, id: randomUUID(), revision: 0, paused: false, decision: { currency: 'USD', revision: 1 }, tasks: { commerce: task(), merch: task(), domain: task() }, leases: {}, fixture: { currency: '', products: [], signedIn: false, domainReady: false, receipts: {} } };
      this.db.prepare('INSERT OR IGNORE INTO run VALUES(1,?)').run(JSON.stringify(state));
    }
    try { this.read(); } catch (error) { this.db.close(); throw error; }
  }
  read(): RunState {
    const row = this.db.prepare('SELECT body FROM run WHERE id=1').get() as { body: string };
    const state = JSON.parse(row.body) as RunState;
    if (state.schema !== 1 || !state.id || !Number.isSafeInteger(state.revision) || !agentIds.every(id => state.tasks?.[id])) throw new Error('Unsupported or corrupt workspace state. It has not been reset.');
    return state;
  }
  update<T>(actor: string, kind: string, detail: string, mutation: (state: RunState) => T): T {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const state = this.read(); const result = mutation(state); state.revision++;
      this.db.prepare('UPDATE run SET body=? WHERE id=1').run(JSON.stringify(state));
      this.db.prepare('INSERT INTO events(time,actor,kind,detail) VALUES(?,?,?,?)').run(new Date().toISOString(), actor, kind, detail);
      this.db.exec('COMMIT'); return result;
    } catch (error) { this.db.exec('ROLLBACK'); throw error; }
  }
  events(): Event[] { return this.db.prepare('SELECT * FROM (SELECT * FROM events ORDER BY seq DESC LIMIT 100) ORDER BY seq').all() as unknown as Event[]; }
  acquire(keys: string[], owner: string, now = Date.now(), ttl = 120_000): number[] {
    if (!owner || owner.length > 128 || keys.length < 1 || keys.length > 8 || new Set(keys).size !== keys.length || keys.some(key => !/^[a-z0-9:.-]{1,128}$/.test(key)) || !Number.isSafeInteger(now) || now < 0 || !Number.isSafeInteger(ttl) || ttl < 1 || ttl > 300_000) throw new Error('Invalid lease request.');
    return this.update(owner.split(':').at(-1) ?? owner, 'lease.acquired', keys.join(', '), state => {
      for (const key of keys) { const lease = state.leases[key]; if (lease?.owner && lease.expires > now) throw new Error(`Resource busy: ${key}`); }
      return keys.map(key => { const generation = (state.leases[key]?.generation ?? 0) + 1; state.leases[key] = { owner, generation, expires: now + ttl }; return generation; });
    });
  }
  assertLease(state: RunState, proposal: Proposal, now = Date.now()) {
    if (state.paused) throw new Error('Workspace is paused.');
    const expected = keysFor(proposal.agent);
    if (proposal.keys.length !== expected.length || proposal.generations.length !== expected.length || !expected.every((key,i)=>proposal.keys[i]===key && Number.isSafeInteger(proposal.generations[i]) && proposal.generations[i]>0)) throw new Error('Proposal resource binding is invalid.');
    proposal.keys.forEach((key, i) => { const lease = state.leases[key]; if (!lease || lease.owner !== proposal.owner || lease.generation !== proposal.generations[i] || lease.expires <= now) throw new Error('Ownership is stale. Request a fresh proposal.'); });
  }
  assertInputs(state: RunState, proposal: Proposal) {
    if (proposal.decisionRevision !== state.decision.revision || !dependsOn[proposal.agent].every(id => state.tasks[id].status === 'done')) throw new Error('Proposal inputs changed. Request a fresh review.');
  }
  release(state: RunState, proposal: Proposal) {
    for (const key of proposal.keys) { const lease = state.leases[key]; if (lease?.owner === proposal.owner && lease.generation === proposal.generations[proposal.keys.indexOf(key)]) { lease.owner = null; lease.expires = 0; } }
  }
  restart() {
    this.update('coordinator', 'runtime.recovered', 'New worker session. Old leases fenced; unfinished actions require reconciliation.', state => {
      for (const lease of Object.values(state.leases)) { lease.owner = null; lease.expires = 0; lease.generation++; }
      for (const task of Object.values(state.tasks)) {
        if (task.status === 'dispatching') task.status = 'uncertain';
        if (task.status === 'proposed') { task.status = 'pending'; task.proposal = null; }
      }
    });
  }
  pause() {
    this.update('you', 'workspace.paused', 'Pending approvals invalidated. In-flight results require checking.', state => {
      state.paused = true;
      for (const task of Object.values(state.tasks)) {
        if (task.status === 'dispatching') task.status = 'uncertain';
        if (task.status === 'proposed') { task.status = 'pending'; task.proposal = null; }
      }
      for (const lease of Object.values(state.leases)) { lease.owner = null; lease.expires = 0; lease.generation++; }
    });
  }
  fixtureAction(proposal: Proposal) {
    return this.update(proposal.agent, 'fixture.action', `${proposal.agent}: ${proposal.id}`, state => {
      if (state.fixture.receipts[proposal.id]) return;
      this.assertLease(state, proposal); this.assertInputs(state, proposal);
      const task = state.tasks[proposal.agent];
      if (task.status !== 'dispatching' || task.proposal?.id !== proposal.id) throw new Error('No matching approved dispatch.');
      if (proposal.agent === 'commerce') state.fixture.currency = state.decision.currency;
      if (proposal.agent === 'merch') {
        if (state.fixture.currency !== state.decision.currency) throw new Error('Currency dependency not satisfied.');
        state.fixture.products = ['Notebook', 'Ceramic mug'];
      }
      if (proposal.agent === 'domain') {
        if (!state.fixture.signedIn) throw new Error('Human fixture handoff required.');
        state.fixture.domainReady = state.fixture.products.length === 2;
      }
      state.fixture.receipts[proposal.id] = { agent: proposal.agent, decisionRevision: proposal.decisionRevision };
    });
  }
  finish(agent: AgentId, id: string, recovered = false) {
    this.update(agent, recovered ? 'task.reconciled' : 'task.completed', `${agent}: recorded fixture receipt verified; no replay.`, state => {
      const task = state.tasks[agent], proposal = task.proposal, receipt = state.fixture.receipts[id];
      if (!proposal || proposal.id !== id || !receipt || receipt.agent !== agent || receipt.decisionRevision !== proposal.decisionRevision || !['dispatching', 'uncertain'].includes(task.status)) throw new Error('No matching fixture result.');
      if (!recovered) this.assertLease(state, proposal);
      this.assertInputs(state, proposal);
      task.status = 'done'; task.checkpoint = `${agent} fixture action verified at decision revision ${proposal.decisionRevision}`; this.release(state, proposal); task.proposal = null;
    });
  }
  close() { this.db.close(); }
}
