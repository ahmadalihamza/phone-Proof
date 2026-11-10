# Android build

JDK 17, Gradle 8.11.1, Android SDK platform 36 and build tools 35.0.0 are required. AGP 8.10.1 supports SDK 36. Kotlin 2.1.20, Compose, Room, WorkManager, CameraX/Camera2 and ML Kit are pinned in Gradle. Minimum API 29, compile/target API 36. Package `pk.phoneproof.app`; debug suffix `.debug`.

```powershell
$env:JAVA_HOME='PATH_TO_JDK_17'
$env:ANDROID_HOME='PATH_TO_ANDROID_SDK'
Set-Location android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :diagnostics:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:bundleRelease -PapiBaseUrl=https://YOUR_API_HOST/api/v1/
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Release AAB: `android/app/build/outputs/bundle/release/app-release.aab`. Configure PHONEPROOF_KEYSTORE, PHONEPROOF_STORE_PASSWORD, PHONEPROOF_KEY_ALIAS and PHONEPROOF_KEY_PASSWORD only in a protected shell/CI secret store. Never commit a keystore. A bundle without production signing credentials is not a Play-ready signed release.

API URL: supply your approved, reachable service using Gradle `-PapiBaseUrl=https://YOUR_API_HOST/api/v1/` or the Account connection screen while signed out. Debug starts unconfigured when the property is omitted; account actions explain how to configure it, and local inspection/reporting remains available. Release builds require an explicit HTTPS API address. Debug permits HTTP for isolated LAN development. Local results are bound to the backend/account on first sync; changing hosts does not transfer old data.

Physical device: enable USB debugging for installation, run `adb install -r app/build/outputs/apk/debug/app-debug.apk`, then disable debugging after testing. Instrumentation: `gradlew.bat connectedDebugAndroidTest`. Camera/sensor/audio/GPS/Bluetooth behavior requires real devices. An emulator is useful for startup/persistence/permission flows but cannot certify hardware diagnostics.

The optional `tools/bootstrap_build.py` downloads isolated official build dependencies. On this workspace the installed JDK is under `.tools`; `android/local.properties` is machine-local and ignored by Git.

For the restored Windows workspace, from the repository root:

```powershell
$env:JAVA_HOME=(Resolve-Path '.tools/jdk-17.0.20.1+1').Path
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
.\.tools\gradle-8.11.1\bin\gradle.bat -p android :app:assembleDebug :app:testDebugUnitTest :diagnostics:testDebugUnitTest :app:lintDebug
```

The workflow repair passed 20 instrumented cases on a physical TECNO CK6n running Android 14. The wider device matrix remains open. See [workflow verification](phoneproof-workflow-repair.md) for results and hands-on checks.

Use the separate **PhoneProof QA** package for the workflow and authentication suites. From the `android` directory:

```powershell
.\gradlew.bat -PdeviceTestBuildType=verification :app:assembleVerification :app:assembleVerificationAndroidTest
adb install --user 0 -r app/build/outputs/apk/verification/app-verification.apk
adb install --user 0 -r app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk
adb shell am instrument --user 0 -w -e notClass pk.phoneproof.app.AuthBackendAcceptanceTest pk.phoneproof.app.verification.test/androidx.test.runner.AndroidJUnitRunner
```

Unlock and authorize the phone before instrumentation. The hardware probes make bounded observations. From the repository root, run `python tools/verify_android_backend.py` to exercise real registration, sign-in, restart and sync using a temporary migrated backend. Use `python tools/verify_android_artifacts.py --apk APK_PATH` with `ANDROID_HOME` set to verify signature, optional features and 16 KiB packaging.
