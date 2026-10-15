# 🪔 Diya Lamp

Coordination for long-running AI workflows. Java 21. Apache 2.0.

Eight workers want the same task. One gets it. That worker disappears, a
replacement takes over, and the original eventually wakes up.

The original worker’s next write should fail.

That is the first problem Diya Lamp tackles: keeping independent workers from
trampling each other’s progress. The current core uses SQL transactions to track
ownership, checkpoints and the decisions that belong to them.

## What’s in the box

`JdbcTaskCoordinator` is a small CockroachDB-backed Java library.

| When this happens | The core does this |
| --- | --- |
| Workers race for a task | Grants one current claim in a serializable transaction. |
| A worker stops renewing its lease | Allows a replacement to claim the task and read the saved checkpoint. |
| The old worker wakes up | Rejects its writes using the ownership generation. |
| A checkpoint changes | Invalidates the approval attached to the previous revision. |
| History recording fails | Rolls back the task change in the same transaction. |
| A commit response disappears | Propagates the uncertainty so the caller can inspect state before retrying. |

Records are keyed by workspace, workflow and task. History is ordered and paged.
Only known serialization aborts during transaction work or commit are retried,
with a fixed attempt limit. An error while closing a connection after commit
doesn’t get to replay the write.

## Run the failure cases

From a source checkout, with JDK 21 or newer:

```sh
sh test.sh
```

The database suite needs a running, loopback-only development CockroachDB instance:

```sh
DIYALAMP_COORDINATION_TEST_URL='jdbc:postgresql://127.0.0.1:26257/defaultdb?sslmode=disable&user=root' \
  sh coordination-test.sh
```

That fixture is for an insecure local development server. The test refuses cloud
URLs and application database names. It creates a fresh database, exercises the
race and failure cases, then removes only the database it created.

The tests cover competing claims and checkpoint writes, expired owners, stale
approvals, lost commit responses, connection-close failures and rollback when the
history write fails. The JDBC test dependency is pinned and checksum-verified.
No model calls are involved.

## A few lines of Java

```java
import com.diyalamp.coordination.JdbcTaskCoordinator;
import com.diyalamp.coordination.JdbcTaskCoordinator.TaskKey;
import java.sql.DriverManager;
import java.time.Duration;

var coordinator = new JdbcTaskCoordinator(
    () -> DriverManager.getConnection(System.getenv("DIYALAMP_JDBC_URL")));
coordinator.initialize();

var task = new TaskKey("lab", "driver-evaluation", "retry-path");
coordinator.create(task, "Check the ambiguous-response case");

var lease = coordinator.claim(task, "build-worker", Duration.ofMinutes(2))
    .orElseThrow();
var checkpoint = coordinator.checkpoint(lease, 0, "Patch ready for review");

// The application authenticates and authorizes the reviewer first.
coordinator.approve(lease, checkpoint.revision(), "reviewer");
coordinator.complete(lease, checkpoint.revision());
```

Change the checkpoint after approval and completion will be rejected until it is
reviewed again. Replace the owner and its previous approval becomes stale too.

[API details, invariants and test instructions](docs/COORDINATION_CORE.md)

## Status

Experimental. This milestone coordinates task records and their history.
Scheduling, dependency-aware admission, model adapters and external tool delivery
are still ahead. A completed database record does not prove an external action
happened exactly once.

The caller must authenticate workers and reviewers, authorize workspace access,
and protect its database connection. Worker names and lease generations are
coordination metadata, not credentials. The local tests don’t establish regional
failover, production throughput or years-long operation.

Next up: dependency-aware task admission, cancellation and approval revocation,
then an action outbox with explicit handling for uncertain external outcomes.

## Earlier work

The earlier writing-review engine and terminal CLI are preserved. Their APIs,
offline tests and packaging still work. The old product pitch lives in the
[legacy CLI documentation](docs/LEGACY_REVIEW_CLI.md).

## License

[Apache License 2.0](LICENSE). Dependencies retain their own licenses; see
[third-party notices](THIRD_PARTY_NOTICES.md).
