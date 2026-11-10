package pk.phoneproof.app.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pk.phoneproof.diagnostics.DiagnosticDefinition
import pk.phoneproof.diagnostics.DiagnosticResult
import pk.phoneproof.diagnostics.DiagnosticStatus
import pk.phoneproof.diagnostics.DiagnosticsEngine
import java.time.Instant

/** Patterns are rendered by the app; physical defects are confirmed only by the inspector. */
@Composable
fun DisplayInspection(definitions: List<DiagnosticDefinition>, engine: DiagnosticsEngine,
    onResults: (List<DiagnosticResult>) -> Unit, onClose: () -> Unit) {
    val checks = remember(definitions) { definitions.filter { it.handler == "display_guided" && it.implementation == "ENABLED" }.distinctBy { it.id } }
    val started = remember { Instant.now().toString() }
    var phase by rememberSaveable { mutableStateOf("intro") }
    val restoredInProgress = remember { phase != "intro" }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var viewedNames by rememberSaveable { mutableStateOf(listOf<String>()) }
    val viewed = viewedNames.mapNotNull { name -> DisplayPattern.entries.firstOrNull { it.name == name } }.toSet()
    var interrupted by rememberSaveable { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(false) }
    var affected by remember { mutableStateOf(setOf<DisplayPattern>()) }
    var issues by remember { mutableStateOf(setOf<DisplayIssue>()) }
    var normalConfirmed by remember { mutableStateOf(false) }
    var brightnessConfirmed by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showIssues by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val patterns = DisplayPattern.entries
    val pattern = patterns[index.coerceIn(patterns.indices)]
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if(restoredInProgress) { interrupted = true; phase = "paused" }
    }
    fun finish(skip: Boolean = false) {
        if(finished)return
        finished = true
        onResults(checks.map { definition ->
            val criterion = definition.parameters["pattern"].orEmpty()
            val status = if(skip)DiagnosticStatus.SKIPPED_BY_USER else DisplayEvidence.outcome(criterion, viewed, issues,
                affected, normalConfirmed, brightnessConfirmed, interrupted)
            val reason = when {
                skip -> "Inspector skipped the consolidated display inspection"
                interrupted -> "Display inspection interrupted; restart is required before passing"
                status == DiagnosticStatus.FAIL -> "Inspector observed a display issue${location.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()}${notes.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
                status == DiagnosticStatus.PASS -> "Inspector confirmed the expected $criterion appearance after viewing its required fullscreen patterns"
                else -> "Required patterns or inspector confirmation for $criterion remain incomplete"
            }
            engine.result(definition, status, mapOf(
                "patterns_viewed" to viewed.joinToString(",") { it.name },
                "required_patterns" to DisplayEvidence.required(criterion).joinToString(",") { it.name },
                "patterns_marked" to affected.joinToString(",") { it.name },
                "issue_types" to issues.joinToString(",") { it.name },
                "normal_appearance_confirmed" to normalConfirmed.toString(),
                "brightness_response_confirmed" to brightnessConfirmed.toString(),
            ), reason, started).copy(evidence = mapOf(
                "workflow" to "fullscreen_display_v1", "confirmation_method" to "USER_CONFIRMED",
                "measurement_limit" to "Rendering or screenshots cannot detect physical dead pixels, burn-in or calibrated panel health",
                "pattern_scope" to "Full app window; physical cutouts and Android-owned regions cannot be certified",
                "location" to location.take(40),
            ))
        })
        onClose()
    }
    fun advance() {
        if(!rendered || interrupted || phase != "patterns")return
        viewedNames = (viewedNames + pattern.name).distinct()
        rendered = false; controls = false
        if(index == patterns.lastIndex)phase = "review" else index++
    }
    fun back() {
        when {
            phase == "patterns" && !controls -> controls = true
            phase == "patterns" -> { controls = false; phase = "review" }
            phase == "review" && !interrupted -> { phase = "patterns"; controls = false }
            else -> finish(skip = phase == "intro")
        }
    }
    LaunchedEffect(phase, index, controls) {
        if(phase == "patterns" && !controls) {
            rendered = false
            withFrameNanos { }
            delay(700)
            rendered = true
            focus.requestFocus()
        }
    }
    val brightness = if(phase == "patterns")when(pattern) {
        DisplayPattern.BRIGHTNESS_MIN -> 0.01f
        DisplayPattern.BRIGHTNESS_MAX -> 1f
        else -> null
    } else null
    ImmersiveInspection("display-inspection", brightness, ::back, {
        if(phase == "patterns" || phase == "review") { interrupted = true; controls = false; rendered = false; phase = "paused" }
    }) {
        if(phase == "patterns")Box(Modifier.fillMaxSize().testTag("display-pattern")
            .onSizeChanged { size ->
                if(surfaceSize != IntSize.Zero && surfaceSize != size) {
                    interrupted = true; controls = false; rendered = false; phase = "paused"
                }
                surfaceSize = size
            }
            .focusRequester(focus).onPreviewKeyEvent { event ->
                if(event.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
                    if(event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.nativeKeyEvent.repeatCount == 0)controls = !controls
                    true
                } else false
            }.focusable().clickable(enabled = !controls) { advance() }) {
            Canvas(Modifier.fillMaxSize()) {
                when(pattern) {
                    DisplayPattern.COLOR_GRADIENT -> drawRect(Brush.horizontalGradient(listOf(Color.Red, Color.Green, Color.Blue)))
                    DisplayPattern.GRAYSCALE -> drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)))
                    else -> drawRect(when(pattern) {
                        DisplayPattern.RED -> Color.Red; DisplayPattern.GREEN -> Color.Green; DisplayPattern.BLUE -> Color.Blue
                        DisplayPattern.BLACK -> Color.Black; DisplayPattern.GRAY -> Color(0xFF808080)
                        else -> Color.White
                    })
                }
            }
            if(controls)Surface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${pattern.title} · ${index + 1} of ${patterns.size}", style = MaterialTheme.typography.headlineSmall)
                    Text("Hide controls to examine this pattern.")
                    Button(onClick = { controls = false }, modifier = Modifier.fillMaxWidth()) { Text("Hide controls") }
                    OutlinedButton(onClick = { affected = affected + pattern; controls = false }, modifier = Modifier.fillMaxWidth()) { Text("Mark this pattern") }
                    OutlinedButton(enabled = rendered, onClick = ::advance, modifier = Modifier.fillMaxWidth()) { Text("Next pattern") }
                    TextButton(onClick = { controls = false; phase = "review" }) { Text("Review partial inspection") }
                }
            }
        }
        if(phase == "intro")InspectionPage("Display inspection") {
            Text("Look for spots, lines, uneven color or retained shapes.")
            Text("Tap anywhere to advance. Back or a volume button reveals controls.")
            Text("10 patterns, including low and high brightness.", style = MaterialTheme.typography.bodyMedium)
            Text("Only your observations can confirm physical display defects.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { viewedNames = emptyList(); index = 0; interrupted = false; phase = "patterns" }, modifier = Modifier.fillMaxWidth()) { Text("Start display inspection") }
            TextButton(onClick = { finish(skip = true) }) { Text("Skip for now") }
        }
        if(phase == "paused")InspectionPage("Display inspection paused") {
            Text("The app was interrupted. Restart before confirming a pass.")
            Button(onClick = { viewedNames = emptyList(); index = 0; affected = emptySet(); issues = emptySet(); normalConfirmed = false; brightnessConfirmed = false; interrupted = false; phase = "patterns" }, modifier = Modifier.fillMaxWidth()) { Text("Restart inspection") }
            OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Save as unverified") }
        }
        if(phase == "review")InspectionPage("Display observations") {
            Text("${viewed.size} of ${patterns.size} patterns viewed", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { showIssues = !showIssues }, modifier = Modifier.fillMaxWidth()) {
                Text(if(showIssues)"Hide problem types" else if(issues.isNotEmpty())"${issues.size} problem types selected" else "Report a display problem")
            }
            if(showIssues)for(issue in DisplayIssue.entries)Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(issue in issues, { selected -> issues = if(selected)issues + issue else issues - issue })
                Text(issue.title)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(normalConfirmed, { normalConfirmed = it })
                Text(if(issues.isEmpty() && affected.isEmpty())"Viewed patterns looked normal" else "Other viewed patterns looked normal")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(brightnessConfirmed, { brightnessConfirmed = it })
                Text("Brightness dimmed and brightened as expected")
            }
            if(issues.isNotEmpty() || affected.isNotEmpty()) {
                Text("Affected patterns (optional)", style = MaterialTheme.typography.titleSmall)
                for(seen in patterns.filter { it in viewed })Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(seen in affected, { selected -> affected = if(selected)affected + seen else affected - seen })
                    Text(seen.title)
                }
                OutlinedTextField(location, { location = it.take(40) }, label = { Text("Area (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it.take(300) }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Save observations") }
            TextButton(onClick = { phase = "patterns"; controls = false }) { Text("Return to patterns") }
        }
    }
}
