# 🪔 diya lamp

**Write a decision you can defend.**

AI that helps you test your argument and sharpen your writing. In Notion. With evidence, explicit assumptions, and edits you approve.

An open-source Java application for defensible writing: identify claims, inspect evidence and assumptions, consider objections, and revise with author approval. Inspired by the connection between clear writing and well-developed ideas. A review supports judgment; it does not certify truth.

## Current status

Early project scaffold, not a connected Notion product. The executable currently prepares a review packet from a local text draft. It makes no network requests and performs no AI review. Notion integration and hosted-provider adapters are planned.

## Run the starter

Requires JDK 21 or newer. No third-party runtime dependencies.

```sh
sh build.sh
java -jar build/diya-lamp.jar --help
java -jar build/diya-lamp.jar prepare examples/draft.txt
```

## First connected milestone

1. Read a user-authorized Notion draft and record its revision.
2. Use a hosted AI provider to return structured claims, evidence gaps, objections, and suggested edits.
3. Publish a separate review page in Notion. Never overwrite the source automatically.
4. Let the author provide evidence and approve individual changes; reject writes against stale revisions.

## Credentials and privacy

Provider API usage is billed separately from chat subscriptions. Do not put keys in Notion pages, source code, or command-line arguments. The planned setup will use a hidden credential prompt and the operating system credential store. Notion authorization is separate. No credentials are collected by the starter.

Only submit selected documents to an AI provider with the user's authorization. Treat document contents as untrusted input, retain source attribution, and never invent supporting evidence.

## Design

Core Java CLI → review workflow → provider adapter / Notion adapter. Version-aware review records and approval handling remain application responsibilities. Prompts are components, not the whole product.

[Website](https://diyalamp.github.io/) · [Writing inspiration: Paul Graham](https://paulgraham.com/goodwriting.html)
