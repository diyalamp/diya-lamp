# Research milestone: explain why a review reopened

Research checked 12 October 2026. This is an engineering research note, not a claim
that the engine can establish truth or outperform another writing product.

## Problem and selected contribution

A review can become stale because its claim changed, supporting context changed,
or a cited source changed. The engine already detects those categories, but a
caller could not ask which dependency caused the result. A UI showing only
“context changed” leaves the writer to inspect every linked paragraph again.

This milestone adds `Receipt.dependencyChanges()`: an immutable set of
`DependencyChange(reason, kind, id)` records. It enables a caller to point to the
specific affected block or source. Existing `reasons()` and `requiresReview()`
behavior stays the same. No model call is needed to explain these mechanical
changes. The new details are not yet exposed by the hosted app.

## Research and architectural decisions

| Primary source | Finding | Application to this engine |
| --- | --- | --- |
| [Acar, Blume, Donham: A Consistent Semantics of Self-Adjusting Computation](https://arxiv.org/abs/1106.0478) | Change propagation and memoization can reuse computations while maintaining consistency under the paper's formal semantics. | Treat completed reviews as artifacts with explicit dependencies. Preserve an unaffected review, but never infer that an LLM result is a pure deterministic computation. The paper's proof does not prove this engine correct. |
| [W3C Web Annotation: text selectors and states](https://www.w3.org/TR/annotation-model/#text-position-selector) | Position selectors are brittle under edits; the model recommends identifying the resource state as well. | Retain stable block IDs, reviewed revision, exact offsets, and exact quote. Do not silently relocate an invalidated review using similar text. Our offsets are UTF-16, not a claim of W3C normalization compliance. |
| [W3C PROV-DM](https://www.w3.org/TR/prov-dm/) | Entities, activities, derivation, revision, and invalidation can be represented separately. | Distinguish the reviewed snapshot, the act of reviewing, and later invalidation. A receipt shows that an activity occurred, not that its judgment was correct. This API does not implement the complete PROV standard. |
| [Gao et al.: Enabling Large Language Models to Generate Text with Citations](https://aclanthology.org/2023.emnlp-main.398/) | ALCE evaluates generated answers and citations, including citation quality, rather than equating a citation's presence with reliable support. | Evaluate semantic support separately from exact anchoring and lifecycle correctness. Passing this milestone's tests says nothing about model judgment accuracy. |

A dependency graph is useful only when its edges describe the actual review
input. If an agent reads the entire document, recording just its final cited
sentence is not a complete read set. Until the integration supplies a complete
context contract, keep rejecting any input change during an in-flight review.
Do not relax that guard merely to reduce reruns.

## Contract

For a receipt `r` and a committed snapshot `s`:

```
changes(reconcile(r, s)) = changes(r) union detectedChanges(original(r), s)
reasons(r) = the distinct reasons in changes(r)
requiresReview(r) = changes(r) is nonempty
```

Each observation contains a reason, a dependency kind (`BLOCK` or `SOURCE`), and
an application-assigned ID. Block and source IDs are separate namespaces, so a
source and paragraph with the same ID remain distinguishable. Repeated evidence
anchors in one source do not create duplicate observations.

Observations are historical and sticky. If context changed and was later undone,
its ID remains present. Deleted dependencies remain identifiable even though
there is no current passage to navigate to. The details are not a current diff,
an event log, or proof that the current text differs. There are no timestamps,
change counts, copied excerpts, or automatically generated explanations.

A fresh explicit review starts its own history. An older receipt is immutable;
reconciliation does not modify it. Applications must retain the returned receipt
after each committed edit. Skipping an intervening edit loses that observation.

The details do not expand the dependency contract: an unrelated block or source
still does not invalidate a completed review, while any change in a referenced
source does. Reordering alone retains existing behavior. If order or surrounding
unlisted text affects meaning, callers need broader invalidation. Dependency IDs
are not guaranteed harmless: use opaque IDs and avoid logging them with private
workspace data without appropriate access controls.

## Evaluation completed

`sh test.sh` passes all ten offline test programs. The evidence tests now include
128 deterministic combinations of claim, context, unrelated-block, source-version,
and deletion changes. Expected dependency identities are independently specified
for each case. Every case also reverts to the original snapshot and checks that
invalidation details survive.

Additional checks cover accumulating distinct causes across revisions, immutable
prior receipts and returned sets, repeated reconciliation, duplicate evidence,
ambiguous claim and source quotes sharing one ID, and compatibility of the old
reason projection. Existing asynchronous stale-response, source-content mutation,
blank-draft, and stable-ID tests continue to pass. No credentials, external model
calls, or real customer material are used.

## Next research gates, not implemented here

1. Define a provider-independent immutable review input: exact claim, context read
   set, source versions, prompt/model version, and explicit limitations. Verify
   that the adapter cannot expose undeclared context to the reviewer.
2. Build a small human-labeled support benchmark before adding reviewer agents:
   supported, overclaimed, contradictory, and insufficient-evidence cases, with
   difficult negatives where vocabulary overlaps but the citation does not support
   the claim. Measure useful finding precision, missed consequential problems,
   abstention, latency, and cost. Compare one reviewer with any proposed ensemble
   on the same held-out cases; more agents are not automatically better.
3. Only after those contracts, investigate an inverse dependency index for larger
   workloads. Compare incremental invalidation against a full recomputation oracle
   over edit traces. Current limits (100 blocks and 10 sources) do not justify an
   unmeasured performance rewrite.

The immediate value is inspectable re-review: explain the mechanical cause
without asking a model to invent a rationale. Semantic judgment, persistence,
author approval, and tenant isolation remain distinct responsibilities.
