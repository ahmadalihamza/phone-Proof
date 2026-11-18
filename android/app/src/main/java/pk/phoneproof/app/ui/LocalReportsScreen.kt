package pk.phoneproof.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pk.phoneproof.app.InspectionViewModel
import pk.phoneproof.app.data.LocalInspection
import pk.phoneproof.app.data.LocalReport
import pk.phoneproof.app.data.LocalReportPdf
import java.io.File
import pk.phoneproof.diagnostics.DiagnosticRegistry
import pk.phoneproof.diagnostics.ExpertAssessment

/** Local inspections remain available without a server or sign-in. Cloud reports retain their own flow. */
@Composable
fun LocalReportsScreen(
    vm:InspectionViewModel,
    activity:Activity,
    onResume:(LocalInspection)->Unit,
    cloudContent:@Composable ()->Unit,
) {
    val session=rememberScreenSession(vm)
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex=tab) {
            Tab(selected=tab==0,onClick={tab=0},text={Text("On phone")})
            Tab(selected=tab==1,onClick={tab=1},text={Text("Cloud")})
        }
        if(tab==1)cloudContent() else key(session) {
            LocalReportsContent(vm,activity,onResume)
        }
    }
}

@Composable
private fun LocalReportsContent(vm:InspectionViewModel,activity:Activity,onResume:(LocalInspection)->Unit) {
    val inspections by vm.inspections.collectAsState()
    val reports by vm.localReports.collectAsState()
    val busy by vm.busy.collectAsState()
    val visible=inspections.filter(vm::canView)
    val saved=reports.associateBy { it.inspectionId }
    var opened by remember { mutableStateOf<LocalReport?>(null) }

    fun open(inspection:LocalInspection) {
        vm.saveLocalReport(inspection.id) { report -> opened=report }
    }
    fun export(report:LocalReport) {
        vm.action("Preparing local PDF") {
            val inspection=requireNotNull(vm.db.dao().inspection(report.inspectionId))
            check(vm.canView(inspection)) { "Sign in to the original account and server to open this inspection." }
            val file=withContext(Dispatchers.IO) { LocalReportPdf.write(report,File(activity.cacheDir,"reports")) }
            check(vm.canView(inspection)) { "The account changed. Open the report again." }
            val uri=FileProvider.getUriForFile(activity,activity.packageName+".files",file)
            val share=Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM,uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData=ClipData.newRawUri("Local inspection summary",uri) }
            activity.startActivity(Intent.createChooser(share,"Share local summary"))
        }
    }

    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=24.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading("On this phone") }
        if(visible.isEmpty())item {
            QuietCard {
                Text("No inspections yet",style=MaterialTheme.typography.titleLarge)
                Text("Your inspections and saved summaries appear here.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(visible,key={it.id}) { inspection ->
            val snapshot=saved[inspection.id]
            val payload=snapshot?.let { runCatching { JSONObject(it.payload) }.getOrNull() }
            val device=runCatching { JSONObject(inspection.profile).getJSONObject("device") }.getOrNull()
            QuietCard {
                Text(device?.optString("model")?.takeIf { it.isNotBlank() }?:"Phone inspection",style=MaterialTheme.typography.titleLarge)
                Text(inspection.createdAt.take(16).replace('T',' '),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(inspection.serverFinalized)"Cloud finalized · Local copy" else if(snapshot==null)"Inspection in progress" else "Saved local summary",style=MaterialTheme.typography.labelLarge)
                if(payload!=null)Text("${payload.optInt("problems")} need attention · ${payload.optInt("unverified")} unverified",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick={open(inspection)},enabled=busy==null,modifier=Modifier.weight(1f)) { Text("View summary") }
                    if(!inspection.serverFinalized)Button(onClick={if(vm.canView(inspection))onResume(inspection)},enabled=busy==null,modifier=Modifier.weight(1f)) { Text("Resume") }
                }
            }
        }
    }

    opened?.let { report ->
        val inspection=visible.firstOrNull { it.id==report.inspectionId }
        if(inspection==null)LaunchedEffect(report) { opened=null } else {
            val payload=remember(report) { JSONObject(report.payload) }
            var showChecks by remember(report.inspectionId) { mutableStateOf(false) }
            DetailSheet("Local summary",{opened=null}) {
                Text(payload.getJSONObject("device").optString("model","Phone"),style=MaterialTheme.typography.headlineSmall)
                Text("Saved on this phone · Not server verified",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                QuietCard {
                    Text("Functional checks",style=MaterialTheme.typography.titleMedium)
                    SummaryCount("Passed",payload.optInt("passed"))
                    SummaryCount("Need attention",payload.optInt("problems"))
                    SummaryCount("Unverified",payload.optInt("unverified"))
                    SummaryCount("Unavailable",payload.optInt("unavailable"))
                    if(!payload.isNull("tested_coverage_percent")) {
                        HorizontalDivider()
                        Text("${"%.0f".format(java.util.Locale.ROOT,payload.optDouble("tested_coverage_percent"))}% conclusive coverage",style=MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(onClick={export(report)},enabled=busy==null,modifier=Modifier.fillMaxWidth()) { Text("Share local PDF") }
                if(payload.has("analysis")) {
                    ExpertAnalysisPanel(remember(report) { DiagnosticRegistry.json.decodeFromString<ExpertAssessment>(payload.getJSONObject("analysis").toString()) })
                }
                if(!inspection.serverFinalized)OutlinedButton(onClick={opened=null;onResume(inspection)},enabled=busy==null,modifier=Modifier.fillMaxWidth()) { Text("Continue inspection") }
                TextButton(onClick={showChecks=!showChecks},modifier=Modifier.fillMaxWidth()) { Text(if(showChecks)"Hide check details" else "Show check details") }
                if(showChecks) {
                    val checks=payload.getJSONArray("checks")
                    for(index in 0 until checks.length()) {
                        val check=checks.getJSONObject(index)
                        if(!check.optBoolean("score_eligible",true))continue
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(check.optString("title"),style=MaterialTheme.typography.titleSmall)
                            Text(LocalReportPdf.statusLabel(check.optString("status")),style=MaterialTheme.typography.bodySmall,color=if(check.optString("status")=="FAIL")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            check.optString("message").takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCount(label:String,count:Int) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,style=MaterialTheme.typography.bodyMedium)
        Text(count.toString(),style=MaterialTheme.typography.titleMedium)
    }
}
