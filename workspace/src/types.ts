export const agentIds = ['commerce', 'merch', 'domain'] as const;
export type AgentId = typeof agentIds[number];
export type TaskStatus = 'pending' | 'proposed' | 'dispatching' | 'done' | 'uncertain';
export interface Lease { owner: string | null; generation: number; expires: number }
export interface Proposal { id: string; agent: AgentId; owner: string; keys: string[]; generations: number[]; decisionRevision: number; observation: string; summary: string }
export interface Task { status: TaskStatus; proposal: Proposal | null; checkpoint: string }
export interface FixtureReceipt { agent: AgentId; decisionRevision: number }
export interface RunState {
  schema: 1; id: string; revision: number; paused: boolean;
  decision: { currency: 'USD' | 'EUR'; revision: number };
  tasks: Record<AgentId, Task>; leases: Record<string, Lease>;
  fixture: { currency: string; products: string[]; signedIn: boolean; domainReady: boolean; receipts: Record<string, FixtureReceipt> };
}
export interface Event { seq: number; time: string; actor: string; kind: string; detail: string }
export interface Snapshot { run: RunState; events: Event[]; adapter: string; tabs: { id: AgentId; url: string }[] }
export interface AgentAdapter {
  readonly name: string;
  propose(agent: AgentId, state: Readonly<RunState>): string;
}
export class DeterministicAdapter implements AgentAdapter {
  readonly name = 'Deterministic fixture workers · No model calls';
  propose(agent: AgentId, state: Readonly<RunState>) {
    return agent === 'commerce' ? `Set fixture currency to ${state.decision.currency}` : agent === 'merch' ? 'Sync notebook and mug into the fixture catalog' : 'Verify the fixture domain connection';
  }
}
export const dependsOn: Record<AgentId, AgentId[]> = { commerce: [], merch: ['commerce'], domain: ['merch'] };
export const keysFor = (agent: AgentId) => [`tab:${agent}`, `resource:${agent === 'domain' ? 'domain-settings' : 'store-catalog'}`];
