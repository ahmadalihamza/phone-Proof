# PhoneProof workflow and reliability repair — 9 November 2026

## Evidence and repair order

The engineering audit, actual Kotlin/Compose/Room application, FastAPI backend, and both supplied recordings were inspected before this repair. The 186-second inspection recording and 40-second authentication recording were reviewed throughout their timelines, with closer inspection of the authentication transitions. Private form values were redacted from review images. These recordings demonstrate the previous build, not the repaired build.

| Priority | Observed problem | Root cause | Implemented repair |
|---|---|---|---|
| P0 | Authentication returns to a form without useful feedback | The snackbar effect cleared its own key before showing the message; account state also discarded failures. The default API hostname failed DNS during the audit, while real local backend registration worked. | Durable account success and inline failure, actionable connection errors, a real connection probe, verified identity before credential persistence, explicit configuration instead of an invented default host. |
| P0 | A sync action can imply success without uploading | Guest sync returned without an outcome; callers equated returning with acknowledgement. | Typed outcomes distinguish local-only, sign-in required, offline, rejected, and acknowledged queues. Pairing/publication check their own inspection's acknowledgements. |
| P0 | Lost acknowledgement can strand finalized inspections | Backend finalization guard ran before matching existing result IDs and hashes. Finalization was not retained locally. | Identical retries remain idempotent; new or changed finalized content remains rejected. Local finalization locks further result writes. |
| P0 | Local history has no usable offline report | Reports navigation requested cloud data only. | On-phone inspections, durable snapshots, explicit partial results, offline PDF export, and separate cloud reports. Room migration retains existing records. |
| P1 | Repeated touch and display checks leave much of the screen covered by controls | Each definition launched its own constrained guided dialog. | One immersive touchscreen workflow and one immersive display sequence retain individual diagnostic IDs and evidence. |
| P1 | Counters and repeated operations overwhelm the inspector | Metadata, weighted checks, attempts, and upload items used different denominators; physical probes repeated per criterion. | Stage navigation, optional technical detail, missing-only automatic collection, and evidence-checked shared probes. |

## Coverage and truthfulness

No registry identity, version, weight, disabled template, or scoring contract was removed or rewritten. See the [complete inventory](diagnostic-workflow-inventory.md) and [machine-readable inventory](diagnostic-workflow-inventory.csv) for all 376 templates and their workflow placement. New inspections pin their expanded definitions. Existing inspections retain their original attempts and score-rule version; old records without a snapshot use the compatible registry fallback.

Touch coverage counts actual delivered samples in every accessible grid cell, including accessible edge and corner cells. It does not interpolate unobserved cells or claim to test behind cutouts, rounded hardware corners, or reserved system gesture regions. Distinct pointer IDs and held simultaneous contacts support multi-touch. Separate gesture criteria remain unverified when not observed. Movement alone cannot pass calibrated jitter. Rotation, interruption, or changed geometry requires a restart before passing.

Display patterns include RGB, white, black, gray, color gradients, grayscale, and brightness observations. Pattern rendering never establishes physical display health. The inspector reviews the viewed patterns and identifies relevant observed defects; unfinished or interrupted observations remain unverified. Controls are transient while inspecting the actual screen.

Shared storage, microphone, sensor, and location windows preserve separate criteria and results. Reuse requires compatible definitions and raw evidence consistent with the source result. Quiet input, missing environmental stimuli, or a stale fix cannot be converted into a fabricated hardware pass. Manual camera, sound, controls, biometric, and charging operations remain available.

Local summaries are explicitly marked as local and not server verified. Untested, unavailable, skipped, inconclusive, passed, and failed observations remain distinct. A report snapshot never acknowledges or deletes pending results. Cloud reports continue to use the existing server signing and verification flow.

## Source map

- `android/app/src/main/java/pk/phoneproof/app/MainActivity.kt`: workflow routing, captured inspection IDs, safe snackbar lifecycle, sync/publication gates.
- `android/app/src/main/java/pk/phoneproof/app/InspectionViewModel.kt`: pinned definitions, transactional result batches, missing-only automatic collection, report access and finalized guards.
- `android/app/src/main/java/pk/phoneproof/app/ui/{InspectionStages,HardwareInspection,TouchInspection,TouchEvidence,DisplayInspection,DisplayEvidence,ImmersiveInspection}.kt`: staged inspection and evidence-based fullscreen examination.
- `android/app/src/main/java/pk/phoneproof/app/ui/{AccountReports,LocalReportsScreen,ScannerScreen}.kt`: account feedback, local/cloud navigation, photo sync handling.
- `android/app/src/main/java/pk/phoneproof/app/data/{Api,ApiErrorMessage,AuthClient,AccountIdentity,TokenVault,SyncWorker,SyncOutcome}.kt`: real authentication, origin-bound sessions, error handling, acknowledged synchronization.
- `android/app/src/main/java/pk/phoneproof/app/data/{Database,LocalReportRepository,LocalReportPdf}.kt`: non-destructive migration and offline reporting.
- `android/diagnostics/src/main/java/pk/phoneproof/diagnostics/{DiagnosticsEngine,DiagnosticBatchExecutor,DiagnosticProbePlanner,ProbeObservations,InspectionWorkflows}.kt`: retained coverage and shared observations.
- `backend/app/{inspections,media}.py`: immutable, idempotent result and photo synchronization recovery.
- `tools/{generate_workflow_inventory,verify_android_backend}.py`: reproducible inventory and isolated real Android/backend acceptance.

## Physical-device verification checklist

Use the isolated QA package for account test data. Preserve and back up the existing installation before updating it. Automated pointer injection tests software handling; it cannot establish the physical condition of a digitizer.

- [ ] Start offline; device self-checks finish, manual stages remain untested, and no fabricated manual pass appears.
- [ ] Reopen an older inspection after upgrading; attempts, photos, errors, ownership, and score-rule version remain intact.
- [ ] Trace every accessible touch cell, edge, and corner on the physical display. Confirm no permanent toolbar, footer, or navigation bar covers the working surface.
- [ ] Hold two fingers, then three if supported; check distinct contacts. Perform horizontal, vertical, diagonal, and continuous dragging observations. Leave a cell untouched and confirm coverage cannot pass.
- [ ] Rotate, background, lock, or interrupt touch/display inspection; confirm unfinished work cannot pass and restarting is required.
- [ ] Examine all display patterns in person. Confirm a viewed pattern alone cannot mark health passed; report a specific visible issue and verify unrelated patterns are not indiscriminately failed.
- [ ] Leave brightness/system bars before and after the flow; verify restoration on finish, Back, interruption, and rotation.
- [ ] Test each exposed camera preview, decoded capture, and available flash; retain the no-flash unavailable outcome on lenses without flash.
- [ ] Check sound at each frequency, a spoken microphone sample, physical buttons/vibration, charging transitions, and real enrolled biometrics. Test denied permissions and missing enrollment.
- [x] Use a real configured backend: create an account, reject duplicate/invalid credentials, sign in, rotate refresh credentials, restart the process, and restore the verified session. Passed on the connected TECNO CK6n using the isolated QA app and migrated loopback backend.
- [ ] With email delivery configured, request and consume a real password-reset email; verify expiry/reuse rejection. Without email service, recovery must clearly show unavailable.
- [ ] Record offline, restart, reconnect, sync repeatedly, and compare exact server acknowledgements and unique result IDs. Rejected work stays visible and locally retained.
- [ ] Finalize on the server, replay an identical acknowledged request, and reject new or changed content. The local inspection must lock on finalization recovery.
- [ ] Switch account/server and sign out; original owned inspections and captures must not be shown or rebound to another account.
- [ ] Save a partial local report, restart offline, reopen it, and export a readable multipage PDF. Repeated saves must update the same report and preserve pending attempts.

## Release boundary

A local HTTP backend and an authorized USB phone support real end-to-end engineering verification. They do not establish a deployed production service. A production release still requires an approved reachable HTTPS API origin, release signing, deployment/backup monitoring, and real email delivery. Merchant approval and live payment credentials are required for any enabled paid release. These cannot be truthfully replaced with synthetic responses, a guessed hostname, or a simulated payment provider. The release build now checks its explicit API configuration.

## Verification evidence

- Android application and diagnostics JVM tests: **96 passed** (52 app, 44 diagnostics). The current verification and acceptance APKs built successfully. Both passed test signature, optional hardware, ZIP alignment and all 10 native ELF 16 KiB alignment checks.
- Android verification lint completed with **0 errors and 15 warnings**. Ruff passed for backend code, tests, and the two new Python verification tools.
- TECNO CK6n instrumented tests: **20 passed, none skipped**. The suite checked full window dimensions, brightness and system UI restoration, explicit unconfirmed results, a saved report across activity recreation, v1/v2 Room migrations, PDF generation, account boundaries, microphone capture and retry, accelerometer events, two real camera captures, and interruption of a real power-source probe. The exported four-page fixture was visually reviewed after rendering on the phone.
- Real Android client and loopback backend: registration, duplicate registration, invalid credentials, sign-in, refresh rotation, process restart, logout, and repeated acknowledged sync of an actual local storage observation passed. The server used migrations and a temporary SQLite database. The runner used the separate `pk.phoneproof.app.verification` package.
- Backend: 141 passed and eight service tests skipped in the full SQLite run. The five targeted sync recovery cases passed, including the additional finalized-photo replay case. The separate current service run passed five PostgreSQL and three MinIO checks. The synthetic PostgreSQL dump/restore preserved signatures, report hashes, ownership, quotas, and immutability.
- Release configuration: the build rejected a missing API origin and accepted valid HTTPS `/api/v1/` syntax. The syntax check used a reserved `.invalid` hostname and contacted no remote service.

Build and device logs are under `output/audit-20261027/`. The current service results and restore evidence are under `output/service-acceptance-0o21gc8l/`. Hands-on checks above remain open where automation covered only the app's behavior or a limited measurement.
