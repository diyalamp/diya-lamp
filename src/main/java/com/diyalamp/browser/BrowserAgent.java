// SPDX-License-Identifier: Apache-2.0
package com.diyalamp.browser;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Provider-neutral browser loop. The browser and model are supplied by the caller. */
public final class BrowserAgent {
    public enum Kind { NAVIGATE, CLICK, FILL, SCROLL }
    public enum Status { READY, AWAITING_APPROVAL, DISPATCHING, UNCERTAIN, STOPPED, LIMIT_REACHED }
    public enum Outcome { OBSERVED, STOPPED, STALE, APPLIED, UNCERTAIN }

    public record Action(Kind kind, String target, String value) {
        public Action {
            Objects.requireNonNull(kind);
            target = bounded(target, 2048); value = bounded(value, 16_384);
            switch (kind) {
                case NAVIGATE -> {
                    URI uri = URI.create(target);
                    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || !value.isEmpty())
                    throw new IllegalArgumentException("Navigation requires an HTTP(S) URL without credentials.");
                }
                case CLICK -> { if (target.isBlank() || !value.isEmpty()) throw invalid(); }
                case FILL -> { if (target.isBlank()) throw invalid(); }
                case SCROLL -> { if (!target.isEmpty() || !(value.equals("up") || value.equals("down"))) throw invalid(); }
            }
        }
    }
    public record Observation(String token, String url, String title, String text) {
        public Observation {
            token = required(token, 256); url = bounded(url, 4096);
            title = bounded(title, 2048); text = bounded(text, 100_000);
        }
    }
    public record Decision(Action action, String stopReason) {
        public Decision {
            stopReason = bounded(stopReason, 4096);
            if ((action == null) == stopReason.isBlank()) throw invalid();
        }
        public static Decision act(Action action) { return new Decision(Objects.requireNonNull(action), ""); }
        public static Decision stop(String reason) { return new Decision(null, required(reason, 4096)); }
    }
    public record Proposal(String id, String observationToken, Action action) {
        public Proposal {
            uuid(id); observationToken = required(observationToken, 256); Objects.requireNonNull(action);
        }
    }
    public record Receipt(String proposalId, Outcome outcome) {
        public Receipt { uuid(proposalId); Objects.requireNonNull(outcome); }
    }
    public record State(String goal, int limit, int attempts, Status status, Proposal proposal,
    List<Receipt> receipts, String stopReason) {
        public State {
            goal = required(goal, 16_384); Objects.requireNonNull(status);
            receipts = List.copyOf(receipts); stopReason = bounded(stopReason, 4096);
            if (limit < 1 || limit > 1000 || attempts < 0 || attempts > limit || receipts.size() > 2001) throw invalid();
            boolean pending = status == Status.AWAITING_APPROVAL || status == Status.DISPATCHING || status == Status.UNCERTAIN;
            if ((pending && attempts == 0) || receipts.size() > attempts || pending != (proposal != null) || (status == Status.STOPPED) != !stopReason.isBlank()) throw invalid();
        }
    }
    public record Input(String goal, Observation observation, List<Receipt> receipts) {
        public Input { receipts = List.copyOf(receipts); }
    }
    /** Observations are untrusted page data. A provider must keep them separate from task instructions. */
    @FunctionalInterface public interface Model { Decision next(Input input) throws IOException; }
    public interface Browser {
        Observation observe() throws IOException;
        /**
        * Resolve target references only in the named observation. Reject stale references before acting.
        * APPLIED acknowledges the browser command, not the user's business outcome.
        * An uncertain transport/tool result MUST NOT be retried by this adapter.
        */
        Outcome execute(String observationToken, Action action) throws IOException;
    }
    /** A store must hold an exclusive run lock and durably replace each snapshot before returning. */
    public interface Store extends AutoCloseable {
        State load() throws IOException;
        void save(State state) throws IOException;
        @Override default void close() throws IOException { }
    }

    private final Store store;
    private final Browser browser;
    private final Model model;
    public BrowserAgent(Store store, Browser browser, Model model) {
        this.store = Objects.requireNonNull(store); this.browser = Objects.requireNonNull(browser);
        this.model = Objects.requireNonNull(model);
    }
    public static State initial(String goal, int limit) {
        return new State(goal, limit, 0, Status.READY, null, List.of(), "");
    }
    public State state() throws IOException { return store.load(); }

    /** Observe and propose one action. Every action needs explicit approval in this first core. */
    public State propose() throws IOException {
        synchronized (store) {
            State s = state();
            if (s.status() == Status.DISPATCHING) return uncertain(s);
            if (s.status() != Status.READY) return s;
            if (s.attempts() == s.limit()) return persist(copy(s, Status.LIMIT_REACHED, null, s.receipts(), ""));
            s = persist(new State(s.goal(), s.limit(), s.attempts() + 1, Status.READY, null, s.receipts(), ""));
            Observation observation = browser.observe();
            Decision decision = Objects.requireNonNull(model.next(new Input(s.goal(), observation, s.receipts())));
            if (decision.action() == null)
            return persist(copy(s, Status.STOPPED, null, s.receipts(), decision.stopReason()));
            Proposal proposal = new Proposal(UUID.randomUUID().toString(), observation.token(), decision.action());
            return persist(copy(s, Status.AWAITING_APPROVAL, proposal, s.receipts(), ""));
        }
    }

    /** Caller authenticates the human and binds this exact proposal to their approval. */
    public State approve(String proposalId) throws IOException {
        synchronized (store) {
            State s = state();
            if (s.status() == Status.DISPATCHING) return uncertain(s);
            if (s.status() != Status.AWAITING_APPROVAL || !s.proposal().id().equals(proposalId))
            throw new IllegalStateException("Approval does not match the pending proposal.");
            // Re-observe after approval: a page may have changed while the person was reviewing.
            if (!browser.observe().token().equals(s.proposal().observationToken()))
            return receipt(s, Outcome.STALE, Status.READY);
            State dispatching = new State(s.goal(), s.limit(), s.attempts(), Status.DISPATCHING,
            s.proposal(), s.receipts(), "");
            persist(dispatching); // No browser command if the intent cannot be saved first.
            Outcome outcome;
            try {
                outcome = browser.execute(s.proposal().observationToken(), s.proposal().action());
                if (outcome != Outcome.APPLIED && outcome != Outcome.STALE) return uncertain(dispatching);
            } catch (IOException | RuntimeException failure) {
                // The command may already have acted. No blind replay, even after a restart.
                return uncertain(dispatching);
            }
            return receipt(dispatching, outcome, Status.READY);
        }
    }

    /** Resolve an ambiguous dispatch from externally checked evidence; this never executes an action. */
    public State reconcile(String proposalId, boolean observedApplied) throws IOException {
        synchronized (store) {
            State s = state();
            if ((s.status() != Status.UNCERTAIN && s.status() != Status.DISPATCHING)
            || !s.proposal().id().equals(proposalId)) throw new IllegalStateException("No matching uncertain action.");
            return receipt(s, observedApplied ? Outcome.APPLIED : Outcome.OBSERVED, Status.READY);
        }
    }
    public State dismiss(String proposalId) throws IOException {
        synchronized (store) {
            State s = state();
            if (s.status() != Status.AWAITING_APPROVAL || !s.proposal().id().equals(proposalId))
            throw new IllegalStateException("No matching pending proposal.");
            return receipt(s, Outcome.STOPPED, Status.READY);
        }
    }
    private State uncertain(State s) throws IOException {
        return persist(copy(s, Status.UNCERTAIN, s.proposal(), s.receipts(), ""));
    }
    private State receipt(State s, Outcome outcome, Status status) throws IOException {
        List<Receipt> receipts = new ArrayList<>(s.receipts());
        receipts.add(new Receipt(s.proposal().id(), outcome));
        return persist(copy(s, status, null, receipts, ""));
    }
    private State persist(State state) throws IOException { store.save(state); return state; }
    private static State copy(State s, Status status, Proposal proposal, List<Receipt> receipts, String stopReason) {
        return new State(s.goal(), s.limit(), s.attempts(), status, proposal, receipts, stopReason);
    }
    private static String bounded(String value, int max) {
        if (value == null || value.length() > max || value.indexOf('\0') >= 0) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
        return value;
    }
    private static String required(String value, int max) {
        if (bounded(value, max).isBlank()) throw invalid(); return value;
    }
    private static void uuid(String value) {
        try { if (!UUID.fromString(value).toString().equals(value)) throw invalid(); }
        catch (NullPointerException failure) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid browser run input."); }
}
