package pk.phoneproof.app

import pk.phoneproof.diagnostics.*

/** Navigation follows actual recorded attempts; metadata never counts as a hardware check. */
data class InspectionProgress(
    val checks: List<DiagnosticDefinition>,
    val recorded: Int,
    val passed: Int,
    val failed: Int,
    val unavailable: Int,
    val required: List<DiagnosticDefinition>,
) {
    val next: DiagnosticDefinition? get() = required.firstOrNull()
    val ready: Boolean get() = checks.isNotEmpty() && required.isEmpty() && passed + failed > 0
    companion object {
        fun from(definitions: List<DiagnosticDefinition>, attempts: List<DiagnosticResult>, rule: ScoringRule?): InspectionProgress {
            val latest = DiagnosticAttempts.latest(attempts)
            val order = listOf("display", "touch", "camera", "audio", "charging", "sensors", "buttons", "storage", "biometrics", "security")
            val checks = definitions.filter { it.implementation == "ENABLED" && it.weight > 0 }
                .sortedWith(compareBy<DiagnosticDefinition> { order.indexOf(it.category).let { index -> if(index < 0) Int.MAX_VALUE else index } }.thenBy { it.id })
            val results = checks.mapNotNull { latest[it.id] }
            val required = checks.filter { d ->
                val r = latest[d.id]
                r == null || (rule?.cap(d) != null && r.status in setOf(DiagnosticStatus.INCONCLUSIVE, DiagnosticStatus.SKIPPED_BY_USER))
            }
            return InspectionProgress(checks, results.size,
                results.count { it.status == DiagnosticStatus.PASS }, results.count { it.status == DiagnosticStatus.FAIL },
                results.count { it.status in setOf(DiagnosticStatus.NOT_SUPPORTED, DiagnosticStatus.NOT_ACCESSIBLE) }, required)
        }
    }
}
