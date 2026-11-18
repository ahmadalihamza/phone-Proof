package pk.phoneproof.diagnostics

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisCondition(val key: String, val op: String, val value: String)

@Serializable
data class AnalysisRule(
    val id: String, val category: String, val title: String, val severity: String,
    val kind: String, val source: String, val conditions: List<AnalysisCondition>,
    val interpretation: String, @SerialName("confidence_basis") val confidenceBasis: String,
    @SerialName("possible_explanations") val possibleExplanations: List<String>,
    @SerialName("further_inspection") val furtherInspection: String, val limitations: String,
)

@Serializable
data class AnalysisPolicy(val version: Int, val rules: List<AnalysisRule>) {
    companion object {
        fun load(context: Context): AnalysisPolicy = DiagnosticRegistry.json.decodeFromString(
            context.assets.open("analysis-policy.json").bufferedReader().use { it.readText() })
    }
}

@Serializable
data class ExpertFinding(
    val id: String, val category: String, val title: String, val severity: String, val kind: String,
    @SerialName("diagnostic_ids") val diagnosticIds: List<String>, val measurements: Map<String, String>,
    val interpretation: String, @SerialName("confidence_basis") val confidenceBasis: String,
    @SerialName("possible_explanations") val possibleExplanations: List<String>,
    @SerialName("further_inspection") val furtherInspection: String, val limitations: String,
    @SerialName("policy_version") val policyVersion: Int,
)

@Serializable
data class ExpertAssessment(@SerialName("policy_version") val policyVersion: Int,
    val method: String = "Deterministic expert rules", val findings: List<ExpertFinding>)

/** Advisory interpretation consumes stored evidence and never changes a diagnostic result or score. */
object ExpertAnalysis {
    fun fields(result: DiagnosticResult): Map<String, String> {
        val extra = result.evidence["advanced_v1"]?.let {
            runCatching { DiagnosticRegistry.json.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
        }.orEmpty()
        return result.measuredValue + extra
    }

    fun analyze(attempts: List<DiagnosticResult>, policy: AnalysisPolicy): ExpertAssessment {
        val latest = DiagnosticAttempts.latest(attempts)
        val findings = policy.rules.mapNotNull { rule ->
            val result = latest[rule.source] ?: return@mapNotNull null
            val values = fields(result)
            if(!rule.conditions.all { matches(values[it.key], it) }) return@mapNotNull null
            ExpertFinding(rule.id, rule.category, rule.title, rule.severity, rule.kind, listOf(rule.source),
                values.filterKeys { key -> rule.conditions.any { it.key == key } }, rule.interpretation,
                rule.confidenceBasis, rule.possibleExplanations, rule.furtherInspection, rule.limitations, policy.version)
        }.toMutableList()
        for(result in latest.values.sortedBy { it.checkId }) {
            if(result.status != DiagnosticStatus.FAIL) continue
            findings += ExpertFinding("functional.${result.checkId}", result.checkId.substringBefore('.'),
                "Recorded problem · ${result.checkId}", "WARNING", "FUNCTIONAL_PROBLEM", listOf(result.checkId),
                result.measuredValue, result.message, "Stored ${result.method} diagnostic outcome",
                listOf("Observed operation or inspector-confirmed issue"), "Review the original evidence and repeat the relevant inspection.",
                "Scope is limited to the recorded operation and its declared method.", policy.version)
        }
        for(category in listOf("battery", "performance", "storage", "system", "thermal", "sensors", "connectivity", "camera")) {
            val entries = latest.values.filter { it.checkId.startsWith(if(category == "sensors") "sensor." else if(category == "connectivity") "network." else "$category.") }
            val unverified = entries.count { it.status !in setOf(DiagnosticStatus.PASS, DiagnosticStatus.FAIL) }
            val kind = when {
                entries.isEmpty() || entries.any { it.status in setOf(DiagnosticStatus.INCONCLUSIVE, DiagnosticStatus.SKIPPED_BY_USER) } -> "INCONCLUSIVE"
                unverified == entries.size -> "UNAVAILABLE"
                else -> "OBSERVATION"
            }
            findings += ExpertFinding("coverage.$category", category, "$category observations", "INFO", kind,
                entries.map { it.checkId }.sorted(), mapOf("recorded_count" to entries.size.toString(), "unverified_count" to unverified.toString()),
                if(entries.isEmpty()) "No observations recorded." else "$unverified of ${entries.size} recorded observations are unavailable or inconclusive.",
                "Recorded evidence availability", listOf("Device capability, permission and inspection prerequisites vary"),
                "Review individual results and unverified areas.", "Recorded metadata alone does not establish hardware condition.", policy.version)
        }
        return ExpertAssessment(policy.version, findings = findings)
    }

    private fun matches(actual: String?, condition: AnalysisCondition): Boolean {
        if(actual == null) return false
        return when(condition.op) {
            "eq" -> actual == condition.value
            "in" -> actual in condition.value.split(',')
            "gte", "lt" -> {
                val number = actual.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return false
                val threshold = condition.value.toDouble()
                if(condition.op == "gte") number >= threshold else number < threshold
            }
            else -> false
        }
    }
}
