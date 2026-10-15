// SPDX-License-Identifier: Apache-2.0
package com.diyalamp.coordination;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Transactional task coordination for CockroachDB. This is not an authentication or tool-execution layer. */
public final class JdbcTaskCoordinator {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    public enum State { READY, CLAIMED, COMPLETED }
    public record TaskKey(String workspace, String workflow, String task) {
        public TaskKey { id(workspace); id(workflow); id(task); }
    }
    /** A concurrency witness, not a secret or an authorization credential. */
    public record Lease(TaskKey key, String worker, long generation, Instant until) {
        public Lease {
            Objects.requireNonNull(key); id(worker); Objects.requireNonNull(until);
            if (generation < 1) throw new IllegalArgumentException("Invalid lease version");
        }
    }
    public record Snapshot(TaskKey key, State state, long revision, long generation, long eventSequence,
                           String checkpoint, String worker, Instant leaseUntil,
                           Long approvedRevision, Long approvedGeneration, String approvedBy) {
        public Optional<Lease> lease() {
            return state == State.CLAIMED
                    ? Optional.of(new Lease(key, worker, generation, leaseUntil)) : Optional.empty();
        }
        public boolean approvalCurrent() {
            return Objects.equals(approvedRevision, revision) && Objects.equals(approvedGeneration, generation);
        }
    }
    public record Event(long sequence, String type, long revision, long generation, String actor) {}
    public static final class Conflict extends IllegalStateException {
        public Conflict() { super("Task state, ownership, lease or approval is no longer current"); }
    }
    private final Connections connections;
    private static final String COLUMNS = "state, revision, generation, event_sequence, checkpoint, worker, lease_until, approved_revision, approved_generation, approved_by";
    private static final String KEY = "workspace_id=? AND workflow_id=? AND task_id=?";
    private static final String OWNED = KEY + " AND state='CLAIMED' AND worker=? AND generation=? AND lease_until>?";

    public JdbcTaskCoordinator(Connections connections) { this.connections = Objects.requireNonNull(connections); }

    /** Run once during application setup. Migrations never drop or overwrite existing tables. */
    public void initialize() throws SQLException {
        try (Connection c = connections.open(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS dl_coord_tasks ("
                    + "workspace_id TEXT NOT NULL, workflow_id TEXT NOT NULL, task_id TEXT NOT NULL, "
                    + "state TEXT NOT NULL CHECK(state IN ('READY','CLAIMED','COMPLETED')), "
                    + "revision BIGINT NOT NULL, generation BIGINT NOT NULL, event_sequence BIGINT NOT NULL, "
                    + "checkpoint TEXT NOT NULL, worker TEXT, lease_until TIMESTAMPTZ, "
                    + "approved_revision BIGINT, approved_generation BIGINT, approved_by TEXT, "
                    + "PRIMARY KEY(workspace_id,workflow_id,task_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS dl_coord_events ("
                    + "workspace_id TEXT NOT NULL, workflow_id TEXT NOT NULL, task_id TEXT NOT NULL, "
                    + "event_sequence BIGINT NOT NULL, event_type TEXT NOT NULL, revision BIGINT NOT NULL, "
                    + "generation BIGINT NOT NULL, actor TEXT NOT NULL, "
                    + "PRIMARY KEY(workspace_id,workflow_id,task_id,event_sequence), "
                    + "FOREIGN KEY(workspace_id,workflow_id,task_id) REFERENCES dl_coord_tasks(workspace_id,workflow_id,task_id))");
        }
    }
    public Snapshot create(TaskKey key, String checkpoint) throws SQLException {
        Objects.requireNonNull(key); content(checkpoint);
        return transaction(c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT INTO dl_coord_tasks "
                    + "(workspace_id,workflow_id,task_id,state,revision,generation,event_sequence,checkpoint) "
                    + "VALUES (?,?,?,'READY',0,0,1,?) ON CONFLICT (workspace_id,workflow_id,task_id) DO NOTHING")) {
                bindKey(p, 1, key); p.setString(4, checkpoint); changed(p);
            }
            Snapshot result = required(c, key); event(c, result, "CREATED", "system"); return result;
        });
    }
    /** Exactly one claimant can acquire a READY task or replace an expired owner. */
    public Optional<Lease> claim(TaskKey key, String worker, Duration ttl) throws SQLException {
        Objects.requireNonNull(key); id(worker); duration(ttl);
        return transaction(c -> {
            Instant now = now(c);
            try (PreparedStatement p = c.prepareStatement("UPDATE dl_coord_tasks SET state='CLAIMED', worker=?, "
                    + "lease_until=?, generation=generation+1, event_sequence=event_sequence+1, "
                    + "approved_revision=NULL, approved_generation=NULL, approved_by=NULL WHERE " + KEY
                    + " AND (state='READY' OR (state='CLAIMED' AND lease_until<=?))")) {
                p.setString(1, worker); time(p, 2, now.plus(ttl)); bindKey(p, 3, key); time(p, 6, now);
                if (p.executeUpdate() == 0) return Optional.empty();
            }
            Snapshot result = required(c, key); event(c, result, "CLAIMED", worker); return result.lease();
        });
    }
    public Lease renew(Lease lease, Duration ttl) throws SQLException {
        Objects.requireNonNull(lease); duration(ttl);
        return transaction(c -> {
            Instant now = now(c);
            try (PreparedStatement p = c.prepareStatement("UPDATE dl_coord_tasks SET lease_until=GREATEST(lease_until,?), "
                    + "event_sequence=event_sequence+1 WHERE " + OWNED)) {
                time(p, 1, now.plus(ttl)); bindOwned(p, 2, lease, now); changed(p);
            }
            Snapshot result = required(c, lease.key()); event(c, result, "RENEWED", lease.worker());
            return result.lease().orElseThrow();
        });
    }
    /** A new checkpoint advances the content revision and invalidates any earlier approval. */
    public Snapshot checkpoint(Lease lease, long expectedRevision, String checkpoint) throws SQLException {
        Objects.requireNonNull(lease); revision(expectedRevision); content(checkpoint);
        return transaction(c -> {
            try (PreparedStatement p = c.prepareStatement("UPDATE dl_coord_tasks SET checkpoint=?, revision=revision+1, "
                    + "event_sequence=event_sequence+1, approved_revision=NULL, approved_generation=NULL, approved_by=NULL "
                    + "WHERE " + OWNED + " AND revision=?")) {
                p.setString(1, checkpoint); bindOwned(p, 2, lease, now(c)); p.setLong(8, expectedRevision); changed(p);
            }
            Snapshot result = required(c, lease.key()); event(c, result, "CHECKPOINTED", lease.worker()); return result;
        });
    }
    /** The application must authenticate and authorize the reviewer before calling this method. */
    public Snapshot approve(Lease lease, long expectedRevision, String reviewer) throws SQLException {
        Objects.requireNonNull(lease); revision(expectedRevision); id(reviewer);
        return transaction(c -> {
            try (PreparedStatement p = c.prepareStatement("UPDATE dl_coord_tasks SET approved_revision=revision, "
                    + "approved_generation=generation, approved_by=?, event_sequence=event_sequence+1 "
                    + "WHERE " + OWNED + " AND revision=? AND approved_revision IS NULL")) {
                p.setString(1, reviewer); bindOwned(p, 2, lease, now(c)); p.setLong(8, expectedRevision); changed(p);
            }
            Snapshot result = required(c, lease.key()); event(c, result, "APPROVED", reviewer); return result;
        });
    }
    /** Completes the database task record only. It does not execute or authorize an external API call. */
    public Snapshot complete(Lease lease, long expectedRevision) throws SQLException {
        Objects.requireNonNull(lease); revision(expectedRevision);
        return transaction(c -> {
            try (PreparedStatement p = c.prepareStatement("UPDATE dl_coord_tasks SET state='COMPLETED', "
                    + "event_sequence=event_sequence+1 WHERE " + OWNED + " AND revision=? "
                    + "AND approved_revision=revision AND approved_generation=generation")) {
                bindOwned(p, 1, lease, now(c)); p.setLong(7, expectedRevision); changed(p);
            }
            Snapshot result = required(c, lease.key()); event(c, result, "COMPLETED", lease.worker()); return result;
        });
    }
    public Optional<Snapshot> find(TaskKey key) throws SQLException {
        Objects.requireNonNull(key); return transaction(c -> read(c, key));
    }
    /** Bounded keyset pagination in the task's transactionally assigned event order. */
    public List<Event> events(TaskKey key, long afterSequence, int limit) throws SQLException {
        Objects.requireNonNull(key);
        if (afterSequence < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid event page");
        return transaction(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT event_sequence,event_type,revision,generation,actor "
                    + "FROM dl_coord_events WHERE " + KEY + " AND event_sequence>? ORDER BY event_sequence LIMIT ?")) {
                bindKey(p, 1, key); p.setLong(4, afterSequence); p.setInt(5, limit);
                try (ResultSet rows = p.executeQuery()) {
                    List<Event> result = new ArrayList<>();
                    while (rows.next()) result.add(new Event(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getLong(4), rows.getString(5)));
                    return List.copyOf(result);
                }
            }
        });
    }
    private Optional<Snapshot> read(Connection c, TaskKey key) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT " + COLUMNS + " FROM dl_coord_tasks WHERE " + KEY)) {
            bindKey(p, 1, key);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                Timestamp until = r.getTimestamp(7);
                return Optional.of(new Snapshot(key, State.valueOf(r.getString(1)), r.getLong(2), r.getLong(3),
                        r.getLong(4), r.getString(5), r.getString(6), until == null ? null : until.toInstant(),
                        nullableLong(r, 8), nullableLong(r, 9), r.getString(10)));
            }
        }
    }
    private Snapshot required(Connection c, TaskKey key) throws SQLException { return read(c, key).orElseThrow(Conflict::new); }
    private void event(Connection c, Snapshot task, String type, String actor) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO dl_coord_events "
                + "(workspace_id,workflow_id,task_id,event_sequence,event_type,revision,generation,actor) VALUES (?,?,?,?,?,?,?,?)")) {
            bindKey(p, 1, task.key()); p.setLong(4, task.eventSequence()); p.setString(5, type);
            p.setLong(6, task.revision()); p.setLong(7, task.generation()); p.setString(8, actor); p.executeUpdate();
        }
    }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(SqlWork<T> work) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            try (Connection c = connections.open()) {
                c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); c.setAutoCommit(false);
                try { T result = work.run(c); c.commit(); return result; }
                catch (SQLException | RuntimeException failure) {
                    try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    // Retry only a known aborted work/commit attempt. Acquisition, configuration and
                    // close errors stay outside this catch: a close error must not replay a committed write.
                    if (!(failure instanceof SQLException sql) || !"40001".equals(sql.getSQLState()) || attempt >= 7)
                        throw failure;
                }
            }
        }
    }
    private static Instant now(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT current_timestamp()")) {
            r.next(); return r.getTimestamp(1).toInstant();
        }
    }
    private static int bindKey(PreparedStatement p, int start, TaskKey key) throws SQLException {
        p.setString(start++, key.workspace()); p.setString(start++, key.workflow()); p.setString(start++, key.task()); return start;
    }
    private static void bindOwned(PreparedStatement p, int start, Lease lease, Instant now) throws SQLException {
        int i = bindKey(p, start, lease.key()); p.setString(i++, lease.worker()); p.setLong(i++, lease.generation()); time(p, i, now);
    }
    private static void changed(PreparedStatement p) throws SQLException { if (p.executeUpdate() != 1) throw new Conflict(); }
    private static void time(PreparedStatement p, int index, Instant instant) throws SQLException { p.setTimestamp(index, Timestamp.from(instant)); }
    private static Long nullableLong(ResultSet r, int index) throws SQLException { long value = r.getLong(index); return r.wasNull() ? null : value; }
    private static void revision(long value) { if (value < 0) throw new IllegalArgumentException("Invalid revision"); }
    private static void duration(Duration ttl) {
        Objects.requireNonNull(ttl);
        if (ttl.compareTo(Duration.ofMillis(1)) < 0 || ttl.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Lease duration must be between 1ms and 1 day");
    }
    private static void content(String value) {
        Objects.requireNonNull(value);
        if (value.length() > 65536) throw new IllegalArgumentException("Checkpoint exceeds 65536 UTF-16 code units");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) throw new IllegalArgumentException("Checkpoint contains a null character");
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Checkpoint contains invalid Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Checkpoint contains invalid Unicode");
        }
    }
    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("Invalid coordination identifier");
    }
}
