@file:Suppress("DEPRECATION")
package pk.phoneproof.app

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.AudioManager
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import pk.phoneproof.app.data.LocalInspection
import pk.phoneproof.diagnostics.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Real bounded device observations, never phone-condition certification.
 * The lifecycle test retains only an interrupted result in an isolated Room fixture until cleanup.
 * No probe is synchronized, uploaded or used to create a report.
 * No speaker playback or torch commands are issued. JPEG pixels stay in RAM and are discarded.
 */
@RunWith(AndroidJUnit4::class)
class HardwareAcceptanceTest {
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
    )).around(activity)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun privateStorageAndSQLiteRoundTripsAreObservedAndCleanedUp() = runBlocking {
        val profile = CapabilityScanner(context).scan()
        val engine = DiagnosticsEngine(context)
        val before = temporaryProbeFiles()
        for(id in listOf("storage.checksum", "storage.sqlite")) {
            val result = engine.execute(definition(id, profile), profile)
            assertEquals("Actual app-private round trip did not complete: ${result.message}", DiagnosticStatus.PASS, result.status)
            if(id == "storage.checksum")assertEquals("262144", result.measuredValue["bytes_verified"])
            else assertEquals("true", result.measuredValue["transaction_committed"])
            observe("$id: actual bounded round trip observed; no NAND health claim")
        }
        assertEquals("Temporary probe files should be removed in finally", before, temporaryProbeFiles())
    }

    @Test fun microphoneCaptureClaimsOnlyAnObservedBuiltInRoute() = runBlocking {
        val profile = CapabilityScanner(context).scan()
        assumeTrue("Device does not expose a built-in microphone", profile.features["microphone"] == true)
        val result = DiagnosticsEngine(context).execute(definition("audio.mic.capture", profile), profile)
        observe("Microphone: ${result.status}; actual samples=${result.measuredValue["samples"] ?: "unavailable"}; not acoustic certification")
        requireObservable(result)
        assertBuiltInCapture(result)
    }

    @Test fun accelerometerObservationUsesFreshFiniteEvents() = runBlocking {
        val profile = CapabilityScanner(context).scan()
        assumeTrue("Accelerometer is absent", profile.features["sensor.accelerometer"] == true)
        val result = DiagnosticsEngine(context).execute(definition("sensor.accelerometer.response", profile), profile)
        observe("Accelerometer: ${result.status}; fresh events=${result.measuredValue["samples"] ?: "unavailable"}; not calibration")
        requireObservable(result)
        assertTrue(requireNotNull(result.measuredValue["samples"]).toInt() > 0)
        assertEquals("true", result.measuredValue["timestamp_monotonic"])
        val axes = requireNotNull(result.measuredValue["latest_axes"]).split(',').map { it.trim().toFloat() }
        assertTrue(axes.isNotEmpty() && axes.all { it.isFinite() })
    }

    @Test fun realPerLensFlashDiscoveryMatchesEligibilityWithoutTurningOnTheLight() {
        val profile = CapabilityScanner(context).scan()
        assumeTrue("No exposed cameras", profile.cameras.isNotEmpty())
        val manager = context.getSystemService(CameraManager::class.java)
        val definitions = DiagnosticRegistry.expanded(context, profile)
        for(id in profile.cameras) {
            val platform = manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            val key = "camera.$id.flash"
            val torch = definitions.first { it.id == "camera.$id.torch" }
            assertEquals(platform, profile.features[key])
            if(platform != null) {
                assertEquals(2, torch.version)
                assertTrue(key in torch.capabilities)
                val gate = EligibilityFilter.evaluate(torch.copy(permissions = emptyList()), profile.api, profile.features, emptySet())
                if(!platform)assertEquals(DiagnosticStatus.NOT_SUPPORTED, gate.status)
                else assertTrue(gate.eligible)
            }
            observe("Lens $id: flash discovery=${platform ?: "unknown"}; eligibility checked without torch activation")
        }
    }

    @Test fun stoppingTheActivityCancelsAnActiveProbe() = runBlocking {
        assumeTrue("Only the isolated verification app may persist a lifecycle fixture", context.packageName.endsWith(".verification"))
        lateinit var vm: InspectionViewModel
        activity.scenario.onActivity { vm = ViewModelProvider(it)[InspectionViewModel::class.java] }
        assumeTrue("Lifecycle fixtures require a signed-out app", vm.api.vault.snapshot().tokens == null)
        val profile = CapabilityScanner(context).scan()
        val plugged = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        assumeTrue("Android did not expose the initial power source", plugged >= 0)
        // Observe the opposite of the actual state so the probe stays active until ON_STOP.
        val charging = definition(if(plugged > 0) "charging.disconnect" else "charging.connect", profile)
        val id = UUID.randomUUID().toString()
        val fixture = LocalInspection(id, Instant.now().toString(), DiagnosticRegistry.json.encodeToString(profile))
        val completed = AtomicBoolean(false)
        vm.db.dao().create(fixture)
        try {
            activity.scenario.onActivity {
                vm.select(fixture)
                vm.runBatch(listOf(charging), id) { completed.set(true) }
                assertTrue("The production probe must start", vm.testRunning.value)
            }
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            withTimeout(10_000) { while(vm.testRunning.value) delay(25) }
            assertFalse("ON_STOP must cancel the active probe before completing", completed.get())
            val rows = vm.db.dao().results(id)
            assertEquals("The interrupted attempt must be recorded once", 1, rows.size)
            val result = DiagnosticRegistry.json.decodeFromString<DiagnosticResult>(rows.single().payload)
            assertEquals(charging.id, result.checkId)
            assertEquals(DiagnosticStatus.INCONCLUSIVE, result.status)
            assertTrue(result.message.startsWith("Observation interrupted"))
            observe("Activity stopped: real power-source probe cancelled and recorded as inconclusive")
        } finally {
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            activity.scenario.onActivity { vm.stop(); vm.selected.value = null }
            withTimeout(10_000) { while(vm.testRunning.value) delay(25) }
            withContext(Dispatchers.IO) {
                vm.db.openHelper.writableDatabase.delete("inspections", "id = ?", arrayOf(id))
            }
        }
    }

    @Test fun cancellingAnActiveMicrophoneProbeReleasesItForRetry() = runBlocking {
        val profile = CapabilityScanner(context).scan()
        assumeTrue("Device does not expose a built-in microphone", profile.features["microphone"] == true)
        val engine = DiagnosticsEngine(context)
        val mic = definition("audio.mic.capture", profile)
        val audio = context.getSystemService(AudioManager::class.java)
        val initialRecordings = audio.activeRecordingConfigurations.size
        val probe = async(Dispatchers.Default) { engine.execute(mic, profile) }
        try {
            val observedActiveRecording = withTimeoutOrNull(3_000) {
                while(probe.isActive && audio.activeRecordingConfigurations.size <= initialRecordings) delay(10)
                probe.isActive
            } ?: false
            if(!observedActiveRecording && probe.isCompleted) requireObservable(probe.await())
            assertTrue("Observe the active platform recording before requesting cancellation", observedActiveRecording)
            withTimeout(5_000) { probe.cancelAndJoin() }
            assertTrue("Cancellation must end the active probe", probe.isCancelled)
            val retry = engine.execute(mic, profile)
            observe("Microphone: active recording cancellation completed; retry=${retry.status}; no result persisted")
            requireObservable(retry)
            assertBuiltInCapture(retry)
        } finally {
            probe.cancelAndJoin()
        }
    }

    @Test fun exposedFrontAndBackCamerasProduceDecodableRealJpegs() = runBlocking {
        val manager = context.getSystemService(CameraManager::class.java)
        val choices = manager.cameraIdList.mapNotNull { id ->
            val info = manager.getCameraCharacteristics(id)
            val sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
            if(sizes.isNullOrEmpty())null else Triple(id, info.get(CameraCharacteristics.LENS_FACING), sizes.toList())
        }
        val lenses = listOf(CameraCharacteristics.LENS_FACING_BACK, CameraCharacteristics.LENS_FACING_FRONT)
            .mapNotNull { facing -> choices.firstOrNull { it.second == facing } }.distinctBy { it.first }
        assumeTrue("No exposed front/back lens offers JPEG output", lenses.isNotEmpty())
        for((id, facing, sizes) in lenses) {
            val size = sizes.filter { it.width.toLong() * it.height <= 2_000_000 }.minByOrNull {
                abs(it.width.toLong() * it.height - 640L * 480)
            } ?: sizes.minBy { it.width.toLong() * it.height }
            val bytes = withTimeout(15_000) { captureJpeg(manager, id, size.width, size.height) }
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                assertTrue("Real JPEG dimensions should be positive", bounds.outWidth > 0 && bounds.outHeight > 0)
                val options = BitmapFactory.Options().apply {
                    var sample = 1
                    while(maxOf(bounds.outWidth, bounds.outHeight) / sample > 800)sample *= 2
                    inSampleSize = sample
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                assertNotNull("Camera JPEG must decode actual pixels", bitmap)
                try { val decoded = requireNotNull(bitmap); assertTrue(decoded.width > 0 && decoded.height > 0) }
                finally { bitmap?.recycle() }
                observe("Lens $id (${if(facing == CameraCharacteristics.LENS_FACING_BACK)"back" else "front"}): real JPEG ${bounds.outWidth}x${bounds.outHeight} decoded; pixels discarded; not optical certification")
            } finally { bytes.fill(0) }
        }
    }

    private fun definition(id: String, profile: CapabilityProfile) = DiagnosticRegistry.expanded(context, profile).first { it.id == id }

    private fun requireObservable(result: DiagnosticResult) {
        assumeTrue("Observation unavailable (${result.status}): ${result.message}", result.status !in setOf(
            DiagnosticStatus.NOT_SUPPORTED, DiagnosticStatus.NOT_ACCESSIBLE, DiagnosticStatus.INCONCLUSIVE,
        ))
        assertEquals("Actual stream probe failed: ${result.message}", DiagnosticStatus.PASS, result.status)
    }

    private fun assertBuiltInCapture(result: DiagnosticResult) {
        assertTrue(requireNotNull(result.measuredValue["samples"]).toInt() > 0)
        assertEquals("BUILTIN_MIC", result.measuredValue["audio_target"])
        assertEquals("true", result.evidence["audio_route_verified"])
        assertTrue(requireNotNull(result.evidence["audio_route_checks"]).toInt() > 0)
        val types = requireNotNull(result.evidence["audio_route_types"]).split(',').map { it.trim().toInt() }
        assertTrue(types.isNotEmpty() && types.all { it == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC })
    }

    private fun temporaryProbeFiles() = context.cacheDir.listFiles().orEmpty()
        .filter { it.name.startsWith("phoneproof-") || it.name.startsWith("diagnostic-") }.map { it.name }.toSet()

    private fun observe(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("phoneproof_observation", message)
        })
    }

    @Suppress("MissingPermission")
    private suspend fun captureJpeg(manager: CameraManager, id: String, width: Int, height: Int): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            val finished = AtomicBoolean(false)
            val lock = Any()
            var camera: CameraDevice? = null
            var session: CameraCaptureSession? = null
            fun closeResources() = synchronized(lock) {
                runCatching { session?.close() }; runCatching { camera?.close() }; runCatching { reader.close() }
            }
            fun fail(error: Throwable) {
                if(finished.compareAndSet(false, true)) {
                    closeResources()
                    if(continuation.isActive)continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { if(finished.compareAndSet(false, true))closeResources() }
            reader.setOnImageAvailableListener({ source ->
                try {
                    val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                    val bytes = image.use {
                        val buffer = it.planes[0].buffer
                        ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
                    }
                    if(finished.compareAndSet(false, true)) {
                        closeResources()
                        if(continuation.isActive)continuation.resume(bytes) else bytes.fill(0)
                    } else bytes.fill(0)
                } catch(error: Exception) { fail(error) }
            }, handler)
            try {
                manager.openCamera(id, object: CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        synchronized(lock) {
                            if(finished.get()) { device.close(); return }
                            camera = device
                        }
                        try {
                            device.createCaptureSession(listOf(reader.surface), object: CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    synchronized(lock) {
                                        if(finished.get()) { configured.close(); return }
                                        session = configured
                                    }
                                    try {
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                        }.build()
                                        configured.capture(request, object: CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                                fail(IllegalStateException("Lens $id capture failed (reason ${failure.reason})"))
                                            }
                                        }, handler)
                                    } catch(error: Exception) { fail(error) }
                                }
                                override fun onConfigureFailed(configured: CameraCaptureSession) {
                                    configured.close(); fail(IllegalStateException("Lens $id JPEG session unavailable"))
                                }
                            }, handler)
                        } catch(error: Exception) { fail(error) }
                    }
                    override fun onDisconnected(device: CameraDevice) { device.close(); fail(IllegalStateException("Lens $id disconnected")) }
                    override fun onError(device: CameraDevice, error: Int) { device.close(); fail(IllegalStateException("Lens $id open error $error")) }
                }, handler)
            } catch(error: Exception) { fail(error) }
        }
}
