package pk.phoneproof.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
enum class DiagnosticStatus { PASS, FAIL, NOT_SUPPORTED, NOT_ACCESSIBLE, SKIPPED_BY_USER, INCONCLUSIVE }

@Serializable
data class DiagnosticDefinition(
    val id: String,
    val title: String,
    val category: String,
    val version: Int = 1,
    val mode: String = "AUTOMATIC",
    val implementation: String = "DISABLED",
    @SerialName("min_api") val minApi: Int = 29,
    val permissions: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val weight: Double = 0.0,
    val critical: Boolean = false,
    @SerialName("pass_criteria") val passCriteria: String,
    @SerialName("fail_criteria") val failCriteria: String,
    @SerialName("not_supported") val notSupported: String,
    @SerialName("not_accessible") val notAccessible: String,
    @SerialName("evidence_type") val evidenceType: String,
    val handler: String,
    val parameters: Map<String, String> = emptyMap(),
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 15,
    val description: String = "",
    @SerialName("scoring_type") val scoringType: String = "INFORMATION",
) {
    val name: String get() = title
    val method: String get() = handler
    val expectedCondition: String get() = passCriteria
}

@Serializable
data class DiagnosticResult(
    @SerialName("check_id") val checkId: String,
    val status: DiagnosticStatus,
    @SerialName("measured_value") val measuredValue: Map<String, String> = emptyMap(),
    val evidence: Map<String, String> = emptyMap(),
    val message: String = "",
    @SerialName("started_at") val startedAt: String = Instant.now().toString(),
    @SerialName("completed_at") val completedAt: String = Instant.now().toString(),
    @SerialName("definition_version") val definitionVersion: Int = 1,
    val method: String = "",
    @SerialName("expected_condition") val expectedCondition: String = "",
    val confidence: Double? = null,
    @SerialName("app_version") val appVersion: String = "1.0.0",
) {
    val timestamp: String get() = completedAt
}

@Serializable
data class CapabilityProfile(
    val api: Int,
    val features: Map<String, Boolean>,
    val cameras: List<String>,
    val sensors: Map<String, Map<String, String>>,
    val device: Map<String, String>,
    val limitations: List<String> = emptyList(),
)

data class Eligibility(val eligible: Boolean, val status: DiagnosticStatus? = null, val reason: String = "")

object EligibilityFilter {
    fun evaluate(definition: DiagnosticDefinition, api: Int, features: Map<String, Boolean>, permissions: Set<String>): Eligibility {
        if (definition.implementation != "ENABLED") return Eligibility(false, null, "Implementation ${definition.implementation.lowercase()}; not tested")
        if (api < definition.minApi) return Eligibility(false, DiagnosticStatus.NOT_SUPPORTED, "Requires Android API ${definition.minApi}")
        val absent = definition.capabilities.filter { features[it] == false }
        if (absent.isNotEmpty()) return Eligibility(false, DiagnosticStatus.NOT_SUPPORTED, "Not exposed: ${absent.joinToString()}")
        val unknown = definition.capabilities.filter { it !in features }
        if (unknown.isNotEmpty()) return Eligibility(false, DiagnosticStatus.NOT_ACCESSIBLE, "Capability discovery unavailable: ${unknown.joinToString()}")
        val denied = definition.permissions.filter { it !in permissions }
        if (denied.isNotEmpty()) return Eligibility(false, DiagnosticStatus.NOT_ACCESSIBLE, "Permission not granted: ${denied.joinToString()}")
        return Eligibility(true)
    }
}

data class ScorePreview(val score: Double?, val completedWeight: Double, val applicableWeight: Double, val incomplete: List<String>, val criticalFailures: List<String>,
    val ruleVersion:Int=1,val coveragePercent:Double=0.0,val categories:List<CategoryScore> = emptyList(),val caps:List<ScoreCap> = emptyList(),val grade:String?=null)

/** Local preview only. Final score and immutable report are calculated by the server. */
object ScoringPreview {
    fun calculate(definitions: List<DiagnosticDefinition>, results: List<DiagnosticResult>, rule:ScoringRule=ScoringRule.legacy()): ScorePreview {
        if(rule.algorithm=="category_weighted")return CategoryScoring.calculate(definitions,results,rule)
        require(rule.algorithm=="check_weighted") { "Unsupported scoring algorithm" }
        val latest = DiagnosticAttempts.latest(results)
        val applicable = definitions.filter { it.implementation == "ENABLED" && it.weight > 0 && latest[it.id]?.status != DiagnosticStatus.NOT_SUPPORTED }.sortedBy { it.id }
        val completed = applicable.filter { latest[it.id]?.status in setOf(DiagnosticStatus.PASS, DiagnosticStatus.FAIL) }
        val total = completed.sumOf { it.weight }
        val passed = completed.filter { latest[it.id]?.status == DiagnosticStatus.PASS }.sumOf { it.weight }
        val critical = completed.filter { it.critical && latest[it.id]?.status == DiagnosticStatus.FAIL }.map { it.id }.sorted()
        val score = if (total == 0.0) null else (passed * 100 / total).let { if (critical.isNotEmpty()) minOf(it, rule.defaultCriticalCap.toDouble()) else it }
        val possible=applicable.sumOf { it.weight }
        return ScorePreview(score, total, possible, applicable.filter { it !in completed }.map { it.id }.sorted(), critical,
            rule.version,if(possible>0)100*total/possible else 0.0,grade=rule.grade(score))
    }
}
