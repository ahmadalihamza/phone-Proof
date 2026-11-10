# PHONEPROOF PK — MASTER PRODUCTION DEVELOPMENT 

You are acting as the complete senior engineering team for a commercial Android product:

# PhoneProof PK

You are simultaneously responsible for:

* Senior Android/Kotlin engineering
* Android hardware diagnostics
* Backend engineering
* AI/Computer Vision engineering
* Database architecture
* Security engineering
* UX/UI engineering
* QA/testing
* DevOps/deployment
* Product engineering
* Technical documentation

I am providing you with an existing document:

`PhoneProof_PK_Real_World_Production_App_Blueprint(1).md`

## 1. FIRST RULE — USE THE BLUEPRINT AS THE BASELINE

Read the entire blueprint before changing or generating code.

The blueprint is the authoritative baseline architecture.

Do NOT remove existing legitimate functionality.

Do NOT convert this project back into a simple prototype.

Do NOT replace real diagnostics with random/mock values.

Do NOT fake successful tests.

Enhance the blueprint substantially, especially the diagnostics engine and hardware/software inspection capabilities.

The finished project must remain:

* Android app based
* Kotlin based
* Jetpack Compose based
* Android 10+ compatible where APIs allow
* `compileSdk = 36`
* `targetSdk = 36`
* capability-aware
* backend-connected
* offline tolerant
* production structured
* security conscious
* suitable for inspection of used Android phones

---

# 2. PRODUCT OBJECTIVE

PhoneProof PK should become a practical used-phone inspection platform for:

* buyers
* sellers
* mobile shops
* phone dealers
* trade-in businesses
* marketplaces
* professional device inspectors

A user should install/open PhoneProof on a phone being inspected and receive a detailed evidence-based report explaining which hardware/software functions work, which fail, which could not be accessed, and which are not supported.

A second Android phone may operate as Scanner Mode for external/cosmetic examination.

The product must feel like a real commercial diagnostic product rather than a university demo.

---

# 3. ABSOLUTELY NO FAKE DIAGNOSTICS

Never generate:

* fake battery health
* fake battery cycle count
* fake OEM screen authenticity
* fake original/replaced battery detection
* fake motherboard health
* fake liquid damage result
* fake repair history
* fake storage/NAND health
* fake charging wattage
* fake IMEI
* fake camera quality
* fake sensor results
* fake benchmark data
* fake PTA status
* fake cosmetic AI detection

If Android cannot expose something, use:

`NOT_ACCESSIBLE`

If hardware does not exist:

`NOT_SUPPORTED`

If the test cannot reach a reliable conclusion:

`INCONCLUSIVE`

If permission is denied:

`NOT_ACCESSIBLE`

If user intentionally skips it:

`SKIPPED_BY_USER`

Actual result states remain:

* PASS
* FAIL
* NOT_SUPPORTED
* NOT_ACCESSIBLE
* SKIPPED_BY_USER
* INCONCLUSIVE

Internally the diagnostic registry may additionally track whether a diagnostic implementation is:

* ENABLED
* EXPERIMENTAL
* DISABLED

But do not expose unimplemented diagnostics as if they had been tested.

---

# 4. MAJOR CHANGE REQUIRED — INDIVIDUAL DIAGNOSTIC RESULTS

The previous prototype showed things such as:

`Display: 8/8 passed`

or

`Hardware: 15/15 passed`

THIS IS NOT ACCEPTABLE AS THE PRIMARY RESULT.

Category totals may appear as secondary summaries, but every diagnostic must be visible separately.

For example:

Display

✓ Red Pixel Test — PASS
✓ Green Pixel Test — PASS
✓ Blue Pixel Test — PASS
✓ White Uniformity — PASS
✕ Dead Pixel Test — FAIL
✓ Brightness Response — PASS
— HDR Support — NOT_SUPPORTED
✓ 120 Hz Mode Detected — PASS
✓ Wide Color Gamut — PASS
? Burn-In Inspection — INCONCLUSIVE

Touch

✓ Single Touch — PASS
✓ Multi-Touch — PASS
✓ Edge Touch — PASS
✕ Bottom-Right Dead Zone — FAIL
✓ Drag Continuity — PASS
✓ Long Press — PASS
✓ Ghost Touch Observation — PASS

Every diagnostic row should show, where applicable:

* diagnostic name
* status
* measured value
* expected condition/range
* diagnostic method
* supporting evidence
* timestamp
* retry button
* explanation when inaccessible
* confidence for AI/guided checks

Users must be able to expand a row to see technical details.

Never hide individual failures behind a percentage.

---

# 5. BUILD AN EXPANDED DIAGNOSTIC REGISTRY

Expand the existing registry from the blueprint.

Target approximately **250+ possible/versioned diagnostic definitions**, while understanding that an individual phone will run only the applicable subset.

Do NOT market the product using a misleading fixed number because Android devices differ.

Diagnostics must be capability-gated dynamically.

---

# 6. DEVICE IDENTITY AND SYSTEM PROFILE

Implement detailed device inspection including accessible information such as:

* manufacturer
* brand
* model
* product
* device codename
* Android version
* API level
* build ID
* build fingerprint
* security patch date
* supported ABIs
* CPU architecture
* processor count where accessible
* RAM total
* RAM currently available
* low-memory state
* total internal storage
* available internal storage
* screen resolution
* screen density
* orientation support
* supported system features
* verified-boot feature availability
* hardware-backed Keystore availability
* StrongBox availability
* boot/integrity information accessible through legitimate APIs
* Play Integrity verdict where configured
* developer options risk signal where legitimately accessible
* root-risk indicators without attempting privilege escalation
* emulator indicators
* app/debug build integrity
* suspicious system clock anomalies

Clearly distinguish:

`Device information`

from

`Tested functionality`.

---

# 7. DISPLAY INSPECTION

Significantly enhance display testing.

Include:

* red screen
* green screen
* blue screen
* white screen
* black screen
* gray screen
* gradient
* grayscale gradient
* color banding observation
* dead pixel guided inspection
* stuck pixel guided inspection
* line artifact detection
* discoloration observation
* display uniformity
* brightness increase response
* brightness decrease response
* minimum brightness
* maximum brightness
* auto-brightness guided test where appropriate
* screen orientation
* supported refresh-rate modes
* active refresh rate
* adaptive refresh-rate capability where exposed
* HDR capability
* supported HDR formats where exposed
* wide-color-gamut support
* supported display modes
* resolution/mode combinations
* flicker observation
* burn-in/image-retention guided test
* edge discoloration
* touchscreen/display coordinate alignment
* screen-on state behavior

Do not claim objective panel luminance without calibrated external hardware.

Do not claim OLED/LCD physical health unless actually inferable.

---

# 8. ADVANCED TOUCH TESTING

Add detailed touch quality testing:

* basic single touch
* complete grid coverage
* horizontal coverage
* vertical coverage
* edge coverage
* corner coverage
* multi-touch support
* maximum simultaneously observed pointers
* two-finger gesture
* three-finger test
* drag continuity
* diagonal drag
* horizontal swipe
* vertical swipe
* long press
* rapid tap
* touch-coordinate continuity
* dead-zone detection
* ghost-touch observation
* unintended-touch observation
* duplicate event anomalies
* irregular event intervals
* touch jitter indicator
* responsiveness indicator
* touch/display coordinate mismatch

Do not advertise an exact hardware touch sampling rate unless Android actually provides defensible data.

If estimating event interval behavior, label it:

`Observed touch event interval`

not

`Official touch sampling rate`.

---

# 9. CAMERA SYSTEM — PER CAMERA / PER LENS

Do not treat “camera” as one single test.

Enumerate every exposed logical/physical camera where Android makes it available.

For each camera/lens inspect appropriate capabilities including:

* camera ID
* facing direction
* logical/physical camera relationship where exposed
* Camera2 hardware level
* preview initialization
* capture success
* supported output sizes
* selected maximum practical photo size
* focal lengths
* aperture values where available
* minimum focus distance where available
* autofocus capability
* autofocus operation
* autofocus stability
* focus transition
* fixed-focus status
* flash availability
* torch availability
* torch on/off
* zoom capability
* maximum digital zoom
* optical stabilization capability
* OIS operation metadata where supported
* electronic/video stabilization capability
* RAW capability
* manual sensor capability
* high-speed video capability
* face detection capability
* exposure adjustment
* auto exposure response
* white balance response
* dark-frame quality indicator
* overexposure indicator
* image sharpness indicator
* blur detection
* excessive image-noise indicator
* camera obstruction indicator
* abnormal black-frame detection
* abnormal white-frame detection
* image corruption detection
* frame-freeze observation
* preview stability
* repeated camera switching
* front/rear switching stability
* video-preview initialization
* camera open/close stress sequence

Quality metrics must be described as diagnostic indicators, not laboratory certification.

---

# 10. AUDIO SYSTEM

Expand audio inspection.

Implement where feasible:

Microphone:

* microphone hardware presence
* permission
* AudioRecord initialization
* actual sample capture
* amplitude
* silence detection
* clipping
* dominant frequency response to controlled test tones where appropriate
* input activity
* excessive background-noise indicator
* microphone stream continuity
* recording corruption detection

Speaker:

* media speaker playback
* left/right behavior where independently addressable
* low-frequency tone
* mid-frequency tone
* high-frequency tone
* speech sample
* distortion observation
* rattling guided observation
* volume increase
* volume decrease
* mute state
* maximum-volume playback confirmation

Earpiece:

* guided earpiece test where Android routing permits

Audio routing:

* speaker
* earpiece
* wired audio when attached
* Bluetooth route when attached
* output-device enumeration
* input-device enumeration

Where environmental noise makes automatic quality judgement unreliable, use:

`GUIDED_AUTOMATIC`

or

`USER_CONFIRMED`

instead of pretending to measure objective speaker quality.

---

# 11. BATTERY INSPECTION

Implement every legitimate battery field exposed on that particular device.

Possible measurements include:

* battery percentage
* charging/discharging status
* plugged state
* AC charging
* USB charging
* wireless charging where exposed
* battery temperature
* Android battery health status
* charge counter
* instantaneous current
* average current
* remaining energy counter
* cycle count where the OS/device exposes it
* battery-low state
* charging-state transitions
* unplugging transition
* charge stability sampling
* current stability sampling
* abnormal battery temperature warning
* thermal throttling state
* charging behavior over a short sampling window

Never calculate or display an authoritative battery-health percentage merely from assumptions.

If defensible information is available, you may show:

`Battery Condition Indicator`

with:

* source
* method
* available measurements
* confidence
* limitations

Do not label it `Battery Health 87%` unless the value is genuinely provided/derived from defensible manufacturer/platform information.

---

# 12. CHARGING AND USB PORT

Add:

* charger connection detection
* charger disconnection detection
* AC source
* USB source
* wireless source
* charging status transition
* current behavior if available
* unstable connection observation
* repeated connection/disconnection anomaly
* USB-host feature support
* connected USB peripheral detection
* USB accessory detection
* OTG capability where exposed/testable
* external USB device communication test when a test accessory exists

Do NOT claim data-pin health from a charger-only connection.

If testing USB data communication requires an external accessory, clearly show:

`External accessory required`.

---

# 13. STORAGE / FILESYSTEM QUALITY

Implement:

* total storage
* available storage
* storage percentage used
* low-storage state
* app-private test file creation
* file write
* flush
* file read
* checksum validation
* file overwrite
* file deletion
* SQLite/Room write
* SQLite/Room read
* SQLite transaction
* temporary sequential write performance
* temporary sequential read performance
* lightweight random access test if safe
* filesystem error handling

Performance measurements must be labeled:

`Performance snapshot`

because background load, temperature and OS state affect performance.

Never call this NAND/UFS health.

---

# 14. MEMORY / COMPUTE / GRAPHICS PROFILE

Where legitimately accessible add:

* available processor count
* supported ABI
* system RAM
* available RAM
* low-memory flag
* memory allocation test with safe limits
* short CPU computation benchmark
* CPU responsiveness snapshot
* graphics/OpenGL ES capability
* OpenGL ES version
* accessible GPU renderer/vendor information
* Vulkan feature/version information where available
* hardware accelerated rendering capability
* safe graphics rendering test

Again:

`performance snapshot`

not

`hardware lifespan`.

Do not run aggressive stress tests that could overheat or damage the phone.

---

# 15. THERMAL SYSTEM

Use public Android thermal APIs.

Add:

* current system thermal status
* thermal status change observation
* thermal throttling category
* thermal headroom where legitimately available
* battery temperature
* thermal warning
* thermal state during short safe benchmark

Do not depend on privileged APIs requiring device-owner/system privileges.

Do not attempt security bypasses to obtain CPU/GPU temperature.

---

# 16. SENSOR INSPECTION

Enumerate all exposed sensors dynamically.

Support appropriate tests for:

* accelerometer
* gyroscope
* proximity
* ambient light
* magnetometer
* barometer
* gravity
* linear acceleration
* rotation vector
* game rotation vector
* step detector
* step counter
* significant motion where supported
* relative humidity where present
* ambient temperature where present
* hinge angle/foldable sensor where exposed
* additional manufacturer-exposed standard Android sensors

For applicable sensors record:

* presence
* vendor
* version
* reporting mode
* resolution
* maximum range
* power metadata
* minimum delay
* response
* timestamp continuity
* stale data
* axis variation
* no-response condition
* observed sampling interval
* abnormal noise
* stationary drift indicator
* sensor accuracy events
* repeated constant-value anomaly

Do not fail a device merely because an optional sensor does not exist.

---

# 17. GPS / GNSS

Expand GPS diagnostics:

* GPS/location feature presence
* permission
* location service enabled
* provider availability
* location fix acquisition
* time to first fix
* latitude/longitude accuracy value
* altitude where available
* speed where available
* bearing where available
* GNSS satellite status where permitted
* satellites observed
* satellites used in fix where available
* repeated fix stability
* accuracy improvement over time
* timeout handling
* stale location detection

Do not upload/store precise location unnecessarily.

The diagnostic report should not publicly expose inspection coordinates.

---

# 18. WI-FI

Add:

* Wi-Fi hardware presence
* enabled state
* current connection
* validated internet connection
* RSSI/signal strength where accessible
* link speed
* receive link speed where available
* transmit link speed where available
* frequency/band
* Wi-Fi standard where available
* IP acquisition
* DNS resolution
* HTTPS reachability
* backend API reachability
* latency sampling
* connection stability
* reconnect observation where safe

Do not scan surrounding networks unnecessarily.

Follow modern Android Wi-Fi privacy restrictions.

---

# 19. BLUETOOTH / BLE

Add:

* Bluetooth hardware presence
* Bluetooth enabled
* classic Bluetooth capability
* BLE capability
* Bluetooth permission
* nearby-device permission handling
* discovery
* BLE scan where supported
* nearby known scanner-phone discovery
* connection workflow
* Bluetooth audio route where appropriate
* BLE advertising capability where exposed
* repeated scan stability

Use the paired PhoneProof scanner device as a real verification target where useful.

---

# 20. CELLULAR / SIM / ESIM

Within Android privacy restrictions inspect accessible values only.

Possible checks:

* telephony capability
* radio-related system features
* SIM presence/status where accessible
* number of active subscriptions where permission allows
* network operator metadata where permitted
* cellular transport active
* mobile data connectivity
* network type/generation where exposed
* signal information where legitimately available
* roaming state where available
* eSIM/eUICC feature capability
* multi-SIM capability where exposed
* airplane mode state

Do not request invasive phone permissions without a strong reason.

Do not collect call logs or SMS.

Do not pretend the app can make unrestricted modem-level diagnostics.

---

# 21. NFC / UWB / SPECIAL RADIOS

Where hardware exists:

NFC:

* NFC presence
* enabled state
* reader capability
* HCE capability where exposed
* actual NFC-tag read test when a physical tag is available

UWB:

* capability detection where supported by Android/device

A physical NFC test requires an NFC tag.

Do not mark it failed simply because no tag was provided.

---

# 22. BIOMETRICS

Inspect:

* biometric capability
* fingerprint hardware feature
* face biometric feature where exposed
* enrollment state
* authenticator availability
* BiometricPrompt launch
* successful authentication
* authentication failure
* lockout/temporarily unavailable handling

Do not claim the fingerprint sensor is healthy simply because the hardware feature exists.

Successful real authentication is stronger evidence.

---

# 23. BUTTONS / HAPTICS / USER INTERACTION

Add:

* volume up
* volume down
* navigation/back interaction
* power-button guided confirmation
* vibration capability
* vibration execution
* haptic feedback
* multiple vibration primitive capability where exposed
* accelerometer-observed vibration confirmation where practical
* orientation change
* rotation lock awareness

Hardware power-button events may not be directly delivered to normal apps, therefore use guided verification instead of fake detection.

---

# 24. SOFTWARE AND OS CONDITION

Create a `Software & System Health` section.

Include defensible checks such as:

* Android release
* API level
* security-patch age
* build fingerprint
* verified boot feature capability
* Play Integrity results where configured
* root-risk indicators
* emulator-risk indicators
* available storage
* memory pressure
* developer/debug risk indicators where accessible
* system clock anomaly
* connectivity stack functionality
* encryption/security capability metadata where public APIs expose it
* hardware-backed Keystore
* StrongBox
* DRM capability
* DRM supported crypto schemes
* Widevine/DRM security information where legitimately obtainable
* WebView/runtime information where appropriate and accessible
* required system services availability

Do NOT enumerate the user's private installed applications without a valid product reason and policy-compliant approach.

Do NOT collect personal application usage.

---

# 25. HARDWARE SECURITY CAPABILITIES

Add a security-capabilities section containing appropriate checks such as:

* Verified Boot feature support
* hardware-backed Android Keystore
* StrongBox availability
* biometric security availability
* Play Integrity
* application integrity
* device integrity
* build integrity indicators
* DRM hardware security capability
* supported DRM crypto schemes

Describe these as security capabilities/signals.

Do not turn them into unsupported claims such as:

`Motherboard original`.

---

# 26. HARDWARE QUALITY INDICATORS

This is an important new PhoneProof feature.

Create a separate:

# Hardware Quality Indicators

These are evidence-based quality observations from measurable tests.

Possible indicators:

### Display Quality

* dead/stuck pixels
* uniformity observations
* line defects
* burn-in observation
* refresh modes
* color anomalies

### Touch Quality

* coverage percentage
* dead zones
* drag continuity
* ghost touch
* touch jitter

### Camera Quality

* autofocus behavior
* image sharpness
* blur
* exposure
* image noise indicator
* black-frame anomaly
* camera switching stability

### Audio Quality

* microphone activity
* clipping
* silence
* speaker frequency-response presence
* distortion/rattle guided observation

### Sensor Quality

* stale readings
* abnormal noise
* drift
* missing axis response

### Charging Quality

* connection stability
* charging transition
* current stability where measurable
* abnormal heating

### Storage Quality

* read/write correctness
* checksum integrity
* performance snapshot

Do NOT transform these indicators into claims Android cannot prove.

---

# 27. COSMETIC INSPECTION WITH SECOND PHONE

Preserve the blueprint's two-device architecture.

Scanner phone captures at minimum:

1. Screen off/front
2. Screen on/front
3. Back
4. Left side
5. Right side
6. Top
7. Bottom
8. Top-left corner
9. Top-right corner
10. Bottom-left corner
11. Bottom-right corner
12. Camera-module close-up

Improve this with image-quality validation:

* blur
* underexposure
* overexposure
* glare
* phone not fully visible
* incorrect angle
* duplicate image
* insufficient resolution
* subject too small
* subject outside framing guide
* camera module not visible when required

Reject bad captures before analysis.

---

# 28. AI COMPUTER VISION

Keep architecture for cosmetic AI.

Potential defect classes:

* front-glass crack
* back-glass crack
* scratch
* deep scratch
* dent
* chipped corner
* frame deformation
* paint wear
* camera-glass damage
* camera-ring damage
* back-panel damage
* screen-edge damage

Pipeline:

Capture
→ Image QC
→ Phone detection
→ Perspective correction
→ Region segmentation
→ defect detection/segmentation
→ severity
→ confidence
→ inspector confirmation
→ report

Use YOLO/OpenCV/PyTorch only with a genuine model.

If validated PhoneProof weights are not available, do NOT create fake predictions.

Instead:

1. implement the complete model interface/pipeline;
2. implement real image-quality validation;
3. implement manual inspector defect annotation;
4. ask me for existing model weights/dataset if required;
5. clearly mark the ML defect classifier as Beta/Unavailable until real weights are installed.

Never use generic YOLO weights and pretend they detect phone scratches/cracks unless the model was actually trained for those classes.

---

# 29. RESULT SCREEN DESIGN

Redesign diagnostics UX completely.

The inspection results page must contain:

## Header

Device name
Inspection ID
Completion state
Functional score
Cosmetic score

## Category cards

Example:

Display
`11 PASS • 1 FAIL • 2 NOT SUPPORTED`

Touch
`9 PASS • 1 FAIL`

Camera
`24 PASS • 2 INCONCLUSIVE`

But the card must expand/show ALL individual checks.

Every test row:

`Icon | Test Name | Result | measured value`

Example:

✓ Accelerometer Response
PASS
X/Y/Z axes responding normally

✕ Proximity Sensor
FAIL
No distance change detected during test

— Barometer
NOT_SUPPORTED
This phone does not expose a barometric sensor

? Battery Cycle Count
NOT_ACCESSIBLE
Android/device does not provide this value

Never show only aggregate counts.

---

# 30. TEST DETAIL SCREEN

Tapping any result opens details.

Show:

* test title
* category
* status
* test description
* diagnostic method
* measured values
* expected criteria
* evidence
* device capability
* permission state
* start time
* end time
* diagnostic definition version
* app version
* retry
* notes

For AI:

* confidence
* model version
* bounding box/segmentation evidence
* human review state

---

# 31. SCORING

Keep scoring deterministic.

AI must not invent overall scores.

Only applicable diagnostics participate.

Separate:

* Functional score
* Cosmetic score
* Integrity/security information

Consider expanding functional score categories to:

* Display
* Touch
* Camera
* Battery/Charging
* Audio
* Sensors
* Connectivity
* Storage/System
* Buttons/Haptics

Critical failures may cap the score.

All score rules must be versioned.

The final report must explain why points were lost.

---

# 32. REPORT DESIGN

The PDF and online report must list diagnostics individually.

Required sections:

* PhoneProof branding
* report ID
* inspection date/time
* device identity
* Android/system information
* functional score
* cosmetic score
* critical failures
* warnings
* hardware quality indicators
* individual display diagnostics
* individual touch diagnostics
* individual camera/lens diagnostics
* individual audio diagnostics
* individual battery diagnostics
* individual charging diagnostics
* individual sensor diagnostics
* individual connectivity diagnostics
* individual storage diagnostics
* individual biometric diagnostics
* individual security/integrity signals
* passed checks
* failed checks
* inconclusive checks
* not-supported checks
* not-accessible checks
* cosmetic evidence
* AI findings
* PTA evidence
* masked IMEI
* QR
* report hash
* digital signature
* disclaimer

The report should make it easy for a used-phone buyer to understand exactly what passed and failed.

---

# 33. QR VERIFICATION

Finalized reports must remain server-generated and immutable.

Use:

* canonical report JSON
* SHA-256
* Ed25519 signature
* public report token
* QR verification

Public verification should show:

* valid/revoked
* model
* date
* masked IMEI where available
* functional score
* cosmetic score
* critical failures
* failed checks
* unavailable checks
* report signature verification

Never expose private account information.

---

# 34. PTA / IMEI

IMEI access is restricted on modern Android.

Therefore implement:

* manual IMEI entry
* camera OCR
* format validation
* masking
* encrypted storage only if needed
* hash for duplicate detection where appropriate
* evidence upload
* PTA inspector confirmation

If there is no official usable PTA API available, do not scrape websites or invent an API.

Design the PTA module as an abstraction that can accept an official integration later.

---

# 35. BACKEND

Use the blueprint architecture.

Recommended:

Python 3.12+
FastAPI
PostgreSQL
SQLAlchemy
Alembic
Pydantic
Redis where needed
Celery/background workers where needed
S3-compatible object storage
JWT authentication
Argon2id
structured logging

For a fast initial deployment, infrastructure should also be compatible with:

* Render/Railway
* managed PostgreSQL
* Cloudflare R2/S3-compatible storage
* Redis Cloud

Do not couple business logic to one cloud provider.

---

# 36. OFFLINE-FIRST AND RELIABILITY

Android uses:

* Room
* WorkManager
* local inspection session
* local result persistence
* resumable inspection
* queued synchronization
* idempotency keys

A network failure must never destroy completed diagnostic results.

A crash/restart must allow inspection recovery.

---

# 37. ANDROID ARCHITECTURE

Use clean modular architecture.

Recommended modules/features:

* app
* core/common
* core/networking
* core/database
* core/security
* core/designsystem
* auth
* home
* inspections
* diagnostics
* deviceprofile
* pairing
* scanner
* cosmetic
* reports
* subscription/account

Inside diagnostics organize by:

* engine
* registry
* system
* display
* touch
* camera
* audio
* battery
* charging
* thermal
* sensors
* gnss
* wifi
* bluetooth
* cellular
* nfc
* usb
* storage
* performance
* biometrics
* buttons
* integrity

Use:

* Kotlin
* Jetpack Compose
* Material 3
* ViewModel
* Coroutines/Flow
* Hilt
* Room
* WorkManager
* Retrofit/OkHttp
* Kotlin serialization
* CameraX
* Camera2 where justified
* SensorManager
* BatteryManager
* ConnectivityManager
* WifiManager
* Bluetooth APIs
* BiometricPrompt
* UsbManager
* NfcAdapter
* Location APIs
* Android Keystore

Do not introduce a library unless it provides real value.

---

# 38. UI/UX REQUIREMENT

Do NOT make the interface look like AI-generated dashboard slop.

Avoid:

* excessive gradients
* giant meaningless cards
* huge paragraphs
* unnecessary animations
* excessive explanation on every screen
* chatbot-like UI
* fake graphs
* marketing language during inspection

The interface should look like a serious diagnostic utility.

Design principle:

`See result → understand result → take action`

Use:

* clean typography
* clear spacing
* compact cards
* category icons
* status icons + text
* expandable test rows
* progress
* meaningful buttons
* dark/light theme
* accessibility
* loading/error/empty states

Do not use color alone for PASS/FAIL.

---

# 39. COMPLETE COMMERCIAL, PAYMENT, TRANSACTION & SUBSCRIPTION SYSTEM

PhoneProof PK is intended to become a commercial product.

Do NOT create only placeholder subscription screens.

Build the complete commercial architecture covering:

* free trial
* free inspections
* paid individual inspections
* paid verified reports
* dealer subscriptions
* business subscriptions
* enterprise plans
* transaction history
* invoices/receipts
* usage limits
* payment verification
* refunds
* discounts
* promo codes
* subscription renewal
* subscription expiry
* payment failure
* plan upgrades/downgrades
* admin billing management

The payment system must remain independent from the diagnostic engine.

A payment failure must never corrupt an inspection.

---

# 39.1 USER COMMERCIAL TYPES

Support:

### Individual Buyer

Can:

* register for free
* receive limited free inspection/report allowance
* run inspection
* purchase additional verified reports
* view purchased reports
* download paid reports
* verify reports through QR

### Seller

Can:

* create inspection
* purchase verified condition report
* share report with buyer
* optionally pay per report

### Dealer / Mobile Shop

Can subscribe monthly.

Dealer features may include:

* larger inspection quota
* inspection history
* dealer branding
* verified dealer profile
* multiple staff accounts
* customer reports
* downloadable PDFs
* QR verification
* inventory integration later
* transaction history
* analytics
* priority report generation

### Enterprise / Marketplace

Supports:

* custom contract
* high-volume inspections
* organization accounts
* API access
* multiple branches
* staff roles
* centralized billing
* usage-based billing
* bulk report generation

---

# 39.2 FREE TRIAL SYSTEM

Implement a real configurable free-trial system.

Do not hard-code trial limits inside Android.

Backend configuration should control:

```text
trial_enabled
trial_inspection_limit
trial_report_limit
trial_duration_days
trial_requires_payment_method
trial_available_for_plan
```

Initial recommended configuration:

Individual:

`1 free complete inspection/report`

Dealer:

`3–5 free inspections`

The exact number must be remotely configurable by admin.

Possible user state:

```text
TRIAL_AVAILABLE
TRIAL_ACTIVE
TRIAL_EXHAUSTED
TRIAL_EXPIRED
PAID
```

---

# 39.3 FREE INSPECTION VS PAID VERIFIED REPORT

Allow product strategy to differentiate between:

### Basic Inspection

May be free.

Shows:

* hardware test results
* failures
* basic score
* basic summary

### Verified PhoneProof Report

Paid product.

May include:

* complete inspection results
* full PDF
* QR verification
* cryptographic signature
* online verification
* cosmetic AI results
* transaction receipt
* report permanence
* shareable report URL

Architecture must support changing this business model from admin configuration.

Do NOT lock core logic to one pricing strategy.

---

# 39.4 PAY-PER-INSPECTION / REPORT

Support users who do not want subscriptions.

Example workflow:

```text
Inspection completed
       ↓
Score calculated
       ↓
Preview shown
       ↓
Purchase Verified Report
       ↓
Checkout
       ↓
Payment provider
       ↓
Backend verifies transaction
       ↓
Payment confirmed
       ↓
Report finalized
       ↓
Signed PDF generated
       ↓
QR verification activated
```

The server — never the Android client — determines whether payment was successfully completed.

---

# 39.5 SUBSCRIPTION PLANS

Create configurable plans.

Do NOT hard-code prices inside Android.

Example architecture:

### Free

Possible configuration:

* 1 free inspection
* basic result
* limited history
* no business features

### Individual

Possible configuration:

* several inspections/month
* verified reports
* PDF download
* QR verification
* longer report history

### Dealer Starter

Possible configuration:

* monthly inspection allowance
* verified reports
* dealer dashboard
* inspection history
* customer report sharing

### Dealer Pro

Possible configuration:

* larger inspection quota
* multiple employees
* dealer profile
* analytics
* priority support
* business report branding

### Enterprise

Possible configuration:

* negotiated quota
* organization management
* multi-branch access
* API
* bulk inspections
* SLA
* custom billing

Prices and limits must come from backend/admin configuration.

---

# 39.6 PLAN DATABASE MODEL

Create tables similar to:

```text
plans

id
code
name
description
plan_type
billing_period
price
currency
inspection_limit
report_limit
organization_limit
staff_limit
api_access
custom_branding
is_trial
is_active
created_at
updated_at
```

Use decimal/numeric database types for money.

Never use floating point for financial calculations.

---

# 39.7 SUBSCRIPTIONS

Create:

```text
subscriptions

id
user_id
organization_id
plan_id

status

TRIALING
ACTIVE
PAST_DUE
CANCELLED
EXPIRED
SUSPENDED

started_at
trial_started_at
trial_ends_at
current_period_start
current_period_end
cancel_at_period_end
cancelled_at

provider
provider_customer_id
provider_subscription_id

created_at
updated_at
```

---

# 39.8 USAGE / QUOTA ENGINE

Create server-side usage accounting.

Example:

```text
subscription_usage

id
subscription_id
period_start
period_end

inspections_used
reports_generated
ai_analyses_used
api_requests_used

updated_at
```

Before starting chargeable functionality:

```text
check entitlement
→ check quota
→ reserve usage
→ perform operation
→ confirm usage
```

Use transactional logic to prevent users from bypassing quotas by sending simultaneous requests.

---

# 39.9 TRANSACTION SYSTEM

Every financial operation must create an immutable transaction record.

Create:

```text
transactions

id
user_id
organization_id
subscription_id
inspection_id
report_id

transaction_type

REPORT_PURCHASE
SUBSCRIPTION_PURCHASE
SUBSCRIPTION_RENEWAL
PLAN_UPGRADE
PLAN_DOWNGRADE
REFUND
CREDIT
PROMOTION

amount
currency

status

PENDING
PROCESSING
PAID
FAILED
CANCELLED
REFUNDED
PARTIALLY_REFUNDED

provider
provider_transaction_id
provider_reference

created_at
paid_at
failed_at
refunded_at
```

Never delete successful financial transactions.

---

# 39.10 PAYMENT ATTEMPTS

Keep payment attempts separate from successful transactions.

Example:

```text
payment_attempts

id
transaction_id

provider
provider_payment_id

status

INITIATED
REDIRECTED
AWAITING_CONFIRMATION
SUCCESS
FAILED
EXPIRED

failure_code
failure_message

created_at
updated_at
```

This allows safe retrying.

---

# 39.11 PAYMENT PROVIDER ABSTRACTION

Do NOT tightly couple PhoneProof to one payment company.

Create interface conceptually similar to:

```text
PaymentProvider

createPayment()
verifyPayment()
refundPayment()
getPaymentStatus()
verifyWebhook()
```

Possible providers can later include:

* Easypaisa
* JazzCash
* card gateway
* bank/payment aggregator
* international provider if PhoneProof expands

Only activate a provider when genuine merchant/API credentials exist.

Never fake successful Easypaisa/JazzCash transactions.

---

# 39.12 CHECKOUT

Checkout screen should display:

```text
PhoneProof Verified Report

Device:
Samsung Galaxy A54

Inspection:
PP-...

Price:
PKR ...

Payment Method

[ Easypaisa ]
[ JazzCash ]
[ Card ]
[ Other configured method ]

[Pay Securely]
```

Only show payment methods currently configured by backend.

Do not display unavailable integrations.

---

# 39.13 SERVER-SIDE PAYMENT VERIFICATION

Never trust:

```text
payment_success=true
```

sent from Android.

Correct workflow:

```text
Android initiates purchase
        ↓
Backend creates transaction
        ↓
Provider payment created
        ↓
Customer completes payment
        ↓
Payment provider sends webhook
                +
Backend independently verifies
        ↓
Transaction marked PAID
        ↓
Entitlement activated
```

Android only displays server-confirmed state.

---

# 39.14 WEBHOOKS

Implement provider webhook endpoint architecture:

```text
POST /api/v1/payments/webhooks/{provider}
```

Requirements:

* signature verification
* replay protection
* event ID deduplication
* timestamp validation
* idempotency
* logging
* secure error handling

Never activate subscriptions based only on redirect URLs.

---

# 39.15 PURCHASE ENTITLEMENTS

Create an entitlement layer.

Example:

```text
CAN_RUN_INSPECTION
CAN_GENERATE_VERIFIED_REPORT
CAN_RUN_COSMETIC_AI
CAN_DOWNLOAD_PDF
CAN_USE_DEALER_TOOLS
CAN_USE_API
CAN_ADD_STAFF
CAN_USE_CUSTOM_BRANDING
```

Android requests entitlements from backend.

Do not scatter plan-specific `if` conditions throughout the application.

---

# 39.16 CREDIT SYSTEM

Architecture may support dealer credits.

Example:

Dealer purchases:

`100 Inspection Credits`

One completed billable inspection:

`-1 credit`

Create:

```text
credit_wallets
credit_transactions
```

Ledger examples:

```text
+100 PURCHASE
-1 INSPECTION
+1 REFUND
+10 PROMOTION
```

Never calculate wallet balance only on Android.

Server is authoritative.

---

# 39.17 PROMOTIONS / COUPONS

Support optional promotional codes.

Create:

```text
promo_codes

id
code
discount_type

PERCENT
FIXED
FREE_REPORT
FREE_INSPECTION
TRIAL_EXTENSION

discount_value

max_redemptions
redemptions
per_user_limit
valid_from
valid_until
applicable_plan
is_active
```

Examples:

```text
LAUNCH2026
SHOPTRIAL
FIRSTREPORT
```

Admin controls promotions.

---

# 39.18 REFUNDS

Support:

```text
refunds

id
transaction_id
amount
reason
status
provider_refund_id
requested_at
completed_at
```

Possible statuses:

```text
REQUESTED
PROCESSING
APPROVED
REJECTED
REFUNDED
FAILED
```

Refund operations must be audited.

---

# 39.19 INVOICES / RECEIPTS

After successful purchase generate receipt.

Contains:

* PhoneProof transaction ID
* customer/business
* plan/report purchased
* amount
* currency
* transaction date
* payment method
* payment-provider reference
* payment status

For business accounts support downloadable invoices where appropriate.

Never expose private gateway secrets.

---

# 39.20 TRANSACTION HISTORY UI

Account should contain:

`Billing & Transactions`

Example:

```text
19 Sep 2026
Verified Phone Report
PKR 299
PAID

14 Sep 2026
Dealer Pro Subscription
PKR ...
PAID

05 Sep 2026
Report Purchase
PKR ...
REFUNDED
```

Each transaction opens:

* transaction ID
* description
* amount
* date
* status
* payment method
* receipt
* related report
* support/refund status

---

# 39.21 SUBSCRIPTION MANAGEMENT UI

Create:

`Account → Subscription`

Show:

* current plan
* subscription status
* current billing cycle
* remaining inspections
* reports remaining
* renewal date
* usage
* upgrade
* downgrade
* cancel
* transaction history

Do NOT use deceptive cancellation UX.

---

# 39.22 PLAN UPGRADE

Support:

```text
Free
   ↓
Individual
   ↓
Dealer Starter
   ↓
Dealer Pro
```

Backend determines:

* effective date
* quota change
* credit handling
* payment requirement

Do not implement complex proration unless the payment provider/business rules genuinely support it.

---

# 39.23 PLAN DOWNGRADE

Users can request downgrade.

Recommended behavior:

```text
Current plan remains active
until billing period ends.

New plan activates next cycle.
```

Do not destroy existing inspection reports after downgrade.

---

# 39.24 SUBSCRIPTION CANCELLATION

Cancellation should normally use:

```text
cancel_at_period_end = true
```

User retains access until:

`current_period_end`

Past signed reports remain available according to product retention policy.

---

# 39.25 FAILED RENEWAL

Handle:

```text
ACTIVE
↓
payment failure
↓
PAST_DUE
↓
grace period
↓
SUSPENDED/EXPIRED
```

Existing signed reports must not disappear because a subscription expires.

The user may lose the ability to create new paid inspections, not ownership of legitimately purchased reports.

---

# 39.26 ADMIN BILLING PANEL

Admin dashboard must provide:

### Plans

* create
* edit
* activate/deactivate
* pricing
* limits
* features

### Subscriptions

* search
* inspect
* status
* usage
* expiry
* cancellation

### Transactions

* search
* filter
* payment status
* provider reference
* amount
* user
* organization

### Refunds

* review
* approve/reject where workflow permits
* provider status

### Promotions

* create code
* expiry
* limits
* usage

### Revenue analytics

Support:

* total transactions
* successful payments
* failed payments
* refund amount
* subscription count
* trial users
* trial-to-paid conversion
* active dealers
* reports sold
* monthly recurring revenue calculations

Do not fabricate analytics.

---

# 39.27 REQUIRED API ENDPOINTS

Implement appropriate routes such as:

```text
GET /plans

GET /billing/entitlements
GET /billing/usage

POST /subscriptions
GET /subscriptions/current
POST /subscriptions/{id}/cancel
POST /subscriptions/{id}/change-plan

POST /payments/create
GET /payments/{transaction_id}/status

POST /payments/webhooks/{provider}

GET /transactions
GET /transactions/{id}

POST /transactions/{id}/refund

GET /invoices
GET /invoices/{id}

POST /promo/validate
POST /promo/redeem
```

All routes require correct authentication/authorization.

---

# 39.28 DATABASE CONSISTENCY

Financial operations must be transaction-safe.

For example:

```text
Payment confirmed
        ↓
DB transaction begins
        ↓
financial transaction → PAID
        ↓
subscription/entitlement activated
        ↓
usage allocation created
        ↓
audit entry created
        ↓
commit
```

Avoid inconsistent situations such as:

```text
money received
but subscription not activated
```

Design retry/reconciliation mechanisms.

---

# 39.29 RECONCILIATION

Create a scheduled backend job capable of checking uncertain transactions.

Example:

```text
PENDING transaction
      ↓
query provider
      ↓
provider says paid
      ↓
mark PAID
      ↓
activate entitlement
```

This protects against lost webhooks.

---

# 39.30 SECURITY REQUIREMENTS FOR PAYMENTS

Never store:

* card CVV
* raw card numbers
* payment-provider passwords
* private merchant secrets inside APK
* provider private keys inside repository

Secrets remain server-side.

Use:

* HTTPS
* webhook verification
* idempotency
* audit logs
* rate limiting
* authenticated checkout creation
* amount verification
* currency verification

The Android app must never determine transaction amount by itself.

Backend calculates authoritative pricing.

---

# 39.31 INSPECTION PAYMENT STATE

Inspection should have commercial state separate from technical state.

Example:

```text
Technical status:

DIAGNOSTICS_COMPLETE

Commercial status:

FREE
TRIAL
PAYMENT_REQUIRED
PAYMENT_PENDING
PAID
REFUNDED
```

A technical test must never change because of payment state.

---

# 39.32 REPORT PURCHASE SECURITY

Never do:

```text
Android:
isPaid = true
```

Correct:

```text
GET /reports/{id}/entitlement
```

Backend responds:

```json
{
  "can_generate_verified_report": true,
  "source": "subscription",
  "transaction_id": "..."
}
```

Only then may the signed commercial report be generated.

---

# 39.33 COMMERCIAL USER JOURNEY

### New Individual

```text
Install PhoneProof
↓
Register
↓
Free Trial Available
↓
Inspect Phone
↓
View Results
↓
Generate First Trial Report
↓
Trial Used
↓
Next Inspection
↓
Purchase Report / Subscribe
```

### Dealer

```text
Register Dealer Account
↓
Free Dealer Trial
↓
Perform Test Inspections
↓
Choose Dealer Plan
↓
Pay
↓
Subscription Activated
↓
Monthly Inspection Quota
↓
Inspect Customer Phones
↓
Generate Verified Reports
↓
Quota Usage Tracked
```

---

# 39.34 COMMERCIAL ACCEPTANCE TESTS

Before calling billing complete test:

1. New account receives correct trial.
2. Trial cannot be reused through simple app reinstall.
3. Trial usage is server-side.
4. Free inspection works.
5. Paid report checkout creates transaction.
6. Failed payment does not unlock report.
7. Successful verified payment unlocks report.
8. Duplicate webhook does not duplicate payment.
9. Dealer subscription activates.
10. Quota decreases correctly.
11. Quota cannot become negative.
12. Upgrade works.
13. Cancellation works.
14. Expiry works.
15. Existing reports remain available.
16. Refund creates correct transaction state.
17. Promo usage is enforced.
18. Transaction history matches backend.
19. Android cannot modify authoritative price.
20. Android cannot fake paid state.

---

# 39.35 INITIAL DEMO / TWO-DAY FALLBACK

If real Easypaisa/JazzCash merchant credentials are not available before the demo:

DO NOT fake a successful external payment.

Implement:

* complete checkout architecture
* transaction database
* subscription database
* usage system
* trial system
* entitlement engine
* transaction history
* provider adapter interface

For development only, create:

`DevelopmentPaymentProvider`

This provider may simulate payment strictly when:

```text
APP_ENV=development
```

The interface must visibly label it:

`DEVELOPMENT PAYMENT — NOT REAL MONEY`

This provider must be completely disabled in production builds.

This allows the commercial flow to be demonstrated without pretending a real transaction occurred.

---

# 39.36 FINAL COMMERCIAL RULE

PhoneProof must distinguish:

**Diagnostic truth**

from

**Payment entitlement.**

Someone who has not paid does not receive fake failed diagnostics.

Someone who pays does not receive fake passed diagnostics.

Payment controls access to commercial services such as verified reports, premium analysis, quota and business tools.

It must never influence the diagnostic evidence itself.


# 40. SECURITY

Implement production-conscious security:

* HTTPS
* JWT access/refresh flow
* refresh-token rotation
* Argon2id passwords
* Android Keystore
* Play Integrity integration interface
* RBAC
* authorization on every protected API
* rate limiting
* idempotency
* MIME checking
* upload limits
* image decompression protection
* signed reports
* immutable audit events
* secret management
* sanitized logs
* input validation
* no secrets committed to Git
* no full IMEI in public reports

---

# 41. PRIVACY

Never request or collect:

* contacts
* SMS
* call logs
* WhatsApp messages
* personal gallery
* documents
* unrelated installed application data
* unnecessary location history

Request permissions only when that particular diagnostic starts.

Explain permission purpose briefly.

---

# 42. TWO-DAY LAUNCH PRIORITY

I need a deployable build urgently.

Therefore use two levels:

# P0 — MUST WORK FOR THE INITIAL DEPLOYABLE PRODUCT

Prioritize genuine implementation of:

* app startup/navigation
* authentication
* device profile
* capability scan
* expanded registry
* display diagnostics
* touch diagnostics
* front/rear cameras
* exposed camera lenses
* flash
* microphone
* speaker
* battery
* charging
* accelerometer
* gyroscope
* proximity
* ambient light
* magnetometer
* storage integrity
* vibration
* volume buttons
* Wi-Fi
* Bluetooth
* GPS
* biometric authentication
* device integrity signals
* security information
* software/system information
* hardware quality indicators
* inspection persistence
* Room
* backend synchronization
* result-by-result UI
* deterministic scoring
* report
* QR
* public verification
* scanner pairing
* exterior image capture
* image-quality validation
* manual cosmetic defect review
* real AI cosmetic analysis only if valid weights exist

# P1 — IMPLEMENT AFTER P0 IS STABLE

Enable additional tests such as:

* NFC physical verification
* USB accessory test
* cellular advanced diagnostics
* eSIM information
* UWB
* DRM advanced diagnostics
* advanced storage snapshots
* advanced camera quality metrics
* advanced sensor quality scoring
* additional foldable-specific hardware
* advanced dealer/business features
* payment integrations

Do not sacrifice P0 reliability merely to display a huge test count.

---

# 43. CRITICAL IMPLEMENTATION PRINCIPLE

I would prefer:

`110 genuine working diagnostics`

over

`300 diagnostics where half are fake or broken`.

Create a registry capable of 250+ definitions, but only enable diagnostics that have a genuine implementation.

---

# 44. AUTOMATED DIAGNOSTIC ORCHESTRATION

Build a real `DiagnosticsEngine`.

Conceptually:

CapabilityScanner
↓
DiagnosticRegistry
↓
EligibilityFilter
↓
PermissionCoordinator
↓
DiagnosticRunner
↓
EvidenceCollector
↓
ResultValidator
↓
LocalPersistence
↓
SyncQueue
↓
Backend
↓
ScoringEngine
↓
Report

Each diagnostic should implement a common contract.

For example conceptually:

`DiagnosticCheck`

with:

* ID
* title
* category
* version
* mode
* criticality
* requirements
* permissions
* timeout
* weight
* execute()
* validate()
* evidence
* result

Avoid giant `when` statements spread across UI screens.

---

# 45. TESTING REQUIREMENT

You are not finished when code has merely been generated.

You must:

1. build the Android project;
2. run Gradle compilation;
3. run unit tests;
4. run lint where practical;
5. build backend;
6. run backend tests;
7. validate migrations;
8. validate API startup;
9. test report generation;
10. test QR verification;
11. test offline persistence;
12. test result serialization;
13. test score determinism;
14. test capability filtering;
15. test permission-denial handling;
16. test unavailable hardware;
17. test API failure;
18. fix discovered compilation/runtime/test errors.

Continue fixing problems until the project reaches a clean build with the environment available to you.

Do not declare success while known compilation errors remain.

---

# 46. NO PLACEHOLDER P0 IMPLEMENTATIONS

Do not leave P0 functionality like:

```kotlin
TODO()
```

or:

```kotlin
return Result.PASS
```

or:

```python
return {"status": "success"}
```

unless that result genuinely follows from a real operation.

No fake delays.

No random results.

No hard-coded score.

No hard-coded phone condition.

No fake AI response.

---

# 47. REQUIRED DEVELOPMENT DOCUMENTS

Generate/update:

* README.md
* docs/architecture.md
* docs/diagnostics.md
* docs/diagnostic-registry.md
* docs/api.md
* docs/database.md
* docs/security.md
* docs/privacy.md
* docs/deployment.md
* docs/android-setup.md
* docs/testing.md
* docs/external-services.md
* docs/current-limitations.md

`diagnostic-registry.md` must contain a complete table of all diagnostics with:

* ID
* category
* name
* test type
* minimum Android/API requirement
* required permission
* capability requirement
* implementation status
* PASS criteria
* FAIL criteria
* NOT_SUPPORTED condition
* NOT_ACCESSIBLE condition
* evidence type

---

# 48. EXTERNAL INFORMATION YOU MAY NEED FROM ME

Do NOT ask unnecessary questions before beginning.

Build everything that can be built independently first.

When an external item becomes genuinely necessary, ask me clearly and specifically.

Possible blocking items include:

* Android application package name preference
* production domain
* backend host credentials
* PostgreSQL URL
* Redis URL
* S3/R2 credentials
* Android signing keystore
* Play Console access
* Google Cloud/Play Integrity configuration
* existing PhoneProof AI model weights
* training dataset
* PTA integration credentials if an official integration exists
* payment-provider credentials
* production logo/branding

Ask for external requirements in a consolidated checklist instead of repeatedly interrupting development.

If something is optional, continue without it.

Never put real credentials into source code.

---

# 49. IF AI MODEL WEIGHTS ARE MISSING

Do not stop the complete project.

Finish:

* Android app
* scanner workflow
* backend
* image upload
* image QC
* ML service interface
* Celery/background job
* model registry
* inference API
* review UI
* database schema
* report integration

Then clearly tell me:

`Real cosmetic defect detection requires trained PhoneProof model weights. Please provide the weights or dataset.`

Do not fake detections.

---

# 50. DEPLOYMENT

Prepare:

Android:

* debug APK build
* release configuration
* signed-release instructions
* AAB generation
* API environment switching

Backend:

* Dockerfile
* Docker Compose
* production environment configuration
* PostgreSQL migrations
* health endpoint
* deployment config suitable for Render/Railway or equivalent

Web verification:

* publicly deployable responsive verification page
* QR-compatible route

Storage:

* S3-compatible abstraction

Provide exact deployment commands.

If you have access to an authorized deployment environment, deploy and smoke-test it.

If credentials are needed, request them only when reaching that actual step.

---

# 51. FINAL ACCEPTANCE TEST

Before calling the project complete, demonstrate a full journey:

Create Account
→ Login
→ Start Inspection
→ Detect Phone
→ Build Capability Profile
→ Show Applicable Diagnostics
→ Run Tests
→ Store Each Result Individually
→ Show PASS/FAIL/Unavailable Per Test
→ Retry Failed Test
→ Pair Scanner
→ Capture Exterior
→ Validate Images
→ Cosmetic Review
→ Calculate Score
→ Finalize Inspection
→ Generate PDF
→ Sign Report
→ Generate QR
→ Open Public Verification Page
→ Verify Report

Also demonstrate:

* permission denied
* unsupported sensor
* diagnostic failure
* offline operation
* backend reconnect
* app restart/resume
* report verification

---

# 52. FINAL OUTPUT I EXPECT FROM YOU

Do not respond with only architecture or explanation.

Actually develop the project.

At completion provide:

## Build Status

What is genuinely complete.

## Working Diagnostics

Number of enabled diagnostics grouped by category.

## Disabled/Unavailable Diagnostics

And the technical reason.

## Android Build

Exact command to build APK/AAB.

## Backend

Exact command to run.

## Database

Migration/setup command.

## Deployment

Exact steps.

## External Inputs Needed From Me

Only remaining real requirements.

## Testing

Tests executed and results.

## Known Limitations

Be completely transparent.

## Demo Instructions

Exact steps I should perform on two Android phones to demonstrate PhoneProof.

---

# 53. WORKING STYLE

Important:

Do not spend the entire response planning.

Inspect the blueprint.

Inspect the existing repository if one is supplied.

Create a requirement matrix.

Then immediately implement.

Work systematically.

After each major subsystem:

* compile
* test
* fix

Do not repeatedly ask permission to continue.

Make reasonable engineering decisions yourself.

Only ask me when something external or impossible to infer is actually blocking progress.

---

# 54. QUALITY BAR

The goal is not:

“Make an impressive-looking FYP demo.”

The goal is:

**Build the strongest genuinely functional PhoneProof release possible with the available Android APIs, hardware, time, backend infrastructure and AI resources.**

The product should be something a mobile-shop owner could understand and use.

Accuracy is more important than test count.

Evidence is more important than marketing.

A failed test must be visible.

An unsupported test must be identified.

An inaccessible value must be disclosed.

An AI prediction must carry confidence.

A finalized report must be verifiable.

No result may be invented simply to make the application look complete.

Now read the supplied PhoneProof blueprint completely and begin implementation.
