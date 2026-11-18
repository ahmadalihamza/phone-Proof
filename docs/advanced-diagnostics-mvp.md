# Advanced diagnostic intelligence increment

This increment is based on public Android observations and deterministic expert rules, policy v1. The source checkpoint is `2404411`. Work is isolated on `codex/advanced-diagnostic-intelligence`.

## Coverage baseline

`diagnostic-baseline-20261118.json` records all 376 original IDs and their complete-definition SHA-256 digests, classification, and the scoring-policy digest. The baseline contains 326 enabled templates, 50 disabled templates, and 61 weighted functional templates. Classification covers 202 metadata entries, 60 telemetry entries, 41 functional measurements, 23 manual observations, and 50 unavailable implementations. The existing complete workflow inventory describes overlapping operations and each independent criterion.

Run `python tools/check_diagnostic_preservation.py` before and after changes. It fails on a missing or modified original definition, including its version, criterion, prerequisite, weight, implementation state, or scoring policy. Run `python tools/generate_workflow_inventory.py --check` to verify the complete workflow mapping. There are no additional registry definitions in this increment.

## Implemented observation inventory

| Existing result | Additive capability | Units and limits |
|---|---|---|
| `battery.health` | Three passive samples, voltage, current/average current, charge, energy, battery level, health/status/source, chemistry, supported cycle count, temperature change, unavailable-field list | V, mA, mAh, mWh, %, C, cycles. Android uA/uAh/nWh are normalized. Three samples are spaced 400 ms apart. Unsupported counters remain absent and explicitly listed. |
| `performance.cpu` | Three bounded SHA-256 computation windows, output agreement, timing ratio, available cores, ABIs, SoC on API 31+, hardware identifier, GLES capability, uptime and thermal context | ms, dimensionless timing ratio, cores. At most 3,000 operations over 4 KiB input, 1.5-second compute budget, cancellation checkpoints. Severe thermal pressure defers the operation. |
| `performance.memory` | Two verified patterns in a bounded allocation, OS total/available memory, low-memory flag, pressure threshold | bytes. Maximum 1 MiB temporary allocation. Allocation rejection remains inconclusive and is recorded. |
| `storage.checksum` and the five shared I/O criteria | Sequential write plus sync latency, read latency, checksum, overwrite and repeated overwrite read, delete, free-space gate and cleanup | ms and bytes. Existing 256 KiB app-private workload, minimum 8 MiB free-space gate. Existing SQLite committed transaction probe remains available. |
| `system.model` | OS/build metadata, patch date/age, build tags, visible su-path heuristic, developer settings, encryption status, secure lock, Verified Boot/StrongBox feature flags, WebView version and uptime | days, ms, Android status codes. Play Integrity is explicitly unavailable until legitimately configured. |
| `thermal.status` | OS thermal-policy observation correlated with battery and processor context | Android thermal-status code. Existing headroom check stays separate to respect platform rate limiting. No deliberate heating workload. |
| `camera.{id}.facing` | Additional Camera2 autofocus/OIS capability, sensor pixels, exposure-time range and sensitivity range | ns, ISO and pixels. Existing preview, decoded capture, flash and per-lens guided criteria remain available independently. Actual capture-result exposure metadata is still a future extension. |
| `network.validated` | Active/validated network, neutral VPN transport, supported Wi-Fi signal/link/frequency and permission-dependent cellular network type | dBm, Mbps, MHz and Android type code. No SSID, MAC, phone number or location is collected. Cellular signal metrics and calibrated network performance remain limited by access and environment. |
| Existing sensor response/continuity/variation results | Reused fresh finite event windows retain sample count, timestamp consistency, variation and environmental limitations in each independent criterion | Existing sensor-specific platform units and timestamps. Missing stimulus remains inconclusive. The existing planner shares only compatible real measurement windows. |
| Existing scanner, image QC and cosmetic review | Preserved real decoding, sharpness/exposure heuristics, OCR, manual review and model-worker boundary | No trained weights or labeled validation data were found. Trained defect inference remains unavailable. |

Supplemental snapshots are stored as bounded `advanced_v1` string maps inside each existing result's evidence. This retains Room history, stable result UUID retries, existing API validation, and exact acknowledgement handling. Each snapshot has a schema version and observation timestamp. Existing result status and scoring semantics remain independent.

## Interpretation and reports

`contracts/analysis-policy.json` is shared by Android and Python. Each finding includes a stable ID, category, title, severity, kind, diagnostic IDs, supporting measurements with units in their keys, interpretation, confidence basis, possible explanations, further inspection, limitations, and policy version. Findings use functional problem, review risk, normal observed, observation, unavailable and inconclusive categories. No artificial confidence percentage is produced.

Rules cover OEM battery-health warnings, an advisory 45 C battery-temperature threshold, contradictory charging reports, inconsistent counters, severe Android thermal pressure, patch age or future dates, root-risk indicators, screen lock, neutral developer settings, attestation availability, memory/storage pressure, repeated computation and network validation. Thresholds are conservative advisory policy choices requiring field calibration. They never change a functional or cosmetic score.

The existing Results & evidence screen has one collapsed interpretation control. The local report sheet uses the same component. Local report snapshots and PDFs include policy-versioned findings. Newly finalized owner reports sign their analysis along with the existing payload. Reopening a finalized report returns its original stored payload and hash even after policy changes. Public verification removes owner analysis and telemetry to maintain its privacy projection. `/api/v1/inspections/{id}/analysis` requires the inspection owner.

## Authentication root cause and runtime

The installed QA application's saved connection map was empty at the start of this increment. Account requests require an explicitly configured origin. Prior local acceptance had cleared its temporary server after testing. No production backend configuration was found locally.

The existing real registration, identity verification, encrypted origin-bound session, refresh rotation, and sign-out implementation is retained. The acceptance runner creates a migrated live backend, uses explicit authorized ADB forwarding, tests actual account and result requests, verifies supplemental evidence round trips, checks the server analysis endpoint and local summary, restarts the app process and verifies restored credentials. Passwords and access tokens stay out of diagnostic logs.

For a usable demonstration, `tools/run_demo_backend.py` keeps a real persistent SQLite backend and signing identity in ignored `runtime/demo/`, while serving only loopback and forwarding the selected port to the authorized phone. Keep the process and USB connection running. SMTP remains unavailable unless a separately configured service is used.

## MVP demonstration

From the repository root, start the backend in a terminal and keep it open. Replace `DEVICE_SERIAL` with `adb devices` output.

```powershell
.\.venv\Scripts\python.exe tools/run_demo_backend.py --serial DEVICE_SERIAL
```

Install the QA APK without clearing its data. In Account, while signed out, open Connection, save `http://127.0.0.1:8765/api/v1/`, and check the saved connection. Alternatively use the guarded QA instrumentation setup after installing its test APK.

```powershell
adb shell am instrument --user 0 -w -e class pk.phoneproof.app.DemoConnectionSetup -e phoneproof_demo_origin http://127.0.0.1:8765/api/v1/ pk.phoneproof.app.verification.test/androidx.test.runner.AndroidJUnitRunner
```

1. Open PhoneProof QA and create an account using the normal Account form. Confirm the success state. Sign out and sign in again.
2. Start an inspection. Allow the existing device self-check stage to collect advanced evidence automatically.
3. Open Results & evidence, then Diagnostic interpretation. Expand a finding to inspect its evidence, explanation and limitation. Developer settings and missing network validation must retain their qualified meaning.
4. Run the existing fullscreen display and touch workflows. Their criteria and individual observations remain available.
5. Observe motion/sensors with the stated stimuli, then cameras, sound, controls and charging as needed. Unperformed checks remain visible.
6. Save a partial report, open Reports / On phone, reopen the summary, and share the local PDF.
7. Restart the app. Confirm the persisted observations and report remain available. Sync twice, checking acknowledgement and rejection feedback.
8. With a second phone and approved reachable shared backend, run the existing pairing, twelve-view scanner and cosmetic review before cloud finalization. A USB-only loopback service requires forwarding on every participating phone or an explicitly configured LAN setup.

The uploaded `main` checkpoint is preserved. This increment is not automatically published or merged. Hosted HTTPS, production signing, real inbox delivery, merchant activation, device attestation, broader OEM acceptance, and trained cosmetic/anomaly models remain separate release gates.

## CI repair and validation boundaries

The last public Actions run shows backend and Android failures. Authenticated job-log retrieval from this host timed out through both CLI and connector. The workflow now uses Ubuntu 24.04 and the runner's explicit SDK-manager path, avoiding the failed SDK setup action and an unpinned runner migration. Registry preservation checks were added to CI. Security checks remain enabled, and the six packages identified by the local advisory audit were updated to compatible patched releases. A remote Actions run is still required to verify this workflow repair.

Current executed checks and exact artifacts are recorded in `output/advanced-20261118/`. Historical evidence from October and November 9 is not counted as a current physical test.

## Executed verification on 18 November 2026

| Check | Outcome |
|---|---|
| Baseline comparison | All 376 original definition digests and every scoring policy match. 326 enabled, 50 disabled, 61 weighted. Existing workflow inventory passes its freshness check. |
| Android JVM | 99 passed, zero failures or skips. Includes the original 96 cases and three expert-engine cases that exercise 18 shared policy scenarios, malformed evidence and recorded failures. |
| Backend | 163 passed, eight optional service tests skipped. After the report-compatibility assertions were extended, all 22 selected policy and signed-report cases passed again. |
| Physical device | Final suite passed all 21 cases on TECNO CK6n / Android 14, including the original 20 regression cases. Actual new automatic evidence was collected and bounded CPU computation completed. |
| Real persistent backend | Registration, duplicate rejection, wrong password rejection, sign-in, refresh rotation, exact advanced-evidence acknowledgement, repeated sync, server analysis and local analysis snapshots passed. Process restart preserved both advanced evidence and local analysis. Logout and explicit demo connection setup passed. |
| Android build and lint | QA APK and test APK built successfully. Lint completed with zero errors and 15 warnings. |
| APK verification | Current APK signature, optional hardware declarations, ZIP alignment and all ten native libraries passed 16 KiB alignment checks. |
| Dependency security | 94 installed distributions audited with zero known advisories after patching the six affected packages. `pip check` and Ruff passed. |
| Remote CI | Workflow repair is prepared locally. A remote run and authenticated inspection of the original failed job logs remain pending because both GitHub access routes failed from this host. |

The QA package is configured to the running real demo service at `http://127.0.0.1:8765/api/v1/` through authorized USB forwarding. Keep the computer, backend process and USB connection active. Restart the service with the command above after a computer restart. Existing application data was retained. The Account form is signed out and ready for normal registration against the demo backend.

The current pass list does not include a second-phone scanner journey, a hosted deployment, SMTP inbox delivery, actual part authenticity, physical panel defect certification or validated trained-model inference. Available camera exposure-range metadata is collected, while capture-result exposure analysis and permission-dependent cellular signal metrics remain future extensions. The original PostgreSQL and object-storage cases were skipped in this increment's backend invocation and their prior evidence remains separately dated.

### Changed source areas

- `AdvancedObservations.kt`, `DiagnosticsEngine.kt` and `ExpertAnalysis.kt` implement bounded observations and the shared interpretation contract. `analysis-policy.json` and `analysis-examples.json` define the policy and cross-platform fixtures.
- `ExpertAnalysisPanel.kt`, `MainActivity.kt`, `LocalReportsScreen.kt`, `LocalReportRepository.kt` and `LocalReportPdf.kt` add collapsed findings to existing results and local reports. `HardwareInspection.kt` recognizes numeric light/proximity sensor IDs for appropriate stimuli.
- `backend/app/expert_analysis.py`, `inspections.py` and `reports.py` add owner-only analysis, new signed analysis payloads and privacy-safe verification projection.
- Android and backend test files exercise the new observations, shared rules, old coverage, history compatibility, actual synchronization and restart behavior.
- Baseline verification and demo backend tools, dependency pins and the CI SDK step support reproduction and the presentation setup.

References for units and API semantics are the [Android BatteryManager API](https://developer.android.com/reference/android/os/BatteryManager) and [PowerManager API](https://developer.android.com/reference/android/os/PowerManager).
