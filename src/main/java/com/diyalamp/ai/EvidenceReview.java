package com.diyalamp.ai;

import java.util.*;

/** Revision-aware review bookkeeping, not an assessment of truth or source authenticity.
 * Stable block IDs must be assigned by the editor, never inferred from matching text.
 */
public final class EvidenceReview {
    private EvidenceReview() {}
    public record Block(String id, String text) {
        public Block { identifier(id); fingerprint(text); }
    }
    public record Draft(String revision, List<Block> blocks) {
        public Draft {
            identifier(revision); blocks = List.copyOf(blocks);
            if (blocks.size() > 100 || blocks.stream().mapToInt(b -> b.text().length()).sum() > 50_000
                    || blocks.stream().map(Block::id).distinct().count() != blocks.size()) throw new IllegalArgumentException("Invalid draft blocks.");
        }
    }
    /** A supplied source excerpt with an immutable application-assigned revision. */
    public record Source(String id, String revision, String excerpt) {
        public Source { identifier(id); identifier(revision); fingerprint(excerpt); }
    }
    public record Anchor(String blockId, int start, int end, String quote) {
        public Anchor {
            identifier(blockId); fingerprint(quote);
            if (start < 0 || end <= start) throw new IllegalArgumentException("Invalid anchor.");
        }
    }
    public record SourceAnchor(String sourceId, int start, int end, String quote) {
        public SourceAnchor {
            identifier(sourceId); fingerprint(quote);
            if (start < 0 || end <= start) throw new IllegalArgumentException("Invalid source anchor.");
        }
    }
    public enum Reason { CLAIM_OR_CONTEXT_CHANGED, SOURCE_CHANGED, AMBIGUOUS_ANCHOR }
    public enum DependencyKind { BLOCK, SOURCE }
    /** Identifies a dependency observed to invalidate this review, without copying its private text.
     * This is historical invalidation, not necessarily a difference in the latest draft after undo.
     */
    public record DependencyChange(Reason reason, DependencyKind kind, String id) {
        public DependencyChange {
            Objects.requireNonNull(reason); Objects.requireNonNull(kind); identifier(id);
            if (reason == Reason.CLAIM_OR_CONTEXT_CHANGED && kind != DependencyKind.BLOCK
                    || reason == Reason.SOURCE_CHANGED && kind != DependencyKind.SOURCE)
                throw new IllegalArgumentException("Invalid dependency kind for reason.");
        }
    }

    /** Captures the complete input presented for a review. A receipt cannot accept a stale response. */
    public static final class Request {
        private final Draft draft;
        private final Map<String, Source> sources;
        private final Anchor claim;
        private final Set<String> contextIds;
        private final List<SourceAnchor> evidence;
        private Request(Draft draft, List<Source> sources, Anchor claim, Set<String> contextIds, List<SourceAnchor> evidence) {
            this.draft = Objects.requireNonNull(draft); this.sources = sourceMap(sources);
            this.claim = Objects.requireNonNull(claim); this.contextIds = Set.copyOf(contextIds); this.evidence = List.copyOf(evidence);
            if (this.evidence.isEmpty() || this.evidence.size() > 10 || this.contextIds.size() > 100) throw new IllegalArgumentException("Missing or excessive dependencies.");
            this.contextIds.forEach(EvidenceReview::identifier);
            Map<String, Block> blocks = blockMap(draft);
            if (!blocks.keySet().containsAll(this.contextIds) || !blocks.containsKey(claim.blockId())) throw new IllegalArgumentException("Unknown context block.");
            exact(blocks.get(claim.blockId()).text(), claim.start(), claim.end(), claim.quote());
            for (SourceAnchor anchor : evidence) {
                Source source = this.sources.get(anchor.sourceId());
                if (source == null) throw new IllegalArgumentException("Unknown source.");
                exact(source.excerpt(), anchor.start(), anchor.end(), anchor.quote());
            }
        }
        public String reviewedRevision() { return draft.revision(); }
        public Draft reviewedDraft() { return draft; }
        public Map<String, Source> reviewedSources() { return sources; }
        public Set<String> contextBlockIds() { return contextIds; }
        public Anchor claim() { return claim; }
        public List<SourceAnchor> evidence() { return evidence; }
        /** Caller records a completed human or agent review; this does not mean the claim is supported. */
        public Receipt complete(Draft current, List<Source> currentSources) {
            if (!draft.equals(current) || !sources.equals(sourceMap(currentSources))) throw new IllegalStateException("Review response is stale.");
            return new Receipt(this, changes(current, currentSources));
        }
        private Set<DependencyChange> changes(Draft current, List<Source> currentSources) {
            Set<DependencyChange> result = new HashSet<>();
            Map<String, Block> before = blockMap(draft), after = blockMap(current);
            Set<String> dependencies = new HashSet<>(contextIds); dependencies.add(claim.blockId());
            for (String id : dependencies) if (!Objects.equals(before.get(id), after.get(id))) result.add(new DependencyChange(Reason.CLAIM_OR_CONTEXT_CHANGED, DependencyKind.BLOCK, id));
            Block block = after.get(claim.blockId());
            if (block != null && ambiguous(block.text(), claim.quote())) result.add(new DependencyChange(Reason.AMBIGUOUS_ANCHOR, DependencyKind.BLOCK, claim.blockId()));
            Map<String, Source> latest = sourceMap(currentSources);
            for (SourceAnchor anchor : evidence) {
                Source source = latest.get(anchor.sourceId());
                if (!Objects.equals(sources.get(anchor.sourceId()), source)) result.add(new DependencyChange(Reason.SOURCE_CHANGED, DependencyKind.SOURCE, anchor.sourceId()));
                if (source != null && ambiguous(source.excerpt(), anchor.quote())) result.add(new DependencyChange(Reason.AMBIGUOUS_ANCHOR, DependencyKind.SOURCE, anchor.sourceId()));
            }
            return Set.copyOf(result);
        }
    }
    /** Immutable and monotonically invalidated: reverting text cannot silently restore an old review. */
    public static final class Receipt {
        private final Request request;
        private final Set<DependencyChange> changes;
        private Receipt(Request request, Set<DependencyChange> changes) { this.request = request; this.changes = Set.copyOf(changes); }
        public String reviewedRevision() { return request.reviewedRevision(); }
        public Map<String, Source> reviewedSources() { return request.reviewedSources(); }
        public Set<String> contextBlockIds() { return request.contextBlockIds(); }
        public Anchor claim() { return request.claim(); }
        public List<SourceAnchor> evidence() { return request.evidence(); }
        public boolean requiresReview() { return !changes.isEmpty(); }
        public Set<Reason> reasons() {
            EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
            changes.forEach(change -> reasons.add(change.reason()));
            return Set.copyOf(reasons);
        }
        /** All observed invalidating dependencies since completion; IDs need not still exist.
         * Block and source IDs are separate namespaces. No ordering is promised.
         */
        public Set<DependencyChange> dependencyChanges() { return changes; }
        /** Persist the returned receipt after each committed edit. */
        public Receipt reconcile(Draft current, List<Source> sources) {
            Set<DependencyChange> next = new HashSet<>(changes); next.addAll(request.changes(current, sources));
            return new Receipt(request, next);
        }
    }
    public static Request begin(Draft draft, List<Source> sources, Anchor claim, Set<String> contextBlockIds, List<SourceAnchor> evidence) {
        return new Request(draft, sources, claim, contextBlockIds, evidence);
    }
    private static Map<String, Block> blockMap(Draft draft) {
        Map<String, Block> result = new HashMap<>(); draft.blocks().forEach(block -> result.put(block.id(), block)); return result;
    }
    private static Map<String, Source> sourceMap(List<Source> sources) {
        sources = List.copyOf(sources);
        if (sources.size() > 10 || sources.stream().mapToInt(s -> s.excerpt().length()).sum() > 40_000) throw new IllegalArgumentException("Excessive sources.");
        Map<String, Source> result = new HashMap<>();
        for (Source source : sources) if (result.put(source.id(), source) != null) throw new IllegalArgumentException("Duplicate source ID.");
        return Map.copyOf(result);
    }
    private static boolean ambiguous(String text, String quote) {
        int first = text.indexOf(quote); return first >= 0 && text.indexOf(quote, first + 1) >= 0;
    }
    private static void exact(String text, int start, int end, String quote) {
        if (end > text.length() || !text.substring(start, end).equals(quote)) throw new IllegalArgumentException("Anchor does not match exact passage.");
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid identifier.");
    }
    private static String fingerprint(String text) { return DocumentReviewAgent.Snapshot.of(text).fingerprint(); }
}
