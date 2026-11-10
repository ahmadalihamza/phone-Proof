package pk.phoneproof.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import pk.phoneproof.diagnostics.DiagnosticDefinition
import pk.phoneproof.diagnostics.DiagnosticResult
import pk.phoneproof.diagnostics.DiagnosticStatus
import pk.phoneproof.diagnostics.DiagnosticsEngine
import java.time.Instant
import kotlin.math.hypot

/** One physical interaction surface, with each legacy check supported by its own evidence. */
@Composable
fun TouchInspection(definitions: List<DiagnosticDefinition>, engine: DiagnosticsEngine,
    onResults: (List<DiagnosticResult>) -> Unit, onClose: () -> Unit) {
    val checks = remember(definitions) { definitions.filter { it.handler == "touch" && it.implementation == "ENABLED" }.distinctBy { it.id } }
    val started = remember { Instant.now().toString() }
    var phase by rememberSaveable { mutableStateOf("intro") }
    val restoredInProgress = remember { phase != "intro" }
    var interrupted by rememberSaveable { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    var evidence by remember { mutableStateOf<TouchEvidence?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var confirmedProblems by remember { mutableStateOf(setOf<String>()) }
    var notes by remember { mutableStateOf("") }
    var otherProblems by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if(restoredInProgress) { interrupted = true; phase = "paused" }
    }
    fun finish(skip: Boolean = false) {
        if(finished)return
        finished = true
        val values = evidence?.measurements().orEmpty()
        val results = checks.map { definition ->
            val test = definition.parameters["test"].orEmpty()
            val status = if(skip)DiagnosticStatus.SKIPPED_BY_USER else touchOutcome(test, evidence, interrupted, confirmedProblems)
            val reason = when {
                skip -> "Inspector skipped the consolidated touchscreen examination"
                interrupted -> "Touch examination interrupted or geometry changed; restart is required before passing"
                status == DiagnosticStatus.FAIL -> "Inspector confirmed a problem during the touchscreen examination${notes.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
                status == DiagnosticStatus.PASS -> "Required $test criterion observed in the consolidated touchscreen examination"
                test == "jitter" -> "No calibrated stationary-jitter threshold is implemented; movement is not hardware jitter certification"
                else -> "Required $test evidence was not observed; this criterion remains unverified"
            }
            engine.result(definition, status, values, reason, started).copy(evidence = mapOf(
                "workflow" to "fullscreen_touch_v1", "criterion" to test,
                "coverage_method" to "Actual MotionEvent samples only; no interpolated cell fill",
                "surface_limits" to (evidence?.geometry?.limitation ?: "No surface geometry observed"),
                "coverage_scope" to "Accessible grid cells, not every physical pixel or calibrated touch accuracy",
                "confirmation_method" to if(status == DiagnosticStatus.FAIL)"USER_CONFIRMED" else "PLATFORM_MEASUREMENT",
            ))
        }
        onResults(results); onClose()
    }
    fun back() {
        when {
            phase == "test" && !controls -> { evidence?.cancel(); controls = true }
            phase == "test" -> { controls = false; phase = "review" }
            phase == "review" -> phase = if(interrupted)"paused" else "test"
            else -> finish(skip = phase == "intro")
        }
    }
    ImmersiveInspection("touch-inspection", onBack = ::back, onInterrupted = {
        if(phase == "test" || phase == "review") {
            interrupted = true; evidence?.cancel(); controls = false; phase = "paused"
        }
    }) {
        // Read revision so both the overlay and native drawing reflect newly observed input.
        val observedRevision = revision
        AndroidView(modifier = Modifier.fillMaxSize().testTag("touch-surface"), factory = { context ->
            TouchInspectionView(context)
        }, update = { view ->
            view.collecting = phase == "test" && !controls && !interrupted
            view.observation = evidence
            view.onGeometry = { geometry ->
                if(evidence?.geometry != geometry) {
                    if(evidence?.samples?.let { it > 0 } == true && phase != "intro") {
                        interrupted = true; phase = "paused"; controls = false
                    }
                    evidence = TouchEvidence(geometry)
                    view.observation = evidence
                }
            }
            view.onChanged = { revision++ }
            view.onControls = { if(phase == "test") { evidence?.cancel(); controls = !controls } }
            view.contentDescription = "Touch examination surface. Back or a volume key opens controls. Observation $observedRevision"
            view.invalidate()
        })
        if(phase == "intro")InspectionPage("Touchscreen") {
            Text("Trace every tile, including the edges and corners.")
            Text("Hold two separated fingers together to check multi-touch.")
            Text("Back or a volume button opens controls.", style = MaterialTheme.typography.bodySmall)
            Text("Camera cutouts, rounded corners and mandatory system-gesture regions are marked unavailable.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = evidence != null, onClick = { evidence = evidence?.let { TouchEvidence(it.geometry) }; interrupted = false; phase = "test" }, modifier = Modifier.fillMaxWidth()) { Text("Start touch examination") }
            TextButton(onClick = { finish(skip = true) }) { Text("Skip for now") }
        }
        if(phase == "paused")InspectionPage("Touch examination paused") {
            Text("The app was interrupted or the screen area changed. No new pass will be recorded.")
            Button(onClick = { evidence = evidence?.let { TouchEvidence(it.geometry) }; confirmedProblems = emptySet(); interrupted = false; phase = "test" }, modifier = Modifier.fillMaxWidth()) { Text("Restart examination") }
            OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Save as unverified") }
        }
        if(phase == "test" && controls)Surface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Touch controls", style = MaterialTheme.typography.headlineSmall)
                TouchCoverageSummary(evidence)
                Text(if(evidence?.twoFingers == true)"Two-finger input observed" else "Hold two separated fingers on the surface for multi-touch.", style = MaterialTheme.typography.bodyMedium)
                Text("A long hold, five quick taps and continuous drags can be observed here without opening other tests.", style = MaterialTheme.typography.bodySmall)
                Text(evidence?.geometry?.limitation.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { controls = false }, modifier = Modifier.fillMaxWidth()) { Text("Continue tracing") }
                OutlinedButton(onClick = { controls = false; phase = "review" }, modifier = Modifier.fillMaxWidth()) { Text(if(evidence?.coverageComplete == true)"Review observations" else "Review partial observations") }
                TextButton(onClick = { finish(skip = true) }) { Text("Skip remaining touch checks") }
            }
        }
        if(phase == "review")InspectionPage("Touch observations") {
            TouchCoverageSummary(evidence)
            Text(if(evidence?.twoFingers == true)"Multi-touch observed" else "Multi-touch remains unverified", style = MaterialTheme.typography.titleSmall)
            Text("Only observed criteria pass. Unperformed gestures remain unverified.", style = MaterialTheme.typography.bodySmall)
            Text("Observed a problem?", style = MaterialTheme.typography.titleSmall)
            val problems = listOf("grid" to "Unresponsive area", "edges" to "Edge touches", "corners" to "Corner touches",
                "multitouch" to "Two-finger input", "three_finger" to "Three-finger input", "drag" to "Continuous dragging",
                "horizontal" to "Horizontal movement", "vertical" to "Vertical movement", "diagonal" to "Diagonal movement",
                "long_press" to "Holding a touch", "rapid_tap" to "Quick repeated taps", "single" to "Single touch")
            for((id, label) in problems.filterIndexed { index, _ -> index < 4 || otherProblems })Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in confirmedProblems, { selected -> confirmedProblems = if(selected)confirmedProblems + id else confirmedProblems - id })
                Text(label)
            }
            TextButton(onClick = { otherProblems = !otherProblems }) { Text(if(otherProblems)"Fewer problem types" else "Other touch problems") }
            if(confirmedProblems.isNotEmpty())OutlinedTextField(notes, { notes = it.take(300) }, label = { Text("Area or notes (optional)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Save observations") }
            TextButton(onClick = { phase = "test" }) { Text("Continue tracing") }
        }
    }
}

@Composable
internal fun InspectionPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}

@Composable
private fun TouchCoverageSummary(evidence: TouchEvidence?) {
    Text("${evidence?.cells?.size ?: 0} / ${evidence?.geometry?.accessibleCells?.size ?: 0} accessible tiles observed", style = MaterialTheme.typography.titleMedium)
    Text("Edges ${evidence?.geometry?.edgeCells?.count { it in evidence.cells } ?: 0}/${evidence?.geometry?.edgeCells?.size ?: 0} · Corners ${evidence?.geometry?.cornerCells?.count { it in evidence.cells } ?: 0}/${evidence?.geometry?.cornerCells?.size ?: 0}", style = MaterialTheme.typography.bodySmall)
    Text("${evidence?.geometry?.excludedCells?.size ?: 0} tiles unavailable. Grid pass requires every accessible tile.", style = MaterialTheme.typography.bodySmall)
}

internal class TouchInspectionView(context: Context): View(context) {
    var observation: TouchEvidence? = null
    var collecting = false
    var onGeometry: (TouchGeometry) -> Unit = {}
    var onChanged: () -> Unit = {}
    var onControls: () -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isFocusableInTouchMode = true; isFocusable = true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestFocus(); requestApplyInsets() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); geometry(rootWindowInsets) }
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets { geometry(insets); return insets }
    private fun geometry(insets: WindowInsets?) {
        if(width <= 0 || height <= 0)return
        val position = IntArray(2); getLocationInWindow(position)
        val compat = insets?.let { WindowInsetsCompat.toWindowInsetsCompat(it, this) }
        val blocked = compat?.displayCutout?.boundingRects.orEmpty().map { TouchRect((it.left - position[0]).toFloat(), (it.top - position[1]).toFloat(), (it.right - position[0]).toFloat(), (it.bottom - position[1]).toFloat()) }.toMutableList()
        val mandatory = compat?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        if(mandatory != null) {
            if(mandatory.left > 0)blocked.add(TouchRect(0f, 0f, mandatory.left.toFloat(), height.toFloat()))
            if(mandatory.top > 0)blocked.add(TouchRect(0f, 0f, width.toFloat(), mandatory.top.toFloat()))
            if(mandatory.right > 0)blocked.add(TouchRect(width - mandatory.right.toFloat(), 0f, width.toFloat(), height.toFloat()))
            if(mandatory.bottom > 0)blocked.add(TouchRect(0f, height - mandatory.bottom.toFloat(), width.toFloat(), height.toFloat()))
        }
        val rounded = if(Build.VERSION.SDK_INT >= 31 && insets != null)listOf(
            RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT,
        ).mapIndexedNotNull { index, corner -> insets.getRoundedCorner(corner)?.let { TouchRoundCorner((it.center.x - position[0]).toFloat(), (it.center.y - position[1]).toFloat(), it.radius.toFloat(), index) } } else emptyList()
        val system = compat?.getInsets(WindowInsetsCompat.Type.systemGestures())
        onGeometry(TouchGeometry(width, height, resources.displayMetrics.density, blocked, rounded,
            "Only fully inaccessible tiles are excluded; partly blocked tiles still require input in their accessible area. Mandatory insets ${mandatory ?: "unknown"}; gesture insets ${system ?: "unknown"}. Unreceived edge input stays unverified."))
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(keyCode in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) { onControls(); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val model = observation ?: return false
        if(!collecting || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))return false
        val action = when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            else -> return true
        }
        if(action == TouchAction.MOVE)for(history in 0 until event.historySize) {
            model.observe(TouchFrame(event.getHistoricalEventTime(history), (0 until event.pointerCount).map { TouchContact(event.getPointerId(it), event.getHistoricalX(it, history), event.getHistoricalY(it, history)) }, TouchAction.MOVE))
        }
        model.observe(TouchFrame(event.eventTime, (0 until event.pointerCount).map { TouchContact(event.getPointerId(it), event.getX(it), event.getY(it)) }, action, event.getPointerId(event.actionIndex)))
        invalidate(); onChanged(); return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF0E191C.toInt())
        val model = observation ?: return
        for(cell in 0 until model.geometry.allCells) {
            val rect = model.geometry.bounds(cell)
            paint.color = when(cell) { in model.geometry.excludedCells -> 0xFF3C4143.toInt(); in model.cells -> 0xFF4BAF91.toInt(); else -> 0xFF21373B.toInt() }
            val padding = resources.displayMetrics.density
            canvas.drawRect(rect.left + padding, rect.top + padding, rect.right - padding, rect.bottom - padding, paint)
            if(cell in model.geometry.excludedCells) {
                paint.color = 0xFF727779.toInt(); paint.strokeWidth = 2f * padding
                canvas.drawLine(rect.left + 3 * padding, rect.top + 3 * padding, rect.right - 3 * padding, rect.bottom - 3 * padding, paint)
            }
        }
        // Keep the exact blocked strips visible without discarding the remainder of an
        // edge tile. A contact inside a shaded strip never contributes to coverage.
        for(blocked in model.geometry.exclusions) {
            paint.color = 0xFF3C4143.toInt()
            canvas.drawRect(blocked.left, blocked.top, blocked.right, blocked.bottom, paint)
        }
        paint.color = 0xFFE1FFF4.toInt(); paint.strokeWidth = 2f * resources.displayMetrics.density
        val last = mutableMapOf<Int, TouchContact>()
        for(point in model.trail) {
            last[point.id]?.let { previous -> if(hypot(point.x - previous.x, point.y - previous.y) <= model.gapLimit)canvas.drawLine(previous.x, previous.y, point.x, point.y, paint) }
            last[point.id] = point
        }
        for(point in model.current.values) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * resources.displayMetrics.density
            canvas.drawCircle(point.x, point.y, 12f * resources.displayMetrics.density, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 11f * resources.displayMetrics.scaledDensity
            canvas.drawText("#${point.id} ${point.x.toInt()},${point.y.toInt()}", point.x.coerceIn(8f, (width - 190f).coerceAtLeast(8f)), (point.y - 18f).coerceAtLeast(paint.textSize), paint)
        }
    }
}
