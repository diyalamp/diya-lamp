# R&D milestone: exact source passages for review proposals

## Problem

The existing AI review contract precisely identifies the author's disputed text,
but points only to whole source IDs. That permits a structurally valid finding
whose explanation paraphrases or invents a passage from a real supplied source.
It also makes the reader locate the relevant text manually. Checking an ID against
a supplied list is necessary but insufficient for inspectable attribution.

This milestone adds an opt-in `reviewGrounded` path. It requires model-generated
source passages and validates them mechanically. Existing callers keep their
current `review` API and wire schema; they do not silently receive new fields.

## Research basis and limits

[Huang et al., Learning Fine-Grained Grounded Citations for Attributed Large
Language Models (ACL Findings 2024)](https://aclanthology.org/2024.findings-acl.838/)
identify the difficulty of locating supporting evidence when citations provide
only document identifiers. Their FRONT method uses fine-grained supporting quotes
in a training framework. This motivates passage-level attribution, but we have
not trained FRONT, replicated its results, or established that adding a schema
field gives the same improvements.

[Gao et al., Enabling Large Language Models to Generate Text with Citations
(EMNLP 2023)](https://aclanthology.org/2023.emnlp-main.398/) evaluate citation quality
alongside generated answers. Our engineering implication is to distinguish an
exactly located quote from whether that quote actually supports a judgment. The
current implementation verifies only the former. Semantic evaluation remains a
separate task and must not be replaced by a green “verified” citation badge.

The [W3C Web Annotation Data Model](https://www.w3.org/TR/annotation-model/#text-position-selector)
warns that position selectors are fragile when resources change and recommends
identifying their state. Our result retains the complete supplied source snapshot
and checks it before reuse. Offsets use Java/JavaScript UTF-16 units; this is not
an implementation of W3C text normalization or code-point offsets.

## Contract and integration

`reviewGrounded` makes one call through the existing bounded runner. No additional
agent, retrieval, or repair call is introduced. Alongside the existing finding
fields, the structured schema requires `source_passages`, containing at most ten:

```json
{"source_id":"survey","start":3,"end":32,"quote":"Three people mentioned price."}
```

This fictional passage appears after a two-unit emoji and a space. Each cited
source ID must have at least one passage. A passage from an uncited source is
rejected, even if its text is valid. Duplicate identical source anchors are
rejected. A non-source finding must carry an empty passage list. Empty findings
remain valid so the reviewer can abstain.

Validation checks exact nonempty text, valid Unicode, UTF-16 boundaries, integral
nonnegative offsets, bounds, known IDs, closed field sets, and existing response
size limits. It does not search for a near match, repair model offsets, relocate
citations, or trim text until it happens to match. Failed output produces no
validated result and never mutates the document.

The immutable returned `GroundedResult` contains the ordinary `Result` and a
per-finding map of immutable `SourcePassage` lists. Use the combined guard:

```java
var reviewed = agent.reviewGrounded(snapshot, suppliedEvidence);
reviewed.requireCurrent(currentDraft, currentEvidence);
var findings = reviewed.review().findings();
var citations = reviewed.passagesByFinding();
```

The source guard compares the complete supplied ID/text map, including uncited
sources: the reviewer saw all of them. Adding, deleting, or changing any source
rejects the result; merely reordering the list does not. Draft changes still
reject through the existing fingerprint guard. Duplicate IDs are rejected.

**Version caveat:** the existing `Evidence` input has no immutable revision field.
Changing an excerpt and restoring it before checking is invisible to this content
comparison. The hosted job must separately retain and compare source revisions,
draft revisions, and the job generation before accepting results. This API does
not make persistence, concurrency, or author-approval decisions.

The stored excerpts are private input and must be treated accordingly. Do not
log the result or citations indiscriminately. This contribution includes only
fictional test data and does not change retention policy.

## Offline verification

`GroundedDocumentReviewTest` checks valid citations, source-schema requirements,
exact Unicode offsets, immutable maps/lists, and one runner call. Rejection cases
cover invented quotes, unknown and mismatched source IDs, out-of-range, reversed,
negative, fractional and string offsets, partial surrogate pairs, blank/null
quotes, missing/unknown fields, missing/excess/duplicate passages, and inconsistent
finding kinds. Multi-source, no-finding, and non-source cases remain supported.

Freshness tests change the draft, a cited source, an uncited source, membership,
and ordering. A deliberately irrelevant but exact emoji citation is accepted to
make the mechanical/semantic boundary explicit. The legacy contract still rejects
unexpected fields and its existing tests continue to pass.

## Follow-on evaluation design

Before switching a paid application to this path, compare both contracts on the
same fictional, manually reviewed examples with frozen draft/source versions:

- Causal overclaim from a self-selected survey.
- A correct number attached to the wrong population or time period.
- A quotation whose next sentence reverses or limits its meaning.
- Conflicting sources and conclusions that should remain uncertain.
- A correctly supported claim for which the reviewer should not invent a problem.
- Instructions embedded in supplied excerpts that should remain untrusted data.

Record citation location validity, relevance and support judgments separately,
plus abstention, consequential false alarms, response rejection rate, latency,
and cost. Do not report accuracy from these synthetic contract tests. Human
labels and held-out cases are needed for a credible semantic comparison. More
review agents should be introduced only if they improve those measured outcomes
within an explicit cost allowance.
