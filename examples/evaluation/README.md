# Semantic-support evaluation starter

This is an offline evaluation instrument, not a published model benchmark result.
The 12 cases are newly authored fictional examples. Their provisional labels were
written during development and are not independently adjudicated human ground
truth. No customer documents or external copyrighted passages are included. No
model is called by the scorer.

## What the labels mean

Assess the **claim relative to the entire supplied source set**, not outside
knowledge or whether the source itself is trustworthy:

- `SUPPORTS`: the supplied evidence warrants the claim at its stated scope,
  population, timeframe, units, and uncertainty.
- `CONTRADICTS`: the supplied evidence directly conflicts with the claim.
- `INSUFFICIENT_CONTEXT`: the evidence neither establishes the claim nor clearly
  contradicts it. Unresolved conflicting sources also use this label.
- `ABSTAIN`: a prediction only. The reviewer declines or does not express a
  classifiable assessment. This is different from recognizing insufficient
  evidence, which is a substantive correct answer for some cases.

A claim describing uncertainty can be supported. A source saying something is
unknown does not necessarily contradict a positive claim. A matching sentence in
one source does not settle an unresolved conflict with another source.

Each case has a rationale and a phenomenon tag. The set balances four examples
per gold label across causality, quantity, units, time, scope, qualification,
conflict, and negation. Six are marked `development` and six `held_out` for a
local experiment. These files are public: “held_out” is a protocol role, **not**
a claim that the cases are absent from model training or genuinely unseen by
future developers. Do not advertise generalization from this small set.

## Paired baseline versus grounded protocol

1. Have an independent reviewer inspect and, where necessary, correct the labels
   and rationales before a model comparison. Record disagreements. Keep ambiguous
   cases visible rather than deleting failures after seeing model responses.
2. Freeze the corpus version, provider/model version, prompt, generation settings,
   output budget, and run order. Use the same claim and complete source excerpts
   in both arms. Never send `expected`, `rationale`, or the split to the model.
3. Run a controlled support-classification task in both arms. Both return the same
   label. The baseline cites source IDs only; the grounded arm additionally gives
   exact source ID, UTF-16 offsets, and quote. The sole intended intervention is
   passage attribution, not a larger context or a different model. This is an
   evaluation task; the production `DocumentReviewAgent` result does not expose a
   categorical support label, and this change does not alter that API.
4. Alternatively evaluate production review outputs through blind human annotation
   into this prediction format. Record that different protocol explicitly. Do not
   infer `SUPPORTS` merely because a reviewer returned no findings. Ambiguous or
   absent classifications become `ABSTAIN`; a failed/missing run remains missing.
5. Preserve raw responses privately. For the baseline, use an empty `citations`
   list in scorer input rather than inventing quotations it never returned. For
   grounded output, preserve incorrect quotes and offsets: the scorer must count
   them as invalid. Do not repair failures or silently retry only one arm.
6. Record latency, actual usage/cost, provider failures and rejected responses
   separately. The scorer does not estimate these. Use a predeclared equal retry
   policy and count every attempt. Repeat a fixed number of paired runs if paying
   for an experiment; report variation rather than choosing the best generation.
7. Report per-case outcomes, unconditional accuracy, coverage, confusion, macro F1,
   false alarms and missed problems. Citation metrics are a separate dimension.
   Inspect whether quoted context omits a limitation, and have a blinded reviewer
   judge relevance; exact location alone cannot measure that.

The grounded arm is not assumed better. Adopt it for semantic-quality reasons
only if the comparison supports that conclusion at an acceptable failure rate,
latency and cost. This tiny corpus can reveal regressions and illustrate errors;
it cannot establish superiority, a commercial moat, or broad model accuracy.

## Prediction file and scoring

Create an array of predictions with exactly these fields. This is only a format
example, not a measured model response:

```json
[
  {"id":"matching-count","label":"SUPPORTS","citations":[
    {"source_id":"roster","start":0,"end":50,"quote":"The final pilot roster contains 24 distinct teams."}
  ]}
]
```

A missing case stays in the total denominator. Duplicate or unknown case IDs fail
rather than selecting one silently. `ABSTAIN` cannot contain citations. Duplicate
identical citations are rejected. Malformed syntax, field types or extra fields
also fail. An invented quote, unknown citation source, or incorrect offset is a
**scored invalid citation**, not a parser failure. Offsets count UTF-16 code units,
matching Java and JavaScript; source excerpts are not trimmed or normalized.

Build first, then keep runs under ignored `.diyalamp/`:

```sh
sh build.sh
java -cp build/classes:build/deps/gson-2.14.0.jar \
  com.diyalamp.ai.SemanticSupportEvaluation \
  examples/evaluation/support-cases.json .diyalamp/evaluation-predictions.json \
  > .diyalamp/evaluation-report.json
```

The scorer requires a nonempty case list and scores exactly the cases supplied.
For a split, prepare a case file containing only that split and a matching
prediction file. Do not compare scores from different case sets without stating
that difference. Files are bounded to 250 KB and malformed UTF-8 is rejected.

## Metric interpretation

- `accuracy_all_cases`: correct labels / all cases, including missing and abstained.
- `accuracy_answered`: correct / non-abstained answers; null when none. Always show
  coverage alongside it to prevent selective answering from looking perfect.
- `macro_f1_present_gold_labels`: mean F1 across labels present in the gold case
  set. Missing and abstained predictions count against recall. Confusion matrices
  retain both categories. Undefined precision/recall is null, not fabricated.
- `unsupported_claims_marked_supported`: contradiction/insufficient cases labeled
  supported. `supported_claims_flagged` counts supported cases labeled problematic.
  These are corpus-specific counts, not estimates of production safety.
- `exact_citation_fraction`: exact nonblank quote/offset matches / submitted
  citations. Null when no citations were provided. It says nothing about relevance,
  coverage of the reasoning, completeness, or factual authenticity.
- `citation_coverage`: cases with at least one submitted citation / all cases.
  This can be high even when citations are wrong. Inspect exactness separately.

Tests use fabricated oracle and always-supported predictions **only to verify
scoring arithmetic**. An always-supported predictor quoting each source verbatim
gets perfect exact-quotation validity but only one-third label accuracy on this
balanced corpus. That is a counterexample to conflating citation presence with
reasoning quality, not a reported result from any real model.

## Research context

The separation of citation quality from answer quality follows the evaluation
concern discussed in [ALCE (Gao et al., EMNLP 2023)](https://aclanthology.org/2023.emnlp-main.398/).
The passage-attribution intervention is motivated by [fine-grained grounded
citations (Huang et al., ACL Findings 2024)](https://aclanthology.org/2024.findings-acl.838/).
This starter is neither their benchmark nor a reproduction of their experiments.
