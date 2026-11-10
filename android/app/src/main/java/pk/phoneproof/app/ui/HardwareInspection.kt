package pk.phoneproof.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pk.phoneproof.diagnostics.*

/** A stage advances only after the observation has been persisted by the caller. */
@Composable
fun HardwareInspection(workflow: InspectionWorkflow, latest: Map<String, DiagnosticResult>, busy: Boolean,
    onRun: (List<DiagnosticDefinition>, (List<DiagnosticResult>) -> Unit) -> Unit,
    onClose: () -> Unit) {
    val batches = remember(workflow) { DiagnosticProbePlanner.batches(workflow.definitions) }
    var steps by remember(workflow.id) { mutableStateOf(batches.indices.filter { index -> batches[index].definitions.any {
        latest[it.id] == null || latest[it.id]?.status in setOf(DiagnosticStatus.INCONCLUSIVE, DiagnosticStatus.SKIPPED_BY_USER, DiagnosticStatus.NOT_ACCESSIBLE)
    } }) }
    var position by remember(workflow.id) { mutableIntStateOf(0) }
    var complete by remember(workflow.id) { mutableStateOf(steps.isEmpty()) }
    var observed by remember(workflow.id) { mutableStateOf(latest) }
    Dialog(onDismissRequest = { if(!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(workflow.title, style = MaterialTheme.typography.headlineMedium)
                if(complete) {
                    Text("Stage saved", style = MaterialTheme.typography.titleLarge)
                    val stageResults=workflow.definitions.mapNotNull { observed[it.id] }
                    Text(if(stageResults.any { it.status == DiagnosticStatus.FAIL }) "A problem was recorded. Review it in the inspection."
                        else if(stageResults.size<workflow.definitions.size || stageResults.any { it.status in setOf(DiagnosticStatus.INCONCLUSIVE, DiagnosticStatus.NOT_ACCESSIBLE, DiagnosticStatus.SKIPPED_BY_USER) }) "Some checks remain unverified."
                        else "Your observations are saved on this phone.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
                    TextButton(onClick={ steps=batches.indices.toList();position=0;complete=false },modifier=Modifier.fillMaxWidth()) { Text("Retest this stage") }
                } else {
                    val batch = batches[steps[position]]
                    val d = batch.definitions.first()
                    Text("Step ${position + 1} of ${steps.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { position.toFloat() / steps.size }, modifier = Modifier.fillMaxWidth())
                    Text(when(d.handler) {
                        "microphone" -> "Microphone"
                        "sensor_sample" -> d.parameters["type"].orEmpty().replace('_', ' ').replaceFirstChar { it.uppercase() }
                        else -> d.title
                    }, style = MaterialTheme.typography.titleLarge)
                    Text(when(d.handler) {
                        "microphone" -> "Speak normally near the phone for a few seconds."
                        "sensor_sample" -> when(d.parameters["type"]) {
                            "light" -> "Cover and uncover the light sensor near the top of the phone."
                            "proximity" -> "Move your hand close to the top of the screen, then away."
                            else -> "Gently move and rotate the phone while it records."
                        }
                        "charging" -> if(d.parameters.values.any { it.contains("disconnect") }) {
                            if(busy)"Unplug the charger now." else "Start with the charger connected. Tap Start, then unplug it."
                        } else {
                            if(busy)"Connect the charger now." else "Start with the charger unplugged. Tap Start, then connect it."
                        }
                        "biometric" -> "Use your enrolled fingerprint or face."
                        "location" -> "Enable location and move near a window."
                        else -> "Complete the next check to continue."
                    }, style = MaterialTheme.typography.bodyLarge)
                    val previous=batch.definitions.mapNotNull { observed[it.id] }
                    if(previous.isNotEmpty()) Text(previous.joinToString(" · ") { when(it.status) {
                        DiagnosticStatus.PASS -> "Observed"; DiagnosticStatus.FAIL -> "Problem recorded"
                        DiagnosticStatus.NOT_SUPPORTED -> "Unavailable on this phone"; else -> "Unverified"
                    } }.split(" · ").distinct().joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy, onClick = {
                        onRun(batch.definitions) { results ->
                            observed=observed+results.associateBy { it.checkId }
                            if(position+1<steps.size)position++ else complete=true
                        }
                    }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if(busy) "Observing…" else "Start step") }
                    TextButton(enabled = !busy, onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Save & close") }
                }
            }
        }
    }
}
