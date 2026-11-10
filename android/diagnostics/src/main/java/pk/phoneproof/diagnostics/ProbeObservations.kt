package pk.phoneproof.diagnostics

internal data class ProbeAssessment(val status: DiagnosticStatus, val values: Map<String, String>, val message: String)

/** Raw values remain observations. These predicates do not certify acoustic/sensor accuracy. */
internal object ProbeObservations {
    fun assess(handler: String, field: String, values: Map<String, String>): ProbeAssessment? = when (handler) {
        "storage" -> storage(field, values)
        "microphone" -> microphone(field, values)
        "sensor_sample" -> sensor(field, values)
        "location" -> location(field, values)
        else -> null
    }

    private fun storage(field: String, values: Map<String, String>): ProbeAssessment? {
        if (field !in setOf("create", "write", "read", "checksum", "overwrite", "delete")) return null
        if (values["probe_kind"] != "private_storage_round_trip" || values["round_trip_complete"] != "true") return null
        if (values["bytes_verified"]?.toLongOrNull()?.let { it > 0 } != true ||
            field !in values["verified_operations"].orEmpty().split(',')) return null
        return ProbeAssessment(DiagnosticStatus.PASS, values + ("operation" to field),
            "App-private $field verified in the same completed I/O round trip; not NAND health")
    }

    private fun microphone(field: String, values: Map<String, String>): ProbeAssessment? {
        if (field !in setOf("capture", "amplitude", "silence", "clipping")) return null
        if (values["probe_kind"] != "builtin_pcm_capture") return null
        val error = values["read_error"]?.toIntOrNull() ?: return null
        val samples = values["samples"]?.toLongOrNull() ?: return null
        val rms = values["rms"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val clipped = values["clipped_samples"]?.toLongOrNull()?.takeIf { it in 0..samples } ?: return null
        val outcome = when {
            values["audio_route_verified"] != "true" -> DiagnosticStatus.INCONCLUSIVE to "Built-in microphone route was not stable; retry without external audio"
            error != 0 -> (if (field == "capture") DiagnosticStatus.FAIL else DiagnosticStatus.INCONCLUSIVE) to "AudioRecord stream error prevented a reliable $field observation"
            samples <= 0 -> DiagnosticStatus.INCONCLUSIVE to "No microphone samples were captured"
            values["capture_window_complete"] != "true" -> DiagnosticStatus.INCONCLUSIVE to "Microphone capture ended before its observation window completed"
            field != "capture" && (rms < 50 || clipped > samples / 100) -> DiagnosticStatus.INCONCLUSIVE to "Quiet or clipped input; environment and speaking level affect this indicator"
            else -> DiagnosticStatus.PASS to "Actual built-in PCM $field observation; raw audio discarded, no acoustic certification"
        }
        return ProbeAssessment(outcome.first, values, outcome.second)
    }

    private fun sensor(field: String, values: Map<String, String>): ProbeAssessment? {
        if (field !in setOf("response", "continuity", "variation")) return null
        if (values["probe_kind"] != "sensor_window" || values["sample_window_complete"] != "true") return null
        val count = values["samples"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val outcome = when {
            values["stream_valid"] != "true" -> DiagnosticStatus.FAIL to "Non-finite, inconsistent or non-monotonic sensor samples observed"
            count == 0 -> DiagnosticStatus.INCONCLUSIVE to "No fresh sensor events; move or cover the device and retry"
            field == "continuity" && count < 2 -> DiagnosticStatus.INCONCLUSIVE to "Not enough fresh events for continuity"
            field == "variation" -> {
                val variation = values["variation"].orEmpty().split(',').map { it.trim().toDoubleOrNull() ?: return null }
                if (variation.isEmpty() || variation.any { !it.isFinite() || it < 0 }) return null
                if (variation.all { it == 0.0 }) DiagnosticStatus.INCONCLUSIVE to "Constant values may be correct while stationary; repeat with the requested stimulus"
                else DiagnosticStatus.PASS to "Variation observed in the same sensor window; not calibrated accuracy"
            }
            else -> DiagnosticStatus.PASS to "Fresh finite sensor $field observation; not calibrated sensor accuracy"
        }
        return ProbeAssessment(outcome.first, values, outcome.second)
    }

    private fun location(field: String, values: Map<String, String>): ProbeAssessment? {
        if (field !in setOf("fix", "accuracy")) return null
        if (values["probe_kind"] != "fresh_location_fix" || values["fix_observed"] != "true") return null
        val age = values["age_ms"]?.toLongOrNull() ?: return null
        val accuracy = values["accuracy_m"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        val outcome = when {
            age !in 0 until 10_000 -> DiagnosticStatus.INCONCLUSIVE to "The location sample is not fresh"
            field == "accuracy" && accuracy == null -> DiagnosticStatus.NOT_ACCESSIBLE to "The fresh location fix did not expose accuracy"
            else -> DiagnosticStatus.PASS to "Fresh location $field observed; coordinates never retained, no radio-health claim"
        }
        return ProbeAssessment(outcome.first, values, outcome.second)
    }
}

internal data class TimedSensorSample(val timestampNanos: Long, val axes: List<Float>)

internal object SensorWindowAnalysis {
    fun values(samples: List<TimedSensorSample>, startNanos: Long, endNanos: Long): Map<String, String> {
        require(startNanos >= 0 && endNanos >= startNanos) { "Invalid sensor observation window" }
        val fresh = samples.filter { it.timestampNanos in startNanos..endNanos }
        val dimensions = fresh.firstOrNull()?.axes?.size ?: 0
        val finite = fresh.all { it.axes.isNotEmpty() && it.axes.size == dimensions && it.axes.all(Float::isFinite) }
        val monotonic = fresh.zipWithNext().all { it.second.timestampNanos > it.first.timestampNanos }
        val variation = if (fresh.isNotEmpty() && finite) (0 until dimensions).map { axis ->
            fresh.maxOf { it.axes[axis].toDouble() } - fresh.minOf { it.axes[axis].toDouble() }
        } else emptyList()
        val intervals = fresh.zipWithNext().map { (it.second.timestampNanos - it.first.timestampNanos) / 1e6 }
        return mapOf("probe_kind" to "sensor_window", "sample_window_complete" to "true",
            "samples" to fresh.size.toString(), "outside_window_samples" to (samples.size - fresh.size).toString(),
            "stream_valid" to (finite && monotonic).toString(), "timestamp_monotonic" to monotonic.toString(),
            "variation" to variation.joinToString(), "latest_axes" to fresh.lastOrNull()?.axes.orEmpty().joinToString(),
            "observed_interval_ms" to if (intervals.isEmpty()) "unavailable" else intervals.average().toString(),
            "sample_window_ms" to ((endNanos - startNanos) / 1e6).toString())
    }
}
