# Hosted Notion-first architecture

Status: approved product direction, not implemented or live validated.

## Product and repository

Keep the Java backend, setup website, Notion connector, payment integration, database migrations, tests, and deployment templates in the public diyalamp/diyalamp repository. Reuse the existing five-phase engine and approval gates. The CLI remains a development tool, not the customer's interface.

Open-source code is separate from operating data: never commit service credentials, OAuth tokens, customer drafts, payment details, production configuration values, or database exports. Stripe collects and stores card details; diyalamp stores customer/subscription identifiers and entitlement state, never card numbers or security codes.

## Customer journey

1. Create a diyalamp account and select a monthly plan with an explicit review allowance.
2. Complete Stripe-hosted Checkout, providing a card and paying the initial subscription invoice. No free trial or unpaid review access in the initial offering.
3. Confirm payment on the server, then connect Notion through a public OAuth connection and duplicate the writing template.
4. Write a rough draft and request the next phase through a native Notion button that changes an action property. Custom slash commands are not a promised feature.
5. Receive questions and review results in Notion, provide evidence there, and explicitly approve the reviewed version after all five phases.

A successful Checkout redirect, saved card, or connected Notion workspace alone does not authorize AI usage. A saved payment method does not guarantee future payments will succeed.

## Components

- Java web service: account sessions, onboarding, Notion OAuth callbacks, Stripe Checkout and billing portal, verified webhook endpoints, authorization.
- Durable SQL database: tenants, encrypted Notion credentials, subscriptions, paid entitlement periods, usage reservations, review sessions, jobs, webhook deduplication, publication receipts. Cockroach Cloud is a candidate; separate virtual clusters per review are not required.
- Background Java workers: detect explicit Notion review requests, run the existing five-phase engine with company-funded OpenAI API access, and publish results back. No reliance on customer Codex CLI sign-in.
- Hosted website: setup, subscription management, connection status, and recovery; everyday writing remains in Notion.
- AWS deployment: web service and workers, managed secret storage, database connectivity, monitoring. Exact services and costs remain to be selected.

## Mandatory billing gate

Every AI phase request requires authenticated tenant ownership, a valid Notion connection, confirmed paid subscription access for the current period, and available usage allowance. Enforce this in the backend when accepting the request and again immediately before a worker sends any content to AI. Never trust a Notion property or browser-provided subscription status as billing authority.

Verify Stripe webhook signatures, deduplicate events, and reconcile authoritative subscription/invoice state rather than assuming event arrival order. Grant access after confirmed initial payment; renew the paid access period after confirmed renewal payment. Block new AI work when payment fails, collection is paused, the subscription expires, or billing state is unknown. Cancellation at period end can retain already-paid access until that period expires. Preserve existing drafts and results when blocked, and show a billing recovery link in Notion.

Reserve usage atomically before AI calls, bind each request to a tenant and draft version, and prevent duplicate requests from consuming allowance twice. A failed or interrupted call can still incur provider cost: record attempts and define refund/retry rules explicitly. Reconcile uncertain provider results rather than blindly retrying. Work already sent to AI cannot be retroactively stopped by a later payment failure.

## Delivery slices and proof

1. Prove one real Notion button request produces one review and writes it back, using controlled test data.
2. Add public OAuth onboarding and template installation.
3. Add persistent tenant-scoped jobs, review sessions, and company-funded AI calls.
4. Add Stripe test-mode Checkout, signed webhook processing, paid access gates, usage limits, and recovery.
5. Test duplicate/out-of-order events, expired access, payment failures, worker restarts, source edits, and tenant isolation. Demonstrate that denied jobs make zero AI calls.
6. Deploy a test environment, validate the complete journey, then configure production accounts and pricing before accepting real payments.

## References

- Notion public OAuth and template installation: https://developers.notion.com/guides/get-started/authorization
- Notion database buttons: https://www.notion.com/help/database-buttons
- Stripe subscription webhooks and payment lifecycle: https://docs.stripe.com/billing/subscriptions/webhooks
- OpenAI Responses API: https://developers.openai.com/api/reference/responses/overview
