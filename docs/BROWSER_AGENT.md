# Browser agent core

Java 21. Browser only. No desktop control or shell actions.

The first slice is a provider-neutral loop with local recovery. It is not a
complete browser agent: concrete browser drivers and model-provider adapters are
not implemented yet. Tests use a fake browser and deterministic model decisions.

## The contract

```
Goal + fresh page observation
             |
         Model adapter
             |
     Typed action proposal
             |
       Human approval
             |
    Re-check observation
             |
     Save dispatch intent
             |
       Browser adapter
             |
   Receipt, or uncertainty
             |
      Fresh observation
```

`BrowserAgent.Model` receives the goal, current page observation and receipt
metadata. It returns one typed action or a short stop reason. A model's stop is
recorded as `STOPPED`; it is not independent verification that the user's task
succeeded. No hidden chain of thought is requested or recorded.

`BrowserAgent.Browser` observes the page and dispatches an action against an
observation token. The token must identify the browser context, page and target
references the proposal was based on. Two materially different views must not
reuse it. References are observation-scoped, not model-invented selectors.

The initial vocabulary is deliberately small:

- Navigate to an HTTP(S) URL without embedded credentials.
- Click an observed target reference.
- Fill an observed target reference.
- Scroll up or down.

No JavaScript evaluation, arbitrary selectors, shell commands or desktop tools are
exposed by the core. This vocabulary is not a security sandbox: a click can still
submit a form or trigger a consequential action. Every action requires explicit
approval in this milestone. There is no automatic low-risk classification.

The host must authenticate the person approving an action, display its exact
contents and bind their approval to the proposal identifier. Page content is
untrusted evidence, not instructions or permission. A model adapter must preserve
that separation. Network restrictions, sensitive-data controls, prompt-injection
resistance, action-time confirmation rules and provider data handling remain
integration work. Do not use this prototype against real accounts yet.

## The lost-click problem

The runtime saves `DISPATCHING` **before** calling the browser. If the command
throws, returns an unknown result, or the process disappears before its receipt
is saved, the next run stops at `UNCERTAIN`.

It does not click again.

A caller has to check the actual outcome and explicitly reconcile the exact
proposal. Reconciliation records that assessment and performs no browser action.
This avoids blind replay; it does not make browser actions exactly-once.

A second observation before dispatch rejects a proposal if its token has changed.
The browser driver must also reject stale references at dispatch. Dynamic web
pages still have race windows; the core cannot make a browser interaction atomic
with a database or guarantee that a selector always means the same thing.

Each proposal attempt consumes the persisted step budget, including provider
failures and dismissed proposals. Restarting the runtime does not reset it.
Browser and model adapters must impose their own bounded timeouts; the current
synchronous core does not interrupt a hung adapter.

## Local snapshots

`FileBrowserRunStore` stores a versioned snapshot in a dedicated directory such as
`.diyalamp/browser-runs/my-fixture/`. That path is ignored by Git. Use one store per
run, hold it open for the run's lifetime, and close it afterwards.

The store takes an exclusive filesystem lock, writes a private temporary snapshot,
flushes it, atomically replaces the previous snapshot and flushes the directory.
It requires POSIX filesystem permissions. Unsupported filesystems fail rather than
falling back to publicly readable snapshots. A corrupt snapshot is not overwritten
with a new run. Recovery preserves pending approvals, ambiguous intents and budget.

Snapshots contain the goal and proposed action values, which may be sensitive.
They are private plaintext, not encrypted storage. Do not put credentials into
model-generated fill actions or commit run directories. Shared-user hosts and
cloud storage need a separate threat model and store implementation.

A local file lock is not a distributed lease. The existing SQL task-coordination
library remains available, but it is not wired into browser run storage. Hosted
coordination, leases and recovery across machines are future work.

## Run the tests

```sh
sh test.sh
```

`BrowserAgentTest` covers exact-proposal approval, a stale page, ambiguous action
results, failed intent/receipt saves, recovery after reopening a file store,
competing approvals, exclusive writers and a persistent proposal budget. It does
not launch a real browser, call a model or establish task-completion accuracy.

## Next working slice

1. Implement an isolated browser adapter with observation-scoped target references
   and a local fixture site. Keep it away from the user's existing browser profile.
2. Add one model adapter behind the neutral contract. Document supported input
   capabilities; model-agnostic does not mean every model can do browser tasks.
3. Run a real task on the fixture: observe, propose, approve, act, observe again,
   and check the result. Inject an ambiguous result and prove it is not replayed.
4. Add cancellation, screenshots, explicit task-result checks and supervision UI.

For the planned browser driver, the relevant primary references are Playwright's
[Java locators](https://playwright.dev/java/docs/locators) and
[accessibility snapshots](https://playwright.dev/java/docs/aria-snapshots).
