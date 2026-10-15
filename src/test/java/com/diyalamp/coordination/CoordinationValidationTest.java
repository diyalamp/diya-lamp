// SPDX-License-Identifier: Apache-2.0
package com.diyalamp.coordination;

import java.time.*;
import static com.diyalamp.coordination.JdbcTaskCoordinator.*;

public final class CoordinationValidationTest {
    public static void main(String[] args) throws Exception {
        JdbcTaskCoordinator store = new JdbcTaskCoordinator(() -> { throw new AssertionError("Invalid inputs must not open a connection"); });
        TaskKey key = new TaskKey("workspace", "workflow", "task");
        bad(() -> new TaskKey("", "workflow", "task"));
        bad(() -> new TaskKey("workspace", "workflow", "task' OR 1=1"));
        bad(() -> store.claim(key, "worker", Duration.ZERO));
        bad(() -> store.claim(key, "worker", Duration.ofDays(2)));
        bad(() -> store.create(key, "x".repeat(65537)));
        bad(() -> store.create(key, "\uD800"));
        bad(() -> store.create(key, "\uDC00"));
        bad(() -> store.create(key, "\0"));
        bad(() -> store.events(key, 0, 1001));
        bad(() -> store.events(key, -1, 10));
        Lease lease = new Lease(key, "worker", 1, Instant.EPOCH);
        bad(() -> store.checkpoint(lease, -1, "checkpoint"));
        bad(() -> store.approve(lease, 0, ""));
        System.out.println("PASS: bounded coordination inputs reject before connecting");
    }
    @FunctionalInterface interface Action { void run() throws Exception; }
    static void bad(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected invalid input rejection"); }
        catch (IllegalArgumentException expected) { }
    }
}
