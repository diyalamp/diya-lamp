# 🪔 diyalamp

**Defensible writing with AI in Notion.**

A defined, sequential workflow for turning a rough draft into an examined argument. AI guides five phases; you supply missing evidence and explicitly approve the result. Notion holds your draft and the published review. A review supports judgment; it does not certify truth.

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
