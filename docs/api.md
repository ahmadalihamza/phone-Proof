# API

Base `/api/v1`. Development OpenAPI UI: `/docs`. Authentication is `Authorization: Bearer ACCESS_TOKEN`. Access tokens last 15 minutes; refresh tokens rotate and reuse revokes the family. Admin TOTP codes are accepted as `otp` in login. Bodies reject unexpected financial/role fields.

| Area | Routes |
|---|---|
| Auth | POST auth/register, auth/login, auth/refresh, auth/logout; GET auth/me |
| Account recovery | GET auth/capabilities; POST auth/recovery/request, auth/recovery/complete |
| Email verification | POST auth/email/request-verification, auth/email/verify |
| Registry | GET diagnostics/definitions |
| Scoring policies | GET scoring/rules (current version and all retained policies) |
| Inspections | POST/GET inspections; GET inspections/{id}; POST pause, resume, cancel |
| Capability | POST inspections/{id}/capabilities; GET required-checks |
| Results | POST inspections/{id}/results/batch; GET results |
| Pairing | POST inspections/{id}/pairing-token; POST pairing/join; GET pairing-status |
| Scanner | GET scanner/views; POST inspections/{id}/images (multipart); GET images; GET images/{id} |
| Cosmetics | POST inspections/{id}/cosmetic/review; GET cosmetic; POST cv/analyze; GET cv/status, cv/results |
| Identity | POST inspections/{id}/identity |
| Scoring | GET inspections/{id}/score; POST score/recalculate |
| Report | POST inspections/{id}/finalize (alias reports); GET reports; GET reports/{id}, reports/{id}/pdf |
| Public | GET verify/{token}; HTML `/r/{token}` |
| Billing | GET plans, billing/entitlements, billing/usage; POST payments/create, payments/{id}/reconcile; GET payments/{id}/status |
| Subscriptions | POST subscriptions; GET subscriptions/current, subscriptions/history; POST subscriptions/{id}/cancel, change-plan, renew |
| Transactions | GET transactions, transactions/{id}, invoices, invoices/{id}; POST transactions/{id}/refund; GET transactions/{id}/receipt, receipt/pdf (also invoices/{id}/pdf) |
| Promotions | POST promo/validate, promo/redeem |
| Administration | GET admin/overview, users, billing, audit; POST admin/plans, promotions, reports/{id}/revoke, refunds/{id}/approve, refunds/{id}/reject |

Inspection creation accepts a UUID and allowlisted device_profile string map. Capabilities accept api, features (boolean map), cameras (string IDs), sensors, device, limitations. A results batch contains results with id (attempt UUID), check_id, definition_version, status, measured_value/evidence string maps, started_at/completed_at ISO timestamps, app_version, method and message. The same UUID with different content returns 409. Batches are limited to 100. Unknown or disabled diagnostics return 422. A missing required result blocks finalization; inaccessible results remain disclosed; critical skipped/inconclusive results block publication.

Image uploads require view, scanner_device_id, capture_nonce, captured_at and file. Maximum 12 MiB / 24 MP. The server does not trust a file extension. Images are private. Rejected QC returns 422 with reasons. A duplicate view with identical content is idempotent; a changed capture invalidates its review. Review requires all current image UUIDs, explicit framing_and_angles_confirmed/all_views_reviewed, and optional normalized bounding-box findings.

Errors: 401 authentication, 403 permission, 404 hidden/nonexistent resource, 409 conflict/incomplete state, 422 invalid evidence, 402 commercial entitlement required, 429 rate limit, 503 unavailable external service. Android retains evidence on all failures.

Inspection creation, inspection detail and capability binding return `score_rule_version`. The server selects the current version for a new inspection; callers cannot override it. An existing inspection keeps its pinned version, including under idempotent creation and capability retries. A duplicate capability submission returns its original definition snapshot rather than expanding the latest registry again.

Score responses for rule v2 include `policy`, `categories` (configured share, category score and coverage), `uncapped_score`, `point_losses` with weighted `points_lost`, and `caps` with check/reason/maximum score. Finalized v2 reports also include cosmetic grade/rule version and `overall` (the lower score, grade and method). Legacy v1 score responses retain their original fields and algorithm. Previously signed payloads are never upgraded. PDF and public HTML render both payload formats.

Development payment settlement route is `/payments/{id}/development-settle`, enabled only by APP_ENV=development and DEVELOPMENT_PAYMENTS=true. The unconfigured production webhook returns 503; redirects never grant access. No Easypaisa/JazzCash API is fabricated.

Checkout returns its immutable `plan_snapshot` and `fulfillment_status`. It accepts only a plan ID, idempotency key, configured provider and (for report purchases) an owned inspection UUID. Pending or paid purchases cannot be duplicated with a new key. A retry with the same key returns its original terms even if the plan has since changed or been deactivated. New subscription purchases cannot be attached to a single inspection. `renew` accepts provider/idempotency_key after expiry and applies the previous period's next-plan selection; it does not debit a recurring payment method. Existing un-snapshotted pending purchases require review before entitlement activation.

Transaction detail includes refund status and receipt availability. Receipt JSON/PDF is owner-authenticated; PDFs use no-store caching and explicitly identify development payments. These are payment receipts, not tax invoices. `paid_at` is server settlement-confirmation time. A completed refund retains the original receipt and existing report access. Generic webhook verification is adapter-based; only automated fixture adapters exercise that path today. See payment-readiness.md for provider activation and reconciliation operation.

`auth/capabilities` is public and reports whether email actions are configured. Recovery requests accept `email` and return the same 202 message for existing and absent accounts. Codes are sent through the encrypted mail outbox, expire after 30 minutes and work once. A new request after the 60-second resend cooldown invalidates earlier codes for that purpose. `auth/recovery/complete` accepts `token`, a 12–128 character `password` and optional six-digit `otp`; administrators must supply an unused authenticator code. Successful reset invalidates existing access and refresh sessions. Verification requires the signed-in account and its verification token. `auth/me` includes `email_verified`; verification currently records address ownership and does not block existing inspection/billing behavior.
