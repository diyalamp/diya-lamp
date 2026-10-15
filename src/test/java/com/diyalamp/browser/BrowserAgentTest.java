// SPDX-License-Identifier: Apache-2.0
package com.diyalamp.browser;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import static com.diyalamp.browser.BrowserAgent.*;

public final class BrowserAgentTest {
    static final Action CLICK = new Action(Kind.CLICK, "button-1", "");
    static final Model MODEL = input -> Decision.act(CLICK);
    static final class Memory implements Store {
        State state = initial("Check the example page", 3);
        boolean fail;
        public State load() { return state; }
        public void save(State state) throws IOException {
            if (fail) throw new IOException("Injected storage failure");
            this.state = state;
        }
    }
    static final class Page implements Browser {
        String token = "view-1";
        int actions;
        boolean ambiguous;
        Memory receiptFailure;
        public Observation observe() { return new Observation(token, "https://example.test", "Fixture", "button-1: Continue"); }
        public Outcome execute(String expected, Action action) throws IOException {
            if (!token.equals(expected)) return Outcome.STALE;
            actions++;
            if (receiptFailure != null) receiptFailure.fail = true;
            if (ambiguous) throw new IOException("Response lost after acting");
            return Outcome.APPLIED;
        }
    }
    public static void main(String[] args) throws Exception {
        approvalAndStaleness(); uncertainAndBudget(); persistenceBoundary(); concurrentApproval(); localStore(); inputValidation();
        System.out.println("PASS: browser proposals, exact approval, stale views, bounded steps, uncertain dispatch recovery, single-writer snapshots and no blind replay.");
    }
    static void approvalAndStaleness() throws Exception {
        Memory store = new Memory(); Page page = new Page(); BrowserAgent agent = new BrowserAgent(store, page, MODEL);
        State proposed = agent.propose();
        check(proposed.status() == Status.AWAITING_APPROVAL && page.actions == 0, "No action before approval");
        reject(() -> agent.approve(UUID.randomUUID().toString()));
        check(page.actions == 0, "Wrong approval cannot act");
        page.token = "view-2";
        check(agent.approve(proposed.proposal().id()).receipts().getFirst().outcome() == Outcome.STALE, "Stale approval rejected");
        check(page.actions == 0, "Stale page cannot act");
        proposed = agent.propose(); agent.approve(proposed.proposal().id());
        check(page.actions == 1 && agent.state().status() == Status.READY, "Approved current action applied");
        reject(() -> agent.approve(UUID.randomUUID().toString()));
        State last = agent.propose(); agent.dismiss(last.proposal().id());
        check(agent.propose().status() == Status.LIMIT_REACHED && page.actions == 1, "Dismissed proposals consume model budget");
        Memory stopped = new Memory();
        check(new BrowserAgent(stopped, page, in -> Decision.stop("The requested result is visible")).propose().status() == Status.STOPPED,
                "Model stop recorded separately from verified success");
    }
    static void uncertainAndBudget() throws Exception {
        Memory store = new Memory(); Page page = new Page(); page.ambiguous = true;
        BrowserAgent agent = new BrowserAgent(store, page, MODEL);
        String id = agent.propose().proposal().id();
        check(agent.approve(id).status() == Status.UNCERTAIN && page.actions == 1, "Uncertain result recorded");
        BrowserAgent restarted = new BrowserAgent(store, page, in -> { throw new AssertionError("Must not ask model to retry"); });
        check(restarted.propose().status() == Status.UNCERTAIN && page.actions == 1, "Restart cannot replay ambiguous click");
        reject(() -> restarted.approve(id));
        restarted.reconcile(id, true);
        check(page.actions == 1 && restarted.state().receipts().getLast().outcome() == Outcome.APPLIED, "Reconciliation does not execute");
        store.state = initial("Bound failing provider calls", 1);
        BrowserAgent failing = new BrowserAgent(store, page, in -> { throw new IOException("Provider unavailable"); });
        reject(failing::propose);
        check(new BrowserAgent(store, page, MODEL).propose().status() == Status.LIMIT_REACHED, "Provider failure cannot reset persisted budget");
    }
    static void persistenceBoundary() throws Exception {
        Memory store = new Memory(); Page page = new Page(); BrowserAgent agent = new BrowserAgent(store, page, MODEL);
        String id = agent.propose().proposal().id(); store.fail = true;
        reject(() -> agent.approve(id));
        check(page.actions == 0, "Failed intent persistence prevents dispatch");
        store.fail = false; page.receiptFailure = store;
        reject(() -> agent.approve(id));
        check(page.actions == 1 && store.state.status() == Status.DISPATCHING, "Receipt failure leaves durable ambiguous intent");
        store.fail = false;
        check(new BrowserAgent(store, page, MODEL).propose().status() == Status.UNCERTAIN && page.actions == 1, "Interrupted receipt never replays");
        store.state = new State("Crash before execution", 1, 1, Status.DISPATCHING,
                new Proposal(UUID.randomUUID().toString(), "view-1", CLICK), List.of(), "");
        check(agent.propose().status() == Status.UNCERTAIN, "Pre-dispatch crash also requires reconciliation");
    }
    static void concurrentApproval() throws Exception {
        Memory store = new Memory(); Page page = new Page(); BrowserAgent first = new BrowserAgent(store, page, MODEL);
        BrowserAgent second = new BrowserAgent(store, page, MODEL);
        String id = first.propose().proposal().id(); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> calls = List.of(() -> approve(first, id), () -> approve(second, id));
            int winners = 0; for (Future<Boolean> f : pool.invokeAll(calls)) if (f.get()) winners++;
            check(winners == 1 && page.actions == 1, "Same store serializes simultaneous approvals");
        } finally { pool.shutdownNow(); }
    }
    static boolean approve(BrowserAgent agent, String id) throws Exception {
        try { agent.approve(id); return true; } catch (IllegalStateException rejected) { return false; }
    }
    static void localStore() throws Exception {
        Path directory = Files.createTempDirectory("diyalamp-browser-fixture-");
        try {
            String proposalId;
            Page page = new Page();
            try (FileBrowserRunStore store = new FileBrowserRunStore(directory, initial("Local recovery fixture", 2))) {
                proposalId = new BrowserAgent(store, page, MODEL).propose().proposal().id();
                reject(() -> { try (var duplicate = new FileBrowserRunStore(directory, initial("Other", 2))) { throw new AssertionError(); } });
                check(Files.getPosixFilePermissions(directory.resolve("state.json")).equals(PosixFilePermissions.fromString("rw-------")), "Private snapshot permissions");
            }
            try (FileBrowserRunStore store = new FileBrowserRunStore(directory, initial("Ignored initializer", 2))) {
                check(store.load().proposal().id().equals(proposalId), "Proposal survives reopen");
                new BrowserAgent(store, page, MODEL).approve(proposalId);
            }
            try (FileBrowserRunStore store = new FileBrowserRunStore(directory, initial("Ignored", 1))) {
                check(store.load().receipts().size() == 1 && store.load().attempts() == 1, "Receipt and budget survive reopen");
            }
            Files.writeString(directory.resolve("state.json"), "{broken");
            reject(() -> { try (var broken = new FileBrowserRunStore(directory, initial("Do not overwrite", 1))) { throw new AssertionError(); } });
            check(Files.readString(directory.resolve("state.json")).equals("{broken"), "Corruption cannot reset state");
        } finally {
            try (var files = Files.walk(directory)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
    static void inputValidation() throws Exception {
        reject(() -> new Action(Kind.NAVIGATE, "javascript:alert(1)", ""));
        reject(() -> new Action(Kind.NAVIGATE, "https://user:secret@example.test", ""));
        reject(() -> new Action(Kind.CLICK, "", ""));
        reject(() -> new Action(Kind.SCROLL, "", "sideways"));
        reject(() -> Decision.stop(""));
        reject(() -> new Decision(CLICK, "Also finish"));
        reject(() -> initial("", 1));
    }
    interface Checked { void run() throws Exception; }
    static void reject(Checked checked) throws Exception {
        try { checked.run(); throw new AssertionError("Expected rejection"); }
        catch (IOException | IllegalArgumentException | IllegalStateException expected) { }
    }
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
