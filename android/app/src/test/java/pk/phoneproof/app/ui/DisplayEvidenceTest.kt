package pk.phoneproof.app.ui

import org.junit.Assert.*
import org.junit.Test
import pk.phoneproof.diagnostics.DiagnosticStatus

class DisplayEvidenceTest {
    private fun outcome(criterion: String, viewed: Set<DisplayPattern> = DisplayPattern.entries.toSet(),
        issues: Set<DisplayIssue> = emptySet(), affected: Set<DisplayPattern> = emptySet(),
        confirmed: Boolean = true, brightness: Boolean = true, interrupted: Boolean = false) =
        DisplayEvidence.outcome(criterion, viewed, issues, affected, confirmed, brightness, interrupted)

    @Test fun renderingAllPatternsWithoutHumanConfirmationNeverPassesPhysicalHealth() {
        for(criterion in listOf("red", "white", "dead_pixels", "burn_in", "lines", "flicker"))
            assertEquals(criterion, DiagnosticStatus.INCONCLUSIVE, outcome(criterion, confirmed = false))
    }

    @Test fun eachCriterionNeedsItsRequiredPatternsAndExplicitNormalConfirmation() {
        assertEquals(DiagnosticStatus.PASS, outcome("red", setOf(DisplayPattern.RED)))
        assertEquals(DiagnosticStatus.INCONCLUSIVE, outcome("dead_pixels", setOf(DisplayPattern.RED)))
        assertEquals(DiagnosticStatus.INCONCLUSIVE, outcome("red", setOf(DisplayPattern.GREEN)))
        assertEquals(DiagnosticStatus.INCONCLUSIVE, outcome("unknown"))
    }

    @Test fun defectSelectionDoesNotFailEveryIndependentCriterion() {
        assertEquals(DiagnosticStatus.FAIL, outcome("dead_pixels", issues = setOf(DisplayIssue.DEAD_PIXELS)))
        assertEquals(DiagnosticStatus.PASS, outcome("burn_in", issues = setOf(DisplayIssue.DEAD_PIXELS)))
        assertEquals(DiagnosticStatus.PASS, outcome("red", issues = setOf(DisplayIssue.DEAD_PIXELS)))
        assertEquals(DiagnosticStatus.FAIL, outcome("red", affected = setOf(DisplayPattern.RED)))
        assertEquals(DiagnosticStatus.PASS, outcome("green", affected = setOf(DisplayPattern.RED)))
    }

    @Test fun brightnessAndFlickerNeedASeparateObservedBrightnessResponse() {
        for(criterion in listOf("brightness_min", "brightness_max", "flicker")) {
            assertEquals(DiagnosticStatus.INCONCLUSIVE, outcome(criterion, brightness = false))
            assertEquals(DiagnosticStatus.PASS, outcome(criterion))
        }
    }

    @Test fun interruptionInvalidatesPassAndUnviewedPatternCannotBeConfirmedFailed() {
        assertEquals(DiagnosticStatus.INCONCLUSIVE, outcome("red", interrupted = true))
        assertEquals(DiagnosticStatus.INCONCLUSIVE, outcome("red", emptySet(), affected = setOf(DisplayPattern.RED)))
        assertEquals(DiagnosticStatus.INCONCLUSIVE, outcome("dead_pixels", emptySet(), issues = setOf(DisplayIssue.DEAD_PIXELS)))
    }
}
