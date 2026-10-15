# 🪔 Diya Lamp

An open-source browser agent. Java 21. Bring your own model. Run it yourself.

The browser clicks **Submit**. The connection drops before the response arrives.

Did it work? Should the agent click again?

Diya Lamp starts at that awkward boundary. A model proposes the next move. A
browser adapter carries it out. The runtime keeps track of what was approved,
what was attempted and what still needs checking.

## What's here

The browser-agent core is an early prototype, with two replaceable interfaces:

- **Model:** read the current observation; propose one action or stop.
- **Browser:** observe the page; execute a typed, observation-bound action.

The Java loop requires approval before each action, saves dispatch intent first,
rejects outdated observations and stops on uncertain outcomes. Local snapshots
preserve pending work and the step budget across restarts.

There is no working browser driver or model-provider integration yet. The tests
use a fake browser. This is the core being built, not a finished automation app.

## Run the failure cases

With JDK 21 or newer:

```sh
sh test.sh
```

The browser tests cover stale pages, mismatched approvals, competing dispatches,
lost responses, failed snapshot writes and reopening a run without repeating an
uncertain action. No model bill. No real account touched.

[Browser architecture and current limits](docs/BROWSER_AGENT.md)

## The boundaries

Browser only. No desktop control or shell execution.

Cloud-agnostic means the core doesn't depend on a cloud vendor. Model-agnostic
means providers sit behind an interface; it doesn't mean every model is supported.
Concrete adapters still have to be implemented and tested.

A successful browser command is not proof that the user's task succeeded. A
model saying “done” isn't proof either. The next slice has to demonstrate the
whole loop against a real browser fixture.

## Also in the repository

The experimental SQL task coordinator handles ownership, fenced leases,
checkpoints, versioned approvals and ordered history. It is available separately;
it isn't connected to browser run storage yet.

[Task coordination API and database tests](docs/COORDINATION_CORE.md)

Earlier writing-review APIs and the terminal CLI are preserved in the
[legacy documentation](docs/LEGACY_REVIEW_CLI.md).

## What's next

An isolated browser driver. One model adapter. One task that works end to end,
including the part where something goes wrong.

A managed cloud and supervision console are planned after the self-hosted runtime
works. Neither is available today.

## License

[Apache 2.0](LICENSE). [Dependency notices](THIRD_PARTY_NOTICES.md).
