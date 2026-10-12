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
        Draft blank = new Draft("empty", List.of());
        check(blank.blocks().isEmpty(), "blank editor is a valid draft");
        check(checked.reconcile(blank, SOURCES).requiresReview(), "deleting all writing invalidates old receipts");
        rejects(() -> request(blank), "blank draft cannot receive a fabricated claim review");
        invalidationDetails();
        dependencyMutationMatrix();
        System.out.println("EvidenceReviewTest passed");
    }
    private static void invalidationDetails() {
        Draft original = draft("d1", "Ten people responded.", "A small sample.", "Appendix.");
        Receipt checked = request(original).complete(original, SOURCES);
        DependencyChange context = new DependencyChange(Reason.CLAIM_OR_CONTEXT_CHANGED, DependencyKind.BLOCK, "context");
        DependencyChange source = new DependencyChange(Reason.SOURCE_CHANGED, DependencyKind.SOURCE, "survey");
        Receipt first = checked.reconcile(draft("d2", "Ten people responded.", "Now a large sample.", "Appendix."), SOURCES);
        check(first.dependencyChanges().equals(Set.of(context)), "points to changed context, not claim");
        Receipt second = first.reconcile(original, List.of());
        check(second.dependencyChanges().equals(Set.of(context, source)), "accumulates distinct causes across context undo and source deletion");
        check(second.reconcile(original, SOURCES).dependencyChanges().equals(Set.of(context, source)), "details survive full undo");
        check(checked.dependencyChanges().isEmpty() && first.dependencyChanges().equals(Set.of(context)), "earlier immutable receipts unchanged");
        try { second.dependencyChanges().clear(); throw new AssertionError("mutable changes"); }
        catch (UnsupportedOperationException expected) { }
        check(second.reconcile(original, List.of()).dependencyChanges().size() == 2, "repeated reconciliation is idempotent");

        Source sameId = new Source("claim", "v1", "Ten people and Ten people");
        Draft repeated = draft("d3", "Ten people and Ten people", "Context.", "Appendix.");
        Request duplicate = begin(repeated, List.of(sameId), new Anchor("claim", 0, 10, "Ten people"), Set.of(),
                List.of(new SourceAnchor("claim", 0, 10, "Ten people"), new SourceAnchor("claim", 15, 25, "Ten people")));
        Receipt ambiguous = duplicate.complete(repeated, List.of(sameId));
        check(ambiguous.dependencyChanges().equals(Set.of(
                new DependencyChange(Reason.AMBIGUOUS_ANCHOR, DependencyKind.BLOCK, "claim"),
                new DependencyChange(Reason.AMBIGUOUS_ANCHOR, DependencyKind.SOURCE, "claim"))), "same ID in separate namespaces remains distinguishable; repeated evidence deduplicated");
        check(ambiguous.reasons().equals(Set.of(Reason.AMBIGUOUS_ANCHOR)), "legacy reasons project from details");
        rejects(() -> new DependencyChange(Reason.SOURCE_CHANGED, DependencyKind.BLOCK, "claim"), "invalid reason/kind pair");
        rejects(() -> new DependencyChange(Reason.CLAIM_OR_CONTEXT_CHANGED, DependencyKind.SOURCE, "claim"), "invalid reverse reason/kind pair");
    }
    /** Deterministic exhaustive mutations; expected read-set effects are specified independently. */
    private static void dependencyMutationMatrix() {
        Draft original = draft("d1", "Ten people responded.", "A small sample.", "Appendix.");
        Receipt checked = request(original).complete(original, SOURCES);
        for (int mask = 0; mask < 128; mask++) {
            boolean claimChanged = (mask & 1) != 0, contextChanged = (mask & 2) != 0;
            boolean otherChanged = (mask & 4) != 0, sourceChanged = (mask & 8) != 0;
            boolean sourceDeleted = (mask & 16) != 0, contextDeleted = (mask & 32) != 0, claimDeleted = (mask & 64) != 0;
            List<Block> blocks = new ArrayList<>();
            if (!claimDeleted) blocks.add(new Block("claim", claimChanged ? "Ten people declined." : "Ten people responded."));
            if (!contextDeleted) blocks.add(new Block("context", contextChanged ? "A large sample." : "A small sample."));
            blocks.add(new Block("other", otherChanged ? "Changed appendix." : "Appendix."));
            List<Source> sources = sourceDeleted ? List.of() : List.of(sourceChanged ? new Source("survey", "v2", SOURCE.excerpt()) : SOURCE);
            Set<DependencyChange> expected = new HashSet<>();
            if (claimChanged || claimDeleted) expected.add(new DependencyChange(Reason.CLAIM_OR_CONTEXT_CHANGED, DependencyKind.BLOCK, "claim"));
            if (contextChanged || contextDeleted) expected.add(new DependencyChange(Reason.CLAIM_OR_CONTEXT_CHANGED, DependencyKind.BLOCK, "context"));
            if (sourceChanged || sourceDeleted) expected.add(new DependencyChange(Reason.SOURCE_CHANGED, DependencyKind.SOURCE, "survey"));
            Receipt result = checked.reconcile(new Draft("current", blocks), sources);
            check(result.dependencyChanges().equals(expected), "exact dependency attribution for mutation " + mask);
            check(result.requiresReview() == !expected.isEmpty(), "status agrees for mutation " + mask);
            check(result.reconcile(original, SOURCES).dependencyChanges().equals(expected), "undo preserves attribution " + mask);
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable run, String message) {
        try { run.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
}
