# Transactional task coordination: first Java milestone

`com.diyalamp.coordination.JdbcTaskCoordinator` is an experimental Java 21 JDBC
primitive targeting CockroachDB. It is a starting point for the open-source
coordination layer, not a scheduler, agent framework or hosted service.

## Implemented behavior

- Composite workspace/workflow/task keys keep records and event histories separate.
- A serializable conditional update grants one current task claim. Leases use
  database time; replacement claims increment a fencing generation.
- Checkpoints use an explicit expected revision. A stale revision cannot overwrite
  newer content, including when two updates race.
- Approval is tied to both the checkpoint revision and ownership generation.
  A checkpoint edit or replacement owner clears it. Task completion requires a
  current approval in the same transaction as the completion update.
- Every successful transition and its ordered history entry commit together.
  History uses bounded keyset pagination; checkpoint text is not duplicated there.
- Only SQLSTATE `40001`, a known aborted serialization attempt, is automatically
  retried, for at most eight attempts on fresh connections. Acquisition, configuration and connection-close errors propagate without retry.
  In particular, a lost commit response is not blindly replayed.
- Checkpoint text is bounded to 65,536 UTF-16 code units, rejects NUL and malformed
  surrogate sequences, and allows empty content and valid Unicode.

## Use from Java

Supply a connection factory for a dedicated application database. The host
application supplies the PostgreSQL JDBC driver and connection configuration.
Do not put a database password or connection secret in source control.

```java
var coordinator = new JdbcTaskCoordinator(
    () -> DriverManager.getConnection(System.getenv("DIYALAMP_JDBC_URL")));
coordinator.initialize();
var key = new JdbcTaskCoordinator.TaskKey("research-team", "driver-evaluation", "retry-path");
coordinator.create(key, "Versioned task inputs");
var lease = coordinator.claim(key, "build-worker", Duration.ofMinutes(2)).orElseThrow();
var progress = coordinator.checkpoint(lease, 0, "Implementation prepared for review");
// Application authenticates/authorizes the reviewer before this call.
coordinator.approve(lease, progress.revision(), "reviewer");
coordinator.complete(lease, progress.revision());
```

Renew ownership before its lease expires. Workflow duration is independent of
worker-lease duration. After an interruption, inspect `find(key)` and reconcile
uncertain writes before retrying. An expired owner cannot renew or modify the
record; a replacement can claim it and retains its last committed checkpoint.
This does not save uncheckpointed process memory or migrate a live model context.

## Reproduce the database tests

Have a separate **local development** CockroachDB server running. The opt-in test
accepts only a loopback, insecure root URL targeting `defaultdb`; it refuses cloud
URLs, passwords, arbitrary query parameters and existing application databases.
It verifies the server is CockroachDB, creates a fresh UUID-named database, and
cleans up only that database. Do not use this fixture configuration in production.

```sh
sh test.sh
DIYALAMP_COORDINATION_TEST_URL='jdbc:postgresql://127.0.0.1:26257/defaultdb?sslmode=disable&user=root' \
  sh coordination-test.sh
```

The integration script fetches the pinned PostgreSQL JDBC 42.7.14 artifact from
Maven Central and verifies its SHA-256 before use. The library itself compiles
against the JDK's JDBC interfaces and does not embed a driver.

Actual integration checks include eight simultaneous claimants with one winner,
two simultaneous checkpoint updates with one winner, persistence across reopened
coordinator instances, expiry/replacement fencing, stale approval rejection,
rollback after an injected history-write failure, ordered paginated history,
bounded serialization retries, no retry of connection failures, and reconciliation
after an injected lost response following a real successful commit, and no replay
after a connection-close error following a committed write. Workspace keys
are tested independently. The regular offline test validates input rejection
before any connection is opened.

These are local, single-node database tests. They do **not** establish regional
failover, years-long execution, throughput, tenant authentication or model quality.

## Deliberate boundaries

A workspace ID, worker ID, reviewer label or fencing generation is **not an
authentication credential**. The application must authenticate callers, authorize
their workspace, tasks and approvals, and protect database access. Someone with
direct SQL write privileges can bypass these library rules. No public HTTP/MCP
endpoint, sign-in, tenant authorization or dashboard is included.

Approvals cover one task's current checkpoint and claim. Dependency discovery,
workflow-wide revision tracking, approval revocation, dependency-aware task
admission, task cancellation, outbox delivery and external effect reconciliation
remain future work. A reviewer label records provenance; it does not prove that
a human reviewed the content or that it is correct.

Completing a task updates a database record. It does not atomically execute an
external API call. Exactly-once tool effects are not promised; adapters will need
provider idempotency and explicit uncertain-outcome handling. The SQL retry loop
contains only database work, not model or tool calls.

Production deployments need appropriate database privileges, TLS, backup and
retention policies, schema migrations, resource limits and a suitable regional
topology. `initialize()` only creates absent tables; it is not a migration system.
The API/schema is experimental. Existing review-engine APIs and CLI remain intact.
