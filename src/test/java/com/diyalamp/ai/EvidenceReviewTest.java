package com.diyalamp.ai;

import java.util.*;
import static com.diyalamp.ai.EvidenceReview.*;

public final class EvidenceReviewTest {
    private static final Source SOURCE = new Source("survey", "v1", "Ten people answered.");
    private static final List<Source> SOURCES = List.of(SOURCE);
    private static Draft draft(String revision, String claim, String context, String other) {
        return new Draft(revision, List.of(new Block("claim", claim), new Block("context", context), new Block("other", other)));
    }
    private static Request request(Draft draft) {
        return begin(draft, SOURCES, new Anchor("claim", 0, 10, "Ten people"), Set.of("context"), List.of(new SourceAnchor("survey", 0, 10, "Ten people")));
    }
    public static void main(String[] args) {
        Draft original = draft("d1", "Ten people responded.", "A small sample.", "Appendix.");
        Request request = request(original);
        Receipt checked = request.complete(original, SOURCES);
        check(!checked.requiresReview(), "fresh receipt");
        Draft unrelated = draft("d2", "Ten people responded.", "A small sample.", "Expanded appendix.");
        check(!checked.reconcile(unrelated, SOURCES).requiresReview(), "unrelated block preserves review");
        check(checked.reviewedRevision().equals("d1"), "original revision retained");
        Draft changed = draft("d3", "Ten people certainly responded.", "A small sample.", "Appendix.");
        Receipt invalid = checked.reconcile(changed, SOURCES);
        check(invalid.reasons().contains(Reason.CLAIM_OR_CONTEXT_CHANGED), "claim context changes invalidate even when quote unchanged");
        check(invalid.reconcile(original, SOURCES).requiresReview(), "revert does not clear invalidation");
        check(checked.reconcile(draft("d4", "Ten people responded.", "A representative sample.", "Appendix."), SOURCES).requiresReview(), "declared context invalidates");
        check(checked.reconcile(original, List.of(new Source("survey", "v2", SOURCE.excerpt()))).reasons().contains(Reason.SOURCE_CHANGED), "source version changed");
        check(checked.reconcile(original, List.of(new Source("survey", "v1", "Ten people refused."))).requiresReview(), "source mutation with reused version detected");
        check(checked.reconcile(original, List.of()).requiresReview(), "deleted source");
        check(checked.reconcile(new Draft("d5", List.of(new Block("other", "Appendix."))), SOURCES).requiresReview(), "deleted claim/context");
        check(checked.reconcile(new Draft("d6", List.of(original.blocks().get(2), original.blocks().get(0), original.blocks().get(1))), SOURCES).requiresReview() == false, "stable ID supports reordering");
        check(request(draft("d7", "Ten people and Ten people", "Context.", "Appendix.")).complete(draft("d7", "Ten people and Ten people", "Context.", "Appendix."), SOURCES).reasons().contains(Reason.AMBIGUOUS_ANCHOR), "duplicate claim conservative");
        Source duplicate = new Source("survey", "v1", "Ten people and Ten people");
        Request ambiguous = begin(original, List.of(duplicate), new Anchor("claim", 0, 10, "Ten people"), Set.of(), List.of(new SourceAnchor("survey", 0, 10, "Ten people")));
        check(ambiguous.complete(original, List.of(duplicate)).requiresReview(), "duplicate source conservative");
        rejects(() -> request.complete(unrelated, SOURCES), "async response rejects even unrelated intervening edit");
        rejects(() -> request.complete(original, List.of(new Source("survey", "v2", SOURCE.excerpt()))), "async source race");
        rejects(() -> request.complete(draft("d1", "Ten people lied.", "A small sample.", "Appendix."), SOURCES), "reused revision content mutation");
        rejects(() -> begin(original, SOURCES, new Anchor("claim", 1, 11, "Ten people"), Set.of(), List.of(new SourceAnchor("survey", 0, 10, "Ten people"))), "wrong claim offsets");
        rejects(() -> begin(original, SOURCES, new Anchor("claim", 0, 10, "Ten people"), Set.of("missing"), List.of(new SourceAnchor("survey", 0, 10, "Ten people"))), "unknown dependency");
        rejects(() -> new Draft("d8", List.of(new Block("same", "one"), new Block("same", "two"))), "duplicate block IDs");
        check(!request(changed).complete(changed, SOURCES).requiresReview(), "new explicit review replaces invalid receipt");
        System.out.println("EvidenceReviewTest passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable run, String message) {
        try { run.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
}
