# Release gate record — 19 October 2026

Status: controlled release candidate. The source repairs and single-device acceptance below are complete. Public production activation remains conditional on the open gates at the end of this record.

## Repaired audit findings

| Finding | Repair and verification |
|---|---|
| Checkout crash could lose provider identity | Pending intent, fixed terms and attempt commit before provider I/O; retry and worker reuse the same transaction ID. Crash/recovery and concurrent PostgreSQL checkout tests pass. |
| Sanitized images could be truncated on retrieval | Separate 12 MiB upload / 32 MiB stored bounds; overflow is rejected and response bodies close. Real encrypted MinIO expanded-image test passes. |
| Inspection/account lock inversion | Account row locks precede inspection locks. Overlapping entitlement/finalization passes against PostgreSQL. |
| Scanner restored another session's draft | Owner/origin-scoped restore, online owner validation and immutable capture callbacks; draft and callback isolation tests pass. Two-account/two-phone field acceptance remains open. |
| Speaker success could use an external output | Actual route checks during playback; interruption invalidates readiness. Microphone also verifies its input route. Unit routing tests and real built-in microphone observations pass; external-audio transition acceptance remains open. |
| No-flash lenses counted as applicable | Versioned per-lens flash eligibility, legacy snapshot preservation and explicit unsupported outcomes. Registry tests and actual per-lens platform discovery pass. |
| Optional hardware became required in APK | Explicit optional features. APK badging confirms no required hardware features. |
| Container health failed Host policy | Host-aware `/ready` probe; database/Redis/storage/signing checks, bounded probe pool and lifecycle-owned async Redis. Operational regressions pass. |

Additional changes: coalesced sync with periodic recovery, no work when the queue is empty, human error messages, shared HTTP pooling, tested dependency constraints in Docker/CI, bounded photo previews, and reproducible service/backup/artifact verification tools.

## UI and phone acceptance

Three main destinations: Inspect, Reports, Account. Functional checks and device metadata are separate. Guided tests use a short instruction and Pass / Problem / Skip actions. Technical evidence, billing, recovery and connection controls sit in optional details. Real automatic observations populate an inspection; manual outcomes are not invented. Scores stay pending until the required functional checks are recorded.

**14 instrumentation tests passed on TECNO CK6n / Android 14.** Includes Compose navigation, form draft disposal, guided cancellation, Activity recreation, Room migration/reopen, scanner draft isolation, bounded preview decoding and six hardware acceptance cases. Actual front/back 640×480 JPEGs were decoded and discarded; built-in microphone samples/routing, fresh accelerometer events, storage/SQLite round trips and lifecycle cancellation were observed. These are bounded software/hardware observations, not phone-condition certification. No camera pixels were uploaded or retained by hardware tests; no test report was published.

The test app is `pk.phoneproof.app.acceptance`, labelled **PhoneProof Test**. Existing application packages/data were preserved. Screenshot evidence is in `output/ux/`: home, inspection, device information, guided check and light-theme account/home screens. The original dark-mode setting was restored after visual inspection.

One earlier run encountered a screenshot helper matching two Compose roots; the helper now targets the guided panel. Another run transiently lost the Compose hierarchy during navigation, with no recorded app crash. The isolated navigation retry and the complete final 14-test run pass. Earlier logs are retained; broader OEM and repeated-session reliability remain acceptance work.

## Executed checks

| Gate | Result | Local evidence |
|---|---|---|
| Full backend suite | 137 passed; 8 opt-in cases skipped in this invocation | `output/final-backend-tests-20261019.xml` |
| PostgreSQL 16.15 | 5 passed, no skips: migrations/drift, SQL guards, lock overlap, quota, refresh reuse, checkout/settlement | `output/postgresql-acceptance-20261019.json`, `.xml` |
| Real S3-compatible storage | 3 passed, no skips: private AES256, expanded JPEG/EXIF, overflow, readiness cleanup | `output/object-storage-acceptance-20261019.json`, `.xml` |
| Synthetic PostgreSQL restore | Fresh DB restore preserves signature/hash, ownership, quota and raw SQL report guards; copied signing key verified | `output/backup-restore-acceptance-20261019.json` |
| Live loopback HTTP | Default provider-disabled and development-billing journeys pass; owned temporary state cleaned | `output/final-http-smoke-20261019.log`, `output/final-billing-smoke-20261019.log` |
| Android JVM | 52 passed | App/diagnostics `build/test-results/testDebugUnitTest` |
| Physical Android | 14 passed | `output/android-device-acceptance-20261019.log`, `.json` |
| Debug Android lint | Zero errors; 32 app warnings, zero diagnostics warnings | `android/app/build/reports/lint-results-debug.xml` |
| Optimized release bundle/lint | Final build outcome is recorded in testing.md and the build log | `output/release-android-final-20261019.log` |
| APK packaging | Signature verified, optional hardware only; ZIP and ten 64-bit native libraries have 16 KiB alignment | `output/android-artifact-acceptance-20261019.json` |
| Dependency/security checks | Ruff and pip check pass; 94 distributions, zero known advisories in the dated audit | `output/dependency-audit-20261019.json` |
| Render Blueprint | Valid against current published JSON schema; not account-validated or deployed | `output/render-blueprint-validation-20261019.json` |

The eight opt-in backend cases were executed separately against real local services, so they are not outstanding local test skips. PostgreSQL/MinIO processes were stopped and their owned temporary data removed. MinIO was built from its official tagged source with the official checksum-verified Go toolchain; provenance and binary hash are in `output/minio-source-20261019.json` and the storage result. This is not the production hosting stack. Two upstream test-client deprecation warnings remain.

## Open release gates

1. **Hosted staging/operations:** authorized Render/AWS/SMTP accounts, owned origin and DNS, stable secrets and signing-key custody; actual Redis/worker/container acceptance, mail inbox delivery, deployed bucket permissions, backup/PITR plus object/key restore, load and alert drills. `render.yaml` and deployment.md are the concrete proposed setup. Creating paid resources was not performed.
2. **Merchant integration:** approved provider account, current contract, real adapter implementation and sandbox settlement/refund/webhook/reconciliation acceptance. Safepay is the researched first candidate, with alternatives and primary sources in payment-readiness.md. Development simulation remains blocked in production.
3. **Physical matrix:** second phone and complete twelve-view capture/review/finalization/revocation journey; Android 10–16/OEM/low-memory, process death, reconnect, denied permissions, CameraX lens selection, external audio-route transitions, accessibility/font scaling and 16 KiB runtime validation.
4. **Distribution/trust:** production keystore/Play account, explicit owned HTTPS API build configuration, signed AAB/device catalog review, privacy/retention operations and signing-key publication/rotation. An unsigned build is not a distributable production release. Client attestation is not implemented.
5. **Advanced product scope:** validated cosmetic model/data and inference evaluation; automatic charging, enterprise/dealer organizations and other unfinished blueprint features remain documented in current-limitations.md. Manual cosmetic review and explicit manual subscription renewal are the implemented paths.

No live payments, completed model inference, public deployment or certified-phone accuracy is claimed by this release candidate.
