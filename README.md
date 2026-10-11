# 🪔 diyalamp

**Defensible writing with AI in Notion.**

AI that helps you test your argument and sharpen your writing. In Notion. With evidence, explicit assumptions, and edits you approve.

An open-source Java application for defensible writing: identify claims, inspect evidence and assumptions, consider objections, and revise with author approval. Inspired by the connection between clear writing and well-developed ideas. A review supports judgment; it does not certify truth.

## Current status

Working local five-phase workflow, not a connected Notion product yet. Start a review from a rough draft, record phase results, answer evidence requests, and explicitly approve the unchanged source. Sessions survive restarts. Phase results are supplied manually in this milestone; the app performs no AI review or network requests. Notion integration and hosted-provider adapters are next.

## Run the workflow

Requires JDK 21 or newer. No third-party runtime dependencies.

```sh
sh build.sh
java -jar build/diya-lamp.jar --help
java -jar build/diya-lamp.jar start examples/draft.txt .diyalamp/draft.review.properties
java -jar build/diya-lamp.jar status .diyalamp/draft.review.properties
```

Use `record <session> <result.txt> [questions.txt]` for the next phase. Questions are one per line. A phase with questions blocks until `respond <session> <context.txt>`; then record a new review of that same phase. After five results without outstanding questions, use `approve <session>`. Approval re-reads the original draft and rejects changed text. This marks the reviewed source Ready; it does not apply edits.

Sessions contain private draft text and author context. Keep them in the ignored `.diyalamp/` directory. They are saved atomically with owner-only file permissions on macOS/Linux. Run `sh test.sh` to verify workflow and persistence guarantees.

## Five review phases

A rough draft moves through argument mapping, stress-testing, assumption checking, decision framing, and final structure. Missing evidence returns to the author; completing the phases still requires author approval before Ready. See [the implementation plan](docs/PLAN.md).

## First connected milestone

1. Read a user-authorized Notion draft and record its revision.
2. Use a hosted AI provider to return structured claims, evidence gaps, objections, and suggested edits.
3. Publish a separate review page in Notion. Never overwrite the source automatically.
4. Let the author provide evidence and approve individual changes; reject writes against stale revisions.

## Credentials and privacy

Provider API usage is billed separately from chat subscriptions. Do not put keys in Notion pages, source code, or command-line arguments. The planned setup will use a hidden credential prompt and the operating system credential store. Notion authorization is separate. No credentials are collected by the local workflow.

Only submit selected documents to an AI provider with the user's authorization. Treat document contents as untrusted input, retain source attribution, and never invent supporting evidence.

## Design

Core Java CLI → review workflow → provider adapter / Notion adapter. Version-aware review records and approval handling remain application responsibilities. Prompts are components, not the whole product.

[Website](https://diyalamp.github.io/) · [Writing inspiration: Paul Graham](https://paulgraham.com/goodwriting.html)
