import { randomUUID } from 'node:crypto';
import { agentIds, dependsOn, keysFor, DeterministicAdapter, type AgentAdapter, type AgentId, type Proposal } from './types.js';
import { Store } from './store.js';
import { BrowserWorkers } from './browser.js';

export class Coordinator {
  readonly session = randomUUID(); private planning = false; private dispatches = new Set<string>();
  constructor(readonly store: Store, readonly browser: BrowserWorkers, readonly adapter: AgentAdapter = new DeterministicAdapter()) {}
  snapshot() { return { run: this.store.read(), events: this.store.events(), adapter: this.adapter.name, tabs: agentIds.map(id => ({ id, url: `${this.browser.origin}/fixture/${id}` })) }; }
  async plan() {
    if (this.planning) throw new Error('A planning pass is already running.');
    this.planning = true;
    try {
      let state = this.store.read();
      if (state.paused) throw new Error('Resume the workspace first.');
      if (Object.values(state.tasks).some(task => task.status === 'uncertain' || task.status === 'dispatching')) throw new Error('Reconcile the unfinished action before planning more work.');
      for (const agent of agentIds) {
        state = this.store.read(); const task = state.tasks[agent];
        if (task.status === 'proposed') {
          try { this.store.assertLease(state, task.proposal!); continue; }
          catch { this.store.update(agent, 'proposal.expired', 'Lease expired; fresh observation and approval required.', run => { this.store.release(run, run.tasks[agent].proposal!); run.tasks[agent] = { status: 'pending', proposal: null, checkpoint: '' }; }); }
        }
        if (this.store.read().tasks[agent].status !== 'pending' || !dependsOn[agent].every(id => state.tasks[id].status === 'done')) continue;
        if (agent === 'domain' && !state.fixture.signedIn) {
          this.store.update(agent, 'human.requested', 'Controlled fixture needs your sign-in handoff. Other checkpoints are preserved.', () => {}); continue;
        }
        const owner = `${this.session}:${agent}`, keys = keysFor(agent);
        const generations = this.store.acquire(keys, owner);
        let observation: string;
        try { observation = await this.browser.observe(agent); }
        catch (error) {
          this.store.update(agent, 'observation.failed', 'Observation failed; acquired resources released.', run => {
            for (const key of keys) if (run.leases[key]?.owner === owner) { run.leases[key].owner = null; run.leases[key].expires = 0; }
          });
          throw error;
        }
        this.store.update(agent, 'proposal.created', this.adapter.propose(agent, state), run => {
          if (run.paused || run.tasks[agent].status !== 'pending') throw new Error('Work changed during observation.');
          const proposal: Proposal = { id: randomUUID(), agent, owner, keys, generations, observation, decisionRevision: run.decision.revision, summary: this.adapter.propose(agent, run) };
          this.store.assertLease(run, proposal); this.store.assertInputs(run, proposal);
          run.tasks[agent] = { status: 'proposed', proposal, checkpoint: run.tasks[agent].checkpoint };
        });
      }
    } finally { this.planning = false; }
    return this.snapshot();
  }
  async approve(id: string, interruptAfterAction = false, crashAfterAction?: () => never) {
    if (this.dispatches.has(id)) throw new Error('This proposal is already being dispatched.');
    this.dispatches.add(id);
    try {
      const state = this.store.read(), task = Object.values(state.tasks).find(t => t.proposal?.id === id);
      if (!task || task.status !== 'proposed') throw new Error('No matching pending approval.');
      const proposal = task.proposal!;
      this.store.assertLease(state, proposal); this.store.assertInputs(state, proposal);
      const observation = await this.browser.observe(proposal.agent);
      if (observation !== proposal.observation) {
        this.store.update('coordinator', 'proposal.stale', 'Observed page changed; proposal and lease invalidated.', run => {
          const current = run.tasks[proposal.agent];
          if (current.proposal?.id === id && current.status === 'proposed') { this.store.release(run, proposal); current.status = 'pending'; current.proposal = null; }
        });
        throw new Error('Page changed. Plan a fresh proposal before approving.');
      }
      this.store.update('you', 'action.approved', `${proposal.agent}: approval bound to proposal and input revision.`, run => {
        this.store.assertLease(run, proposal); this.store.assertInputs(run, proposal);
        if (run.tasks[proposal.agent].status !== 'proposed' || run.tasks[proposal.agent].proposal?.id !== id || observation !== proposal.observation) throw new Error('Page or proposal changed. Request a fresh proposal.');
        run.tasks[proposal.agent].status = 'dispatching';
      });
      try {
        await this.browser.execute(proposal);
        if (crashAfterAction) crashAfterAction();
        if (interruptAfterAction) throw new Error('Injected interruption after browser action, before completion receipt.');
        if (!await this.browser.receiptVisible(proposal.agent, id)) throw new Error('Result not visible.');
        this.store.finish(proposal.agent, id);
      } catch {
        this.store.update('coordinator', 'action.uncertain', `${proposal.agent}: outcome needs checking; action will not be replayed.`, run => {
          const current = run.tasks[proposal.agent]; if (current.proposal?.id === id && current.status !== 'done') current.status = 'uncertain';
        });
      }
    } finally { this.dispatches.delete(id); }
    return this.snapshot();
  }
  async recover() {
    for (const agent of agentIds) {
      const task = this.store.read().tasks[agent];
      if (task.status !== 'uncertain') continue;
      if (await this.browser.receiptVisible(agent, task.proposal!.id)) this.store.finish(agent, task.proposal!.id, true);
    }
    return this.snapshot();
  }
  setCurrency(currency: 'USD' | 'EUR') {
    this.store.update('you', 'decision.changed', `Currency changed to ${currency}; downstream approvals invalidated.`, state => {
      if (Object.values(state.tasks).some(task => ['dispatching','uncertain'].includes(task.status))) throw new Error('Resolve the in-flight action first.');
      state.decision = { currency, revision: state.decision.revision + 1 };
      for (const agent of agentIds) { const task = state.tasks[agent]; if (task.proposal) this.store.release(state, task.proposal); state.tasks[agent] = { status: 'pending', proposal: null, checkpoint: '' }; }
      state.fixture.domainReady = false;
    });
  }
}
