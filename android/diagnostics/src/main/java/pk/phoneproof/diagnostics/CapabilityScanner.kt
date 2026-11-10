package pk.phoneproof.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

class CapabilityScanner(private val context: Context) {
    companion object {
        val sensorTypes = mapOf("accelerometer" to 1,"magnetometer" to 2,"gyroscope" to 4,"light" to 5,"barometer" to 6,"proximity" to 8,"gravity" to 9,"linear_acceleration" to 10,"rotation_vector" to 11,"humidity" to 12,"ambient_temperature" to 13,"game_rotation" to 15,"step_detector" to 18,"step_counter" to 19,"hinge" to 36)
        val featureNames = mapOf("verified_boot" to "android.software.verified_boot","strongbox" to "android.hardware.strongbox_keystore","fingerprint" to "android.hardware.fingerprint","face" to "android.hardware.biometrics.face","vulkan" to "android.hardware.vulkan.version","usb_host" to "android.hardware.usb.host","nfc" to "android.hardware.nfc","nfc_hce" to "android.hardware.nfc.hce","uwb" to "android.hardware.uwb","telephony" to "android.hardware.telephony","esim" to "android.hardware.telephony.euicc","bluetooth" to "android.hardware.bluetooth","ble" to "android.hardware.bluetooth_le","wifi" to "android.hardware.wifi","gps" to "android.hardware.location.gps","microphone" to "android.hardware.microphone","touchscreen" to "android.hardware.touchscreen","multitouch" to "android.hardware.touchscreen.multitouch")
    }
    fun scan(): CapabilityProfile {
        val pm = context.packageManager
        val sm = context.getSystemService(SensorManager::class.java)
        val features = featureNames.mapValues { pm.hasSystemFeature(it.value) }.toMutableMap()
        sensorTypes.forEach { (name, type) -> features["sensor.$name"] = sm?.getDefaultSensor(type) != null }
        val limitations = mutableListOf<String>()
        val cameras = try { context.getSystemService(CameraManager::class.java)?.cameraIdList?.toList() ?: emptyList() }
        catch (e: Exception) { limitations.add("Camera enumeration unavailable: ${e.javaClass.simpleName}"); emptyList() }
        features["camera"] = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val cameraManager = context.getSystemService(CameraManager::class.java)
        cameras.forEach { id ->
            try {
                // Null or blocked discovery remains unknown, never absent hardware.
                cameraManager?.getCameraCharacteristics(id)?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)?.let {
                    features["camera.$id.flash"] = it
                }
            } catch (e: Exception) {
                limitations.add("Flash discovery unavailable for lens $id: ${e.javaClass.simpleName}")
            }
        }
        val sensors = sm?.getSensorList(android.hardware.Sensor.TYPE_ALL)?.associate { "${it.type}:${it.id}" to mapOf("name" to it.name, "vendor" to it.vendor,"type" to it.type.toString(),"version" to it.version.toString()) } ?: emptyMap()
        return CapabilityProfile(Build.VERSION.SDK_INT,features,cameras,sensors,mapOf(
            "manufacturer" to Build.MANUFACTURER,"brand" to Build.BRAND,"model" to Build.MODEL,"product" to Build.PRODUCT,
            "device" to Build.DEVICE,"release" to Build.VERSION.RELEASE,"api" to Build.VERSION.SDK_INT.toString(),"build_id" to Build.ID,
            "fingerprint" to Build.FINGERPRINT,"security_patch" to Build.VERSION.SECURITY_PATCH,"abis" to Build.SUPPORTED_ABIS.joinToString()),limitations)
    }
}
