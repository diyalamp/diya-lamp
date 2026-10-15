// SPDX-License-Identifier: Apache-2.0
package com.diyalamp.coordination;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.diyalamp.coordination.JdbcTaskCoordinator.*;

/** Explicit opt-in test against a fresh disposable CockroachDB database, never the application database. */
public final class JdbcTaskCoordinatorIntegrationTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/defaultdb\\?sslmode=disable&user=root"))
            throw new IllegalArgumentException("Use a loopback, insecure development root URL targeting defaultdb");
        String base = args[0];
        String database = "dl_coord_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(base)) {
            try (Statement s = admin.createStatement(); ResultSet r = s.executeQuery("SELECT version()")) {
                r.next(); check(r.getString(1).startsWith("CockroachDB"), "A real CockroachDB server is required");
            }
            try (Statement s = admin.createStatement()) { s.executeUpdate("CREATE DATABASE " + database); }
            String url = base.replace("/defaultdb?", "/" + database + "?");
            try { exercise(url); }
            finally {
                // Only this test's newly created UUID-named database is eligible for cleanup.
                try (Statement s = admin.createStatement()) { s.executeUpdate("DROP DATABASE " + database + " CASCADE"); }
            }
        }
        System.out.println("PASS: real CockroachDB concurrent ownership, restart persistence, fencing, approval invalidation, atomic history, bounded retries and workspace keys");
    }
    private static void exercise(String url) throws Exception {
        JdbcTaskCoordinator store = new JdbcTaskCoordinator(() -> DriverManager.getConnection(url));
        store.initialize(); store.initialize();
        TaskKey key = new TaskKey("workspace-a", "driver-workflow", "implementation");
        Snapshot created = store.create(key, "initial constraints 🪔");
        check(created.revision() == 0 && created.eventSequence() == 1, "Initial task record");
        check(created.checkpoint().equals("initial constraints 🪔"), "Valid Unicode round-trips through the database");
        conflict(() -> store.create(key, "must not replace existing progress"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        List<Future<Optional<Lease>>> attempts = new ArrayList<>();
        Lease owner;
        try {
            for (int i = 0; i < 8; i++) {
                String worker = "worker-" + i;
                attempts.add(pool.submit(() -> { ready.countDown(); start.await(); return store.claim(key, worker, Duration.ofMinutes(2)); }));
            }
            check(ready.await(10, TimeUnit.SECONDS), "Concurrent workers ready"); start.countDown();
            List<Lease> winners = new ArrayList<>();
            for (Future<Optional<Lease>> attempt : attempts) attempt.get(30, TimeUnit.SECONDS).ifPresent(winners::add);
            check(winners.size() == 1, "One winner among simultaneous claims"); owner = winners.getFirst();
        } finally { start.countDown(); pool.shutdownNow(); }
        final Lease firstOwner = owner;
        conflict(() -> store.complete(firstOwner, 0));
        Snapshot saved = store.checkpoint(owner, 0, "implementation checkpoint v1");
        check(saved.revision() == 1, "Checkpoint revision advances");
        JdbcTaskCoordinator reopened = new JdbcTaskCoordinator(() -> DriverManager.getConnection(url));
        check(reopened.find(key).orElseThrow().checkpoint().equals("implementation checkpoint v1"), "Progress survives reopening the coordinator");
        conflict(() -> store.checkpoint(firstOwner, 0, "stale overwrite"));
        Snapshot approved = store.approve(owner, 1, "reviewer");
        check(approved.approvalCurrent(), "Approval is scoped to current content and ownership");
        Snapshot edited = store.checkpoint(owner, 1, "implementation checkpoint v2");
        check(!edited.approvalCurrent(), "A new checkpoint invalidates approval");
        conflict(() -> store.complete(firstOwner, 1));
        conflict(() -> store.complete(firstOwner, 2));
        conflict(() -> store.approve(firstOwner, 1, "reviewer"));
        store.approve(owner, 2, "reviewer");
        Snapshot beforeRenew = store.find(key).orElseThrow();
        Lease renewed = store.renew(owner, Duration.ofMillis(1));
        check(!renewed.until().isBefore(beforeRenew.leaseUntil()), "Renewal never shortens a lease");

        // Controlled failure injection in this disposable database avoids time-dependent sleeps.
        try (Connection c = DriverManager.getConnection(url); PreparedStatement p = c.prepareStatement(
                "UPDATE dl_coord_tasks SET lease_until=current_timestamp()-INTERVAL '1 second' WHERE workspace_id=? AND workflow_id=? AND task_id=?")) {
            p.setString(1,key.workspace()); p.setString(2,key.workflow()); p.setString(3,key.task()); p.executeUpdate();
        }
        conflict(() -> store.checkpoint(firstOwner, 2, "expired owner overwrite"));
        Lease successor = store.claim(key, "replacement-worker", Duration.ofMinutes(2)).orElseThrow();
        check(successor.generation() > owner.generation(), "Replacement gets a higher fencing generation");
        Snapshot recovered = store.find(key).orElseThrow();
        check(recovered.checkpoint().equals("implementation checkpoint v2"), "Replacement retains durable progress");
        check(!recovered.approvalCurrent(), "Ownership change requires fresh approval");
        conflict(() -> store.renew(firstOwner, Duration.ofMinutes(1)));
        conflict(() -> store.checkpoint(firstOwner, 2, "zombie overwrite"));
        conflict(() -> store.approve(firstOwner, 2, "reviewer"));
        conflict(() -> store.complete(firstOwner, 2));

        Snapshot beforeFailure = store.find(key).orElseThrow();
        AtomicBoolean failEvent = new AtomicBoolean(true);
        JdbcTaskCoordinator broken = new JdbcTaskCoordinator(() -> proxy(DriverManager.getConnection(url), sql -> {
            if (sql.startsWith("INSERT INTO dl_coord_events") && failEvent.getAndSet(false)) throw new SQLException("Synthetic event failure", "XX000");
        }));
        try { broken.checkpoint(successor, 2, "must roll back with its history record"); throw new AssertionError("Expected event failure"); }
        catch (SQLException expected) { check("XX000".equals(expected.getSQLState()), "Expected fault only"); }
        check(store.find(key).orElseThrow().equals(beforeFailure), "Task mutation rolls back if event recording fails");
        List<Event> history = store.events(key, 0, 1000);
        check(history.size() == beforeFailure.eventSequence(), "Every committed transition has one ordered event");
        for (int i = 0; i < history.size(); i++) check(history.get(i).sequence() == i + 1, "No missing or duplicate sequence");
        List<Event> firstPage = store.events(key, 0, 2), secondPage = store.events(key, 2, 2);
        check(firstPage.size() == 2 && secondPage.getFirst().sequence() == 3, "History pagination does not repeat events");

        AtomicInteger serializationFailures = new AtomicInteger(1), openings = new AtomicInteger();
        JdbcTaskCoordinator retried = new JdbcTaskCoordinator(() -> { openings.incrementAndGet(); return proxy(DriverManager.getConnection(url), sql -> {
            if (sql.startsWith("UPDATE dl_coord_tasks") && serializationFailures.getAndDecrement() > 0) throw new SQLException("Synthetic serialization abort", "40001");
        }); });
        Snapshot afterRetry = retried.checkpoint(successor, 2, "checkpoint after known serialization abort");
        check(openings.get() == 2 && afterRetry.revision() == 3, "Known abort retries once on a fresh connection");
        AtomicInteger unavailableConnections = new AtomicInteger();
        JdbcTaskCoordinator unavailable = new JdbcTaskCoordinator(() -> { unavailableConnections.incrementAndGet(); throw new SQLException("Synthetic connection failure", "08006"); });
        try { unavailable.find(key); throw new AssertionError("Expected connection failure"); }
        catch (SQLException expected) { check("08006".equals(expected.getSQLState()), "Connection failure is preserved"); }
        check(unavailableConnections.get() == 1, "Ambiguous failures are not automatically retried");
        AtomicInteger abortedConnections = new AtomicInteger();
        JdbcTaskCoordinator alwaysAborted = new JdbcTaskCoordinator(() -> { abortedConnections.incrementAndGet(); return proxy(DriverManager.getConnection(url), sql -> { throw new SQLException("Synthetic serialization abort", "40001"); }); });
        try { alwaysAborted.find(key); throw new AssertionError("Expected bounded failure"); }
        catch (SQLException expected) { check("40001".equals(expected.getSQLState()), "Serialization failure is preserved after retry limit"); }
        check(abortedConnections.get() == 8, "Retry attempts are bounded");

        AtomicInteger commitConnections = new AtomicInteger();
        AtomicBoolean loseCommitResponse = new AtomicBoolean(true);
        JdbcTaskCoordinator commitUnknown = new JdbcTaskCoordinator(() -> {
            commitConnections.incrementAndGet();
            Connection delegate = DriverManager.getConnection(url);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, method, arguments) -> {
                try {
                    Object result = method.invoke(delegate, arguments);
                    if (method.getName().equals("commit") && loseCommitResponse.getAndSet(false))
                        throw new SQLException("Synthetic lost commit response", "08006");
                    return result;
                } catch (InvocationTargetException e) { throw e.getCause(); }
            });
        });
        try { commitUnknown.checkpoint(successor, 3, "committed despite lost response"); throw new AssertionError("Expected unknown commit response"); }
        catch (SQLException expected) { check("08006".equals(expected.getSQLState()), "Commit connection error is preserved"); }
        Snapshot reconciled = store.find(key).orElseThrow();
        check(commitConnections.get() == 1 && reconciled.revision() == 4,
                "Lost commit response is reconciled by reading, never blind replay");
        check(reconciled.checkpoint().equals("committed despite lost response"), "Durable commit can be inspected after ambiguous response");

        ExecutorService updates = Executors.newFixedThreadPool(2);
        CountDownLatch updateStart = new CountDownLatch(1);
        try {
            List<Future<Boolean>> writes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String proposal = "parallel proposal " + i;
                writes.add(updates.submit(() -> {
                    updateStart.await();
                    try { store.checkpoint(successor, 4, proposal); return true; }
                    catch (Conflict expected) { return false; }
                }));
            }
            updateStart.countDown(); int committed = 0;
            for (Future<Boolean> write : writes) if (write.get(30, TimeUnit.SECONDS)) committed++;
            check(committed == 1 && store.find(key).orElseThrow().revision() == 5,
                    "Concurrent checkpoint updates cannot both replace the same revision");
        } finally { updateStart.countDown(); updates.shutdownNow(); }

        AtomicInteger closeConnections = new AtomicInteger();
        JdbcTaskCoordinator closeFailed = new JdbcTaskCoordinator(() -> {
            closeConnections.incrementAndGet();
            Connection delegate = DriverManager.getConnection(url);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, method, arguments) -> {
                try {
                    Object result = method.invoke(delegate, arguments);
                    if (method.getName().equals("close")) throw new SQLException("Synthetic close error after commit", "40001");
                    return result;
                } catch (InvocationTargetException e) { throw e.getCause(); }
            });
        });
        try { closeFailed.checkpoint(successor, 5, "committed before connection close failed"); throw new AssertionError("Expected close error"); }
        catch (SQLException expected) { check("40001".equals(expected.getSQLState()), "Close failure is propagated"); }
        check(closeConnections.get() == 1 && store.find(key).orElseThrow().revision() == 6,
                "Even a 40001 from connection close must not replay an already committed write");

        TaskKey other = new TaskKey("workspace-b", key.workflow(), key.task());
        store.create(other, "");
        Lease otherOwner = store.claim(other, "another-worker", Duration.ofMinutes(2)).orElseThrow();
        store.checkpoint(otherOwner, 0, "separate progress");
        check(store.find(key).orElseThrow().revision() == 6, "Equal task IDs in different workspaces remain separate");
        check(store.events(other, 0, 100).size() == 3, "History is scoped by workspace/workflow/task");
        store.approve(successor, 6, "reviewer");
        Snapshot completed = store.complete(successor, 6);
        check(completed.state() == State.COMPLETED, "Fresh approval permits task completion");
        conflict(() -> store.complete(successor, 6));
        check(store.claim(key, "late-worker", Duration.ofSeconds(1)).isEmpty(), "Completed tasks cannot be reclaimed");
    }
    @FunctionalInterface private interface BeforePrepare { void run(String sql) throws SQLException; }
    private static Connection proxy(Connection delegate, BeforePrepare before) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, method, args) -> {
            if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) before.run(sql);
            try { return method.invoke(delegate, args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void conflict(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejected stale or unapproved transition"); }
        catch (Conflict expected) { }
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
