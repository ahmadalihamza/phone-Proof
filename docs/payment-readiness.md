# Payments and commercial readiness — 19 October 2026

**Easypaisa, JazzCash and card payments are not active.** No merchant adapter or credentials were supplied. The only implemented provider is a visibly labeled local development simulator. It moves no money, is disabled by default, is rejected by production configuration, and its controls are hidden in release Android builds.

## Implemented in this increment

- Checkout fixes the server's price, currency, plan name, quota and duration in a purchase snapshot. Editing/deactivating a plan does not alter a pending purchase or an idempotent retry. A client cannot submit a price or paid status. Financial terms cannot be rewritten through the ORM or raw SQL after insertion.
- Payment attempts track references, verification attempts and bounded error codes. The shared settlement service independently verifies transaction ID, provider reference, amount, currency and status before allocating an entitlement. Initiation timeouts retain the same transaction ID for an adapter's idempotent retry.
- The webhook orchestration requires an adapter-authenticated event, checks time/reference, detects event-ID reuse and deduplicates processed events. Uncertain/mismatched/pending verification returns a retryable 503 and does not unlock reports. No genuine merchant webhook authentication algorithm is implemented yet; unconfigured providers return 503 and development webhooks are rejected.
- The reconciliation command checks pending transactions fairly, with a separate database transaction per candidate. It can recover a verified settlement after a lost callback. It never interprets the absence of an adapter as payment success. Safe operation with a real provider still requires its integration tests, bounded timeouts and documented idempotency guarantees.
- New subscription cycles use purchased quota/duration, reset usage through a new cycle record, and link to their exact purchase. Scheduled plan changes apply to an explicit manual renewal after expiry. Cancellation preserves the current period and clears scheduled changes. There are no automatic recurring charges, grace periods or proration in this implementation.
- Refund requests are reviewable, approvable and rejectable. Provider-confirmed refunds are idempotent and suspend only the purchased subscription cycle. Previously issued reports and receipt snapshots remain available. Historical subscriptions without a purchase linkage require review before refund approval; unfulfilled reviewed payments can be refunded without suspending another cycle.
- Confirmed payments generate an immutable private receipt snapshot and downloadable PDF. Development receipts explicitly say **NOT REAL MONEY**. A receipt records the original settlement and does not assert current refund status or tax invoice compliance. Its timestamp is when the server confirmed settlement. Transaction details show subsequent refunds.
- Android Account loads billing, offers plan review, preserves checkout idempotency keys across transient retries, shows transaction details, shares receipts, requests refund review, redeems one-report promotions and manages cancellation, next plans and manual renewal. The development simulator includes paid and failed outcomes. Billing data is published to Compose on its main coroutine context after network operations finish.
- Administrator totals separate development simulations from real PKR payments. Expired periods are excluded from active counts. Attempt failures and fulfillment review states are visible to administrators.

## Run locally

From `backend`, migrate with `python -m alembic upgrade head`. To demonstrate simulated payments only, set `APP_ENV=development` and `DEVELOPMENT_PAYMENTS=true` in the local environment, then restart the API. Sign in with the debug app and open Account. Prices shown by seeded development plans are examples, not approved business pricing.

From `backend`, run one reconciliation batch with:

```text
python -m app.reconcile_payments --limit 100
```

For a supervised worker use `python -m app.reconcile_payments --watch --interval 60`. Compose provides an opt-in `payments` profile. It is supplied configuration; the worker/container has not been deployed here. A real adapter must support creation and refund idempotency before this worker can be enabled for its transactions.

## Live payment activation requirements

Public documentation was researched on 19 October. **Safepay hosted checkout is the first candidate for merchant evaluation**, based on its public API documentation and hosted flow; this is an engineering recommendation, not an approved merchant selection. Its [API reference](https://apidocs.getsafepay.com/) requires onboarding for production, and its [integration guide](https://safepay.helpscoutdocs.com/article/173-asp-net-integration-guide) covers hosted checkout and signed webhooks. These sources do not establish all creation/refund idempotency and independent status-query guarantees required by this application's adapter contract. Obtain the approved contract and sandbox account before implementing and activating it. Do not combine the hosted-checkout webhook protocol with a separate Raast/aggregator protocol.

[JazzCash's official sandbox documentation](https://sandbox.jazzcash.com.pk/SandboxDocumentation/v4.2/index.html) is another candidate; confirm the currently approved API/version and supported refund methods with the merchant account. Easypaisa provides a [merchant portal](https://merchantportal.easypaisa.com.pk/) and [integration-guide portal](https://easypay.easypaisa.com.pk/easypay-merchant/faces/pg/site/IntegrationGuides.jsf); merchant access and approved integration materials are still needed. No credentials or approved merchant configuration were found in the project. No provider endpoint or signature scheme has been guessed into production code.

1. Select the merchant-approved provider and obtain its official sandbox/production API documentation, hosted checkout or supported Android collection flow, and approved business prices.
2. Supply merchant credentials and verification keys through the server's secret manager. Do not place them in the APK, source repository, screenshots or chat.
3. Implement the adapter's real initiation, independent status query, webhook merchant/signature verification and confirmed refund operations. Map the provider's pending/late-settlement/expiry and retry semantics explicitly, including the orchestration's current five-minute unprocessed-event window.
4. Verify accepted/rejected payments, amount/reference mismatches, duplicate/stale callbacks, lost callbacks, request timeouts, retries, refunds and provider reconciliation using the approved sandbox. Test PostgreSQL concurrency, worker restart and stale/late settlements before enabling live collection.
5. Confirm legal merchant details and invoicing/tax requirements before issuing tax invoices, and complete a controlled real settlement/refund acceptance with the merchant account.

Credits/wallets, percentage/fixed discounts, organization billing, staff permissions, automatic renewal charging and tax invoices remain separate unfinished scope. A test adapter in automated tests is not a publicly registered provider and is never reported as Easypaisa, JazzCash or a card gateway.

## Historical data

The migration adds nullable historical purchase snapshots/linkages; it does not guess original terms from current mutable plans. Existing settled records retain legacy entitlement behavior. An old pending purchase confirmed after upgrade receives `REVIEW_REQUIRED` when its original terms cannot be established, rather than silently activating changed terms. Existing subscription usage, signed reports and audit history are preserved. Source and database checkpoints are stored under `output` before local migration.
