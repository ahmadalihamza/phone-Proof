package pk.phoneproof.app.ui

import org.junit.Assert.*
import org.junit.Test
import pk.phoneproof.diagnostics.DiagnosticStatus

/** Synthetic input validates bookkeeping; it does not certify a physical panel. */
class TouchEvidenceTest {
    private val geometry = TouchGeometry(288, 384)
    private fun contact(id: Int, x: Float, y: Float) = TouchContact(id, x, y)
    private fun cover(evidence: TouchEvidence, cells: Collection<Int> = evidence.geometry.accessibleCells) {
        cells.forEachIndexed { index, cell ->
            val bounds = evidence.geometry.bounds(cell)
            evidence.observe(TouchFrame(index * 20L, listOf(contact(7, (bounds.left + bounds.right) / 2,
                (bounds.top + bounds.bottom) / 2)), TouchAction.DOWN, 7))
        }
    }

    @Test fun gridRequiresEveryAccessibleTileIncludingLastCorner() {
        val evidence = TouchEvidence(geometry)
        val missing = geometry.cornerCells.last()
        cover(evidence, geometry.accessibleCells - missing)
        assertFalse(evidence.coverageComplete)
        assertEquals(DiagnosticStatus.INCONCLUSIVE, touchOutcome("grid", evidence, false, emptySet()))
        val bounds = geometry.bounds(missing)
        evidence.observe(TouchFrame(10_000, listOf(contact(7, bounds.left + 2, bounds.top + 2)), TouchAction.DOWN, 7))
        assertTrue(evidence.edgesComplete)
        assertTrue(evidence.cornersComplete)
        assertTrue(evidence.coverageComplete)
        assertEquals(100.0, evidence.coverage, 0.0)
        assertEquals(DiagnosticStatus.PASS, touchOutcome("grid", evidence, false, emptySet()))
    }

    @Test fun distantSamplesNeverInterpolateCoverageOrContinuousDrag() {
        val evidence = TouchEvidence(geometry)
        evidence.observe(TouchFrame(0, listOf(contact(7, 1f, 1f)), TouchAction.DOWN, 7))
        evidence.observe(TouchFrame(30, listOf(contact(7, 286f, 382f)), TouchAction.MOVE))
        assertEquals(2, evidence.cells.size)
        assertFalse(evidence.continuousDrag)
        assertFalse(evidence.coverageComplete)
        assertEquals(1, evidence.gaps)
    }

    @Test fun partiallyBlockedEdgeTileStillRequiresAccessibleInput() {
        val geometry = TouchGeometry(288, 384, exclusions = listOf(TouchRect(0f, 364f, 288f, 384f)))
        assertTrue(geometry.excludedCells.isEmpty())
        val evidence = TouchEvidence(geometry)
        evidence.observe(TouchFrame(0, listOf(contact(3, 10f, 380f)), TouchAction.DOWN, 3))
        assertTrue(evidence.cells.isEmpty())
        assertNull(geometry.cell(10f, 380f))
        evidence.observe(TouchFrame(20, listOf(contact(3, 10f, 350f)), TouchAction.DOWN, 3))
        assertEquals(setOf(42), evidence.cells)
    }

    @Test fun cutoutDoesNotDiscardAccessiblePartsOfCornerTile() {
        val geometry = TouchGeometry(288, 384, exclusions = listOf(TouchRect(0f, 0f, 15f, 15f)))
        assertTrue(0 in geometry.accessibleCells)
        assertNull(geometry.cell(5f, 5f))
        assertEquals(0, geometry.cell(20f, 20f))
    }

    @Test fun roundedCornerRejectsBlockedPointButPreservesUsableTile() {
        val geometry = TouchGeometry(288, 384, roundedCorners = listOf(TouchRoundCorner(24f, 24f, 24f, 0)))
        assertNull(geometry.cell(0f, 0f))
        assertEquals(0, geometry.cell(24f, 24f))
        assertTrue(0 in geometry.accessibleCells)
    }

    @Test fun completelyBlockedTilesAreExcludedWithoutMakingTheirNeighboursUnavailable() {
        val geometry = TouchGeometry(288, 384, exclusions = listOf(TouchRect(0f, 0f, 288f, 48f)))
        assertEquals((0..5).toSet(), geometry.excludedCells)
        assertEquals(42, geometry.accessibleCells.size)
        assertTrue((6..11).all { it in geometry.edgeCells })
    }

    @Test fun multiTouchRequiresDistinctStablePointerIdsAndSeparatedCoordinates() {
        val evidence = TouchEvidence(geometry)
        val a = contact(11, 50f, 50f); val b = contact(42, 120f, 50f)
        evidence.observe(TouchFrame(0, listOf(a), TouchAction.DOWN, a.id))
        evidence.observe(TouchFrame(10, listOf(a, b), TouchAction.POINTER_DOWN, b.id))
        evidence.observe(TouchFrame(100, listOf(a, b), TouchAction.MOVE))
        assertFalse(evidence.twoFingers)
        evidence.observe(TouchFrame(180, listOf(a, b), TouchAction.MOVE))
        assertTrue(evidence.twoFingers)
        assertFalse(evidence.threeFingers)
        assertEquals(DiagnosticStatus.INCONCLUSIVE, touchOutcome("three_finger", evidence, false, emptySet()))
    }

    @Test fun changingPointerIdentityCannotAccumulateAMultiTouchHold() {
        val evidence = TouchEvidence(geometry)
        evidence.observe(TouchFrame(0, listOf(contact(1, 50f, 50f), contact(2, 120f, 50f)), TouchAction.POINTER_DOWN, 2))
        evidence.observe(TouchFrame(140, listOf(contact(1, 50f, 50f), contact(3, 120f, 50f)), TouchAction.MOVE))
        evidence.observe(TouchFrame(200, listOf(contact(1, 50f, 50f), contact(3, 120f, 50f)), TouchAction.MOVE))
        assertFalse(evidence.twoFingers)
    }

    @Test fun duplicateIdsAndNonFiniteOrBlockedCoordinatesCannotCreateMultiTouch() {
        val evidence = TouchEvidence(geometry)
        evidence.observe(TouchFrame(0, listOf(contact(1, 50f, 50f), contact(1, 120f, 50f)), TouchAction.POINTER_DOWN, 1))
        assertEquals(0, evidence.samples)
        evidence.observe(TouchFrame(200, listOf(contact(1, Float.NaN, 50f), contact(2, -1f, 50f)), TouchAction.MOVE))
        assertEquals(0, evidence.samples)
        assertFalse(evidence.twoFingers)
    }

    @Test fun interruptedSessionAndUncalibratedJitterCannotPass() {
        val evidence = TouchEvidence(geometry)
        cover(evidence)
        assertTrue(evidence.coverageComplete)
        assertEquals(DiagnosticStatus.INCONCLUSIVE, touchOutcome("grid", evidence, true, emptySet()))
        assertEquals(DiagnosticStatus.INCONCLUSIVE, touchOutcome("jitter", evidence, false, emptySet()))
        assertEquals(DiagnosticStatus.FAIL, touchOutcome("grid", evidence, false, setOf("grid")))
        assertEquals(DiagnosticStatus.PASS, touchOutcome("single", evidence, false, setOf("grid")))
    }

    @Test fun cancellationDoesNotTurnAMovingTouchIntoALongPress() {
        val evidence = TouchEvidence(geometry)
        evidence.observe(TouchFrame(0, listOf(contact(9, 50f, 50f)), TouchAction.DOWN, 9))
        evidence.observe(TouchFrame(500, listOf(contact(9, 100f, 50f)), TouchAction.MOVE))
        evidence.observe(TouchFrame(900, emptyList(), TouchAction.CANCEL))
        evidence.observe(TouchFrame(1000, listOf(contact(9, 50f, 50f)), TouchAction.UP, 9))
        assertEquals(0L, evidence.longestHoldMs)
        assertEquals(1, evidence.cancellations)
    }

    @Test fun leavingAccessibleAreaBreaksLongPressTracking() {
        val evidence = TouchEvidence(TouchGeometry(288, 384, exclusions = listOf(TouchRect(0f, 364f, 288f, 384f))))
        evidence.observe(TouchFrame(0, listOf(contact(9, 50f, 350f)), TouchAction.DOWN, 9))
        evidence.observe(TouchFrame(300, listOf(contact(9, 50f, 380f)), TouchAction.MOVE))
        evidence.observe(TouchFrame(900, listOf(contact(9, 50f, 350f)), TouchAction.UP, 9))
        assertFalse(evidence.observed("long_press"))
    }

    @Test fun continuousHorizontalSamplesDoNotInventVerticalOrDiagonalEvidence() {
        val evidence = TouchEvidence(geometry)
        evidence.observe(TouchFrame(0, listOf(contact(8, 10f, 150f)), TouchAction.DOWN, 8))
        for(step in 1..16)evidence.observe(TouchFrame(step * 16L,
            listOf(contact(8, 10f + step * 16f, 150f)), TouchAction.MOVE))
        assertTrue(evidence.horizontal)
        assertTrue(evidence.continuousDrag)
        assertFalse(evidence.vertical)
        assertFalse(evidence.diagonal)
        assertEquals(0, evidence.taps)
    }

    @Test fun distinctLongPressAndRapidTapCriteriaUseDurationAndTravel() {
        val evidence = TouchEvidence(geometry)
        evidence.observe(TouchFrame(0, listOf(contact(3, 60f, 60f)), TouchAction.DOWN, 3))
        evidence.observe(TouchFrame(800, listOf(contact(3, 61f, 60f)), TouchAction.UP, 3))
        assertTrue(evidence.observed("long_press"))
        assertFalse(evidence.observed("rapid_tap"))
        repeat(5) { tap ->
            evidence.observe(TouchFrame(1000L + tap * 200, listOf(contact(3, 60f, 60f)), TouchAction.DOWN, 3))
            evidence.observe(TouchFrame(1100L + tap * 200, listOf(contact(3, 60f, 60f)), TouchAction.UP, 3))
        }
        assertEquals(5, evidence.taps)
        assertEquals(5, evidence.rapidTaps)
        assertTrue(evidence.observed("rapid_tap"))
    }
}
