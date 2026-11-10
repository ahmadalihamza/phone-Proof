package pk.phoneproof.diagnostics

data class ProbeBatch(val key: String, val definitions: List<DiagnosticDefinition>)

/** Only these physical operations have criterion-specific, evidence-checked reuse. */
object DiagnosticProbePlanner {
    private val fields = mapOf(
        "storage" to setOf("create", "write", "read", "checksum", "overwrite", "delete"),
        "microphone" to setOf("capture", "amplitude", "silence", "clipping"),
        "sensor_sample" to setOf("response", "continuity", "variation"),
        "location" to setOf("fix", "accuracy"),
    )

    fun batches(definitions: List<DiagnosticDefinition>): List<ProbeBatch> {
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Duplicate diagnostic definitions" }
        return definitions.groupBy(::key).map { (key, members) -> ProbeBatch(key, members) }
    }

    internal fun key(d: DiagnosticDefinition): String {
        if (d.implementation != "ENABLED" || d.parameters["field"] !in fields[d.handler].orEmpty()) return "single:${d.id}"
        if (d.handler == "sensor_sample" && d.parameters["type"] == null) return "single:${d.id}"
        // Eligibility and physical parameters are part of the key. Never mix lenses,
        // sensors, permissions or future parameterized probe configurations.
        fun StringBuilder.part(value: String) { append(value.length).append(':').append(value) }
        return buildString {
            part(d.handler); part(d.version.toString()); part(d.minApi.toString()); part(d.timeoutSeconds.toString()); part(d.mode)
            d.capabilities.sorted().forEach { part("cap:$it") }
            d.permissions.sorted().forEach { part("permission:$it") }
            d.parameters.filterKeys { it != "field" }.toSortedMap().forEach { (name, value) -> part(name); part(value) }
        }
    }

    internal fun representative(definitions: List<DiagnosticDefinition>): DiagnosticDefinition {
        val preferred = when (definitions.first().handler) {
            "storage" -> "checksum"
            "microphone" -> "capture"
            "sensor_sample" -> "response"
            "location" -> "fix"
            else -> null
        }
        return definitions.firstOrNull { it.parameters["field"] == preferred } ?: definitions.first()
    }
}
