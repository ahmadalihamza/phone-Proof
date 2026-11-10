# Executed verification

Latest verification date: 19 October 2026. Windows host, Python 3.12.14, JDK 17, Gradle 8.11.1 and Android SDK 36. This record distinguishes compilation/API evidence from physical-device acceptance. Older sections below are historical; the current [release gate record](release-readiness.md) supersedes their outstanding-gate statements.

## Release repairs and UX — 19 October 2026

- Full backend: **137 passed, 8 opt-in skips**, zero failures/errors, 273.80 seconds. The skipped cases separately passed against **real PostgreSQL 16.15 (5)** and **encrypted private MinIO (3)**, with no skips. Evidence is listed in release-readiness.md.
- A real local `pg_dump` / fresh-database `pg_restore` drill preserved a synthetic report's exact signature/hash, ownership, quota and raw SQL immutability guards. A separately copied fixture signing key was verified; throwaway private keys and temporary server data were removed. Hosted/PITR/object/key disaster recovery remains a separate gate.
- **52 Android JVM cases pass. 14 instrumentation cases pass on TECNO CK6n / Android 14.** Actual bounded hardware observations, navigation, guided cancellation, Activity recreation, Room migration/reopen, scanner draft isolation and preview decode bounds were exercised. No hardware-test image/result was uploaded, and the pre-existing application was not cleared. The last complete device run took 46.62 seconds. Earlier screenshot-helper and transient navigation instrumentation failures are retained and explained in the gate record.
- Debug APK and acceptance/test APKs compile; debug lint reports zero errors, 32 app warnings and zero diagnostics warnings. Final optimized release build/lint outcome is recorded below when complete. The acceptance APK signature, optional feature declarations and 16 KiB ZIP/native alignment pass; this is not a runtime test on a 16 KiB device.
- Both live loopback HTTP smoke journeys pass, including provider exclusion, development settlement idempotency, receipt/PDF, refund request and cleanup. Ruff/pip check pass. The dated strict dependency audit reports **94 distributions, zero known advisories**.
- `render.yaml` validates against Render's current published JSON schema. Hosting/merchant accounts were not provisioned, and no cloud deployment, real email/payment/refund or Play release occurred.

## Current billing verification — 18 October 2026

- Final complete backend suite: **102 passed, zero failures/errors/skips**, 104.67 seconds. Records: `output/backend-billing-tests-20261018.xml` and `.log`. The previous scoring baseline had 76 passing cases. Two existing FastAPI/Starlette test-client deprecation warnings remain.
- New commercial cases exercise immutable plan terms/identity/reference/timestamp, idempotent checkout after plan edits, duplicate purchases, failed retries, independent provider identity/reference/amount/currency checks, webhook authentication/replay/stale-event handling, retryable pending verification, lost-callback reconciliation, uncertain initiation, concurrent settlement, private immutable receipts, manual renewal/new quotas, cancellation/expiry, precise/idempotent refunds and rejection, legacy review and exclusion of simulated money from revenue.
- The production gateway contract is exercised with a private in-memory **test adapter**, never registered in the application. The development provider is a separate explicit simulation. No real merchant sandbox, payment, refund, webhook authentication or recurring charge was exercised.
- Android debug APK, both JVM suites, lint and instrumentation APK compilation: **BUILD SUCCESSFUL**, 5 minutes 36 seconds, 133 tasks (26 executed, 107 up to date). Log: `.tools/android-billing-20261018.log`. **34 JVM tests, zero failures/errors**: 16 app session/ownership and 18 diagnostics/scoring cases. Lint: **zero errors**, 14 app warnings, no diagnostics warnings. New billing screens compiled; Android UI/device acceptance remains unexecuted.
- APK signature verification passes. Current debug APK SHA-256: `64dc56d80be1136eca4feeaa2739b1212adb5e89fac6ce2eee90047a83ceadc6`; artifact: `android/app/build/outputs/apk/debug/app-debug.apk`. The instrumentation APK is compiled, not executed. The owned Gradle daemon was stopped afterward.
- Two live loopback HTTP commands pass: default provider-disabled startup, and `tools/smoke_backend.py --development-payments` covering checkout, repeated settlement, subscription quota, private receipt JSON/PDF and refund request. Logs: `output/billing-disabled-http-smoke-20261018.log`, `output/billing-http-smoke-20261018.log`. Temporary servers/state are cleaned up and the development mode moves no money.
- Fresh and populated migrations pass, including a historical subscription with consumed report usage, legacy transaction fields and raw SQL financial/event/receipt mutation guards. The actual local development DB was consistently backed up to `output/database-before-billing-20261018.db`, migrated and drift checked. All historical table rows/columns were compared with that checkpoint and remain unchanged; foreign-key and integrity checks pass. New nullable historical links/snapshots are not guessed.
- PostgreSQL migration SQL generation succeeds in offline mode (`output/postgresql-migrations-billing-20261018.sql`). This is **not** execution against PostgreSQL. Runtime PostgreSQL/concurrent provider acceptance remains pending.
- Ruff and `pip check` pass. No dependency pins changed in this increment; the earlier audit below remains the latest advisory audit. Compose's opt-in payment worker configuration parses; its containers were not launched.
- `output/pdf/billing-development-receipt.pdf` renders to one readable page, with explicit **DEVELOPMENT PAYMENT — NOT REAL MONEY**, transaction/reference/customer/amount fields and a statement that it is not a tax invoice. The fixture receipt records a simulated settlement, not real revenue.
- Source checkpoint: `output/source-before-billing-20261018.zip`. Review diff/manifest: `output/billing-changes-20261018.patch` and `.json`. No source files were deleted in the billing increment.

## Previous scoring verification — 18 October 2026

- Final full backend suite: **76 passed, zero failures/errors/skips**, 64.34 seconds. Record: `output/backend-tests-20261018.xml`. The unchanged baseline passed all 50 tests before implementation. The final run includes PyJWT 2.15.1, report pagination and the smoke cleanup regressions.
- **34 Android JVM tests passed with zero failures/errors**: 16 app session/ownership tests and 18 diagnostics/scoring tests. Nine shared acceptance examples run in both Python and Kotlin, covering category shares, unequal check counts/weights, unavailable/unsupported/partial coverage, critical caps, metadata exclusion and retry timestamps across time zones.
- Debug APK, both modules' lint, JVM suites and instrumentation test APK all completed successfully in the clean build: **BUILD SUCCESSFUL**, 9 minutes 31 seconds, 133 executed tasks. Log: `.tools/android-scoring-clean-20261018.log`. Lint has **zero errors**, 14 warning entries in the app report and no issues in the diagnostics report. KAPT/native-symbol and missing platform-tools warnings remain; they do not establish runtime device acceptance.
- The initial incremental Android build encountered stale `F:` paths in restored generated artifacts. Previous module outputs and project caches were archived under `output/android-build-before-scoring-20261018`; the clean build from `G:` succeeded. No source or local inspection data was removed to repair the build cache.
- New backend coverage includes policy pinning, immutable capability-retry snapshots, preservation of legacy scoring/report formats, conservative overall grades, category point losses and critical publication checks that do not consume quota on rejection. Malformed signed `exp`, `iat` and `nbf` claims return 401 instead of a server error.
- Fresh migrations and populated legacy upgrades pass, including preservation of stored report payload/hash/signature fields. The development SQLite database was backed up to `output/database-before-scoring-20261018.db` before migration; upgrade and schema-drift checks pass. Room migration 1→2 instrumentation coverage was added and compiled, with both schema assets verified inside the test APK. It has **not been executed** on a device.
- Ruff checks pass for backend application/tests and tools; `pip check` reports no broken requirements. The final strict dependency audit checked **94 installed packages with zero known vulnerabilities**, recorded in `output/dependency-audit-20261018.json`. The earlier audit found two PyJWT advisories; upgrading the pin from 2.14.0 to 2.15.1 cleared them. This is a dated advisory-database result.
- Two final live HTTP smoke runs pass migrations, startup, health, registration/login, authenticated inspection persistence, policy pinning/public policy retrieval, disabled production payments and temporary-state cleanup. The owned server process is shut down afterward. A Windows SQLite WAL/SHM cleanup race was reproduced and fixed with bounded permission-error retries restricted to the command's own temporary directory. Persistent cleanup errors still fail the command; its PASS message is emitted only after cleanup succeeds.
- PDF tests cover legacy and v2 layouts, signature/QR verification, explicit grade caps, and long camera metadata split across page boundaries without dropping its beginning/end. The five-page `output/pdf/scoring-v2-cap-test-fixture.pdf` was rendered and visually inspected: readable category weights, separate functional/cosmetic/overall grades, charging cap, individual results and complete verification block. It is labeled **TEST FIXTURE - NOT A PHONE INSPECTION**.
- Current installable development artifact: `android/app/build/outputs/apk/debug/app-debug.apk`; APK signature verification passes and its scoring-policy asset is version 2. SHA-256: `21b0c6cfe693bafa734b6c7e6062a034e82af5e815966e49319091a8d6302784`. Instrumentation APK: `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. No production-signed release was built in this increment.

Physical-phone diagnostics, actual Room migration execution, the two-phone journey, merchant sandbox certification, genuine ML inference, production PostgreSQL/Redis/S3 integration and deployment/backup acceptance remain pending. The connectivity probes remain unscored observations until field validation supports a versioned scoring change; category shares do not imply certified hardware coverage.

## Previous backend verification — 10 October 2026

- Full backend suite: **50 passed, zero failures**, 122.98 seconds. Record: `output/backend-tests-20261010.xml`. This includes 29 new recovery/mail cases and the existing-account migration regression.
- Recovery coverage includes account-existence response parity, resend cooldown, superseded/expired/single-use codes, cross-account verification rejection, password-reset access/refresh revocation, administrator MFA, transaction rollback, concurrent reset/recovery behavior, encrypted mail cancellation, bounded SMTP retries and stale worker lease fencing. SMTP was mocked throughout; no real email was sent.
- Fresh and populated database migration tests pass. The existing local development SQLite database was backed up before upgrading to the recovery migration; `alembic upgrade head` and `alembic check` pass with no schema drift.
- Live HTTP smoke test passes: migrations/startup/health, registration/login, authenticated ownership, persisted inspections and unavailable production payment rejection.
- Ruff correctness checks and `pip check` pass. Compose YAML parses and the opt-in mail worker command is verified; Docker services were not started.
- Two FastAPI/Starlette test-client deprecation warnings remain. They do not indicate a failed application test.

## Previous Android verification — 10 October 2026

- Debug APK, app and diagnostics JVM tests, lint and instrumentation APK compilation all pass. The final build completed in **4 minutes 54 seconds**; record: `.tools/android-final-20261010.log`. The initial cold build completed in 19 minutes 14 seconds; record: `.tools/android-check-20261010.log`.
- **24 JVM tests, zero failures or errors**: nine session-refresh coordination cases, seven authenticated-request scope cases and eight diagnostics cases. Coverage includes concurrent refresh, stale refresh after login/logout, transient failures, account/server changes during synchronization and permitted token rotation within the same session.
- Lint reports **zero errors and 30 warnings** in `android/app/build/reports/lint-results-debug.xml`. KAPT option and native-symbol stripping warnings do not prevent compilation.
- Current installable debug artifact: `android/app/build/outputs/apk/debug/app-debug.apk`. This is a development APK; no new production-signed release was built.
- Instrumentation tests compiled but were **not executed**. Physical-phone diagnostics, the two-phone journey and runtime acceptance remain pending. SDK platform-tools/device setup and license acceptance still need completing before connected testing.

## Historical verification — 21 September 2026

The following records describe the previous machine and build. Current validation is recorded above. No physical device or configured AVD was available in that historical run.

## Backend

- The final consolidated suite passed: **19 tests, zero failures**, recorded in `output/backend-tests.xml` (91.24 seconds). This includes the dependency upgrades and retry ordering across timezone offsets.
- Covered: six-state/registry behavior, disabled/absent hardware rejection, ownership, UUID idempotency, refresh replay, admin TOTP/replay, score rules, image bounds/QC/duplicates, pairing expiry/single use, replacement invalidation, manual-review finalization, signature tampering/revocation, private/public evidence boundaries, trial concurrency, development purchases, quotas/promos/cancellation and rate limits.
- The migration test creates a separate database using Alembic, checks model/schema parity and verifies that raw SQL cannot update/delete an audit event. `alembic upgrade head` and `alembic check` also completed against the development database with no new operations detected.
- Full API fixture journey registers an account, creates an inspection, records individual synthetic results, pairs a scanner, uploads twelve synthetic QC fixtures, manually reviews, finalizes, downloads the PDF, decodes its QR, checks signatures, detects tampering and verifies revocation. The device label is **TEST FIXTURE - NOT A PHONE INSPECTION**. This is never presented as a real hardware test.
- Python Ruff's configured correctness checks passed. `pip check` verifies dependency compatibility; `pip-audit --strict` audited 94 installed distributions and reported **zero known vulnerabilities**. This is a dated database-based check, not proof that no vulnerability exists. Versions are recorded in `backend/requirements-tested.txt`; that snapshot includes Windows/test tools and is not the Linux production requirements file.
- `tools/smoke_backend.py` exercises real loopback HTTP using temporary state: migration, Uvicorn startup, health, registration/login, authenticated account/inspection persistence and disabled payments. It shuts down the owned server process afterward.
- Two deprecation warnings remain in the FastAPI/Starlette test client for its HTTPX/AnyIO integration. Tests pass; these warnings do not describe an application failure.

Commands from the repository root:

```powershell
.\.venv\Scripts\python.exe tools\smoke_backend.py
Set-Location backend
..\.venv\Scripts\python.exe -m pytest -q --junitxml=../output/backend-tests.xml
..\.venv\Scripts\python.exe -m ruff check app tests
..\.venv\Scripts\python.exe -m pip_audit --strict
..\.venv\Scripts\python.exe -m alembic upgrade head
..\.venv\Scripts\python.exe -m alembic check
```

## Android

The diagnostics JVM suite contained eight tests covering serialization, eligibility, missing permissions/hardware, disabled definitions, retry selection and scoring. It passed. At that time, the app module had no JVM test cases; its successful `testDebugUnitTest` task was not counted as additional tests.

The initial debug APK compiled. Lint then exposed five errors (a local SDK path escape, a restricted Activity key-event override and a Compose StateFlow read). These were corrected. The historical final APK/lint/instrumentation-APK build in `.tools/android-final-check.log` ended `BUILD SUCCESSFUL`. The historical release-bundle build in `.tools/android-release-check.log` also succeeded. These logs come from the previous machine and do not verify the current account/session changes.

The current workflow repair passed Room migration, reopen, and offline PDF tests on the TECNO CK6n. See [workflow verification](phoneproof-workflow-repair.md) for current outcomes and [Android setup](android-setup.md) for the isolated QA commands.

## PDF visual QA

The final synthetic fixture renders to five pages. All five page images were inspected: readable individual rows, repeated table headers, complete identifiers, clear fixture label, no clipped columns and a complete QR/signature/disclaimer block. A previous orphan disclaimer page was corrected. Poppler reported missing optional Symbol/ArialUnicode display fonts, but the rendered Latin report content was checked and legible. QR extraction/decoding is asserted in the API test.

## Not executed

Two-phone acceptance, Android UI/runtime instrumentation, real sensor/camera/audio/GPS measurements, physical permission/offline/restart flows, PostgreSQL/Redis/S3 integration and load tests, merchant sandbox payments, genuine ML inference, Docker container startup, public deployment, signing/Play release, independent security assessment and backup restore. Follow two-phone-demo.md and current-limitations.md before release.
