# 🪔 diyalamp implementation plan

Defensible writing with AI in Notion. Java 21, a local CLI first, a simple desktop setup later.

## Product workflow

The input is a rough draft, not a filled template. The five review phases are:

1. Argument map: claims, grounds, connecting assumptions, qualifications, and gaps.
2. Stress test: strongest objections, alternatives, and a premortem.
3. Assumption check: questions about certainty, framing, and missing context.
4. Decision frame: alternatives, reversibility, success measures, review dates, and conditions for changing course.
5. Final structure: recommendation first, grouped support, evidence, uncertainties, and proposed revision.

Each phase produces an inspectable artifact. Missing evidence pauses the workflow for author input, then the phase is reviewed again. Completing five phases yields Awaiting approval. Only the author can mark the draft Ready. Editing the source invalidates the completed review.

## Atomic implementation milestones

- Core workflow: typed phases and states, explicit evidence gates, source fingerprint, approval guard, offline tests.
- Local CLI: start a session, inspect the current phase, record a phase result, supply context, and approve. Persist review artifacts locally, outside tracked files. This milestone is a workflow engine, not an AI reviewer.
- Notion adapter: explicit page selection, pagination and nested text, block attribution, source-change detection, separate review page publication. No automatic source edits.
- Hosted AI adapter: structured results for each phase, configurable model, bounded output, malformed/refused-response handling. Explicit consent to transmit the selected draft; no automatic provider spending.
- Connected demo: rough draft → five reviewed artifacts with author responses → author approval → separate Notion review page. Verify offline first; live validation requires the user's credentials and chosen test page.
- Setup: hidden credential prompts and OS credential storage; Notion authorization independent of AI authorization. Package and sign a macOS launcher.

Push each verified milestone separately. Do not claim the connected product works before it has been demonstrated.

## Subsequent work

Public Notion OAuth, provider choice, retries for safe reads, rate limits, cancellation, job recovery, idempotent publication, per-edit approval, source version history, expanded block coverage, accessibility, release automation, clean-install checks, and security review.

## Correctness and privacy

Never bundle keys or put them in Notion, examples, command arguments, logs, or Git. Use fake credentials in tests. Runtime artifacts can contain private drafts and must stay outside version control. API billing is separate from chat subscriptions.

An AI review cannot prove truth or diagnose a writer's bias. Questions about evidence return to the author. Prompts and external documents are untrusted data. Notion's read-then-create check is not a transaction; disclose the race and retain source fingerprints.

## References

- https://developers.notion.com/reference/get-block-children
- https://developers.notion.com/reference/post-page
- https://platform.claude.com/docs/en/api/messages/create
