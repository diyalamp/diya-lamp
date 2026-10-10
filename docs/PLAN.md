# 🪔 diyalamp implementation plan

Defensible writing with AI in Notion. Core Java 21, a local CLI first, a simple desktop setup later.

## First usable slice

1. Build and packaging: repeatable compilation, JSON support, a runnable JAR, offline integration tests.
2. Notion connector: explicit page selection, paginated block reads, nested text, source block attribution, fail clearly on unsupported content, detect changes during a read.
3. Review engine: Claude Messages API first, explicit model configuration, claims/evidence/assumptions/objections/questions/revisions as structured output. Drafts are untrusted data, not instructions. Reject malformed or truncated output.
4. Local review artifact: source ID, content fingerprint, review results. Only the selected draft is sent to the provider; require an explicit CLI consent flag.
5. Publication: separate child review page, never automatic source edits. Re-fetch the source and reject stale reviews; require an explicit publish flag. Do not retry ambiguous writes automatically.
6. Setup documentation and release checks: keys through environment variables initially; no secrets in flags, Notion pages, examples, logs, or Git. Offline mock services verify the workflow; live validation needs the user's integration credentials and a selected test page.

Each verified step is a small commit pushed independently. The initial CLI is a development milestone, not the finished product.

## Following milestones

- Hidden setup prompts and OS credential-store integration; package for macOS with one launcher.
- Public Notion OAuth connection and a clear page permissions flow for other users.
- OpenAI adapter behind the same review interface; provider/model selection, bounded usage, timeouts, and sanitized errors.
- Notion-native review status and evidence responses, explicit proposed edits, per-edit author approval, revision history.
- Robust block coverage and clear limitations for tables, synced blocks, embeds, child pages, and large documents.
- Local persistence, job resume, deduplication, idempotent publication, cancellation, rate limits, and audit trail.
- Accessibility, onboarding with a sample page, signing/release automation, clean-install testing, security review.

## Correctness and privacy

No automatic source mutation. No claim that an AI review proves truth. Model suggestions must distinguish quoted evidence from inferred assumptions. No source text is sent without explicit selection and consent. No credentials are bundled; chat subscriptions and API billing are separate. Notion does not provide a transactional compare-and-create operation: pre-publication checks reduce stale publication but do not remove the race. Review pages retain a source fingerprint so that limitation is visible.

## Acceptance demo

Read one authorized draft → show claims and missing evidence → save the review locally → preview it → explicitly publish a child review page. Changing the draft invalidates publication. Missing credentials, denied permissions, provider failure, malformed review, and duplicate publication must fail clearly without leaking secrets or editing the original.

## References

- https://developers.notion.com/reference/get-block-children
- https://developers.notion.com/reference/post-page
- https://platform.claude.com/docs/en/api/messages/create
- https://github.com/google/gson
