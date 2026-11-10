package pk.phoneproof.diagnostics

/** Presentation groups retain the original definitions, versions and scoring weights. */
data class InspectionWorkflow(
    val id: String,
    val title: String,
    val kind: String,
    val definitions: List<DiagnosticDefinition>,
)

object InspectionWorkflows {
    private val automaticHandlers = setOf(
        "system", "feature", "display", "battery", "thermal", "sensor_metadata", "camera_metadata",
        "audio_info", "network", "storage", "sqlite", "cpu", "memory", "keystore",
    )

    fun automatic(definitions: List<DiagnosticDefinition>): List<DiagnosticDefinition> = definitions.filter {
        it.implementation == "ENABLED" && it.mode == "AUTOMATIC" && it.handler in automaticHandlers
    }

    fun manual(definitions: List<DiagnosticDefinition>): List<InspectionWorkflow> {
        val enabled = definitions.filter { it.implementation == "ENABLED" }
        val result = mutableListOf<InspectionWorkflow>()
        fun group(id: String, title: String, kind: String, checks: List<DiagnosticDefinition>) {
            if (checks.isNotEmpty()) result += InspectionWorkflow(id, title, kind, checks.sortedBy { it.id })
        }
        group("display", "Display", "DISPLAY", enabled.filter { it.handler == "display_guided" })
        group("touch", "Touchscreen", "TOUCH", enabled.filter { it.handler == "touch" })
        enabled.filter { it.handler in setOf("camera_capture", "torch") }.groupBy {
            it.parameters["camera_id"] ?: it.id.removePrefix("camera.").substringBefore('.')
        }.toSortedMap().forEach { (camera, checks) ->
            group("camera:$camera", "Camera $camera", "CAMERA", checks)
        }
        group("audio", "Sound & microphone", "AUDIO", enabled.filter { it.handler in setOf("microphone", "speaker") })
        group("controls", "Buttons & vibration", "CONTROLS", enabled.filter { it.handler == "buttons" })
        val scoredSensorTypes = enabled.filter { it.handler == "sensor_sample" && it.weight > 0 }
            .map { it.parameters["type"] }.toSet()
        group("motion", "Motion & sensors", "MOTION", enabled.filter {
            it.handler == "sensor_sample" && it.parameters["type"] in scoredSensorTypes
        })
        group("charging", "Charging", "CHARGING", enabled.filter { it.handler == "charging" })
        group("biometric", "Face or fingerprint", "BIOMETRIC", enabled.filter { it.handler == "biometric" })
        return result
    }

    fun advanced(definitions: List<DiagnosticDefinition>): List<InspectionWorkflow> {
        val assigned = (manual(definitions).flatMap { it.definitions } + automatic(definitions)).map { it.id }.toSet()
        return definitions.filter { it.implementation == "ENABLED" && it.id !in assigned }.groupBy {
            when (it.handler) {
                "backend" -> "connection"
                "location", "bluetooth_scan" -> "connectivity"
                else -> it.handler
            }
        }.toSortedMap().map { (key, checks) ->
            InspectionWorkflow("advanced:$key", when (key) {
                "connection" -> "Server connection"
                "connectivity" -> "Nearby devices & location"
                else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }, if (key == "connection") "CONNECTION" else "ADVANCED", checks.sortedBy { it.id })
        }
    }
}
