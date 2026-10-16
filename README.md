# 🪔 diyalamp

One task. Many agents. No crossed wires.

The new implementation is a local TypeScript browser workspace. Three
responsibility-based workers share a task, acquire browser tabs and resources,
and hand off completed work. You inspect and approve each browser action.

## Run the working slice

[Start here: the TypeScript workspace](workspace/README.md).
The [local verification record](workspace/verification/local-slice.json) describes
the tested scope.

It runs a real Playwright browser with three isolated **fixture tabs**. The
console shows actual screenshots, proposals, ownership, dependencies and durable
action history. A process-crash test verifies that recovery checks a recorded
browser effect instead of blindly repeating it.

The workers are deterministic. No model is called. This is a working coordination
slice, not an autonomous agent that can operate arbitrary websites. No real
Shopify, Printify, domain account, sign-in, payment or hosted service is connected.

Console, coordinator and workers are TypeScript. Local state uses SQLite for this
slice; a CockroachDB adapter and cross-machine coordination remain future work.
The public marketing site stays separate at https://diyalamp.com/.

## Earlier experiments

The Java browser-loop prototype, SQL task coordinator and writing-review CLI are
preserved; they are not the new workspace backend.

- [Java browser prototype](docs/JAVA_BROWSER_PROTOTYPE.md)
- [Task coordination API](docs/COORDINATION_CORE.md)
- [Earlier writing-review CLI](docs/LEGACY_REVIEW_CLI.md)

## License

[Apache 2.0](LICENSE). [Dependency notices](THIRD_PARTY_NOTICES.md).
