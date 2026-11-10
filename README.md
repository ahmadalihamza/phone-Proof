# PhoneProof PK

An Android 10+ used-phone inspection application with individual evidence records, offline storage, a paired scanner workflow, server-side scoring, signed PDF reports and public QR verification.

This repository contains working implementations and automated verification. It is a **release candidate** with real PostgreSQL/S3-compatible acceptance and bounded physical-phone checks. Further device/two-phone acceptance and production service activation remain open. Test fixtures do not certify phone condition, and no production service has been deployed. See the [release gate record](docs/release-readiness.md).

## Run the backend

From the repository root in PowerShell, using Python 3.12:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -c backend\requirements-tested.txt -r backend\requirements-dev.txt
Copy-Item backend\.env.example backend\.env
Set-Location backend
..\.venv\Scripts\python.exe -m alembic upgrade head
..\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Open `http://127.0.0.1:8000/docs` for the API, `/health` for health status and `/admin` for the admin console. Create an admin with `..\.venv\Scripts\python.exe -m app.provision_admin admin@YOUR_DOMAIN` from the backend directory; the interactive command provisions TOTP MFA. There are no shared default credentials. Normal accounts register in the Android app or API.

SQLite is the local default. PostgreSQL, Redis and private object storage are required in production. [Deployment instructions](docs/deployment.md) include the Docker Compose and hosted configurations. For two phones, use the computer's LAN address and bind the development API to `0.0.0.0` as described there.

## Build Android

Install JDK 17 and Android SDK platform 36/build tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME` for your machine, then:

```powershell
Set-Location android
.\gradlew.bat :app:assembleDebug :diagnostics:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Minimum Android API 29; compile/target API 36. Gradle wrapper 8.11.1, AGP 8.10.1, Kotlin 2.1.20. Select the development API in Account before signing in. Production builds require HTTPS.

For a production AAB, set `PHONEPROOF_KEYSTORE`, `PHONEPROOF_STORE_PASSWORD`, `PHONEPROOF_KEY_ALIAS` and `PHONEPROOF_KEY_PASSWORD` through a protected environment, then run:

```powershell
.\gradlew.bat :app:bundleRelease -PapiBaseUrl=https://YOUR_API_HOST/api/v1/
```

See [Android setup](docs/android-setup.md) and [two-phone demonstration](docs/two-phone-demo.md).

## What is implemented

| Area | Behavior |
|---|---|
| Diagnostics | Capability gates, six explicit states, per-check evidence/timestamps, retries, automatic and guided checks |
| Persistence | Room saves attempts before sync; WorkManager retries; account/origin isolation; capture upload queue |
| Scanner | One-use expiring pairing, twelve camera views, bounded image QC, duplicate detection, manual defect rectangles and IMEI OCR |
| Backend | Authentication and refresh rotation, ownership checks, immutable result attempts, capability snapshots and server scoring |
| Account recovery | Email verification and password reset, session revocation and encrypted SMTP outbox; delivery requires SMTP and the mail worker |
| Reports | Canonical SHA-256, Ed25519 signatures, immutable records, detailed PDF with QR, public privacy projection, revocation |
| Scoring | Shared category-weighted v2 policy, check-specific caps, coverage and lost-point explanations, separate cosmetic and conservative overall grades; legacy inspections retain v1 |
| Commercial controls | Server quotas, fixed purchase terms, attempt/event history, private receipt PDFs, reconciliation orchestration, manual subscription renewal, cancellation/refund/promo UI; development-only payment simulation |
| Administration | Role-gated console/API, TOTP MFA, plans, promotions, account/billing overview and append-only audit |

The shared registry contains **376 definitions: 326 enabled and 50 disabled**. Of the enabled templates, **61 are weighted functional checks**; 265 are unscored metadata or observations. Sensor fields are separate information entries. Camera templates expand for public camera IDs, and availability varies by phone. These numbers describe code coverage, not successful tests on a phone.

| Category | Enabled templates |
|---|---:|
| Sensors | 165 |
| System | 28 |
| Connectivity | 28 |
| Display | 24 |
| Camera | 19 |
| Touch | 14 |
| Battery | 14 |
| Audio | 10 |
| Storage | 7 |
| Security | 5 |
| Buttons | 5 |
| Charging | 2 |
| Thermal | 2 |
| Performance | 2 |
| Biometrics | 1 |

[Complete registry](docs/diagnostic-registry.md) lists every definition and disabled reason. Restricted OEM diagnostics, battery lifespan/health estimates, repair history and hidden liquid damage are not fabricated. [Diagnostic behavior](docs/diagnostics.md) explains the available probes and their limits.

## Verification and remaining work

Run backend checks from `backend`:

```powershell
..\.venv\Scripts\python.exe -m pytest -q
..\.venv\Scripts\python.exe -m ruff check app tests
..\.venv\Scripts\python.exe -m pip_audit --strict
..\.venv\Scripts\python.exe -m alembic check
```

See [progress estimate](docs/progress.md), [testing evidence](docs/testing.md), [current limitations](docs/current-limitations.md), [security](docs/security.md) and [privacy](docs/privacy.md). The generated PDF under `output/pdf` is visibly labeled as a synthetic test fixture; it is not a customer inspection.

Remaining external inputs are listed in [external services](docs/external-services.md): two or more physical Android phones, a deployment host/domain and production data services, release signing/Play setup, approved merchant integrations, and validated cosmetic-model weights. Real payments and AI defect inference are unavailable until their corresponding integrations are implemented and validated.

[Payment readiness](docs/payment-readiness.md) details the implemented billing flows and the merchant activation work. Easypaisa, JazzCash and card collection are not active; local development simulations move no money.

Architecture, API, database and requirement mapping are in [docs](docs/architecture.md). The original request and blueprint are preserved in `docs/source-requirements.md` and `docs/source-blueprint.md`.
