package com.diyalamp.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Workflow guarantees are independent of the model and Notion adapters. */
public final class ReviewSession {
    public enum State { ROUGH_DRAFT, REVIEWING, NEEDS_INPUT, AWAITING_APPROVAL, READY }
    private final String draft;
    private final String fingerprint;
    private final List<PhaseResult> results = new ArrayList<>();
    private final Map<Phase, String> responses = new EnumMap<>(Phase.class);
    private PhaseResult pending;
    private boolean answered;
    private boolean approved;

    public ReviewSession(String draft) {
        if (draft == null || draft.isBlank() || draft.length() > 100_000)
            throw new IllegalArgumentException("Draft must contain 1–100,000 characters.");
        this.draft = draft;
        this.fingerprint = fingerprint(draft);
    }
    public String draft() { return draft; }
    public String fingerprint() { return fingerprint; }
    public List<PhaseResult> results() { return List.copyOf(results); }
    public Map<Phase,String> responses() { return Map.copyOf(responses); }
    public Optional<PhaseResult> pending() { return Optional.ofNullable(pending); }
    public boolean answered() { return answered; }
    public Optional<Phase> nextPhase() {
        return results.size() == Phase.values().length ? Optional.empty() : Optional.of(Phase.values()[results.size()]);
    }
    public State state() {
        if (approved) return State.READY;
        if (nextPhase().isEmpty()) return State.AWAITING_APPROVAL;
        if (pending != null && !answered) return State.NEEDS_INPUT;
        if (results.isEmpty() && pending == null) return State.ROUGH_DRAFT;
        return State.REVIEWING;
    }
    public void submit(PhaseResult result) {
        Phase expected = nextPhase().orElseThrow(() -> new IllegalStateException("All phases are complete; author approval is required."));
        if (result.phase() != expected) throw new IllegalStateException("Cannot skip phases. Expected " + expected.label() + ".");
        if (pending != null && !answered) throw new IllegalStateException("Supply author context before reviewing this phase again.");
        if (!result.questions().isEmpty()) {
            pending = result; answered = false;
        } else {
            results.add(result); pending = null; answered = false;
        }
    }
    public void respond(String context) {
        if (pending == null || answered) throw new IllegalStateException("No unanswered request for context.");
        if (context == null || context.isBlank() || context.length() > 100_000)
            throw new IllegalArgumentException("Author context must contain 1–100,000 characters.");
        String previous = responses.get(pending.phase());
        String combined = previous == null ? context : previous + "\n\n" + context;
        if (combined.length() > 100_000)
            throw new IllegalArgumentException("Accumulated context exceeds the size limit; start a new session.");
        responses.put(pending.phase(), combined);
        answered = true;
    }
    public void approve(String currentDraft) {
        if (state() != State.AWAITING_APPROVAL) throw new IllegalStateException("Complete all five phases before approval.");
        if (!fingerprint.equals(fingerprint(currentDraft)))
            throw new IllegalStateException("The source draft changed. Start a new review before marking it Ready.");
        approved = true;
    }
    public static String fingerprint(String text) {
        Objects.requireNonNull(text, "Draft is required.");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
}
