# Implementation and acceptance matrix

The supplied blueprint is preserved in source-blueprint.md; the user's overriding requirements are in source-requirements.md. A check definition is not a tested phone. ENABLED denotes implemented code, not field certification.

| Requirement | Implementation location | Acceptance evidence |
|---|---|---|
| Android 10+, Kotlin, Compose, SDK 36 | android | Gradle compilation, APK, lint |
| Individual evidence and six explicit result states | diagnostics models, registry, result UI | Serialization and eligibility tests |
| 250+ versioned definitions; only genuine implementations enabled | contracts/diagnostics.json | Registry validation and documented status |
| Profile, display, touch, cameras, audio, sensors, battery, connectivity, storage | diagnostics engine, guided UI | Unit checks; physical-device tests still required |
| Offline results, restart recovery, retries | Room, WorkManager | Room instrumentation and queue tests |
| Concurrent refresh and synchronization ownership | SessionRefreshCoordinator, AuthenticatedRequestScope, SyncWorker | JVM rotation/session-switch coverage and source review; physical background/restart acceptance pending |
| Auth, rotation, authorization | backend/app/auth.py | API security integration tests |
| Email verification and password recovery | account_recovery.py, mail_delivery.py, Android Account UI | Single-use/expiry, account binding, session revocation, mocked delivery/retry and migration tests; real SMTP acceptance pending |
| Scanner pairing and 12 exterior views | scanner UI, media API | Pairing expiry/ownership and image-QC tests |
| Cosmetic review and genuine model interface | backend/app/media.py, ml | Real QC, manual annotations, unavailable model state |
| Deterministic scoring and immutable signed reports | backend/app/reports.py | Tampering, immutability, PDF and QR verification tests |
| Shared category scoring, caps, coverage and conservative overall grade | contracts/scoring-rules.json, backend/app/scoring.py, Android ScoringRules.kt | Shared Python/JVM examples, legacy-version and migration regressions, PDF/QR report integration; field calibration pending |
| Commercial state independent of evidence | backend/app/billing.py | Entitlement, deduplication and quota tests |
| Purchase terms, callbacks, receipts, renewal and refunds | payments.py, payment_providers.py, billing_receipts.py, Android BillingPanel.kt | Explicit simulated adapter tests, mismatch/replay/lost-callback/renewal/refund coverage and populated migrations; genuine merchant adapter and certification pending |
| Privacy and safe platform limitations | platform probes, public projection | Restricted-field and report privacy tests |
| Deployment and operations | infra, CI, docs | Startup/migrations/smoke checks; credentials required for hosting |

Release readiness requires real-device matrix testing, configured production services, restore testing and merchant/model validation. Automated tests cannot substitute for these.
