package pk.phoneproof.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pk.phoneproof.diagnostics.*

@Composable
fun ExpertAnalysisPanel(assessment: ExpertAssessment) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(if(expanded) "Hide diagnostic interpretation" else "Diagnostic interpretation")
    }
    if(expanded) {
        Text("Advisory rules · v${assessment.policyVersion}", style = MaterialTheme.typography.labelMedium)
        assessment.findings.forEach { finding ->
            var details by remember(finding.id) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { details = !details }) {
                    Text(finding.title.replaceFirstChar { it.uppercase() }, color = if(finding.severity == "WARNING") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                if(details) {
                    Text(finding.interpretation, style = MaterialTheme.typography.bodyMedium)
                    Text(finding.furtherInspection, style = MaterialTheme.typography.bodySmall)
                    Text(finding.confidenceBasis, style = MaterialTheme.typography.bodySmall)
                    Text(finding.limitations, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    finding.measurements.forEach { (key, value) -> Text("${key.replace('_', ' ')} · $value", style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun ExpertAnalysisPanel(attempts: List<DiagnosticResult>) {
    val context = LocalContext.current
    val policy = remember(context) { AnalysisPolicy.load(context) }
    val assessment = remember(attempts, policy) { ExpertAnalysis.analyze(attempts, policy) }
    ExpertAnalysisPanel(assessment)
}
