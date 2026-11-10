package pk.phoneproof.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import pk.phoneproof.app.InspectionViewModel
import pk.phoneproof.app.data.LocalInspection
import pk.phoneproof.app.data.LocalResult
import pk.phoneproof.diagnostics.*

@Composable
fun InspectionStages(vm: InspectionViewModel, inspection: LocalInspection, rows: List<LocalResult>, busy: Boolean,
    onWorkflow: (InspectionWorkflow) -> Unit, onDetails: () -> Unit, onPair: () -> Unit, onCloudReport: () -> Unit) {
    val definitions = remember(inspection.id, inspection.definitionSnapshot) { vm.definitions() }
    val workflows = remember(definitions) { InspectionWorkflows.manual(definitions) }
    val automatic = remember(definitions) { InspectionWorkflows.automatic(definitions) }
    val latest = remember(rows) { DiagnosticAttempts.latest(rows.map { DiagnosticRegistry.json.decodeFromString<DiagnosticResult>(it.payload) }) }
    var advanced by rememberSaveable(inspection.id) { mutableStateOf(false) }
    fun checks(w: InspectionWorkflow) = w.definitions.filter { it.weight > 0 }.ifEmpty { w.definitions }
    fun reviewed(w: InspectionWorkflow) = checks(w).all { latest[it.id] != null }
    fun label(w: InspectionWorkflow): String {
        val results = checks(w).mapNotNull { latest[it.id] }
        return when {
            results.isEmpty() -> "Not started"
            results.any { it.status == DiagnosticStatus.FAIL } -> "Problem recorded"
            !reviewed(w) -> "In progress"
            results.all { it.status == DiagnosticStatus.NOT_SUPPORTED } -> "Unavailable on this phone"
            results.any { it.status in setOf(DiagnosticStatus.INCONCLUSIVE, DiagnosticStatus.SKIPPED_BY_USER, DiagnosticStatus.NOT_ACCESSIBLE) } -> "Needs review"
            else -> "Reviewed"
        }
    }
    val autoDone = automatic.all { latest[it.id] != null }
    val autoProblem=automatic.any { it.weight>0&&latest[it.id]?.status==DiagnosticStatus.FAIL }
    val autoUnverified=automatic.any { it.weight>0&&latest[it.id]?.status in setOf(DiagnosticStatus.INCONCLUSIVE,DiagnosticStatus.SKIPPED_BY_USER,DiagnosticStatus.NOT_ACCESSIBLE) }
    val done = workflows.count(::reviewed) + if(autoDone) 1 else 0
    val total = workflows.size + 1
    val next = workflows.firstOrNull { !reviewed(it) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("inspection-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if(inspection.serverFinalized) "Inspection finalized" else "Check this phone", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text("$done of $total stages reviewed", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            }
            item { Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(if(autoProblem)AppSymbol.Alert else if(autoDone&&!autoUnverified) AppSymbol.Check else AppSymbol.Info,
                        tint = if(autoProblem)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Device self-checks", style = MaterialTheme.typography.titleMedium)
                        Text(if(autoProblem)"Problem recorded" else if(autoUnverified)"Needs review" else if(autoDone) "Collected automatically" else if(busy) "Reading this phone…" else "Some information is unavailable", style = MaterialTheme.typography.bodySmall)
                    }
                    if((!autoDone||autoProblem||autoUnverified) && !busy && !inspection.serverFinalized) TextButton(onClick = vm::runAutomatic) { Text("Retry") }
                }
            } }
            items(workflows, key = { it.id }) { stage ->
                val state = label(stage)
                Surface(onClick = { onWorkflow(stage) }, enabled = !busy && !inspection.serverFinalized,
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(when(state) { "Reviewed" -> AppSymbol.Check; "Problem recorded" -> AppSymbol.Alert; else -> AppSymbol.Play },
                            tint = if(state == "Problem recorded") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stage.title, style = MaterialTheme.typography.titleMedium)
                            Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AppIcon(AppSymbol.Next, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text(if(inspection.serverFinalized) "Cloud report saved · this inspection is locked"
                    else if(rows.any { it.syncError != null }) "Saved on phone · sync needs attention"
                    else if(rows.any { !it.synced }) "Saved on phone · awaiting sync" else "Saved on phone",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDetails) { Text("Results & evidence") }
                TextButton(onClick = { advanced = !advanced }) { Text(if(advanced) "Hide additional checks" else "Additional checks") }
            }
            if(advanced) items(InspectionWorkflows.advanced(definitions), key = { it.id }) { stage ->
                OutlinedButton(onClick = { onWorkflow(stage) }, enabled = !busy && !inspection.serverFinalized, modifier = Modifier.fillMaxWidth()) { Text(stage.title) }
            }
            item {
                OutlinedButton(onClick = onPair, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Add photos with another phone") }
                TextButton(onClick = onCloudReport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if(inspection.serverFinalized) "Open cloud report" else "Publish cloud report") }
            }
        }
        Surface {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if(next != null && !inspection.serverFinalized) Button(onClick = { onWorkflow(next) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue · ${next.title}") }
                if(next == null) Text("Stages reviewed. Check any unverified results before sharing.", style = MaterialTheme.typography.bodySmall)
                if(next == null) Button(onClick = { vm.saveLocalReport() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save report on phone") }
                else TextButton(onClick = { vm.saveLocalReport() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save partial report") }
            }
        }
    }
}
