# PhoneProof PK
## Real-World Production Application Blueprint
### Production v1 — Android + Backend + AI/CV + Verified Reports

**Audience:** Development team, FYP team, technical supervisor, future engineering hires  
**Team assumption:** 2 development partners  
**Target:** A real deployable product, not a prototype  
**Primary platform:** Android  
**Recommended first public release:** Android 10+ with capability-gated diagnostics  
**Google Play target:** Android 16 / API 36+ for new submissions in 2026

---

# 0. Executive Decision

PhoneProof PK should be built as a **real inspection platform**, not as a demo that pretends every diagnostic is available on every phone.

The production product has four core systems:

1. **Target Phone Android App** — runs functional diagnostics using Android public APIs, user-authorized permissions, and guided tests.
2. **Scanner/Inspector Android App Mode** — a second Android device is paired to the inspection and captures standardized exterior photographs for cosmetic analysis.
3. **Production Backend** — FastAPI + PostgreSQL stores users, sessions, results, images, audit logs, scores, reports, subscriptions, and verification metadata.
4. **AI / Computer Vision Service** — PyTorch + Ultralytics YOLO/OpenCV analyzes standardized exterior images for visible defects such as cracks, scratches, dents, chipped edges, and camera-glass damage.

The final result is a **server-backed, signed, tamper-evident PhoneProof Report** with a unique ID and QR verification page.

A production system must explicitly distinguish:

- `PASS`
- `FAIL`
- `NOT_SUPPORTED`
- `NOT_ACCESSIBLE`
- `SKIPPED_BY_USER`
- `INCONCLUSIVE`

PhoneProof must never mark unsupported hardware as failed and must never invent information that Android does not expose.

---

# 1. Product Goal

## 1.1 Problem

Used-phone transactions depend heavily on claims such as “10/10 condition”, “everything works”, or “battery is perfect”. These claims are inconsistent and difficult to verify.

PhoneProof PK standardizes the inspection process so that:

- a **buyer** can reduce purchase risk,
- a **dealer** can inspect a phone before buying or accepting a trade-in,
- a **reputable seller** can prove the condition of a good device,
- an **inspector** can perform repeatable checks,
- a **marketplace or reseller** can later consume structured condition data through an API.

## 1.2 Product Promise

> PhoneProof PK provides a repeatable, evidence-based assessment of the functions and visible physical condition that can actually be tested through supported Android APIs, user-guided tests, and standardized external imaging.

## 1.3 Non-Goals

PhoneProof must **not** claim that it can universally:

- prove that a screen is an original OEM part,
- prove that a battery is original,
- identify every motherboard repair,
- reconstruct complete device repair history,
- detect hidden internal liquid damage,
- automatically read IMEI on every modern Android device,
- guarantee that a phone will not fail in the future,
- bypass Android security,
- root devices,
- exploit manufacturer service menus,
- guarantee exact battery state-of-health on every model.

If a feature cannot be verified, the report must say so.

---

# 2. Real-World Users and Roles

## 2.1 Buyer

Can create or join a buyer inspection, view results, purchase a report if enabled, scan and verify QR reports, compare reports, and report suspicious reports.

## 2.2 Seller

Can allow a phone to be inspected, share device claim information, receive a finalized report, and share the report with buyers. A seller cannot alter finalized diagnostic evidence.

## 2.3 Inspector / Dealer

Can create inspections, run diagnostics, pair a scanner phone, perform guided physical checks, review AI suggestions, add notes, finalize reports, and access business inspection history.

## 2.4 Admin

Can manage users, organizations, inspectors, check definitions, pricing plans, flagged inspections, model price baselines, ML versions, revocations, and audit logs.

---

# 3. Production Success Criteria

A production v1 is considered ready only when all of the following are true:

- Android app is installable through an internal/closed Play Store track.
- Backend is deployed with HTTPS.
- Authentication and role authorization are functional.
- Inspection data persists across devices and app restarts.
- Target and Scanner phones can pair securely.
- Offline target-phone checks can queue and sync later.
- All check results are timestamped and versioned.
- AI inference is asynchronous and retriable.
- Score calculation is deterministic and reproducible.
- Final reports cannot be silently modified.
- QR verification works without app installation.
- Database migrations are automated.
- Backups are tested.
- Crash reporting and backend monitoring are enabled.
- CI/CD runs tests before deployment.
- Security secrets are not stored inside the repository or APK.
- Partial failure never corrupts an inspection.

---

# 4. High-Level Architecture

```text
                        ┌─────────────────────────────┐
                        │      PHONEPROOF CLOUD       │
                        │                             │
                        │  FastAPI REST / WebSocket   │
                        │           │                 │
                        │   PostgreSQL + Redis        │
                        │           │                 │
                        │  Object Storage (S3)        │
                        │           │                 │
                        │  Celery / ML Worker         │
                        │           │                 │
                        │ YOLO / OpenCV Inference     │
                        │           │                 │
                        │ Report + QR + Signatures    │
                        └────────────┬────────────────┘
                                     │ HTTPS/WSS
                      ┌──────────────┴───────────────┐
                      │                              │
          ┌───────────▼────────────┐     ┌──────────▼───────────┐
          │ TARGET PHONE           │     │ SCANNER PHONE        │
          │ Kotlin + Compose       │     │ Kotlin + Compose     │
          │                        │     │                      │
          │ Diagnostics Engine     │     │ Guided Camera        │
          │ Sensors / Audio        │     │ Image QC             │
          │ Camera / Battery       │     │ Upload Queue         │
          │ Touch / Display        │     │ Session Pairing      │
          │ Room Offline DB        │     │ Room Offline DB      │
          └────────────────────────┘     └──────────────────────┘
```

---

# 5. Recommended Technology Stack

## 5.1 Android

- **Kotlin**
- **Jetpack Compose**
- Android 10+ minimum supported OS for production v1
- Target Android 16 / API 36+
- Room for local persistence
- WorkManager for background sync
- Hilt for dependency injection
- Retrofit/OkHttp for REST
- Kotlinx Serialization
- CameraX for most camera workflows
- Camera2 interop where deeper capability information is needed
- SensorManager
- BatteryManager
- ConnectivityManager / WifiManager
- BluetoothManager
- BiometricManager / BiometricPrompt
- VibratorManager
- StatFs
- MediaPlayer / AudioTrack
- AudioRecord
- Fused Location Provider or Android Location API
- ML Kit Barcode Scanner or ZXing for QR
- Android Keystore for sensitive local credentials

## 5.2 Backend

- Python 3.12+
- FastAPI
- SQLAlchemy 2.x
- Alembic migrations
- PostgreSQL
- Pydantic
- Redis
- Celery workers
- Uvicorn/Gunicorn
- Nginx or cloud load balancer
- S3-compatible object storage
- JWT access + refresh tokens
- Argon2id password hashing
- rate limiting
- structured logging

## 5.3 AI / CV

- PyTorch
- Ultralytics YOLO
- OpenCV
- Pillow
- NumPy
- ONNX export for future optimization
- lightweight model registry or MLflow
- server-side inference first
- edge inference later only after validated model compression

## 5.4 Reporting

- ReportLab
- QR-code generation
- SHA-256 canonical report hash
- Ed25519 digital signature
- public verification webpage

## 5.5 Production Infrastructure

Preferred production reference:

- AWS ECS/Fargate — API and worker containers
- AWS RDS PostgreSQL
- AWS ElastiCache Redis
- AWS S3 — report/image storage
- CloudFront — report delivery
- AWS Secrets Manager
- CloudWatch
- AWS WAF
- Route53 + ACM TLS

Lower-cost early production alternative:

- Railway/Render for FastAPI
- managed PostgreSQL
- Cloudflare R2 / Supabase Storage
- Redis Cloud

The API contract should remain infrastructure-independent.

---

# 6. Production Repository Structure

```text
phoneproof/
│
├── android/
│   ├── app/
│   ├── core/
│   │   ├── networking/
│   │   ├── database/
│   │   ├── security/
│   │   ├── design-system/
│   │   └── common/
│   ├── feature-auth/
│   ├── feature-home/
│   ├── feature-inspection/
│   ├── feature-diagnostics/
│   ├── feature-pairing/
│   ├── feature-scanner/
│   ├── feature-report/
│   ├── feature-subscription/
│   └── diagnostics/
│       ├── engine/
│       ├── definitions/
│       ├── display/
│       ├── touch/
│       ├── camera/
│       ├── audio/
│       ├── battery/
│       ├── sensors/
│       ├── connectivity/
│       ├── storage/
│       ├── buttons/
│       └── integrity/
│
├── backend/
│   ├── app/
│   │   ├── main.py
│   │   ├── api/
│   │   ├── auth/
│   │   ├── models/
│   │   ├── schemas/
│   │   ├── services/
│   │   ├── scoring/
│   │   ├── reports/
│   │   ├── pairing/
│   │   ├── audit/
│   │   ├── billing/
│   │   └── security/
│   ├── migrations/
│   ├── tests/
│   └── Dockerfile
│
├── ml/
│   ├── datasets/
│   ├── training/
│   ├── inference/
│   ├── evaluation/
│   ├── model_registry/
│   └── Dockerfile
│
├── web-verify/
│   ├── public-report/
│   └── admin/
│
├── infra/
│   ├── docker-compose.yml
│   ├── terraform/
│   └── deployment/
│
├── docs/
│   ├── architecture.md
│   ├── diagnostics.md
│   ├── scoring.md
│   ├── api.md
│   ├── privacy.md
│   └── runbooks/
│
└── .github/
    └── workflows/
```

---

# 7. Environment Configuration

## 7.1 Backend `.env`

```env
APP_ENV=production
APP_SECRET_KEY=...
JWT_PRIVATE_KEY=...
JWT_PUBLIC_KEY=...
DATABASE_URL=postgresql+psycopg://...
REDIS_URL=redis://...
S3_BUCKET=...
S3_REGION=...
S3_ACCESS_KEY=...
S3_SECRET_KEY=...
PUBLIC_REPORT_BASE_URL=https://verify.phoneproof.pk
REPORT_SIGNING_PRIVATE_KEY=...
REPORT_SIGNING_PUBLIC_KEY=...
SENTRY_DSN=...
```

Never commit production secrets.

## 7.2 Android Build Variants

- `debug`
- `staging`
- `production`

Each environment gets separate API base URL, signing configuration, analytics, feature flags, and Firebase project if used.

---

# 8. Inspection Lifecycle

A production inspection is a state machine.

```text
DRAFT
  ↓
CREATED
  ↓
TARGET_CONNECTED
  ↓
SCANNER_PAIRED
  ↓
DIAGNOSTICS_IN_PROGRESS
  ↓
COSMETIC_CAPTURE
  ↓
ML_PROCESSING
  ↓
READY_FOR_REVIEW
  ↓
FINALIZED
  ↓
SIGNED
  ↓
PUBLISHED
```

Other terminal states:

- `CANCELLED`
- `EXPIRED`
- `REVOKED`

No report may be published while required evidence is incomplete.

---

# 9. Secure Two-Device Pairing

## 9.1 Target Phone

1. Inspector creates inspection.
2. Backend returns inspection UUID, short-lived pairing token, and QR payload.
3. Target app shows pairing QR.

## 9.2 Scanner Phone

1. Inspector opens Scanner Mode.
2. Scans pairing QR.
3. Backend validates token expiry, user/organization authorization, and session state.
4. Backend binds scanner device to inspection.
5. Scanner receives capture checklist.

## 9.3 QR Pairing Payload

```json
{
  "type": "phoneproof_pair",
  "inspection_id": "6cb2...",
  "token": "short-lived-random-token",
  "expires_at": "2026-09-18T12:10:00Z"
}
```

Never put long-lived authentication tokens inside pairing QR codes.

---

# 10. Diagnostics Engine Design

## 10.1 Core Rule

Diagnostics are **definition-driven**. Do not hard-code the full inspection flow inside UI screens.

A diagnostic definition contains:

```json
{
  "id": "touch.grid.coverage",
  "version": 3,
  "category": "touch",
  "title": "Full Screen Touch Coverage",
  "mode": "GUIDED_AUTOMATIC",
  "criticality": "CRITICAL",
  "capability_requirements": ["touchscreen"],
  "runtime_permissions": [],
  "timeout_seconds": 60,
  "weight": 4.0,
  "scoring_rule": "coverage >= 0.98",
  "evidence_required": true
}
```

This makes checks versionable, auditable, remotely configurable, testable, and reproducible.

## 10.2 Diagnostic Modes

- `AUTOMATIC`
- `GUIDED_AUTOMATIC`
- `USER_CONFIRMED`
- `EXTERNAL_VERIFICATION`
- `AI_ASSISTED`

## 10.3 Diagnostic Result Schema

```json
{
  "check_id": "touch.grid.coverage",
  "definition_version": 3,
  "status": "PASS",
  "measured_value": {"coverage": 0.992},
  "evidence": {
    "touch_cells_total": 540,
    "touch_cells_completed": 536
  },
  "started_at": "...",
  "completed_at": "...",
  "device_time": "...",
  "server_received_at": "...",
  "app_version": "1.0.0",
  "notes": null
}
```

---

# 11. Capability Detection

Before inspection, generate a capability profile.

```json
{
  "accelerometer": true,
  "gyroscope": true,
  "proximity": true,
  "light_sensor": true,
  "magnetometer": true,
  "barometer": false,
  "nfc": true,
  "biometric": true,
  "camera_count_logical": 4,
  "flash": true
}
```

Unsupported checks become `NOT_SUPPORTED`, not `FAIL`.

---

# 12. Production Diagnostic Registry

The production registry should contain **150+ deterministic/guided checks**, but each phone runs only the applicable subset. The registry is dynamic because devices expose different cameras and sensors.

## 12.1 Device / OS / Integrity

1. Manufacturer
2. Model
3. Product name
4. Device name
5. Android release
6. API level
7. Security patch date
8. Build fingerprint
9. Screen resolution
10. Density
11. Refresh-rate availability
12. ABI / architecture
13. Total memory
14. Available memory
15. Total storage
16. Available storage
17. Low-storage condition
18. Play Integrity verdict
19. Root-risk indicators
20. Emulator-risk indicators
21. Debug-build indicator
22. Developer-options indicator
23. Boot integrity signal where available
24. App integrity signal
25. Clock anomaly detection

## 12.2 Display

26. Red full-screen
27. Green full-screen
28. Blue full-screen
29. White full-screen
30. Black full-screen
31. Gray full-screen
32. Gradient test
33. Brightness response
34. Minimum brightness guided check
35. Maximum brightness guided check
36. Color-uniformity guided check
37. Dead-pixel guided check
38. Line artifact guided check
39. Burn-in guided check
40. Flicker complaint capture
41. Orientation portrait
42. Orientation landscape
43. Refresh-rate reported value

## 12.3 Touch

44. Single touch
45. Full-grid coverage
46. Edge coverage
47. Corner coverage
48. Multi-touch count
49. Multi-touch simultaneous points
50. Drag continuity
51. Long-press
52. Swipe horizontal
53. Swipe vertical
54. Gesture responsiveness
55. Dead-zone detection
56. Ghost-touch observation window

## 12.4 Camera

Per exposed camera/lens:

57. Camera enumeration
58. Front camera available
59. Rear camera available
60. Capture success
61. Preview success
62. Autofocus available
63. Autofocus execution
64. Exposure response
65. Flash available
66. Flash on
67. Flash off
68. Photo resolution metadata
69. Video preview initiation
70. Camera switch stability
71. Image blur quality check
72. Extreme-dark capture check
73. Extreme-overexposure check

Dynamic per-lens checks may increase total registry count.

## 12.5 Audio

74. Microphone permission
75. Microphone capture
76. Input amplitude
77. Silence detection
78. Clipping detection
79. Main speaker playback
80. Speech playback
81. Low-frequency playback
82. Mid-frequency playback
83. High-frequency playback
84. Earpiece guided test
85. Audio route change
86. Wired audio route where present
87. Bluetooth audio route where paired
88. Volume-control response

## 12.6 Battery / Charging

89. Battery percentage
90. Charging state
91. Power source
92. Battery temperature
93. Android health status
94. Charge counter where supported
95. Current now where supported
96. Current average where supported
97. Energy counter where supported
98. Cycle count where supported
99. Charge connection transition
100. Charge disconnection transition
101. USB charging detected
102. AC charging detected
103. Wireless charging where exposed
104. Thermal warning state
105. Battery unsupported-field handling

Exact battery state-of-health percentage must only be reported when defensible data is available.

## 12.7 Sensors

106. Accelerometer presence
107. Accelerometer response
108. Accelerometer axis variation
109. Gyroscope presence
110. Gyroscope response
111. Gyroscope axis variation
112. Proximity presence
113. Proximity near
114. Proximity far
115. Ambient light presence
116. Ambient light variation
117. Magnetometer presence
118. Magnetometer response
119. Barometer presence
120. Barometer response
121. Gravity sensor presence
122. Linear acceleration presence
123. Rotation vector presence
124. Step detector presence
125. Step counter presence
126. Sensor timestamp continuity
127. Sensor stale-data detection

## 12.8 Connectivity

128. Wi-Fi hardware present
129. Wi-Fi enabled
130. Wi-Fi connected
131. Internet reachable
132. DNS reachable
133. HTTPS API reachable
134. Bluetooth hardware present
135. Bluetooth enabled
136. Bluetooth scan permission
137. Nearby Bluetooth discovery
138. NFC hardware present
139. NFC enabled
140. GPS/location provider present
141. Location permission
142. Location fix acquired
143. Location accuracy threshold
144. Cellular capability present
145. SIM state where accessible
146. Airplane mode state
147. Network transport type
148. Network handoff observation

## 12.9 Buttons / Haptics / Physical Interaction

149. Volume up
150. Volume down
151. Back navigation
152. Vibration capability
153. Vibration execution
154. Haptic response confirmation
155. Power button guided confirmation
156. Charging-port guided physical check

## 12.10 Storage / Filesystem

157. Storage statistics
158. Temporary file create
159. Temporary file write
160. Temporary file read
161. Temporary file checksum
162. Temporary file delete
163. Low-storage warning
164. Database local-write test

## 12.11 Biometric

165. Biometric hardware availability
166. Biometric enrollment state
167. Biometric authentication prompt
168. Authentication success/failure
169. Biometric temporarily unavailable handling

## 12.12 External Verification

170. IMEI manual capture
171. IMEI OCR scan
172. IMEI format validation
173. Public report IMEI masking
174. PTA verification evidence attachment
175. PTA status inspector confirmation

The exact count is not a marketing claim. The production app should expose a **versioned registry** and report exactly which checks were applicable and performed.

---

# 13. Android Diagnostic Mechanisms

| Area | Android mechanism |
|---|---|
| Touch | Compose pointer input / MotionEvent |
| Display | Full-screen Compose test surfaces |
| Cameras | CameraX + Camera2 capability queries |
| Microphone | AudioRecord |
| Speaker | AudioTrack / MediaPlayer |
| Battery | BatteryManager + battery broadcasts |
| Charging | ACTION_BATTERY_CHANGED / BatteryManager |
| Sensors | SensorManager |
| Wi-Fi | ConnectivityManager / WifiManager |
| Bluetooth | BluetoothManager |
| GPS | Fused Location / LocationManager |
| Biometrics | BiometricManager / BiometricPrompt |
| Storage | StatFs + app-private read/write test |
| Vibration | VibratorManager |
| Buttons | KeyEvent |
| NFC | NfcAdapter |
| Integrity | Play Integrity API |

Production rule: use documented public APIs only.

---

# 14. Permission Strategy

Never ask for every permission at launch.

Request permissions contextually:

- Camera → only when camera test starts
- Microphone → only when mic test starts
- Location → only when GPS test starts
- Nearby devices → only when Bluetooth test starts
- Notifications → only when useful

Each permission screen must explain why it is needed, what data is captured, whether data is uploaded, and whether it is retained.

PhoneProof does not need contacts, SMS, call logs, or full gallery access.

---

# 15. Offline-First Behavior

## 15.1 Offline Capabilities

Target phone can create a local draft inspection, run diagnostics, store results, pause/resume, and queue sync.

## 15.2 Sync

Use Room + WorkManager.

Every mutation gets a local UUID, idempotency key, and sync state:

```text
LOCAL_ONLY
QUEUED
SYNCING
SYNCED
FAILED_RETRYABLE
FAILED_PERMANENT
```

Do not delete local evidence until server acknowledgment.

---

# 16. Scanner Phone Capture Protocol

Required standardized views:

1. Front screen off
2. Front screen on
3. Back
4. Left edge
5. Right edge
6. Top edge
7. Bottom edge
8. Top-left corner
9. Top-right corner
10. Bottom-left corner
11. Bottom-right corner
12. Camera module close-up

The scanner app overlays a silhouette guide.

Before upload, run image-quality checks for blur, exposure, phone visibility, angle, duplicate image, resolution, and glare. Bad images must be rejected before AI inference.

---

# 17. AI / Computer Vision Pipeline

```text
Image Upload
   ↓
Image Quality Validation
   ↓
Device Region Detection
   ↓
Perspective / Crop Normalization
   ↓
YOLO Detection / Segmentation
   ↓
Defect Classes
   ├── crack
   ├── scratch
   ├── dent
   ├── chip
   └── camera_glass_damage
   ↓
Severity Calculation
   ↓
Confidence + Evidence Boxes
   ↓
Inspector Review
```

## 17.1 AI Rules

AI is **advisory**.

Example inference:

```json
{
  "defect": "scratch",
  "confidence": 0.91,
  "severity": "minor",
  "bbox": [0.12, 0.18, 0.48, 0.31]
}
```

It cannot silently override deterministic tests.

## 17.2 Confidence Policy

Initial policy:

- `>= 0.90`: high confidence
- `0.75–0.89`: medium confidence
- `0.55–0.74`: low confidence, require inspector review
- `< 0.55`: do not show as confirmed defect

Thresholds must be calibrated on validation data.

## 17.3 Model Versioning

Every inference stores model version, confidence, input image ID, timestamp, and threshold configuration.

---

# 18. Dataset Plan

For production-quality cosmetic grading, build a local dataset containing clean devices, scratched glass, cracked glass, chipped corners, frame dents, paint wear, back-glass damage, camera-glass damage, different brands, colors, and lighting.

Recommended internal target:

- 500+ unique phones
- 3,000–5,000 standardized images
- balanced clean/defect examples

Public datasets can bootstrap training, but production validation should use locally captured images.

Use CVAT or Label Studio for annotation.

---

# 19. Deterministic Scoring Engine

The PhoneProof Score must be explainable. AI must not invent the score.

## 19.1 Functional Category Weights

Recommended production starting point:

- Display + touch: 25%
- Cameras: 15%
- Battery + charging: 15%
- Audio: 10%
- Sensors: 10%
- Connectivity: 10%
- Storage/system: 10%
- Buttons/haptics: 5%

Only applicable supported checks are included.

## 19.2 Result Values

Example starting policy:

- PASS = 1.0
- FAIL = 0.0
- NOT_SUPPORTED = excluded
- NOT_ACCESSIBLE = excluded but disclosed
- INCONCLUSIVE = review required
- SKIPPED = penalty or incomplete report depending criticality

## 19.3 Grade Bands

- A: 90–100
- B: 80–89
- C: 70–79
- D: 55–69
- E: below 55

## 19.4 Critical Failure Caps

Examples:

- major dead touch region → maximum overall grade C
- main charging failure → maximum D
- severe display line/artifact → maximum C
- main camera unavailable → maximum C
- no microphone capture → maximum C
- severe crack → cosmetic cap D

Rules are stored in versioned backend configuration.

---

# 20. Cosmetic Grade

Functional and cosmetic grades remain separate.

```text
Functional Score: 92/100
Functional Grade: A

Cosmetic Score: 78/100
Cosmetic Grade: C+

Overall PhoneProof Grade: B
```

This prevents a beautiful phone with broken hardware from receiving an artificially high grade.

---

# 21. Price / Claim Estimation

Production v1 should use a transparent baseline, not an opaque “AI price”.

Inputs:

- brand/model
- storage
- PTA status evidence
- age/release year
- functional score
- cosmetic grade
- admin-maintained baseline price
- optional market data later

Example:

```text
estimated_value =
    base_model_price
    × storage_factor
    × functional_condition_factor
    × cosmetic_condition_factor
    × pta_factor
```

Label it clearly as an estimated market range, not a guaranteed sale price.

---

# 22. Backend Database Model

Core tables:

## Identity

- `users`
- `roles`
- `user_roles`
- `organizations`
- `organization_members`
- `devices_registered`

## Inspection

- `inspections`
- `inspection_sessions`
- `paired_devices`
- `device_capabilities`
- `diagnostic_definitions`
- `diagnostic_definition_versions`
- `diagnostic_results`
- `manual_confirmations`
- `inspector_notes`

## Media / AI

- `inspection_images`
- `image_quality_results`
- `cv_inferences`
- `cv_defects`
- `ml_models`
- `ml_model_versions`

## Score / Report

- `score_calculations`
- `score_rule_versions`
- `reports`
- `report_hashes`
- `report_signatures`
- `report_verification_events`

## Business

- `subscriptions`
- `plans`
- `payments`
- `usage_counters`

## Audit

- `audit_events`
- `security_events`

---

# 23. Suggested Inspection Table

```sql
inspections
-----------
id UUID PRIMARY KEY
organization_id UUID NULL
created_by UUID NOT NULL
target_device_uuid UUID NULL
status VARCHAR NOT NULL
purpose VARCHAR NOT NULL
device_brand VARCHAR
device_model VARCHAR
android_version VARCHAR
imei_last4 VARCHAR NULL
imei_encrypted BYTEA NULL
pta_status VARCHAR NULL
functional_score NUMERIC NULL
functional_grade VARCHAR NULL
cosmetic_score NUMERIC NULL
cosmetic_grade VARCHAR NULL
overall_score NUMERIC NULL
overall_grade VARCHAR NULL
score_rule_version INTEGER NULL
created_at TIMESTAMPTZ NOT NULL
finalized_at TIMESTAMPTZ NULL
```

---

# 24. API Surface

Base:

```text
/api/v1
```

## Authentication

```text
POST /auth/register
POST /auth/login
POST /auth/refresh
POST /auth/logout
GET  /auth/me
```

## Inspections

```text
POST /inspections
GET  /inspections/{id}
GET  /inspections
POST /inspections/{id}/pause
POST /inspections/{id}/resume
POST /inspections/{id}/cancel
```

## Pairing

```text
POST /inspections/{id}/pairing-token
POST /pairing/join
GET  /inspections/{id}/pairing-status
```

## Capabilities

```text
POST /inspections/{id}/capabilities
GET  /inspections/{id}/required-checks
```

## Results

```text
POST /inspections/{id}/results
POST /inspections/{id}/results/batch
GET  /inspections/{id}/results
```

Every result POST supports idempotency.

## Images

```text
POST /inspections/{id}/images/presign
POST /inspections/{id}/images/complete
GET  /inspections/{id}/images
```

Prefer presigned upload directly to object storage.

## CV

```text
POST /inspections/{id}/cv/analyze
GET  /inspections/{id}/cv/status
GET  /inspections/{id}/cv/results
```

## Scoring

```text
POST /inspections/{id}/score/recalculate
GET  /inspections/{id}/score
```

## Finalization

```text
POST /inspections/{id}/finalize
```

## Reports

```text
POST /inspections/{id}/reports
GET  /reports/{report_id}
GET  /reports/{report_id}/pdf
GET  /verify/{public_token}
```

---

# 25. Async ML Processing

Do not block FastAPI while YOLO inference runs.

```text
FastAPI
  ↓
Create ML job
  ↓
Redis
  ↓
Celery Worker
  ↓
Download image
  ↓
Run CV inference
  ↓
Persist result
  ↓
Publish completion event
  ↓
Android app refreshes / WebSocket update
```

Implement retries, timeouts, dead-letter handling, and model-version logging.

---

# 26. Report Finalization

Finalization is server-side only.

## 26.1 Canonical Payload

Create canonical JSON containing report ID, inspection ID, device identity, performed checks, statuses, score rule version, scores, AI findings, model version, inspector/organization identity, and timestamps.

## 26.2 Hash

```text
SHA-256(canonical_json)
```

## 26.3 Signature

Use Ed25519 private key on server:

```text
signature = Sign(private_key, report_hash)
```

Store hash, signature, and public key version.

## 26.4 QR

QR points to:

```text
https://verify.phoneproof.pk/r/{public_token}
```

The QR should not contain private device data.

---

# 27. Public Verification Page

Without installing the app, a buyer can see:

- report status: Valid / Revoked
- report ID
- inspection date
- phone model
- masked IMEI if available
- PTA evidence status
- functional score
- cosmetic score
- failed checks
- unsupported checks
- report hash fingerprint
- verification status

Never expose private inspector/user information unnecessarily.

---

# 28. Report PDF

Generated with ReportLab.

Sections:

1. PhoneProof branding
2. Report ID
3. Device identification
4. Inspection timestamp
5. Functional score
6. Cosmetic score
7. Overall grade
8. Critical findings
9. Passed checks
10. Failed checks
11. Not supported/not accessible
12. Cosmetic AI findings
13. PTA evidence
14. QR code
15. Disclaimer
16. Cryptographic verification fingerprint

---

# 29. Authentication and Authorization

## Authentication

- email/password initially
- optional phone OTP later
- short-lived access JWT
- refresh token rotation
- admin MFA

Passwords use Argon2id.

## Authorization

RBAC:

- buyer
- seller
- inspector
- organization_admin
- system_admin

Every inspection access is checked server-side. Never trust a role sent by the Android client.

---

# 30. Security

Production controls:

- TLS everywhere
- encrypted database storage
- encrypted object storage
- presigned S3 URLs
- Android Keystore
- Play Integrity
- API rate limiting
- brute-force protection
- refresh token rotation
- server-side authorization
- immutable audit logs
- report signatures
- idempotency keys
- input validation
- MIME/type checking
- image decompression limits
- secret management
- dependency scanning
- SAST
- backup encryption

---

# 31. Data Privacy

Collect the minimum needed.

Do not collect:

- contacts
- SMS
- call logs
- unrelated gallery images
- WhatsApp data
- personal documents

IMEI:

- avoid storing full IMEI unless necessary
- encrypt if stored
- public report shows masked value
- use salted hash for duplicate detection where appropriate

Raw microphone test recordings should normally be deleted immediately after the test unless explicit consent and a valid product reason exist.

---

# 32. Auditability

Every important event writes an audit event.

```json
{
  "event": "DIAGNOSTIC_RESULT_RECORDED",
  "inspection_id": "...",
  "actor_id": "...",
  "check_id": "touch.grid.coverage",
  "result": "PASS",
  "timestamp": "...",
  "app_version": "1.0.0"
}
```

Audit login, inspection creation, pairing, permission denial, results, image upload, AI output, score calculation, manual override, finalization, verification, and revocation.

---

# 33. Manual Overrides

An inspector may add a note but should not silently change raw evidence.

If override is allowed:

```text
Original result: FAIL
Inspector override: PASS
Reason: ...
Evidence: ...
```

Both remain visible in audit history.

---

# 34. Anti-Fraud Measures

Verified inspection mode:

- no gallery upload for required cosmetic images
- live in-app camera capture
- server session nonce
- capture timestamps
- target/scanner pairing
- duplicate-image detection
- perceptual image hashing
- inspection expiry
- Play Integrity verdict
- device/app integrity signals
- final report immutable after signing

A new inspection creates a new report version, not an edit of the old signed report.

---

# 35. UX Structure

Bottom navigation:

1. Home
2. Inspections
3. Scan / Pair
4. Reports
5. Account

Inspection Wizard:

```text
Start
→ Device Profile
→ Capability Scan
→ Display
→ Touch
→ Camera
→ Audio
→ Battery/Charging
→ Sensors
→ Connectivity
→ Buttons/Haptics
→ IMEI/PTA
→ Pair Scanner
→ Cosmetic Capture
→ AI Review
→ Score Preview
→ Finalize
→ QR Report
```

Show progress, estimated remaining time, permission explanation, retry, skip rules, and pause.

---

# 36. Accessibility

Target:

- strong contrast
- scalable text
- TalkBack labels
- no color-only pass/fail communication
- large touch targets
- clear error messages
- vibration + text alternatives
- localization-ready strings

Initial languages: English first, Urdu later, optional Roman Urdu guidance.

---

# 37. Error Handling

Every diagnostic must handle permission denied, hardware missing, timeout, app backgrounding, no sensor events, network offline, upload interruption, and ML worker unavailability.

Example:

```text
Microphone Test
Unable to run because microphone permission was denied.

[Grant Permission]
[Skip]
```

Do not crash the inspection.

---

# 38. Observability

Android:

- Firebase Crashlytics or Sentry
- non-sensitive analytics
- performance tracing

Backend:

- Sentry
- structured JSON logs
- request IDs
- OpenTelemetry
- uptime monitoring
- DB metrics
- task queue depth
- ML latency
- report generation failures

Business metrics:

- inspections started
- inspections completed
- average completion time
- permission denial rate
- diagnostic failure frequency
- report views
- QR verification scans
- paid conversions

---

# 39. Database Backups and Recovery

Production:

- daily automated backups
- point-in-time recovery if available
- monthly restore test
- object storage versioning
- report signing keys backed up securely
- documented disaster recovery runbook

Early targets:

- RPO: <= 24 hours
- RTO: <= 4 hours

---

# 40. CI/CD

## Android CI

On pull request:

- ktlint
- detekt
- unit tests
- Compose tests where possible
- debug APK build

On release:

- signed AAB from protected CI secret
- upload to Play internal/closed track

## Backend CI

On pull request:

- Ruff
- mypy
- pytest
- migration validation
- security dependency scan
- Docker build

On main:

- deploy staging
- smoke tests
- manual approval
- deploy production

## ML CI

- preprocessing tests
- model evaluation gate
- model checksum
- registry metadata
- block promotion if validation metrics regress beyond threshold

---

# 41. Test Strategy

## Android Unit Tests

Test score mapping, state transitions, capability filtering, serialization, offline queue, and retry behavior.

## Instrumentation Tests

Use real devices for permissions, CameraX, AudioRecord, SensorManager, Bluetooth, GPS, WorkManager, and app restart/resume.

## Device Matrix

Before public release, test on at least:

- Samsung A-series
- Samsung S-series if available
- Xiaomi/Redmi
- Poco
- Oppo
- Vivo
- Tecno
- Infinix
- Pixel if available
- multiple Android OS versions

## Backend

Test auth, authorization, idempotency, inspection state machine, report immutability, hash reproducibility, signature verification, upload abuse, and rate limits.

## ML

Measure precision, recall, F1, mAP, per-class metrics, false-positive rate, false-negative rate, and confidence calibration.

---

# 42. Performance Targets

Initial production targets:

- API p95 response < 500 ms for normal CRUD
- session creation < 1 second
- report generation < 5 seconds after data is ready
- QR verification page < 2 seconds
- resumable/retriable image uploads
- CV inference target < 10 seconds per image batch under normal load
- Android crash-free sessions > 99.5%
- report hash verification deterministic 100%

---

# 43. Scaling Strategy

Early:

```text
1 API service
1 worker service
1 PostgreSQL
1 Redis
Object storage
```

At growth:

- horizontal FastAPI scaling
- separate CV worker pool
- GPU inference workers
- CDN for public reports
- read replicas
- image lifecycle rules
- queue priority
- organization-level rate limits

Do not build microservices prematurely.

---

# 44. Subscription and Payments

Production billing model can support:

- free trial inspections
- per-report payment
- dealer subscription
- enterprise contract

Backend tables:

- plan
- subscription
- usage
- invoice/payment

Pakistan payment providers should be integrated through an abstraction layer so Easypaisa/JazzCash/card providers can be changed without touching inspection logic.

---

# 45. Admin Dashboard

Must support:

- search users
- inspect organizations
- view inspection metadata
- revoke report
- manage plans
- manage diagnostic definition versions
- manage score rule versions
- manage ML model versions
- review flagged AI results
- export anonymized analytics
- view system health

Admin actions are fully audited.

---

# 46. Real Production Development Plan — 20 Weeks

For a **2-partner team**:

## Partner A

Primary responsibility: Android, diagnostics engine, pairing UI, offline sync, scanner capture, Compose UI.

## Partner B

Primary responsibility: FastAPI, PostgreSQL, scoring, reports, ML pipeline, deployment.

Shared: QA, device testing, security, dataset, field pilots.

## Phase 1 — Weeks 1–2: Foundation

Deliver repository, Android modules, backend skeleton, PostgreSQL, Alembic, Docker Compose, staging, auth, CI.

**Exit:** Android login communicates with real staging backend.

## Phase 2 — Weeks 3–5: Inspection Core

Deliver inspection state machine, Room persistence, capability scanner, diagnostics definition registry, batch result sync, pause/resume, and first 40–50 checks.

**Exit:** inspection survives restart and backend persists results.

## Phase 3 — Weeks 6–8: Full Functional Diagnostics

Deliver display/touch, camera, audio, battery/charging, sensors, connectivity, storage, buttons/haptics, biometric, integrity.

**Exit:** cross-brand inspection completes on at least five device models.

## Phase 4 — Weeks 9–10: Two-Device Pairing

Deliver pairing QR, short-lived token, scanner mode, capture checklist, image-quality validation, object-storage upload.

**Exit:** scanner photos are attached to the correct target inspection.

## Phase 5 — Weeks 11–13: AI/CV

Deliver dataset, annotations, training pipeline, YOLO model, async worker, confidence policy, inspector review.

**Exit:** model runs from production-like worker and stores versioned inferences.

## Phase 6 — Weeks 14–15: Scoring and Reports

Deliver deterministic score engine, critical caps, grade, PDF, canonical JSON, SHA-256, Ed25519 signature, QR, public verification page.

**Exit:** modifying report payload breaks verification.

## Phase 7 — Weeks 16–17: Security + Billing + Admin

Deliver RBAC, rate limits, Play Integrity, admin panel, subscription usage counters, privacy controls, audit review, security testing.

**Exit:** staging security checklist passed.

## Phase 8 — Weeks 18–19: Field Pilot

Deliver 10+ Android-model matrix if possible, reseller pilot, 100+ inspections, crash analytics, performance tuning, AI error review, UX improvements.

**Exit:** no critical data-loss bug.

## Phase 9 — Week 20: Production Release

Deliver production infrastructure, verified backups, monitoring alerts, Play Store release track, privacy policy, terms, support, operational runbook, and incident response plan.

---

# 47. Production Release Checklist

- [ ] API HTTPS only
- [ ] Database backup enabled
- [ ] Restore tested
- [ ] Secrets moved to secret manager
- [ ] Admin MFA
- [ ] Rate limiting
- [ ] Play Integrity
- [ ] Privacy policy
- [ ] Terms
- [ ] Report disclaimer
- [ ] Object storage private by default
- [ ] Public report exposes minimum data
- [ ] QR verification tested
- [ ] Report signatures tested
- [ ] Offline sync tested
- [ ] Permission denial tested
- [ ] 10-device regression set
- [ ] AI confidence thresholds validated
- [ ] Crash reporting active
- [ ] Production alerts active
- [ ] Dependency scan clean
- [ ] Database migrations rehearsed

---

# 48. What Makes This a Real App Instead of a Prototype

A prototype may hard-code fake results, simulate diagnostics, lose data after restart, generate local-only PDFs, use one device, have no real authentication, and have no monitoring.

The real PhoneProof production app must:

- execute genuine Android checks,
- persist evidence,
- capability-gate unsupported checks,
- sync reliably,
- use a real backend,
- have real authentication,
- have role authorization,
- store versioned check definitions,
- pair two physical devices securely,
- run real ML inference,
- keep immutable audit history,
- calculate deterministic scores,
- cryptographically sign reports,
- verify reports online,
- survive network failure,
- recover from crashes,
- monitor production,
- back up user data,
- deploy through CI/CD.

---

# 49. Features That Must Not Be Faked

If Android does not expose a value, the production app must return:

```text
NOT ACCESSIBLE ON THIS DEVICE
```

Do not invent battery-health percentage, OEM screen authenticity, OEM battery authenticity, repair history, motherboard health, exact internal water damage, or hidden hardware history.

---

# 50. Production Report Example

```text
PhoneProof PK
Verified Device Condition Report

Report ID: PP-PK-2026-09-18382
Inspection: 18 Sep 2026

Device
Samsung Galaxy A54
Android 16

FUNCTIONAL
Score: 91/100
Grade: A

Passed: 63
Failed: 2
Not Supported: 9
Not Accessible: 3

Critical findings:
- Proximity response intermittent
- Minor charging-current instability

COSMETIC
Score: 84/100
Grade: B

AI findings:
- Minor rear-panel scratch — confidence 0.94
- Small frame dent — confidence 0.87

PTA
Evidence recorded: Approved

Report verification:
https://verify.phoneproof.pk/r/...

SHA-256 Fingerprint:
8F:2A:...

Scan QR to verify the server-signed report.
```

---

# 51. Operational Rules

1. Never call an unsupported feature failed.
2. Never let AI override raw diagnostics.
3. Never silently edit a finalized report.
4. Never expose full IMEI publicly.
5. Never upload unrelated personal data.
6. Never claim certainty where only a guided test was performed.
7. Every rule/model/check version must be recorded.
8. Every critical manual override must be audited.
9. A revoked report remains visible as revoked.
10. A new inspection creates a new report.

---

# 52. Recommended First Production Scope

Even though the registry can contain 150+ checks, the first public release should prioritize reliability.

Must be production-grade:

- capability scan
- display
- touch
- front/rear camera
- flash
- microphone
- speakers
- vibration
- buttons
- battery
- charging
- storage
- accelerometer
- gyroscope
- proximity
- light
- magnetometer
- Wi-Fi
- Bluetooth
- GPS
- biometric
- device integrity
- scanner pairing
- 12-view cosmetic capture
- AI cosmetic analysis
- score engine
- PTA evidence
- QR report
- PDF
- report verification
- audit logs
- offline sync
- auth
- admin
- subscriptions

Additional dynamic diagnostics stay enabled only after device-matrix testing proves they are stable.

---

# 53. Acceptance Criteria for Real Production v1

## Functional

- 95%+ of required inspection flows complete without app crash on supported test devices.
- All supported checks produce valid results or explicit inconclusive states.
- Data survives app restart.
- Pairing works with short-lived secure tokens.
- AI results are versioned and reviewable.
- Final score is deterministic.
- QR report is verifiable.

## Security

- No sensitive keys in APK.
- No unauthorized inspection access.
- Signed reports reject modified payloads.
- Full IMEI is not public.
- Admin actions are audited.

## Operations

- CI/CD deploys staging reliably.
- Production backup restore is tested.
- Crash/error monitoring is live.
- ML queue failures retry safely.
- Report generation is idempotent.

## Product

- A real buyer/dealer can complete an inspection without developer assistance.
- Inspection result is understandable.
- Report clearly distinguishes measured facts, user confirmations, AI observations, and unavailable information.

---

# 54. Final Build Recommendation

For a real-world PhoneProof app, do **not** attempt to recreate every enterprise Phonecheck capability in one release.

Build a stable platform with versioned deterministic diagnostics, capability detection, two-device evidence capture, AI cosmetic analysis, deterministic scoring, cryptographically verifiable reports, offline reliability, real backend/auth/storage, monitoring, and security.

Then expand with:

- iOS companion workflows,
- marketplace APIs,
- advanced trade-in pricing,
- partner inspection stations,
- enterprise batch processing,
- deeper OEM integrations where officially available.

The architecture above allows those additions without rewriting the core system.

---

# 55. External Platform Notes

As of September 2026:

- Google Play requires new Android apps and app updates to target **Android 16 / API level 36 or higher**.
- Use the **Play Integrity API** for app/device integrity signals rather than custom security bypasses.
- Background location is not required for PhoneProof and should not be requested.
- Camera, microphone, location, Bluetooth, and related protected features use normal Android runtime permission flows.

Official references:

- Android target API requirement: https://developer.android.com/google/play/requirements/target-sdk
- Play Integrity API: https://developer.android.com/google/play/integrity
- Android permissions: https://developer.android.com/guide/topics/permissions/overview

---

# 56. Final Definition

> **PhoneProof PK Production v1 is a real two-device Android inspection platform that executes capability-aware functional diagnostics on a used phone, captures standardized exterior evidence from a paired scanner phone, performs server-side AI-assisted cosmetic analysis, computes a deterministic and auditable condition score, and publishes a cryptographically signed QR-verifiable report through a secure production backend.**

That is the product the development team should build.
