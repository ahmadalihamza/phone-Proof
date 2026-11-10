package pk.phoneproof.diagnostics

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ModelsTest {
    private fun definition(id:String="test",weight:Double=1.0,critical:Boolean=false)=DiagnosticDefinition(id,"Test","touch",implementation="ENABLED",capabilities=listOf("touchscreen"),permissions=listOf("camera"),weight=weight,critical=critical,passCriteria="Observed",failCriteria="Actual error",notSupported="Absent",notAccessible="Permission denied",evidenceType="EVENTS",handler="touch")
    @Test fun absentHardwareIsNotFailure() {
        val gate=EligibilityFilter.evaluate(definition(),36,mapOf("touchscreen" to false),setOf("camera"))
        assertEquals(DiagnosticStatus.NOT_SUPPORTED,gate.status);assertFalse(gate.eligible)
    }
    @Test fun permissionDenialIsInaccessible() {
        assertEquals(DiagnosticStatus.NOT_ACCESSIBLE,EligibilityFilter.evaluate(definition(),36,mapOf("touchscreen" to true),emptySet()).status)
    }
    @Test fun unknownCapabilityIsNotAbsent() {
        assertEquals(DiagnosticStatus.NOT_ACCESSIBLE,EligibilityFilter.evaluate(definition(),36,emptyMap(),setOf("camera")).status)
    }
    @Test fun disabledDefinitionIsNotAResult() {
        val gate=EligibilityFilter.evaluate(definition().copy(implementation="DISABLED"),36,mapOf("touchscreen" to true),setOf("camera"))
        assertFalse(gate.eligible);assertNull(gate.status)
    }
    @Test fun torchUsesPerLensFlashAndPreservesOldSnapshots() {
        val template=definition("camera.{camera_id}.torch").copy(version=2,handler="torch",permissions=emptyList(),capabilities=listOf("camera","camera.{camera_id}.flash"))
        val features=mapOf("camera" to true,"camera.front.flash" to false,"camera.rear.flash" to true)
        val front=DiagnosticRegistry.expandCamera(template,"front",features)
        assertEquals(listOf("camera","camera.front.flash"),front.capabilities)
        assertEquals(2,front.version)
        assertEquals(DiagnosticStatus.NOT_SUPPORTED,EligibilityFilter.evaluate(front,36,features,emptySet()).status)
        val rear=DiagnosticRegistry.expandCamera(template,"rear",features)
        assertTrue(EligibilityFilter.evaluate(rear,36,features,emptySet()).eligible)
        val legacy=DiagnosticRegistry.expandCamera(template,"front",mapOf("camera" to true))
        assertEquals(1,legacy.version)
        assertEquals(listOf("camera"),legacy.capabilities)
    }
    @Test fun serializationRetainsEvidenceAndVersion() {
        val result=DiagnosticResult("touch.grid",DiagnosticStatus.INCONCLUSIVE,mapOf("coverage" to "0.5"),mapOf("cells" to "24"),"Incomplete",definitionVersion=3)
        assertEquals(result,Json.decodeFromString<DiagnosticResult>(Json.encodeToString(result)))
        assertTrue(Json.encodeToString(result).contains("definition_version"))
    }
    @Test fun latestRetryWinsAndMetadataDoesNotInflateScore() {
        val defs=listOf(definition("a"),definition("meta",0.0))
        val results=listOf(DiagnosticResult("a",DiagnosticStatus.FAIL,completedAt="2026-01-01T00:00:00Z"),DiagnosticResult("a",DiagnosticStatus.PASS,completedAt="2026-01-01T00:01:00Z"),DiagnosticResult("meta",DiagnosticStatus.FAIL))
        assertEquals(100.0,ScoringPreview.calculate(defs,results).score!!,0.01)
    }
    @Test fun excludedAndInconclusiveNeverBecomePassed() {
        val result=ScoringPreview.calculate(listOf(definition("a"),definition("b")),listOf(DiagnosticResult("a",DiagnosticStatus.NOT_SUPPORTED),DiagnosticResult("b",DiagnosticStatus.INCONCLUSIVE)))
        assertNull(result.score);assertEquals(listOf("b"),result.incomplete)
    }
    @Test fun criticalFailureCapAndOrderDeterminism() {
        val defs=listOf(definition("a",1.0,true),definition("b",99.0))
        val results=listOf(DiagnosticResult("a",DiagnosticStatus.FAIL),DiagnosticResult("b",DiagnosticStatus.PASS))
        assertEquals(79.0,ScoringPreview.calculate(defs,results).score!!,0.01)
        assertEquals(ScoringPreview.calculate(defs,results),ScoringPreview.calculate(defs.reversed(),results.reversed()))
    }
}
