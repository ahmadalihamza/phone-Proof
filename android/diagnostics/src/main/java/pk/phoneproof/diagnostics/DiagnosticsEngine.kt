@file:Suppress("DEPRECATION")
package pk.phoneproof.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.*
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.webkit.WebView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import kotlin.math.abs
import kotlin.math.sqrt

class DiagnosticsEngine(private val context: Context) {
    private val advanced = AdvancedObservations(context)
    private val main = Handler(Looper.getMainLooper())
    private val handlers: Map<String,suspend (DiagnosticDefinition) -> Observation> = mapOf(
        "system" to { system(it) },"feature" to { feature(it) },"display" to { display(it) },
        "battery" to { battery(it) },"thermal" to { thermal(it) },"sensor_metadata" to { sensorMetadata(it) },
        "sensor_sample" to { sensorSample(it) },"camera_metadata" to { cameraMetadata(it) },
        "audio_info" to { audioInfo(it) },"microphone" to { microphone(it) },"network" to { network(it) },
        "storage" to { storage(it) },"sqlite" to { sqlite() },"cpu" to { cpu() },"memory" to { memory() },
        "keystore" to { keystore() },"location" to { location(it) },"bluetooth_scan" to { bluetoothScan() },
        "charging" to { charging(it) }
    )
    data class Observation(val status:DiagnosticStatus, val values:Map<String,String>, val message:String="",val evidence:Map<String,String> = values)
    private fun info(value:Any?,key:String="value") = if(value==null) unavailable("Platform did not expose this field") else Observation(DiagnosticStatus.PASS,mapOf(key to value.toString()),"Device information; not proof of working hardware")
    private fun unavailable(reason:String) = Observation(DiagnosticStatus.NOT_ACCESSIBLE,emptyMap(),reason)
    private fun unsupported(reason:String) = Observation(DiagnosticStatus.NOT_SUPPORTED,emptyMap(),reason)
    private fun inconclusive(reason:String,values:Map<String,String> = emptyMap()) = Observation(DiagnosticStatus.INCONCLUSIVE,values,reason)
    fun canRun(d:DiagnosticDefinition) = d.handler in handlers

    suspend fun executeBatch(batch:ProbeBatch,profile:CapabilityProfile):List<DiagnosticResult> =
        DiagnosticBatchExecutor.execute(batch,{ blocked(it,profile,Instant.now().toString()) },{ execute(it,profile) })

    private fun blocked(d:DiagnosticDefinition,profile:CapabilityProfile,started:String):DiagnosticResult? {
        val permissions=DiagnosticRegistry.permissions(d)
        val granted=permissions.filter { context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }.toSet()
        val gate=EligibilityFilter.evaluate(d.copy(permissions=permissions),Build.VERSION.SDK_INT,profile.features,granted)
        return if(!gate.eligible)result(d,gate.status?:DiagnosticStatus.NOT_ACCESSIBLE,emptyMap(),gate.reason,started) else null
    }

    suspend fun execute(d:DiagnosticDefinition, profile:CapabilityProfile):DiagnosticResult {
        val started=Instant.now().toString()
        blocked(d,profile,started)?.let { return it }
        val handler=handlers[d.handler] ?: return result(d,DiagnosticStatus.INCONCLUSIVE,emptyMap(),"Start the guided test and record real evidence",started)
        return try {
            withContext(Dispatchers.IO) { withTimeout(d.timeoutSeconds*1000L) {
                val observation = handler(d)
                advanced.enrich(d, result(d,observation.status,observation.values,observation.message,started,observation.evidence))
            } }
        } catch(e:TimeoutCancellationException) {
            result(d,DiagnosticStatus.INCONCLUSIVE,emptyMap(),"Test timed out without a reliable conclusion",started)
        } catch(e:CancellationException) { throw e }
        catch(e:SecurityException) { result(d,DiagnosticStatus.NOT_ACCESSIBLE,emptyMap(),"Permission or Android policy blocked this operation",started) }
        catch(e:Exception) { result(d,DiagnosticStatus.INCONCLUSIVE,emptyMap(),"Operation could not conclude: ${e.javaClass.simpleName}",started) }
    }

    fun result(d:DiagnosticDefinition,status:DiagnosticStatus,values:Map<String,String>,message:String,started:String=Instant.now().toString(),evidence:Map<String,String> = values):DiagnosticResult =
        DiagnosticResult(d.id,status,values,evidence,message,started,Instant.now().toString(),d.version,d.handler,d.passCriteria)

    private fun system(d:DiagnosticDefinition):Observation {
        val field=d.parameters["field"]
        CapabilityScanner(context).scan().device[field]?.let { return info(it) }
        val am=context.getSystemService(ActivityManager::class.java)
        val ram=ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val fs=StatFs(context.filesDir.absolutePath)
        return when(field) {
            "processors" -> info(Runtime.getRuntime().availableProcessors())
            "ram_total" -> info(ram.totalMem,"bytes")
            "ram_available" -> info(ram.availMem,"bytes")
            "low_memory" -> info(ram.lowMemory)
            "storage_total" -> info(fs.totalBytes,"bytes")
            "storage_available" -> info(fs.availableBytes,"bytes")
            "storage_used" -> info(100.0*(fs.totalBytes-fs.availableBytes)/fs.totalBytes,"percent")
            "security_patch_age" -> runCatching { info(ChronoUnit.DAYS.between(LocalDate.parse(Build.VERSION.SECURITY_PATCH),LocalDate.now()),"days") }.getOrElse { unavailable("Security patch date missing or invalid") }
            "developer_options" -> info(Settings.Global.getInt(context.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1)
            "debuggable" -> info(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            "emulator" -> info(mapOf("generic_fingerprint" to Build.FINGERPRINT.startsWith("generic"),"emulator_model" to (Build.MODEL.contains("Emulator")||Build.MODEL.contains("sdk_gphone"))),"risk_indicators")
            "root_risk" -> info(mapOf("test_keys" to (Build.TAGS?.contains("test-keys")==true),"visible_su_path" to listOf("/system/bin/su","/system/xbin/su").any { File(it).exists() }),"risk_indicators_not_proof")
            "webview" -> info(withMainWebView(),"runtime")
            "gles" -> info(am.deviceConfigurationInfo.glEsVersion)
            "clock" -> info(mapOf("wall_clock" to Instant.now().toString(),"uptime_ms" to SystemClock.elapsedRealtime(),"plausible_year" to (LocalDate.now().year in 2024..2100)),"clock_signal_not_trusted_time")
            "encryption" -> info(context.getSystemService(DevicePolicyManager::class.java)?.storageEncryptionStatus)
            "rotation_lock" -> info(Settings.System.getInt(context.contentResolver,Settings.System.ACCELEROMETER_ROTATION,0)==0)
            else -> unavailable("Field is not implemented")
        }
    }
    private fun withMainWebView():String? = WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
    private fun feature(d:DiagnosticDefinition):Observation {
        val name=d.parameters.getValue("field")
        val present=CapabilityScanner(context).scan().features[name]?:return unavailable("Feature mapping unavailable")
        return if(present) info(true,"feature_exposed") else unsupported("Optional feature is not exposed by PackageManager")
    }
    private fun display(d:DiagnosticDefinition):Observation {
        val display=context.getSystemService(WindowManager::class.java).defaultDisplay
        val metrics=android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        return when(d.parameters["field"]) {
            "resolution" -> info("${metrics.widthPixels} x ${metrics.heightPixels}","pixels")
            "density" -> info(metrics.densityDpi,"dpi")
            "refresh" -> info(display.refreshRate,"hz_reported")
            "modes" -> info(display.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" })
            "hdr" -> if(display.hdrCapabilities.supportedHdrTypes.isEmpty()) unsupported("No HDR format exposed") else info(display.hdrCapabilities.supportedHdrTypes.joinToString())
            "wide_color" -> if(display.isWideColorGamut) info(true) else unsupported("Wide color gamut not exposed")
            "state" -> info(display.state)
            else -> unavailable("Display field unavailable")
        }
    }
    private fun battery(d:DiagnosticDefinition):Observation {
        val b=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?:return unavailable("Battery broadcast unavailable")
        val bm=context.getSystemService(BatteryManager::class.java)
        fun extra(key:String,scale:Double=1.0):Observation=if(b.hasExtra(key)) info(b.getIntExtra(key,Int.MIN_VALUE)/scale) else unavailable("Battery broadcast field not exposed")
        fun property(id:Int):Observation { val value=bm.getIntProperty(id); return if(value==Int.MIN_VALUE) unavailable("BatteryManager does not expose this property") else info(value) }
        return when(d.parameters["field"]) {
            "level" -> { val level=b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);val scale=b.getIntExtra(BatteryManager.EXTRA_SCALE,-1);if(level<0||scale<=0) unavailable("Level/scale absent") else info(level*100.0/scale,"percent") }
            "status" -> extra(BatteryManager.EXTRA_STATUS)
            "plugged" -> extra(BatteryManager.EXTRA_PLUGGED)
            "temperature" -> if(b.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) info(b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0,"celsius") else unavailable("Temperature absent")
            "voltage" -> extra(BatteryManager.EXTRA_VOLTAGE)
            "health" -> extra(BatteryManager.EXTRA_HEALTH)
            "technology" -> info(b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
            "present" -> if(b.hasExtra(BatteryManager.EXTRA_PRESENT)) info(b.getBooleanExtra(BatteryManager.EXTRA_PRESENT,false)) else unavailable("Battery presence not exposed")
            "charge_counter" -> property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            "current_now" -> property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            "current_average" -> property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            "energy" -> bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).let { if(it==Long.MIN_VALUE) unavailable("Energy counter not exposed") else info(it,"nanowatt_hours") }
            "cycle_count" -> if(Build.VERSION.SDK_INT>=34) extra(BatteryManager.EXTRA_CYCLE_COUNT) else unsupported("Requires API 34")
            "low" -> if(b.hasExtra(BatteryManager.EXTRA_BATTERY_LOW)) info(b.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW,false)) else unavailable("Battery low state not exposed")
            else -> unavailable("Battery field unavailable")
        }
    }
    private fun thermal(d:DiagnosticDefinition):Observation {
        val power=context.getSystemService(PowerManager::class.java)
        return if(d.parameters["field"]=="status") info(power.currentThermalStatus,"thermal_status")
        else if(Build.VERSION.SDK_INT>=30) power.getThermalHeadroom(0).let { if(it.isNaN()) unavailable("Thermal headroom unsupported or rate-limited") else info(it,"headroom") }
        else unsupported("Thermal headroom requires API 30")
    }
    private fun sensorMetadata(d:DiagnosticDefinition):Observation {
        val sensor=context.getSystemService(SensorManager::class.java).getDefaultSensor(d.parameters.getValue("type").toInt())?:return unsupported("Sensor absent")
        return info(when(d.parameters["field"]) {"name"->sensor.name;"vendor"->sensor.vendor;"version"->sensor.version;"range"->sensor.maximumRange;"resolution"->sensor.resolution;"power"->sensor.power;"min_delay"->sensor.minDelay;"reporting_mode"->sensor.reportingMode;else->null})
    }
    private suspend fun sensorSample(d:DiagnosticDefinition):Observation {
        val manager=context.getSystemService(SensorManager::class.java)
        val sensor=manager.getDefaultSensor(d.parameters.getValue("type").toInt())?:return unsupported("Sensor absent")
        val samples=java.util.Collections.synchronizedList(mutableListOf<TimedSensorSample>())
        val windowStart=SystemClock.elapsedRealtimeNanos()
        val listener=object:SensorEventListener {
            override fun onSensorChanged(event:SensorEvent) { if(samples.size<500) samples.add(TimedSensorSample(event.timestamp,event.values.toList())) }
            override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
        }
        try {
            val registered=withContext(Dispatchers.Main) { manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_GAME,main) }
            if(!registered)return unavailable("Sensor listener registration failed")
            delay(2500) // Real observation window, not simulated completion.
        } finally { manager.unregisterListener(listener) }
        val values=synchronized(samples) { samples.toList() }
        val result=SensorWindowAnalysis.values(values,windowStart,SystemClock.elapsedRealtimeNanos())
        val observation=checkNotNull(ProbeObservations.assess("sensor_sample",d.parameters.getValue("field"),result))
        return Observation(observation.status,observation.values,observation.message)
    }
    private fun cameraMetadata(d:DiagnosticDefinition):Observation {
        val c=context.getSystemService(CameraManager::class.java).getCameraCharacteristics(d.parameters.getValue("camera_id"))
        return info(when(d.parameters["field"]) {
            "facing" -> c.get(CameraCharacteristics.LENS_FACING)
            "hardware_level" -> c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            "physical_ids" -> c.physicalCameraIds.joinToString().ifBlank { "No physical IDs exposed" }
            "sizes" -> c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.joinToString()
            "focal_lengths" -> c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
            "apertures" -> c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString()
            "focus_distance" -> c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            "af_modes" -> c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString()
            "flash" -> c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            "max_zoom" -> c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            "ois_modes" -> c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.joinToString()
            "video_stabilization" -> c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString()
            "capabilities" -> c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString()
            "sensor_size" -> c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            "exposure_range" -> c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            "fps_ranges" -> c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()
            else -> null
        })
    }
    private fun audioInfo(d:DiagnosticDefinition):Observation {
        val manager=context.getSystemService(AudioManager::class.java)
        return when(d.parameters["field"]) {
            "inputs" -> info(manager.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { "type=${it.type}, channels=${it.channelCounts.joinToString()}" })
            "outputs" -> info(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { "type=${it.type}, channels=${it.channelCounts.joinToString()}" })
            "volume" -> info("${manager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}; muted=${manager.isStreamMute(AudioManager.STREAM_MUSIC)}")
            else -> unavailable("Unknown audio field")
        }
    }
    @Suppress("MissingPermission")
    private suspend fun microphone(d:DiagnosticDefinition):Observation {
        val mic=context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?:return unavailable("Built-in microphone unavailable; disconnect external audio and retry")
        val rate=16000
        val size=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(size<=0)return unavailable("PCM configuration unsupported")
        val recorder=AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size*2)
        if(recorder.state!=AudioRecord.STATE_INITIALIZED) { recorder.release();return unavailable("AudioRecord could not initialize") }
        val route=BuiltInAudioRouteCheck(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        var preferred=false
        val routeListener=AudioRouting.OnRoutingChangedListener { routing -> route.observe(runCatching { observedAudioRoutes(routing) }.getOrDefault(emptyList())) }
        var samples=0;var sum=0.0;var peak=0;var clipped=0;var error=0;var windowComplete=false
        try {
            preferred=recorder.setPreferredDevice(mic)
            recorder.addOnRoutingChangedListener(routeListener,main)
            recorder.startRecording()
            val buffer=ShortArray(size);val routeDeadline=SystemClock.elapsedRealtime()+500
            while(!route.observe(observedAudioRoutes(recorder))&&!route.invalid()&&SystemClock.elapsedRealtime()<routeDeadline) {
                delay(20)
            }
            val deadline=SystemClock.elapsedRealtime()+2000
            while(SystemClock.elapsedRealtime()<deadline) {
                currentCoroutineContext().ensureActive()
                if(!route.observe(observedAudioRoutes(recorder)))break
                val n=recorder.read(buffer,0,buffer.size,AudioRecord.READ_NON_BLOCKING)
                if(n<0) { error=n;break }
                for(index in 0 until n) { val v=abs(buffer[index].toInt());sum+=v.toDouble()*v;peak=maxOf(peak,v);if(v>32000)clipped++ }
                samples+=n;delay(10)
            }
            windowComplete=SystemClock.elapsedRealtime()>=deadline
            route.observe(observedAudioRoutes(recorder))
        } finally {
            recorder.removeOnRoutingChangedListener(routeListener)
            try { if(recorder.recordingState==AudioRecord.RECORDSTATE_RECORDING)recorder.stop() } finally { recorder.release() }
        }
        val rms=if(samples>0)sqrt(sum/samples) else 0.0
        val values=mapOf("samples" to samples.toString(),"rms" to rms.toString(),"peak" to peak.toString(),"clipped_samples" to clipped.toString(),"sample_rate_hz" to rate.toString(),"audio_target" to "BUILTIN_MIC","preferred_route_requested" to preferred.toString(),
            "probe_kind" to "builtin_pcm_capture","capture_window_complete" to windowComplete.toString(),"read_error" to error.toString())+route.evidence()
        val observation=checkNotNull(ProbeObservations.assess("microphone",d.parameters.getValue("field"),values))
        return Observation(observation.status,observation.values,observation.message)
    }
    @Suppress("MissingPermission")
    private fun network(d:DiagnosticDefinition):Observation {
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val cap=cm.getNetworkCapabilities(cm.activeNetwork)
        return when(d.parameters["field"]) {
            "wifi_enabled" -> info(context.getSystemService(WifiManager::class.java).isWifiEnabled)
            "transport" -> info(listOf(NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",NetworkCapabilities.TRANSPORT_VPN to "VPN").filter { cap?.hasTransport(it.first)==true }.joinToString { it.second }.ifBlank { "No active network" })
            "validated" -> info(cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?:false)
            "wifi_link" -> {
                val w=context.getSystemService(WifiManager::class.java).connectionInfo
                if(w.networkId==-1) inconclusive("Not connected to Wi-Fi") else info("RSSI=${w.rssi}dBm, link=${w.linkSpeed}Mbps, frequency=${w.frequency}MHz, Rx=${w.rxLinkSpeedMbps}Mbps, Tx=${w.txLinkSpeedMbps}Mbps")
            }
            "airplane" -> info(Settings.Global.getInt(context.contentResolver,Settings.Global.AIRPLANE_MODE_ON,0)==1)
            "sim_state" -> if(!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))unsupported("No telephony feature") else info(context.getSystemService(TelephonyManager::class.java).simState)
            "nfc_enabled" -> NfcAdapter.getDefaultAdapter(context)?.let { info(it.isEnabled) }?:unsupported("NFC absent")
            "usb_devices" -> info(context.getSystemService(UsbManager::class.java).deviceList.values.joinToString { "class=${it.deviceClass}, interfaces=${it.interfaceCount}" }.ifBlank { "No USB peripheral attached; data-pin health not tested" })
            "bluetooth_enabled" -> context.getSystemService(BluetoothManager::class.java)?.adapter?.let { info(it.isEnabled) }?:unsupported("Bluetooth absent")
            else -> unavailable("Network field unavailable")
        }
    }
    private suspend fun storage(d:DiagnosticDefinition):Observation {
        val free = StatFs(context.cacheDir.absolutePath).availableBytes
        if(free < 8L * 1024 * 1024) return inconclusive("Insufficient free space for a safe private I/O probe", mapOf("available_bytes" to free.toString()))
        val file=File.createTempFile("phoneproof-",".bin",context.cacheDir)
        return try {
            val data=ByteArray(256*1024) { (it%251).toByte() }
            val start=SystemClock.elapsedRealtimeNanos()
            currentCoroutineContext().ensureActive()
            FileOutputStream(file).use { it.write(data);it.fd.sync() }
            val writtenAt = SystemClock.elapsedRealtimeNanos()
            val read=file.readBytes()
            val readAt = SystemClock.elapsedRealtimeNanos()
            val digest=MessageDigest.getInstance("SHA-256")
            if(!digest.digest(data).contentEquals(digest.digest(read)))return Observation(DiagnosticStatus.FAIL,mapOf("bytes" to read.size.toString()),"Checksum mismatch")
            FileOutputStream(file).use { it.write(byteArrayOf(11,22,33));it.fd.sync() }
            if(!file.readBytes().contentEquals(byteArrayOf(11,22,33)))return Observation(DiagnosticStatus.FAIL,mapOf("overwrite_verified" to "false"),"Overwrite mismatch")
            currentCoroutineContext().ensureActive()
            val readAgain = file.readBytes()
            if(!readAgain.contentEquals(byteArrayOf(11,22,33))) return Observation(DiagnosticStatus.FAIL,mapOf("repeat_read_verified" to "false"),"Repeated overwrite read mismatch")
            if(!file.delete())return Observation(DiagnosticStatus.FAIL,mapOf("deleted" to "false"),"Temporary file deletion failed")
            Observation(DiagnosticStatus.PASS,mapOf("bytes_verified" to data.size.toString(),"round_trip_ms" to ((SystemClock.elapsedRealtimeNanos()-start)/1e6).toString(),"operation" to d.parameters.getValue("field"),
                "sequential_write_sync_ms" to ((writtenAt-start)/1e6).toString(), "sequential_read_ms" to ((readAt-writtenAt)/1e6).toString(), "repeat_read_verified" to "true",
                "probe_kind" to "private_storage_round_trip","round_trip_complete" to "true","verified_operations" to "create,write,read,checksum,overwrite,delete"),"App-private I/O and checksum verified; performance snapshot, not NAND health")
        } catch(e:java.io.IOException) { Observation(DiagnosticStatus.FAIL,mapOf("error_type" to e.javaClass.simpleName),"Filesystem I/O failed") }
        finally { file.delete() }
    }
    private fun sqlite():Observation {
        val path=File(context.cacheDir,"diagnostic-${UUID.randomUUID()}.db")
        val db=SQLiteDatabase.openOrCreateDatabase(path,null)
        return try {
            db.execSQL("CREATE TABLE test(value TEXT NOT NULL)");db.beginTransaction()
            try { db.execSQL("INSERT INTO test VALUES ('phoneproof')");db.setTransactionSuccessful() } finally { db.endTransaction() }
            val value=db.rawQuery("SELECT value FROM test",null).use { if(it.moveToFirst())it.getString(0) else "" }
            Observation(if(value=="phoneproof")DiagnosticStatus.PASS else DiagnosticStatus.FAIL,mapOf("transaction_committed" to (value=="phoneproof").toString()),"SQLite write/read transaction verified")
        } finally { db.close();SQLiteDatabase.deleteDatabase(path) }
    }
    private suspend fun cpu():Observation {
        if(context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
            return inconclusive("Computation deferred while Android reports severe thermal pressure")
        val digest = MessageDigest.getInstance("SHA-256")
        val data = ByteArray(4096) { it.toByte() }
        val expected = digest.digest(data)
        val timings = mutableListOf<Double>()
        val deadline = SystemClock.elapsedRealtime() + 1500
        repeat(3) {
            val start = SystemClock.elapsedRealtimeNanos()
            repeat(1000) { iteration ->
                if(iteration % 32 == 0) {
                    currentCoroutineContext().ensureActive()
                    if(SystemClock.elapsedRealtime() > deadline) return inconclusive("Bounded computation budget reached")
                }
                if(!digest.digest(data).contentEquals(expected)) return Observation(DiagnosticStatus.FAIL, mapOf("output_consistent" to "false"), "Repeated computation output disagreed")
            }
            timings += (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            delay(50)
        }
        return Observation(DiagnosticStatus.PASS, mapOf("performance_snapshot_ms" to timings.average().toString(),
            "window_times_ms" to timings.joinToString(","), "windows_completed" to "3", "output_consistent" to "true",
            "timing_ratio" to (timings.max() / timings.min().coerceAtLeast(0.001)).toString()),
            "Three bounded computation windows completed. Timing reflects current operating conditions.")
    }

    private suspend fun memory():Observation {
        return try {
            val data = ByteArray(1024*1024)
            repeat(2) { pass ->
                for(index in data.indices) {
                    if(index % 65536 == 0) currentCoroutineContext().ensureActive()
                    data[index] = ((index + pass) % 127).toByte()
                }
                if(!data.indices.all { data[it] == ((it + pass) % 127).toByte() })
                    return Observation(DiagnosticStatus.FAIL,mapOf("bytes" to data.size.toString(),"verified" to "false"),"Allocated memory verification disagreed")
            }
            Observation(DiagnosticStatus.PASS,mapOf("bytes" to data.size.toString(),"verified" to "true", "patterns_verified" to "2"),"Bounded 1 MiB allocation and two patterns verified")
        } catch(error:OutOfMemoryError) {
            inconclusive("The bounded allocation was rejected under current memory pressure",mapOf("allocation_failed" to "true", "requested_bytes" to "1048576"))
        }
    }
    private fun keystore():Observation {
        val alias="phoneproof-diagnostic-${UUID.randomUUID()}";val store=KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        return try {
            val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            val key=generator.generateKey();val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key)
            val data="PhoneProof challenge".toByteArray();val encrypted=cipher.doFinal(data)
            val decrypt=Cipher.getInstance("AES/GCM/NoPadding");decrypt.init(Cipher.DECRYPT_MODE,key,javax.crypto.spec.GCMParameterSpec(128,cipher.iv))
            val valid=data.contentEquals(decrypt.doFinal(encrypted))
            val info=SecretKeyFactory.getInstance(key.algorithm,"AndroidKeyStore").getKeySpec(key,KeyInfo::class.java) as KeyInfo
            Observation(if(valid)DiagnosticStatus.PASS else DiagnosticStatus.FAIL,mapOf("round_trip" to valid.toString(),"hardware_backed" to info.isInsideSecureHardware.toString()),"Key operation verified; hardware backing is capability metadata")
        } finally { store.deleteEntry(alias) }
    }
    @Suppress("MissingPermission")
    private suspend fun bluetoothScan():Observation {
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter?:return unsupported("Bluetooth absent")
        if(!adapter.isEnabled)return inconclusive("Enable Bluetooth to run discovery")
        val scanner=adapter.bluetoothLeScanner?:return unavailable("BLE scanner unavailable")
        val count=java.util.concurrent.atomic.AtomicInteger();val error=java.util.concurrent.atomic.AtomicInteger()
        val callback=object:ScanCallback() { override fun onScanResult(type:Int,result:ScanResult) { count.incrementAndGet() };override fun onScanFailed(code:Int) { error.set(code) } }
        try { withContext(Dispatchers.Main) { scanner.startScan(callback) };delay(5000) } finally { scanner.stopScan(callback) }
        return if(error.get()!=0)inconclusive("BLE scan error ${error.get()}") else if(count.get()==0)inconclusive("No nearby advertisements observed; not proof of radio failure") else Observation(DiagnosticStatus.PASS,mapOf("advertisements_observed" to count.get().toString()),"Nearby BLE advertisements observed; no addresses retained")
    }
    @Suppress("MissingPermission")
    private suspend fun location(d:DiagnosticDefinition):Observation {
        val manager=context.getSystemService(LocationManager::class.java)
        if(!manager.isLocationEnabled)return unavailable("Location services disabled")
        if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER))return unavailable("GPS provider disabled")
        if(d.parameters["field"]=="satellites") {
            val observed=CompletableDeferred<Pair<Int,Int>>()
            val callback=object:GnssStatus.Callback() { override fun onSatelliteStatusChanged(status:GnssStatus) { observed.complete(status.satelliteCount to (0 until status.satelliteCount).count { status.usedInFix(it) }) } }
            try { withContext(Dispatchers.Main) { manager.registerGnssStatusCallback(callback,main) };val v=withTimeoutOrNull(10000) { observed.await() };return if(v==null)inconclusive("No GNSS status; run outdoors with location active") else info("visible=${v.first}, used=${v.second}","satellites") } finally { manager.unregisterGnssStatusCallback(callback) }
        }
        val acquired=CompletableDeferred<Location>();val start=SystemClock.elapsedRealtime()
        val listener=object:LocationListener { override fun onLocationChanged(location:Location) { if(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos in 0L until 10_000_000_000L)acquired.complete(location) };override fun onProviderEnabled(provider:String) {};override fun onProviderDisabled(provider:String) {} }
        try {
            withContext(Dispatchers.Main) { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,listener,Looper.getMainLooper()) }
            val fix=withTimeoutOrNull(28000) { acquired.await() }?:return inconclusive("No fresh GPS fix within 28 seconds. Retry outdoors.")
            val values=mapOf("accuracy_m" to (if(fix.hasAccuracy())fix.accuracy.toString() else "unavailable"),"time_to_fix_ms" to (SystemClock.elapsedRealtime()-start).toString(),"age_ms" to ((SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos)/1000000).toString(),"probe_kind" to "fresh_location_fix","fix_observed" to "true")
            val observation=checkNotNull(ProbeObservations.assess("location",d.parameters.getValue("field"),values))
            return Observation(observation.status,observation.values,observation.message)
        } finally { manager.removeUpdates(listener) }
    }
    private suspend fun charging(d:DiagnosticDefinition):Observation {
        fun plugged()=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1)?:-1
        val initial=plugged();if(initial<0)return unavailable("Power source unavailable")
        val wantConnect=d.parameters["event"]=="connect"
        if((initial>0)==wantConnect)return inconclusive(if(wantConnect)"Unplug first, then restart and connect the charger" else "Connect a charger first, then restart and unplug")
        val until=SystemClock.elapsedRealtime()+35000
        while(SystemClock.elapsedRealtime()<until) { delay(300);val now=plugged();if(now>=0&&(now>0)==wantConnect)return Observation(DiagnosticStatus.PASS,mapOf("initial_plugged" to initial.toString(),"final_plugged" to now.toString()),"Real power-source transition observed; charging watts and USB data pins are not inferred") }
        return inconclusive("No requested power transition observed. Retry with a known charger.")
    }
}
