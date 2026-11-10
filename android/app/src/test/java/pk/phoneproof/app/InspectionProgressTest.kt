package pk.phoneproof.app

import org.junit.Assert.*
import org.junit.Test
import pk.phoneproof.diagnostics.*

/** Synthetic model cases; these assertions do not claim any physical phone was checked. */
class InspectionProgressTest {
    private fun definition(id: String, category: String = "display", weight: Double = 1.0) = DiagnosticDefinition(
        id, "Synthetic check", category, implementation = "ENABLED", weight = weight,
        passCriteria = "Observed", failCriteria = "Observed issue", notSupported = "Absent", notAccessible = "Blocked",
        evidenceType = "USER_OBSERVATION", handler = "display_guided", mode = "USER_CONFIRMED",
    )
    private val policy = ScoringRule(2, "category_weighted", failureCaps = listOf(
        FailureCapRule("charging.connect", "", 69, "Charging connection failure"),
    ))
    private fun result(id: String, status: DiagnosticStatus, at: String = "2026-10-18T12:00:00Z") =
        DiagnosticResult(id, status, completedAt = at)

    @Test fun metadataAndDisabledDefinitionsCannotInflateChecklistProgress() {
        val manual = definition("display.white")
        val metadata = definition("system.model", "system", 0.0)
        val disabled = definition("display.unimplemented").copy(implementation = "DISABLED")
        val progress = InspectionProgress.from(listOf(metadata, disabled, manual), listOf(
            result(metadata.id, DiagnosticStatus.PASS), result(disabled.id, DiagnosticStatus.PASS),
        ), policy)
        assertEquals(listOf(manual), progress.checks)
        assertEquals(0, progress.recorded)
        assertEquals(0, progress.passed)
        assertEquals(manual, progress.next)
        assertFalse(progress.ready)
    }

    @Test fun emptyOrMetadataOnlyInspectionIsNeverReadyForAReport() {
        assertFalse(InspectionProgress.from(emptyList(), emptyList(), policy).ready)
        val metadata = definition("system.model", "system", 0.0)
        assertFalse(InspectionProgress.from(listOf(metadata), listOf(result(metadata.id, DiagnosticStatus.PASS)), policy).ready)
    }

    @Test fun skippedOrInconclusivePolicyCriticalCheckStillNeedsAttention() {
        val charging = definition("charging.connect", "charging") // The policy makes this critical.
        for(status in listOf(DiagnosticStatus.SKIPPED_BY_USER, DiagnosticStatus.INCONCLUSIVE)) {
            val progress = InspectionProgress.from(listOf(charging), listOf(result(charging.id, status)), policy)
            assertEquals(1, progress.recorded)
            assertEquals(listOf(charging), progress.required)
            assertFalse(progress.ready)
        }
    }

    @Test fun terminalCriticalOutcomesAllowProgressWithoutTurningUnavailableIntoPass() {
        val charging = definition("charging.connect", "charging")
        for(status in listOf(DiagnosticStatus.PASS, DiagnosticStatus.FAIL, DiagnosticStatus.NOT_SUPPORTED, DiagnosticStatus.NOT_ACCESSIBLE)) {
            val progress = InspectionProgress.from(listOf(charging), listOf(result(charging.id, status)), policy)
            assertEquals(status in setOf(DiagnosticStatus.PASS, DiagnosticStatus.FAIL), progress.ready)
            assertNull(progress.next)
            assertEquals(if(status == DiagnosticStatus.PASS)1 else 0, progress.passed)
            assertEquals(if(status == DiagnosticStatus.FAIL)1 else 0, progress.failed)
            assertEquals(if(status in setOf(DiagnosticStatus.NOT_SUPPORTED, DiagnosticStatus.NOT_ACCESSIBLE))1 else 0, progress.unavailable)
        }
    }

    @Test fun unavailableOnlyInspectionCannotCreateAReportWithoutAnyMeasuredCheck() {
        val charging = definition("charging.connect", "charging")
        val storage = definition("storage.checksum", "storage")
        val unavailable = result(charging.id, DiagnosticStatus.NOT_ACCESSIBLE)
        val blocked = InspectionProgress.from(listOf(charging), listOf(unavailable), policy)
        assertFalse(blocked.ready)
        assertNull(blocked.next)
        assertTrue(blocked.required.isEmpty())
        val measured = InspectionProgress.from(listOf(charging, storage), listOf(unavailable, result(storage.id, DiagnosticStatus.PASS)), policy)
        assertTrue(measured.ready)
        assertEquals(1, measured.passed)
        assertEquals(1, measured.unavailable)
    }

    @Test fun latestRetryControlsReadinessAcrossTimeZones() {
        val charging = definition("charging.connect", "charging")
        val passed = result(charging.id, DiagnosticStatus.PASS, "2026-10-18T17:00:00+05:00")
        val retry = result(charging.id, DiagnosticStatus.INCONCLUSIVE, "2026-10-18T12:01:00Z")
        assertFalse(InspectionProgress.from(listOf(charging), listOf(retry, passed), policy).ready)
        val completedRetry = result(charging.id, DiagnosticStatus.FAIL, "2026-10-18T12:02:00Z")
        val progress = InspectionProgress.from(listOf(charging), listOf(completedRetry, passed, retry), policy)
        assertTrue(progress.ready)
        assertEquals(1, progress.failed)
        assertEquals(0, progress.passed)
        assertEquals(1, progress.recorded)
    }

    @Test fun nextCheckOrderIsDeterministicAndStartsWithTheScreen() {
        val camera = definition("camera.0.capture", "camera")
        val touch = definition("touch.grid", "touch")
        val screen = definition("display.white")
        val progress = InspectionProgress.from(listOf(camera, touch, screen), emptyList(), policy)
        assertEquals(listOf(screen, touch, camera), progress.required)
        assertEquals(screen, progress.next)
    }
}
