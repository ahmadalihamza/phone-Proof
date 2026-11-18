package pk.phoneproof.diagnostics

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Supplemental telemetry stays inside existing immutable result evidence and sync acknowledgements. */
class AdvancedObservations(private val context: Context) {
    suspend fun enrich(definition: DiagnosticDefinition, result: DiagnosticResult): DiagnosticResult {
        val fields = try {
            when(definition.id) {
                "battery.health" -> battery()
                "system.model" -> software()
                "thermal.status" -> thermal()
                "performance.memory" -> memory()
                "performance.cpu" -> processor()
                "storage.checksum" -> storage()
                "network.validated" -> connectivity()
                else -> if(definition.handler == "camera_metadata" && definition.parameters["field"] == "facing") camera(definition) else return result
            }
        } catch(error: CancellationException) { throw error }
        catch(error: Exception) { mapOf("collection_status" to "UNAVAILABLE", "reason" to error.javaClass.simpleName) }
        val snapshot = mapOf("schema_version" to "1", "observed_at" to Instant.now().toString()) + fields
        val encoded = DiagnosticRegistry.json.encodeToString(snapshot)
        check(encoded.length <= 4000) { "Advanced evidence exceeds the synchronization bound" }
        return result.copy(evidence = result.evidence + ("advanced_v1" to encoded), completedAt = Instant.now().toString())
    }

    private suspend fun battery(): Map<String, String> {
        val manager = context.getSystemService(BatteryManager::class.java)
        val readings = mutableListOf<Map<String, String>>()
        repeat(3) { index ->
            if(index > 0) delay(400)
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val values = mutableMapOf<String, String>()
            fun extra(key: String, name: String, scale: Double = 1.0) {
                if(intent?.hasExtra(key) == true) values[name] = (intent.getIntExtra(key, Int.MIN_VALUE) / scale).toString()
            }
            fun property(id: Int, name: String, scale: Double = 1.0) {
                val number = manager.getIntProperty(id)
                if(number != Int.MIN_VALUE) values[name] = (number / scale).toString()
            }
            extra(BatteryManager.EXTRA_TEMPERATURE, "temperature_c", 10.0)
            extra(BatteryManager.EXTRA_VOLTAGE, "voltage_v", 1000.0)
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if(level >= 0 && scale > 0) values["level_percent"] = (100.0 * level / scale).toString()
            property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, "current_ma", 1000.0)
            property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, "average_current_ma", 1000.0)
            property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, "charge_mah", 1000.0)
            val energy = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            if(energy != Long.MIN_VALUE) values["energy_mwh"] = (energy / 1_000_000.0).toString()
            for((key, name) in listOf(BatteryManager.EXTRA_STATUS to "status_code", BatteryManager.EXTRA_HEALTH to "health_code", BatteryManager.EXTRA_PLUGGED to "plugged_code")) {
                if(intent?.hasExtra(key) == true) values[name] = intent.getIntExtra(key, -1).toString()
            }
            if(Build.VERSION.SDK_INT >= 34 && intent?.hasExtra(BatteryManager.EXTRA_CYCLE_COUNT) == true) {
                values["cycle_count"] = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).toString()
            }
            intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.let { values["technology"] = it.take(80) }
            readings += values
        }
        val last = readings.last().toMutableMap()
        last["sample_count"] = readings.size.toString()
        last["sample_interval_ms"] = "400"
        last["current_samples_ma"] = readings.mapNotNull { it["current_ma"] }.joinToString(",")
        val temperatures = readings.mapNotNull { it["temperature_c"]?.toDoubleOrNull() }
        if(temperatures.size == 3) last["temperature_change_c"] = (temperatures.last() - temperatures.first()).toString()
        last["charging_without_source"] = (last["status_code"] == "2" && last["plugged_code"] == "0").toString()
        last["unavailable_fields"] = listOf("current_ma", "average_current_ma", "charge_mah", "energy_mwh", "cycle_count", "temperature_c", "voltage_v")
            .filter { it !in last }.joinToString(",")
        last["limits"] = "Short passive fuel-gauge observations. Current sign follows Android. No capacity or lifespan estimate."
        return last
    }

    private fun software(): Map<String, String> {
        val patchAge = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(Build.VERSION.SECURITY_PATCH), LocalDate.now()).toString() }.getOrNull()
        val fields = mutableMapOf(
            "android_api" to Build.VERSION.SDK_INT.toString(), "android_release" to Build.VERSION.RELEASE,
            "build_id" to Build.ID.take(120), "build_tags" to Build.TAGS.orEmpty().take(120),
            "security_patch" to Build.VERSION.SECURITY_PATCH, "hardware" to Build.HARDWARE.take(120),
            "test_keys" to Build.TAGS.orEmpty().contains("test-keys").toString(),
            "visible_su" to listOf("/system/bin/su", "/system/xbin/su").any { File(it).exists() }.toString(),
            "developer_options" to (Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1).toString(),
            "secure_lock" to context.getSystemService(KeyguardManager::class.java).isDeviceSecure.toString(),
            "encryption_status_code" to context.getSystemService(DevicePolicyManager::class.java).storageEncryptionStatus.toString(),
            "verified_boot_feature" to context.packageManager.hasSystemFeature("android.software.verified_boot").toString(),
            "strongbox_feature" to context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore").toString(),
            "uptime_ms" to SystemClock.elapsedRealtime().toString(), "play_integrity" to "UNAVAILABLE",
            "limits" to "Public API metadata and visible-path heuristics. Boot-chain, malware and firmware authenticity are unverified."
        )
        patchAge?.let { fields["patch_age_days"] = it }
        WebView.getCurrentWebViewPackage()?.let { fields["webview_version"] = it.versionName.orEmpty().take(100) }
        return fields
    }

    private fun thermal(): Map<String, String> = mapOf(
        "thermal_status" to context.getSystemService(PowerManager::class.java).currentThermalStatus.toString(),
        "limits" to "Android thermal policy status. Headroom is collected by the existing rate-limited thermal.headroom check."
    )

    private fun memory(): Map<String, String> {
        val info = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return mapOf("total_bytes" to info.totalMem.toString(), "available_bytes" to info.availMem.toString(),
            "pressure_threshold_bytes" to info.threshold.toString(), "low_memory" to info.lowMemory.toString(),
            "limits" to "App-level allocation and OS memory pressure. Physical RAM chip integrity remains unverified.")
    }

    private fun processor(): Map<String, String> {
        val fields = mutableMapOf("cores_available" to Runtime.getRuntime().availableProcessors().toString(),
            "abis" to Build.SUPPORTED_ABIS.joinToString(","), "hardware" to Build.HARDWARE.take(120),
            "gles_version" to context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.glEsVersion,
            "thermal_status" to context.getSystemService(PowerManager::class.java).currentThermalStatus.toString(),
            "uptime_ms" to SystemClock.elapsedRealtime().toString())
        if(Build.VERSION.SDK_INT >= 31) {
            fields["soc_manufacturer"] = Build.SOC_MANUFACTURER.take(100)
            fields["soc_model"] = Build.SOC_MODEL.take(100)
        }
        return fields
    }

    private fun storage(): Map<String, String> {
        val fs = StatFs(context.cacheDir.absolutePath)
        return mapOf("total_bytes" to fs.totalBytes.toString(), "available_bytes" to fs.availableBytes.toString(),
            "available_percent" to (100.0 * fs.availableBytes / fs.totalBytes.coerceAtLeast(1)).toString(),
            "limits" to "Temporary app-private files only. Timing includes caches, scheduling and filesystem effects. NAND wear is unavailable.")
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun connectivity(): Map<String, String> {
        val network = context.getSystemService(ConnectivityManager::class.java)
        val caps = network.getNetworkCapabilities(network.activeNetwork)
        val fields = mutableMapOf("validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true).toString(),
            "active_network" to (caps != null).toString(), "vpn_transport" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true).toString(),
            "limits" to "Network conditions and permissions affect observations. VPN use is a neutral setting. No external probe sent.")
        if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) runCatching {
            val wifi = context.getSystemService(WifiManager::class.java).connectionInfo
            if(wifi.rssi in -126..-1) fields["wifi_rssi_dbm"] = wifi.rssi.toString()
            if(wifi.linkSpeed > 0) fields["wifi_link_mbps"] = wifi.linkSpeed.toString()
            if(wifi.frequency > 0) fields["wifi_frequency_mhz"] = wifi.frequency.toString()
        }
        runCatching {
            val phone = context.getSystemService(TelephonyManager::class.java)
            if(phone.phoneType != TelephonyManager.PHONE_TYPE_NONE) fields["cellular_network_type"] = phone.dataNetworkType.toString()
        }.onFailure { fields["cellular_metrics"] = "UNAVAILABLE_PERMISSION_OR_PLATFORM" }
        return fields
    }

    private fun camera(definition: DiagnosticDefinition): Map<String, String> {
        val c = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(definition.parameters.getValue("camera_id"))
        return buildMap {
            put("camera_id", definition.parameters.getValue("camera_id"))
            c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.let { put("af_modes", it.joinToString(",")) }
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.let { put("ois_modes", it.joinToString(",")) }
            c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { put("exposure_time_ns", it.toString()) }
            c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { put("sensitivity_iso", it.toString()) }
            c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { put("sensor_pixels", it.toString()) }
            put("limits", "Reported Camera2 capabilities. Existing guided preview and decoded captures verify operations separately.")
        }
    }
}
