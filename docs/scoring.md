# Scoring and report verification

New inspections use **scoring rule version 2**. Each inspection is pinned to its rule version when created. The migration backfills existing inspections with version 1; signed payloads are never recalculated or rewritten. The bundled `contracts/scoring-rules.json` is shared by the backend and Android, and `GET /api/v1/scoring/rules` publishes the supported policies. A new policy requires a new version, preserving earlier versions.

Both versions use the latest completed attempt, comparing timestamps as instants across time zones. Server receipt order/UUID breaks equal completion times; the Android preview retains the last local attempt for a tie. Device timestamps remain bounded observations rather than a trusted clock. Local previews are provisional until server finalization.

## Version 2 category scoring

| Category | Share |
|---|---:|
| Display and touch | 25% |
| Cameras | 15% |
| Battery and charging | 15% |
| Audio | 10% |
| Sensors | 10% |
| Connectivity | 10% |
| Storage and system | 10% |
| Buttons and haptics | 5% |

Biometric, security and performance checks belong to storage/system. Only weighted, enabled functional checks participate. Hardware metadata always has zero weight. Within a category, score equals passed check weight divided by conclusive PASS/FAIL weight. The functional score combines these category scores using their configured shares, normalized across categories that have conclusive checks. Adding more definitions or camera lenses inside one category therefore does not increase that category's configured share.

Unsupported checks are excluded from applicable weight. Missing, inaccessible, skipped and inconclusive checks remain disclosed and reduce coverage. Category coverage equals conclusive weight divided by applicable weight; total coverage combines those ratios with category shares, normalized across applicable categories. An entirely unmeasured category reduces coverage while remaining absent from the score denominator. A score of 100 at low coverage does not establish complete device health.

For example, a passing display/touch category and failing storage/system category score `100 × 25 / (25 + 10) = 71.43`. If audio is also applicable but inaccessible, the score remains 71.43 while coverage becomes `100 × (25 + 10) / (25 + 10 + 10) = 77.78%`.

Failure caps apply independently of category averaging: touch-grid, display-line, microphone-capture and per-lens camera-capture failures cap the functional score at 79 (grade C); charging-connection failure caps it at 69 (grade D). All exposed lens capture failures receive the camera cap because an OEM-independent main-camera designation is not guaranteed. Other definitions marked critical retain a cap of 79. Skipped/inconclusive checks covered by these caps block publication; unsupported/inaccessible outcomes remain explicitly disclosed. Missing required weighted results also block publication.

Scores use decimal arithmetic, round half up to two places, and publish category scores, coverage, lost weighted points, the uncapped score, caps and the policy snapshot. Failure-point deductions explain the uncapped score; caps may lower the final score further. Functional and cosmetic grades remain visible separately. Overall score is the lower of the two scores, with the same grade bands; if either score is unavailable, overall score is unavailable. This conservative rule prevents cosmetic condition from raising a low functional grade.

## Legacy version 1

1. Ignore metadata/observation definitions with weight zero and disabled definitions.
2. Exclude NOT_SUPPORTED from applicable weight.
3. Count PASS and FAIL in measured weight; earn the definition's weight only for PASS.
4. Divide earned weight by measured weight and multiply by 100, rounded to two decimals. No measured weight means no score.
5. If any critical functional check fails, cap the score at 79.
6. Publish coverage as measured weight / applicable weight. NOT_ACCESSIBLE, SKIPPED_BY_USER, INCONCLUSIVE and missing results reduce coverage rather than inventing failures or passes.

Grades: A >=90, B >=80, C >=70, D >=55, E <55. Example: two checks weighted 5 and 10, with the first failing and the second passing, score 66.67. An unscored metadata PASS cannot increase that score. A high score at low coverage does not establish overall device health; every incomplete check is listed.

Finalization requires a record for each applicable weighted definition, no skipped/inconclusive critical check under the inspection's policy, at least one conclusive weighted result, and all twelve accepted images with a review matching their current identities (unless an operator explicitly disables the cosmetic requirement). Inaccessible critical hardware is disclosed. This is an inspection report, not certification that every component works. Version 1 retains its original single-pool check weights and generic critical cap.

Cosmetic scoring is separate: after manual review, start at 100 and deduct 5/15/35 for each minor/moderate/severe finding; floor at zero and cap at 69 for a severe crack. No valid review means no cosmetic score. These deductions are included in the versioned policy. AI does not override functional results. Legacy reports have no combined overall score.

The server serializes the full report as sorted compact JSON with UTF-8, hashes it with SHA-256, and signs the raw 32-byte digest with Ed25519. Hash/signature/key version and the full owner payload are returned by the authenticated report API. Immutable database triggers protect evidence, reports, revocations and audit events. Revocation appends a record; it never edits the signed payload.

The public `/r/{token}` page and verification API recheck the stored signature and current revocation state. Their payload removes private evidence/measurements/free-text messages; `payload_is_privacy_projection` explicitly marks that difference. Do not hash the redacted projection and expect the signed full-payload hash. A trusted publication/rotation policy for public signing keys remains a production release gate.

Android Room migration 1→2 preserves previous attempts and assigns rule version 1 to saved inspections. New local drafts use the bundled current version; first server synchronization adopts the server's pinned version. The selected inspection refreshes when the worker updates that metadata. An unknown future rule produces an unavailable preview until the app is updated. Shared acceptance examples under `contracts/scoring-examples.json` are run by both the Python and Kotlin test suites.
