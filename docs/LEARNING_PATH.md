# Build the agent you understand

Learning path for the proposed persistent-agent architecture · 18 October 2026.

**Keep Java as your main language. Add TypeScript when you work on the console.**
AI can draft the implementation, but your job is to explain its boundaries, spot
incorrect assumptions and demonstrate its behavior. The goal is not to memorize
framework annotations or finish a course before building anything.

| Step | Read | Build and explain |
| --- | --- | --- |
| 1. Java foundations | [Official Java learning](https://dev.java/learn/), particularly records, interfaces, collections, generics and exceptions | Represent an agent and a task. Explain which values are immutable and how failures are reported. |
| 2. A small HTTP service | [Spring REST guide](https://spring.io/guides/gs/rest-service/) | Create and list agents using synthetic data. Distinguish input validation from account authorization. |
| 3. SQL persistence | [Spring JDBC guide](https://spring.io/guides/gs/relational-data-access/) | Save a task, stop the process, restart and retrieve it. Add a unique command identity and prove that a retry does not create another task. |
| 4. Transactions and retries | [CockroachDB transaction retries](https://docs.cockroachlabs.com/docs/stable/transaction-retry-error-reference) | Atomically claim work and advance a fencing generation. Explain why a paid model request cannot run inside a retryable transaction. |
| 5. Model integration | [OpenRouter PKCE](https://openrouter.ai/docs/guides/overview/auth/oauth) and [model capabilities](https://openrouter.ai/docs/api/api-reference/models/get-models) | Send a bounded request with synthetic context and a protected local credential. Persist normalized messages and reconstruct context after restart. |
| 6. Concurrency | [Java virtual threads](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html) | Run a few independent jobs while separately bounding model calls and DB connections. Explain why threads are not durable jobs or security isolation. |
| 7. The console | [JavaScript guide](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide), then [TypeScript for Java programmers](https://www.typescriptlang.org/docs/handbook/typescript-in-5-minutes-oop.html) and [the Handbook](https://www.typescriptlang.org/docs/handbook/) | Display real server states with loading, empty, failed and expired-session behavior. Understand async/await and runtime validation; TypeScript types alone do not validate JSON. |
| 8. Database estate | [Continuum architecture](https://www.cockroachlabs.com/blog/continuum-architecture/), [virtualization overview](https://docs.cockroachlabs.com/docs/continuum/virtualization-overview) and [feature reference](https://docs.cockroachlabs.com/docs/continuum/feature-reference) | Trace agent creation to a provisioning operation and one data boundary. Verify eligibility and cost before creating anything paid. |

The Java virtual-thread reference uses Java 25; use the reference matching the JDK
pinned for implementation. The core design does not require preview features.

## How to use AI while learning

For each small feature, read the relevant page, write a short behavior contract,
then have AI draft a bounded change. Read the diff and explain the happy path,
failure path, ownership check and restart behavior. Ask for a competing simpler
design. Run a test that could distinguish the two. Do not accept green tests that
only repeat the implementation's assumptions.

Useful review questions:

- What survives if the process dies here?
- What happens if this request arrives twice?
- Can another account select this agent's identifier?
- What if an old worker wakes up after its replacement?
- What leaves the database and is sent to a model provider?
- Can a retry repeat a paid or externally visible action?
- What is the maximum resource use of this feature?

## Papers, after the first working loop

These are background, not dependencies or claims that diyalamp reproduces them.

- [ReAct: Synergizing Reasoning and Acting in Language Models](https://arxiv.org/abs/2210.03629)
  introduces an interleaved reasoning/action approach. Study what the loop does,
  then keep authorization and recovery in deterministic code outside the model.
- [AppWorld](https://arxiv.org/abs/2407.18901) demonstrates executable application
  environments with state-based checks, including unintended changes.
- [Tau-squared bench](https://arxiv.org/abs/2506.07982) studies agents and users
  acting on shared changing state. It is relevant to coordination, not a reason
  to claim that a model's plausible answer proves successful execution.

The proposed product is persistent model-independent agents, not the evaluation
platform considered and rejected during brainstorming. These papers can inform
evaluation later without changing that product direction.

For the complete product and repository split, read
[Persistent agents architecture](PERSISTENT_AGENTS_ARCHITECTURE.md).
