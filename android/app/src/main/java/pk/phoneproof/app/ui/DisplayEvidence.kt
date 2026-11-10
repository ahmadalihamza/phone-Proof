package pk.phoneproof.app.ui

import pk.phoneproof.diagnostics.DiagnosticStatus

internal enum class DisplayPattern(val title: String) {
    RED("Red"), GREEN("Green"), BLUE("Blue"), WHITE("White"), BLACK("Black"), GRAY("Gray"),
    COLOR_GRADIENT("Color gradient"), GRAYSCALE("Grayscale"), BRIGHTNESS_MIN("Minimum brightness"), BRIGHTNESS_MAX("Maximum brightness"),
}

internal enum class DisplayIssue(val title: String, val criteria: Set<String>) {
    DEAD_PIXELS("Dead pixels", setOf("dead_pixels")),
    STUCK_PIXELS("Stuck pixels", setOf("stuck_pixels")),
    LINES("Lines", setOf("lines")),
    UNEVEN_LIGHT("Uneven brightness", setOf("white", "gray")),
    DISCOLORATION("Uneven color", setOf("discoloration")),
    EDGES("Edge discoloration", setOf("edges")),
    BURN_IN("Burn-in / retained shapes", setOf("burn_in")),
    FLICKER("Visible flicker", setOf("flicker")),
    BRIGHTNESS("Brightness did not respond", setOf("brightness_min", "brightness_max")),
}

internal object DisplayEvidence {
    fun required(criterion: String): Set<DisplayPattern> = when(criterion) {
        "red" -> setOf(DisplayPattern.RED)
        "green" -> setOf(DisplayPattern.GREEN)
        "blue" -> setOf(DisplayPattern.BLUE)
        "white" -> setOf(DisplayPattern.WHITE)
        "black" -> setOf(DisplayPattern.BLACK)
        "gray" -> setOf(DisplayPattern.GRAY)
        "gradient" -> setOf(DisplayPattern.COLOR_GRADIENT)
        "grayscale" -> setOf(DisplayPattern.GRAYSCALE)
        "brightness_min" -> setOf(DisplayPattern.BRIGHTNESS_MIN)
        "brightness_max" -> setOf(DisplayPattern.BRIGHTNESS_MAX)
        "dead_pixels" -> setOf(DisplayPattern.RED, DisplayPattern.GREEN, DisplayPattern.BLUE, DisplayPattern.WHITE)
        "stuck_pixels" -> setOf(DisplayPattern.RED, DisplayPattern.GREEN, DisplayPattern.BLUE, DisplayPattern.BLACK)
        "lines", "discoloration" -> setOf(DisplayPattern.RED, DisplayPattern.GREEN, DisplayPattern.BLUE, DisplayPattern.WHITE, DisplayPattern.GRAY)
        "edges", "burn_in" -> setOf(DisplayPattern.WHITE, DisplayPattern.GRAY)
        "flicker" -> setOf(DisplayPattern.WHITE, DisplayPattern.BRIGHTNESS_MIN, DisplayPattern.BRIGHTNESS_MAX)
        else -> emptySet()
    }

    fun outcome(criterion: String, viewed: Set<DisplayPattern>, issues: Set<DisplayIssue>,
        affectedPatterns: Set<DisplayPattern>, normalConfirmed: Boolean, brightnessConfirmed: Boolean,
        interrupted: Boolean): DiagnosticStatus {
        if(interrupted)return DiagnosticStatus.INCONCLUSIVE
        val needed = required(criterion)
        if(needed.isEmpty())return DiagnosticStatus.INCONCLUSIVE
        val directPattern = when(criterion) {
            "red" -> DisplayPattern.RED; "green" -> DisplayPattern.GREEN; "blue" -> DisplayPattern.BLUE
            "white" -> DisplayPattern.WHITE; "black" -> DisplayPattern.BLACK; "gray" -> DisplayPattern.GRAY
            "gradient" -> DisplayPattern.COLOR_GRADIENT; "grayscale" -> DisplayPattern.GRAYSCALE
            else -> null
        }
        if((issues.any { criterion in it.criteria } && needed.any { it in viewed }) ||
            (directPattern != null && directPattern in affectedPatterns && directPattern in viewed))return DiagnosticStatus.FAIL
        if(!viewed.containsAll(needed) || !normalConfirmed)return DiagnosticStatus.INCONCLUSIVE
        if(criterion in setOf("brightness_min", "brightness_max", "flicker") && !brightnessConfirmed)return DiagnosticStatus.INCONCLUSIVE
        return DiagnosticStatus.PASS
    }
}
