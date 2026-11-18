package pk.phoneproof.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import pk.phoneproof.diagnostics.*

@RunWith(AndroidJUnit4::class)
class AdvancedDiagnosticAcceptanceTest {
    @Test fun realAutomaticEvidenceUsesExistingDefinitionsAndRetainsLimits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification"))
        val profile = CapabilityScanner(context).scan()
        val definitions = DiagnosticRegistry.expanded(context, profile)
        val engine = DiagnosticsEngine(context)
        val ids = listOf("system.model", "battery.health", "performance.cpu", "performance.memory", "storage.checksum", "storage.sqlite", "thermal.status", "network.validated")
        val results = ids.map { id -> engine.execute(definitions.single { it.id == id }, profile) }
        assertEquals(ids, results.map { it.checkId })
        val evidence = results.filter { it.checkId != "storage.sqlite" }.associate { result ->
            val encoded = requireNotNull(result.evidence["advanced_v1"]) { "Missing advanced evidence for ${result.checkId}: ${result.message}" }
            assertTrue(encoded.length <= 4000)
            result.checkId to JSONObject(encoded)
        }
        assertEquals(3, evidence.getValue("battery.health").getString("sample_count").toInt())
        assertEquals("UNAVAILABLE", evidence.getValue("system.model").getString("play_integrity"))
        assertTrue(evidence.getValue("performance.memory").getString("total_bytes").toLong() > 0)
        assertTrue(evidence.getValue("storage.checksum").getString("available_bytes").toLong() >= 0)
        val memory = results.single { it.checkId == "performance.memory" }
        assertEquals(DiagnosticStatus.PASS, memory.status)
        assertEquals("2", memory.measuredValue["patterns_verified"])
        val storage = results.single { it.checkId == "storage.checksum" }
        assertEquals(DiagnosticStatus.PASS, storage.status)
        assertEquals("true", storage.measuredValue["repeat_read_verified"])
        assertTrue(storage.measuredValue.getValue("sequential_read_ms").toDouble() >= 0)
        val cpu = results.single { it.checkId == "performance.cpu" }
        if(cpu.status == DiagnosticStatus.PASS) {
            assertEquals("3", cpu.measuredValue["windows_completed"])
            assertEquals(3, cpu.measuredValue.getValue("window_times_ms").split(',').size)
        } else assertEquals(DiagnosticStatus.INCONCLUSIVE, cpu.status)
        val assessment = ExpertAnalysis.analyze(results, AnalysisPolicy.load(context))
        assertEquals(1, assessment.policyVersion)
        assertTrue(assessment.findings.all { it.limitations.isNotBlank() && it.confidenceBasis.isNotBlank() })
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("advanced_observation", "Actual battery, software, memory, storage, thermal and network evidence collected; cpu=${cpu.status}; policy=1")
        })
    }
}
