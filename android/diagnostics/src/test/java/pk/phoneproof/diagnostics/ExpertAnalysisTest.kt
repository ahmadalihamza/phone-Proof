package pk.phoneproof.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.junit.Assert.*
import org.junit.Test

class ExpertAnalysisTest {
    @Serializable data class Case(val name: String, val results: List<DiagnosticResult>, @SerialName("expected_rule_ids") val expected: List<String>)
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
    private val policy = DiagnosticRegistry.json.decodeFromString<AnalysisPolicy>(resource("analysis-policy.json"))

    @Test fun sharedPolicyFixturesMatchWithoutInventingConfidenceOrChangingResults() {
        val cases = DiagnosticRegistry.json.decodeFromString<List<Case>>(resource("analysis-examples.json"))
        for(case in cases) {
            val before = case.results.toList()
            val findings = ExpertAnalysis.analyze(case.results, policy).findings
            assertEquals(case.name, case.expected, findings.filterNot { it.id.startsWith("coverage.") }.map { it.id })
            assertEquals(before, case.results)
            assertTrue(findings.all { it.policyVersion == 1 })
        }
    }

    @Test fun nestedTelemetryAndCorruptEvidenceRemainDistinct() {
        val original = DiagnosticResult("battery.health", DiagnosticStatus.PASS, evidence = mapOf("advanced_v1" to "{\"temperature_c\":\"46\"}"))
        assertTrue(ExpertAnalysis.analyze(listOf(original), policy).findings.any { it.id == "battery.hot" })
        val corrupt = original.copy(evidence = mapOf("advanced_v1" to "{broken"))
        assertFalse(ExpertAnalysis.analyze(listOf(corrupt), policy).findings.any { it.id == "battery.hot" })
        assertEquals("INCONCLUSIVE", ExpertAnalysis.analyze(emptyList(), policy).findings.first().kind)
    }

    @Test fun anObservedFailureRemainsVisibleAlongsideAdvisoryResults() {
        val failed = DiagnosticResult("storage.checksum", DiagnosticStatus.FAIL, mapOf("verified" to "false"))
        assertTrue(ExpertAnalysis.analyze(listOf(failed), policy).findings.any { it.kind == "FUNCTIONAL_PROBLEM" })
    }
}
