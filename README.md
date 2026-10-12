# 🪔 diyalamp

**Find your flow. Write a better essay.**

The open-source Java review engine behind diyalamp’s AI-assisted essay-writing direction. Inspect the prompts, phase contracts, evidence gates, and author-approval rules. The hosted writing app, accounts, billing, and customer data are separate private services.

The current engine and CLI were built around defensible recommendations and argument review. Broader essay feedback on clarity, structure, and voice is the product direction, not a completed capability. Notion is an optional existing connector, not a requirement of the future writing app. AI feedback supports the author’s judgment; it does not certify truth or guarantee improvement.

## What works today

This first CLI release imports a selected Notion page, runs one review phase at a time with Codex, pauses for author context, persists sessions, checks the source before approval, exports a readable report, and publishes an approved review as a separate Notion page. A guided terminal menu brings these actions together. It never automatically overwrites the source draft.

The full path is tested with offline Notion and AI fixtures. Live account validation has not been performed. This is an unsigned CLI release, not a signed desktop application or a Notion-native plugin. Public Notion OAuth is future work. Codex is the only AI reviewer.

## Quick start: fictional demo

Install **JDK 21 or newer**, and ensure `java` and `javac` are on your path, or set `JAVA_HOME` and add its `bin` directory to your path. The build uses `curl`, `shasum`, and `zip`, available on macOS and in the CI images.

```sh
sh build.sh
sh run.sh --help
sh run.sh start examples/onboarding/draft.txt .diyalamp/onboarding.review.properties
sh run.sh guide .diyalamp/onboarding.review.properties
```

Install a current [Codex CLI](https://learn.chatgpt.com/docs/developer-commands?surface=cli) and sign in once:

```sh
codex login
codex login status
```

The application reuses Codex's saved sign-in and never reads or stores its credentials. ChatGPT sign-in uses your eligible plan's Codex access and limits; API-key sign-in follows API billing. No separate AI key is requested by diyalamp. Review and guide commands use Codex's default model, or accept an optional Codex model ID as the final argument.

In the guided menu:

1. Choose `review`. Read the transmission notice, then type `SEND` to authorize that phase's request.
2. Inspect the result. If evidence is missing, choose `respond`, enter your context, and type `.end` on its own line. `.cancel` discards the entry.
3. Choose `review` again to re-examine the same phase. Answering a question does not automatically pass it. Re-review receives the outstanding questions alongside your accumulated context, including after a restart.
4. Continue through all five phases. Choose `approve` only after you have inspected the outputs.
5. Choose `export` for a local report. A Notion-imported session can also use `publish`.

Notion tokens are reused in memory while the guided menu is open; quitting clears that cache. Choose `keys` to replace a Notion token. Codex manages its own sign-in separately. Every AI request still requires consent; no AI key is entered into diyalamp.

The [fictional walkthrough](examples/onboarding/README.md) explains the intended learning points. Start with its draft alone, then supply relevant parts of the separate evidence packet when asked. Model outputs vary; the walkthrough is not an expected-output script.

## Connect your Notion draft

Create a Notion integration with **read content** and **insert content** capabilities. Grant it access to the draft page and your chosen destination parent page. Use a text-focused draft page; linked child pages and databases are not imported.

```sh
sh run.sh notion-read <draft-page-id> .diyalamp/notion-draft
sh run.sh guide .diyalamp/notion-draft/session.review.properties
```

Supply a Notion page ID, not its full URL. The importer requests the token in a hidden terminal prompt and creates a new private local directory. It reads paragraphs, headings, lists, checkboxes, toggles, quotes, callouts, code, and table rows, follows pagination and nested blocks, and records block references. Unsupported media blocks produce an error instead of being silently omitted. The page revision is checked before and after the import.

The source is a local snapshot. Approval of an imported session re-reads the live Notion page and checks both revision and text fingerprint. If you edit the source, import a new session and repeat the review. Proposed revisions appear in the phase artifacts; the app does not apply them to the source. Ready means the author approved the reviewed source and its review, not that a suggested rewrite was automatically installed.

## Publish the review

After approval, choose `publish` in the guided menu and enter a destination parent page ID, or run:

```sh
sh run.sh notion-publish .diyalamp/notion-draft/session.review.properties <parent-page-id>
```

Type `PUBLISH` to confirm the destination. The app checks the original Notion page again, then creates one separate review page containing the reviewed source, all five phase outputs, supplied context, status, and source fingerprint. It makes no edits to the source blocks. Reports too large for one bounded create request must be exported locally.

A local receipt is saved before the write. Repeating a publication command is blocked, even if the request timed out. A timeout does not prove the page was not created.

```sh
sh run.sh publication-status <session>
sh run.sh publication-resolve <session> <created-review-page-id>
```

For a pending receipt, look in the destination for the page whose title includes the receipt's reference. `publication-resolve` verifies the page's parent and title, then records its ID without creating another page. If you cannot establish what happened, keep the pending receipt and investigate before retrying. Do not casually remove it. Source checking and page creation are separate API requests, so a source edit between them remains possible; the report preserves the exact reviewed fingerprint.

## Five phases

| Phase | Purpose | Method behind it |
| --- | --- | --- |
| Map the argument | Separate claims, evidence, assumptions, and limits | Toulmin's argument model |
| Stress-test the proposal | Explore objections, alternatives, and failure causes | Klein's premortem |
| Question assumptions | Examine certainty, framing, and missing context | Kahneman and Tversky's bias research |
| Frame the decision | Define alternatives, reversibility, measures, and review conditions | Decision framing |
| Structure the draft | Lead with the recommendation and group support | Minto's Pyramid Principle |

These sources inform our workflow; the combined product is not a scientifically validated truth test. An empty question list is the model's judgment, not proof that all relevant facts are known.

## Individual commands

| Command | Behavior |
| --- | --- |
| `start <draft.txt> <new-session>` | Create a local review snapshot |
| `status <session>` | Inspect state, results, questions, and next phase |
| `guide <session> [codex-model-id]` | Open the interactive menu |
| `review <session> [codex-model-id]` | Request and validate one hosted review |
| `record <session> <result.txt> [questions.txt]` | Supply a manual result; one question per line |
| `respond <session> <context.txt>` | Supply author context without skipping a phase |
| `approve <session>` | Approve the unchanged source after all phases |
| `export <session> <new-report.md>` | Export results without editing the source |
| `notion-read <page-id> <new-directory>` | Import a Notion draft and source metadata |
| `notion-publish <session> <parent-page-id>` | Publish an approved, source-checked review |
| `publication-status <session>` | Inspect the local write receipt |
| `publication-resolve <session> <page-id>` | Reconcile an existing review page |

## Credentials, privacy, and failure behavior

- Never put secrets in Notion pages, source code, examples, command arguments, logs, or Git. The app collects them only in hidden terminal prompts.
- Runtime sessions contain private text and context. Keep them in `.diyalamp/`, which is ignored by Git. Export reports there too. Local source files outside that directory remain your responsibility.
- The app uses owner-only permissions where supported. Session snapshots are saved atomically, and a process lock prevents concurrent commands from overwriting one session. It is not an encrypted vault.
- An AI request sends the selected draft, completed reviews, and author context through Codex. Runs use a private temporary working directory, read-only sandboxing, ephemeral sessions, and disabled shell, browser, app, and plugin features. User configuration is not loaded, but saved Codex authentication is reused. The app never automatically applies source edits. Document text is treated as untrusted input, but prompt-injection resistance and factual accuracy are not guaranteed.
- Each Codex run has a five-minute application deadline, a structured output schema, and application-side result size limits. There is no fixed output-token or dollar cap. Failed, interrupted, malformed, or wrong-phase responses do not advance the phase. Failed or discarded runs can still consume Codex usage. Draft input travels through stdin rather than process arguments; temporary schema/result files are removed afterward. Ephemeral mode avoids saving Codex session files, but is not a promise of zero provider-side retention.
- Network requests do not automatically retry. Notion rate-limit errors show a wait interval when provided. A failed publication keeps its receipt for reconciliation. Cancellation before a request leaves the session unchanged; quitting the terminal interrupts ongoing work, but remote work may already have happened.

## Build, verify, and package

```sh
sh test.sh
sh package.sh
sh smoke-test.sh
```

The build downloads Gson 2.14.0 with a pinned SHA-256. Tests cover phase order, evidence gates, persistence, stale-source approval, malformed AI responses, pagination, source attribution, publication receipts, locks, and the entire connected path using offline fixtures. CI runs on Linux and macOS with Java 21.

`package.sh` creates `build/release/diyalamp.zip`, containing the JAR, its dependency, scripts, example, documentation, and dependency license. Unzip it anywhere and use `sh run.sh`. On macOS, `diyalamp.command` opens the guide for an existing session. It is unsigned and still requires Java; a signed application with a bundled runtime is future work.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| No terminal / private credential prompt unavailable | Run from Terminal or another terminal app, not a piped command |
| Java is unavailable or too old | Install JDK 21+ and set `JAVA_HOME` / your command path |
| Notion access failure | Share both pages with the integration and enable read/insert capabilities |
| Source changed | Import or start a new session; completed results belong to the original snapshot |
| Missing evidence | Supply context, then re-review the current phase |
| Model/access/billing error | Run `codex login status`, check Codex usage and the optional model ID; use `codex login` if needed |
| Malformed or truncated review | Session remains unchanged; a deliberate retry consumes more Codex usage |
| Pending publication | Inspect the destination and use `publication-resolve`, never blindly repeat the write |
| Session busy | Close the other command; an abandoned lock file itself does not keep an OS lock |

## Architecture and next work

Core Java CLI → five-phase workflow → Codex reviewer / Notion connector → review report. State and approval rules belong to the application, not the prompt. Sessions use a versioned local format; incompatible or damaged snapshots are rejected.

See [the implementation plan](docs/PLAN.md) for completed release scope, live validation, and later work. Notion OS credential storage, public OAuth, per-edit application, larger publication batches, and signed desktop distribution are not included in this release.

[Website](https://diyalamp.github.io/) · [Codex non-interactive execution](https://learn.chatgpt.com/docs/non-interactive-mode) · [Notion page API](https://developers.notion.com/reference/post-page) · [Notion block API](https://developers.notion.com/reference/get-block-children) · [Dependency notices](THIRD_PARTY_NOTICES.md)

## Document-centered agent contract

`DocumentReviewAgent` is a separate structured review interface for the proposed browser experience. It returns up to three exact passage anchors, consequential findings, optional replacement wording, evidence labels, an alternative, and limitations. Its runner can reuse the existing Codex transport or a server-held provider adapter. It does not mutate, approve, publish, or certify a document.

Offsets are zero-based UTF-16 (start inclusive, end exclusive), matching Java and JavaScript strings. The exact quote, valid Unicode boundaries, source IDs, limits, strict field types, and SHA-256 draft fingerprint are validated before returning a result. `requireCurrent` rejects a result after any draft change. Supplied excerpts are author-provided material, not independently verified evidence; URLs are not fetched. A client still needs to validate editor-position mapping and separately require author approval before applying any proposal.

The interface and adversarial fixtures pass offline tests. It is not yet connected to browser review, hosted tenant admission, or live model validation. No new paid calls are made by the tests.

### Revision-aware evidence review receipts

`com.diyalamp.ai.EvidenceReview` is an offline lifecycle primitive for exact claim
and source anchors. It records that a review occurred, **not** that a claim is
true, supported, safe to publish, or approved by its author.

1. Build a `Draft` with an application-assigned revision and stable editor block
   IDs, plus `Source` records containing immutable revisions and supplied excerpts.
2. Call `begin` with an exact UTF-16 claim anchor, source passage anchors, and all
   block IDs whose context the review depends on. Keep this request with the job.
3. On completion call `Request.complete(currentDraft, currentSources)`. Any input
   change while the reviewer ran rejects the response, including unrelated edits.
4. After subsequent committed edits call `Receipt.reconcile` and persist the
   returned receipt. Unchanged claim/context blocks retain the receipt even if an
   unrelated block changes. Any edit inside a dependency block, source revision
   or excerpt change, deletion, or duplicate matching quote requires review again.
5. Invalidation is sticky, including after a text revert. Only a newly completed
   review can replace it. Never discard the returned invalidated receipt.

Duplicate quotes are handled conservatively even with exact offsets. Stable IDs
are supplied by the editor, not inferred by searching text. Reordering blocks
alone preserves checks; callers must invalidate or include additional context
when order affects meaning. The caller is responsible for complete dependencies:
this primitive cannot discover semantic dependencies or detect a new contradictory
claim elsewhere in a document. Within a referenced source, any change invalidates
its checks, even if the quoted sentence remains identical. Additional unrelated
sources do not invalidate an already completed receipt.

Receipts expose the original draft revision, claim and source anchors, source
versions, context IDs, and explicit invalidation reasons. This milestone does not
add persistence, HTTP endpoints, live model calls, semantic source assessment,
or automatic editor mutation. `DocumentReviewAgent` remains a separate proposal
contract; its findings must be deliberately mapped to stable blocks and source
anchors by an integration, never treated as approval.

`Receipt.dependencyChanges()` additionally identifies the block or source behind
each invalidation reason. These immutable details accumulate across edits and
undo, so they are historical observations rather than a current diff. Removed
IDs remain in the record, and block/source namespaces are distinct. Callers can
use them to explain why a check reopened without another model call. The hosted
interface does not yet display these details. See the [research and evaluation
note](docs/REVIEW_RESEARCH.md) for primary sources, tested invariants, and limits.

### Exact source passages in AI proposals

`DocumentReviewAgent.reviewGrounded(snapshot, suppliedEvidence)` is an opt-in
extension of `review`. Each source-backed finding must identify an exact excerpt
passage with `source_id`, UTF-16 `start`/`end`, and verbatim `quote`. The engine
rejects invented text, unknown or unmatched source IDs, invalid boundaries,
duplicate passages, and missing passages for cited sources. Other finding kinds
must return an empty passage list. The original `review`, `Finding`, and `Result`
contracts remain available unchanged.

The returned `GroundedResult.review()` exposes the existing proposal result;
`passagesByFinding()` provides immutable source anchors keyed by finding ID. Call
`requireCurrent(currentDraft, currentEvidence)` before showing a result as current.
It rejects changes to **any** supplied excerpt, including uncited context, plus
added or removed sources. Source list ordering alone is irrelevant. An application
must additionally compare immutable source revisions and job versions to detect
an edit followed by an undo while the job was running.

An exact quotation proves only that the text occurs at that location in the
supplied excerpt. It does not prove relevance, logical support, source authenticity,
or completeness. The method never fetches sources, edits the draft, approves a
claim, or automatically retries a failed response. These are offline contract
tests; model quality and the hosted integration remain to be evaluated. See the
[grounded review research note](docs/GROUNDED_REVIEW.md).

All review output is parsed with strict JSON syntax, unique decoded object keys,
valid Unicode, and bounded nesting before field validation. Malformed output is
rejected without exposing raw model text in the validation error or advancing the
review. This hardening does not establish factual accuracy or complete prompt
injection resistance.

### Offline semantic-support evaluation

The [evaluation starter](examples/evaluation/README.md) includes 12 fictional
claim/source cases with provisional support, contradiction, and insufficient-context
labels. Its offline Java scorer keeps semantic-label accuracy separate from exact
quotation validity, counts missing and abstained outputs, and reports per-case
confusion and false alarms. The paired baseline/grounded protocol requires label
adjudication and controlled runs before making model-quality claims. No live
results are included, and the scorer makes no model or network calls.

Hosted callers can use `DocumentReviewAgent.prepareGrounded(snapshot, evidence)`
before reserving review allowance, then pass the immutable `PreparedReview` to
`agent.reviewGrounded(prepared)`. This validates the full escaped prompt without a
provider call and executes that same input later. Keep it private; freshness,
attempt ownership, idempotency, and payment authorization remain the caller's job.
