# 🪔 diyalamp workspace

TypeScript console, coordinator and browser workers. Local-first. No cloud bill.

Three deterministic workers use three real Playwright tabs: Commerce saves a
currency, Merch waits for that checkpoint and syncs two fixture products, then
Domain waits for Merch and a controlled user handoff before checking a connection.
These are local test pages, not real Shopify, Printify or domain integrations.
The console renders actual browser screenshots and server-owned state.

## Start

Requires **Node 24+** and **pnpm 11.25.0**:

```sh
cd workspace
pnpm install --frozen-lockfile
pnpm exec playwright install chromium
pnpm run build
pnpm start
```

Open **http://127.0.0.1:4180/**. Keep the process running. An existing Chrome
installation can be used in an isolated temporary profile instead:

```sh
DIYALAMP_CHROME='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' pnpm start
```

This does not attach to your Chrome profile or read its sessions. Browser-context
requests are restricted to the fixture server's origin. This is an application
restriction, not an operating-system network sandbox.

1. **Plan next step.** The eligible worker observes its page and proposes an action.
2. **Approve this action.** Approval binds to that proposal, decision revision,
   observed page and resource-lease generations.
3. Plan and approve the dependent product-sync step.
4. When requested, **Complete controlled user handoff** changes a fixture flag.
   This is not real authentication. Plan and approve Domain's check.
5. Inspect completed tasks, released leases and action history.

**Pause** invalidates pending approvals and fences existing leases. **Resume**
continues from checkpoints. Expired leases require a new planning pass and approval.
Changing currency invalidates dependent results/proposals; existing fixture effects
and historical events remain recorded.

## Break it, then recover

Before approving product sync, select **Interrupt after the next approved browser
action**. The browser acts, but its intent stays uncertain. Planning is blocked.
**Check recovery** reads the actual fixture receipt and confirms the outcome
without repeating the click.

Stop and restart the app to test durable recovery. Tasks, checkpoints, fixture
effects and events survive. A new worker session fences old leases and invalidates
pending approvals. A dispatch without completion becomes uncertain. Reload the
console after a restart; its anti-CSRF token is per process.

An uncertain action without a verifiable receipt remains blocked. There is no
'just retry' button. Broader reconciliation/manual resolution are future work.

## Verify

```sh
pnpm run build
pnpm test
pnpm smoke
```

Unit tests cover competing connections, atomic multi-resource acquisition,
generation fencing, rollback, in-flight pause fencing, stale observations, expired approvals, failed
observation cleanup and pause/restart invalidation.

The real-browser smoke test starts a disposable app and three fixture tabs,
exercises dependencies, stale approvals, pause/resume and the user handoff. It
restarts the app/browser, then **kills the app process after a real browser effect
but before completion is recorded**, restarts and reconciles. It checks exactly
three fixture effects, with no replay. Temporary test state is removed.
The process-crash hook is enabled only by `DIYALAMP_ENABLE_TEST_CRASH=1`, which the
test sets for its own child process.

## Boundaries

- The fixed template assigns each responsibility a fixture tab; workers acquire
  and release leases, rather than running a permanent autonomous loop per tab.
- One local user, one fixed task and deterministic workers. No autonomous model
  reasoning, arbitrary web tasks or real account support yet.
- `AgentAdapter` is an initial planning seam. Only the deterministic fixture
  adapter is implemented; this does not establish model compatibility.
- SQLite stores private plaintext state in the repository's ignored
  `.diyalamp/workspace/` directory. `DIYALAMP_DATA_DIR` overrides the dedicated
  directory and `DIYALAMP_PORT` the port. No credentials belong in this prototype.
- Tab and shared-resource leases are acquired atomically and generation-fenced.
  Task/history updates are atomic. Fixture writes validate the recorded dispatch
  and lease before changing fixture state. Real websites cannot participate in
  that transaction.
- Fixture action IDs/receipts are cooperative. This tests this controlled recovery
  protocol, **not exactly-once actions on arbitrary websites**.
- No multi-user auth, cloud deployment, distributed failover, model-budget controls
  or CockroachDB adapter is shipped. The production marketing site is unchanged.

Built on [Playwright](https://playwright.dev/docs/api/class-browser) and
[Node's SQLite API](https://nodejs.org/api/sqlite.html). Inter's
[OFL license](public/fonts/Inter-LICENSE.txt) is included. Dependencies retain their
upstream licenses. The project is Apache 2.0.
