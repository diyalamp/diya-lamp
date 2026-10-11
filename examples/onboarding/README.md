# Fictional onboarding decision demo

This example is deliberately imperfect. No figures refer to real customers or companies.

Start with `draft.txt` only. Keep `author-context.txt` separate until the reviewer requests evidence. Supply the relevant parts as author context; a response should trigger another review of the same phase, not automatically pass it. The model's questions and results will vary. Do not force the reviewer to match this guide.

## What each phase should explore

1. **Argument map:** Does low activation establish complicated onboarding as the cause? Separate observations from causal claims. Identify the unsupported 60% forecast.
2. **Stress test:** Consider an optional pilot, improvements to assisted onboarding, and bulk import. Imagine the wizard ships but activation stays low. Which explanations could account for that?
3. **Assumption check:** Inspect the acquisition mix, small interview sample, competitor comparison, engineering estimate, and assumed support savings. Ask for missing facts rather than inventing them.
4. **Decision frame:** Choose the goal, alternatives, pilot eligibility, success measures, guardrails, and a review date. Identify what is reversible and what would change the recommendation. Do not claim the pilot proves causality unless its design supports that claim.
5. **Final structure:** Lead with the revised recommendation. Group evidence, distinguish uncertainty, explain the tradeoff, and propose the next action. A defensible outcome might be a bounded pilot or prioritizing import; the author decides.

## Running the example

```sh
mkdir -p .diyalamp
java -jar build/diya-lamp.jar start examples/onboarding/draft.txt .diyalamp/onboarding.review.properties
java -jar build/diya-lamp.jar review .diyalamp/onboarding.review.properties <claude-model-id>
java -jar build/diya-lamp.jar status .diyalamp/onboarding.review.properties
# When the reviewer requests context:
java -jar build/diya-lamp.jar respond .diyalamp/onboarding.review.properties examples/onboarding/author-context.txt
java -jar build/diya-lamp.jar review .diyalamp/onboarding.review.properties <claude-model-id>
# Repeat review/respond as needed, then explicitly approve:
java -jar build/diya-lamp.jar approve .diyalamp/onboarding.review.properties
```

Each review requires consent and a hidden API key prompt; provider charges apply. The app records review outputs but does not rewrite the source. Do not edit `draft.txt` mid-session: a changed source needs a new review. Ready means the author approved the reviewed source, not that the software proved the proposal true or implemented its suggested revision.
