package pk.phoneproof.app.ui

import kotlin.math.*

internal data class TouchRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(other: TouchRect) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

internal data class TouchRoundCorner(val x: Float, val y: Float, val radius: Float, val position: Int)

internal data class TouchGeometry(
    val width: Int,
    val height: Int,
    val density: Float = 1f,
    val exclusions: List<TouchRect> = emptyList(),
    val roundedCorners: List<TouchRoundCorner> = emptyList(),
    val limitation: String = "",
) {
    val columns = ceil(width / (48f * density.coerceAtLeast(0.5f))).toInt().coerceIn(6, 40)
    val rows = ceil(height / (48f * density.coerceAtLeast(0.5f))).toInt().coerceIn(8, 80)
    val cellWidth = width.toFloat() / columns
    val cellHeight = height.toFloat() / rows
    val allCells get() = columns * rows
    fun bounds(cell: Int) = TouchRect(cell % columns * cellWidth, cell / columns * cellHeight,
        (cell % columns + 1) * cellWidth, (cell / columns + 1) * cellHeight)
    fun isAccessible(x: Float, y: Float): Boolean {
        if(!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height)return false
        if(exclusions.any { it.contains(x, y) })return false
        return roundedCorners.none { corner ->
            val inCorner = when(corner.position) {
                0 -> x < corner.x && y < corner.y
                1 -> x > corner.x && y < corner.y
                2 -> x < corner.x && y > corner.y
                else -> x > corner.x && y > corner.y
            }
            inCorner && hypot(x - corner.x, y - corner.y) > corner.radius
        }
    }
    // A partly blocked tile still needs input in its accessible portion. Split at every
    // exclusion boundary so even a narrow strip beside a cutout is retained.
    private fun hasAccessibleArea(rect: TouchRect): Boolean {
        val xs = (listOf(rect.left, rect.right) + exclusions.flatMap { listOf(it.left, it.right) }
            .filter { it > rect.left && it < rect.right }).distinct().sorted()
        val ys = (listOf(rect.top, rect.bottom) + exclusions.flatMap { listOf(it.top, it.bottom) }
            .filter { it > rect.top && it < rect.bottom }).distinct().sorted()
        return xs.zipWithNext().any { (left, right) -> ys.zipWithNext().any { (top, bottom) ->
            // The point nearest each rounded-corner center is the accessible-most point
            // in this rectangle. Corners plus center also handle a rectangle spanning it.
            val candidatesX = listOf(left + (right - left) * 0.0001f, (left + right) / 2,
                right - (right - left) * 0.0001f) + roundedCorners.map { it.x.coerceIn(left, right) }
            val candidatesY = listOf(top + (bottom - top) * 0.0001f, (top + bottom) / 2,
                bottom - (bottom - top) * 0.0001f) + roundedCorners.map { it.y.coerceIn(top, bottom) }
            candidatesX.any { x -> candidatesY.any { y -> isAccessible(x, y) && x < right && y < bottom } }
        } }
    }
    val excludedCells: Set<Int> = (0 until allCells).filter { cell ->
        !hasAccessibleArea(bounds(cell))
    }.toSet()
    val accessibleCells: Set<Int> = (0 until allCells).filterNot { it in excludedCells }.toSet()
    val edgeCells: Set<Int> = accessibleCells.filter { cell ->
        val x = cell % columns; val y = cell / columns
        x == 0 || y == 0 || x == columns - 1 || y == rows - 1 ||
            listOf(cell - 1, cell + 1, cell - columns, cell + columns).any { it in excludedCells }
    }.toSet()
    val cornerCells: Set<Int> = accessibleCells.filter { cell ->
        val x = cell % columns; val y = cell / columns
        (x < 2 || x >= columns - 2) && (y < 2 || y >= rows - 2)
    }.toSet()
    fun cell(x: Float, y: Float): Int? {
        if(!isAccessible(x, y))return null
        return (y / cellHeight).toInt() * columns + (x / cellWidth).toInt()
    }
}

internal data class TouchContact(val id: Int, val x: Float, val y: Float)
internal enum class TouchAction { DOWN, MOVE, POINTER_DOWN, POINTER_UP, UP, CANCEL }
internal data class TouchFrame(val time: Long, val contacts: List<TouchContact>, val action: TouchAction,
    val changedId: Int? = null)

/** Counts actual supplied samples only. Lines drawn between samples never fill coverage. */
internal class TouchEvidence(val geometry: TouchGeometry) {
    private data class Track(val start: TouchContact, val downAt: Long, var last: TouchContact,
        var lastAt: Long, var segmentStart: TouchContact, var segmentSamples: Int = 0,
        var travel: Float = 0f, var drift: Float = 0f)
    val cells = mutableSetOf<Int>()
    private val tracks = mutableMapOf<Int, Track>()
    val current = mutableMapOf<Int, TouchContact>()
    val trail = ArrayDeque<TouchContact>()
    var samples = 0; private set
    var moves = 0; private set
    var taps = 0; private set
    var maxPointers = 0; private set
    var longestHoldMs = 0L; private set
    var rapidTaps = 0; private set
    var gaps = 0; private set
    var cancellations = 0; private set
    var horizontal = false; private set
    var vertical = false; private set
    var diagonal = false; private set
    var continuousDrag = false; private set
    var twoFingers = false; private set
    var threeFingers = false; private set
    private var lastTapAt = -1L
    private var tapStreak = 0
    private data class MultiHold(val ids: Set<Int>, val since: Long)
    private var multiStart = mutableMapOf<Int, MultiHold>()
    private var lastFrameTime = -1L
    private val intervals = ArrayDeque<Long>()
    val coverage get() = if(geometry.accessibleCells.isEmpty())0.0 else 100.0 * cells.count { it in geometry.accessibleCells } / geometry.accessibleCells.size
    val edgesComplete get() = geometry.edgeCells.isNotEmpty() && cells.containsAll(geometry.edgeCells)
    val cornersComplete get() = geometry.cornerCells.isNotEmpty() && cells.containsAll(geometry.cornerCells)
    val coverageComplete get() = geometry.accessibleCells.isNotEmpty() && cells.containsAll(geometry.accessibleCells) && edgesComplete && cornersComplete
    val gapLimit get() = maxOf(80f * geometry.density, 2f * maxOf(geometry.cellWidth, geometry.cellHeight))

    fun cancel() {
        tracks.clear(); current.clear(); multiStart.clear(); cancellations++
    }

    fun observe(frame: TouchFrame) {
        if(frame.action == TouchAction.CANCEL) { cancel(); return }
        if(frame.time < lastFrameTime || frame.contacts.map { it.id }.distinct().size != frame.contacts.size)return
        if(lastFrameTime >= 0 && frame.time > lastFrameTime && frame.time - lastFrameTime <= 250) {
            intervals.addLast(frame.time - lastFrameTime)
            if(intervals.size > 300)intervals.removeFirst()
        }
        lastFrameTime = frame.time
        val valid = frame.contacts.filter { geometry.cell(it.x, it.y) != null }
        // Leaving the accessible surface breaks a tracked hold/drag. Returning later
        // cannot reuse its old down time to claim an unobserved stationary press.
        tracks.keys.retainAll(valid.map { it.id }.toSet())
        current.clear(); valid.forEach { current[it.id] = it }
        maxPointers = maxOf(maxPointers, valid.size)
        for(contact in valid) {
            val cell = geometry.cell(contact.x, contact.y)!!
            if(cell in geometry.accessibleCells)cells.add(cell)
            samples++
            trail.addLast(contact)
            if(trail.size > 600)trail.removeFirst()
            val isDown = frame.action in setOf(TouchAction.DOWN, TouchAction.POINTER_DOWN) && frame.changedId == contact.id
            if(isDown)tracks[contact.id] = Track(contact, frame.time, contact, frame.time, contact)
            val track = tracks[contact.id] ?: continue
            track.drift = maxOf(track.drift, hypot(contact.x - track.start.x, contact.y - track.start.y))
            if(frame.action == TouchAction.MOVE && frame.time > track.lastAt) {
                moves++
                val distance = hypot(contact.x - track.last.x, contact.y - track.last.y)
                track.drift = maxOf(track.drift, hypot(contact.x - track.start.x, contact.y - track.start.y))
                if(distance > gapLimit || frame.time - track.lastAt > 250) {
                    gaps++; track.segmentStart = contact; track.segmentSamples = 0; track.travel = 0f
                } else {
                    track.segmentSamples++; track.travel += distance
                    val dx = abs(contact.x - track.segmentStart.x) / geometry.width
                    val dy = abs(contact.y - track.segmentStart.y) / geometry.height
                    if(track.segmentSamples >= 8) {
                        horizontal = horizontal || dx >= 0.7f
                        vertical = vertical || dy >= 0.7f
                        diagonal = diagonal || (dx >= 0.6f && dy >= 0.6f)
                    }
                    continuousDrag = continuousDrag || (track.segmentSamples >= 15 && track.travel >= minOf(geometry.width, geometry.height) * 0.7f)
                }
                track.last = contact; track.lastAt = frame.time
            }
        }
        for(count in listOf(2, 3)) {
            val separated = valid.size >= count && valid.take(count).withIndex().all { (index, a) ->
                valid.take(count).drop(index + 1).all { b -> hypot(a.x - b.x, a.y - b.y) >= 16f * geometry.density }
            }
            if(separated) {
                val ids = valid.take(count).map { it.id }.toSet()
                val previous = multiStart[count]
                val hold = if(previous?.ids == ids)previous else MultiHold(ids, frame.time).also { multiStart[count] = it }
                if(frame.time - hold.since >= 150) {
                    if(count == 2)twoFingers = true else threeFingers = true
                }
            } else multiStart.remove(count)
        }
        if(frame.action in setOf(TouchAction.UP, TouchAction.POINTER_UP)) {
            val id = frame.changedId
            val track = tracks.remove(id)
            if(track != null) {
                val duration = frame.time - track.downAt
                if(track.drift <= 20f * geometry.density)longestHoldMs = maxOf(longestHoldMs, duration)
                if(duration in 0..350 && track.drift <= 12f * geometry.density) {
                    taps++
                    tapStreak = if(lastTapAt >= 0 && frame.time - lastTapAt <= 700)tapStreak + 1 else 1
                    rapidTaps = maxOf(rapidTaps, tapStreak); lastTapAt = frame.time
                }
            }
            current.remove(id)
            multiStart.clear()
        }
    }

    fun observed(test: String): Boolean = when(test) {
        "single" -> samples > 0
        "grid" -> coverageComplete
        "edges" -> edgesComplete
        "corners" -> cornersComplete
        "multitouch" -> twoFingers
        "three_finger" -> threeFingers
        "drag" -> continuousDrag
        "horizontal" -> horizontal
        "vertical" -> vertical
        "diagonal" -> diagonal
        "long_press" -> longestHoldMs >= 700
        "rapid_tap" -> rapidTaps >= 5
        "interval" -> intervals.size >= 15
        else -> false // Uncalibrated motion variance is not a passing jitter measurement.
    }

    fun measurements(): Map<String, String> = mapOf(
        "covered_cells" to cells.size.toString(), "accessible_cells" to geometry.accessibleCells.size.toString(),
        "excluded_cells" to geometry.excludedCells.size.toString(), "coverage_percent" to "%.2f".format(java.util.Locale.ROOT, coverage),
            "required_coverage_percent" to "100", "edge_cells_observed" to geometry.edgeCells.count { it in cells }.toString(),
        "edge_cells_total" to geometry.edgeCells.size.toString(), "corner_cells_observed" to geometry.cornerCells.count { it in cells }.toString(),
        "corner_cells_total" to geometry.cornerCells.size.toString(), "max_observed_pointers" to maxPointers.toString(),
        "two_finger_hold_observed" to twoFingers.toString(), "three_finger_hold_observed" to threeFingers.toString(),
        "tap_count" to taps.toString(), "max_rapid_tap_streak" to rapidTaps.toString(), "longest_press_ms" to longestHoldMs.toString(),
        "move_events" to moves.toString(), "discontinuous_gaps" to gaps.toString(), "cancelled_gestures" to cancellations.toString(),
        "event_interval_count" to intervals.size.toString(),
        "observed_event_interval_ms" to if(intervals.isEmpty())"unavailable" else "%.2f".format(java.util.Locale.ROOT, intervals.average()),
        "surface_width_px" to geometry.width.toString(), "surface_height_px" to geometry.height.toString(),
    )
}

internal fun touchOutcome(test: String, evidence: TouchEvidence?, interrupted: Boolean, confirmedProblems: Set<String>): pk.phoneproof.diagnostics.DiagnosticStatus {
    if(interrupted || test == "jitter")return pk.phoneproof.diagnostics.DiagnosticStatus.INCONCLUSIVE
    if(test in confirmedProblems)return pk.phoneproof.diagnostics.DiagnosticStatus.FAIL
    return if(evidence?.observed(test) == true)pk.phoneproof.diagnostics.DiagnosticStatus.PASS else pk.phoneproof.diagnostics.DiagnosticStatus.INCONCLUSIVE
}
