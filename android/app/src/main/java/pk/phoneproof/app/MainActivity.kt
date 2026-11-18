package pk.phoneproof.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import pk.phoneproof.app.data.*
import pk.phoneproof.app.ui.*
import pk.phoneproof.diagnostics.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PhoneProofTheme { PhoneProof(viewModel(), this) } }
    }
}

private data class InspectionProbeRequest(val inspectionId:String,val definitions:List<DiagnosticDefinition>,
    val onCompleted:(List<DiagnosticResult>)->Unit)
private data class UnsavedObservation(val inspectionId:String,val results:List<DiagnosticResult>,val onSaved:()->Unit,val error:String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneProof(vm: InspectionViewModel, activity: MainActivity) {
    var tab by rememberSaveable { mutableStateOf("Inspect") }
    var scanner by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val inspections by vm.inspections.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val running by vm.testRunning.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var guided by remember { mutableStateOf<InspectionProbeRequest?>(null) }
    var pending by remember { mutableStateOf<InspectionProbeRequest?>(null) }
    var workflow by remember { mutableStateOf<Pair<String,InspectionWorkflow>?>(null) }
    var details by remember { mutableStateOf(false) }
    var unsaved by remember { mutableStateOf<UnsavedObservation?>(null) }
    var pairing by remember { mutableStateOf<String?>(null) }
    var reportUrl by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val session = rememberScreenSession(vm)
    LaunchedEffect(session) {
        pairing = null; reportUrl = null; pending = null; guided = null; workflow = null
        unsaved=null
        if(vm.selected.value?.let { !vm.canView(it) } == true) { vm.stop();vm.selected.value=null }
    }
    LaunchedEffect(session,selected?.ownerId,selected?.serverUrl) {
        if(selected?.let { !vm.canView(it) }==true) { vm.stop();vm.selected.value=null;workflow=null;guided=null;pending=null }
    }
    LaunchedEffect(notice) {
        notice?.let { message ->
            snackbar.showSnackbar(message, "Dismiss", duration = SnackbarDuration.Long)
            if(vm.notice.value==message)vm.notice.value=null
        }
    }
    DisposableEffect(activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if(event == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.stop() }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    BackHandler(scanner || (tab == "Inspect" && selected != null)) {
        if(scanner) scanner = false else if(details) details=false else { vm.stop(); vm.selected.value = null }
    }
    fun saveObserved(id:String,results:List<DiagnosticResult>,onSaved:()->Unit={}) {
        vm.saveResults(id,results,onError={ error -> unsaved=UnsavedObservation(id,results,onSaved,error) }) {
            unsaved=null;onSaved()
        }
    }
    fun execute(request: InspectionProbeRequest) {
        if(request.inspectionId!=vm.selected.value?.id)return
        val d=request.definitions.first()
        when {
            request.definitions.all { vm.engine.canRun(it) } -> vm.runBatch(request.definitions,request.inspectionId,request.onCompleted)
            d.handler == "biometric" -> authenticateBiometric(activity, d, vm.engine) { result ->
                saveObserved(request.inspectionId,listOf(result)) { request.onCompleted(listOf(result)) }
            }
            d.handler == "backend" -> vm.action("Checking connection") {
                val start = Instant.now().toString()
                val result = try {
                    val connection=withContext(Dispatchers.IO) { vm.api.probeConnection() }
                    check(connection.connected) { connection.message }
                    vm.engine.result(d, DiagnosticStatus.PASS, mapOf("health_endpoint" to "HTTP 200"), "Backend reachable", start)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { vm.engine.result(d, DiagnosticStatus.INCONCLUSIVE, emptyMap(), e.message?:"Backend unavailable", start) }
                vm.persist(d, result, request.inspectionId);request.onCompleted(listOf(result))
            }
            else -> guided = request
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val request = pending; pending = null
        if(request != null && request.inspectionId == vm.selected.value?.id) {
            if(grants.values.all { it }) execute(request)
            else {
                val results=request.definitions.map { vm.engine.result(it, DiagnosticStatus.NOT_ACCESSIBLE, emptyMap(), "Permission denied. Retry to allow access.") }
                saveObserved(request.inspectionId,results) { request.onCompleted(results) }
            }
        }
    }
    fun startBatch(definitions:List<DiagnosticDefinition>,onCompleted:(List<DiagnosticResult>)->Unit) {
        val id=vm.selected.value?.id?:return
        if(vm.selected.value?.serverFinalized==true) { vm.notice.value="This cloud report is finalized. Start a new inspection.";return }
        val request=InspectionProbeRequest(id,definitions,onCompleted)
        val d=definitions.first()
        val profile = vm.profile() ?: return
        val gate = EligibilityFilter.evaluate(d.copy(permissions = emptyList()), profile.api, profile.features, emptySet())
        if(!gate.eligible && definitions.size==1) {
            val result=vm.engine.result(d, gate.status ?: DiagnosticStatus.NOT_ACCESSIBLE, emptyMap(), gate.reason)
            saveObserved(id,listOf(result)) { onCompleted(listOf(result)) };return
        }
        if(d.handler == "torch") {
            val flash = runCatching { activity.getSystemService(android.hardware.camera2.CameraManager::class.java)
                .getCameraCharacteristics(d.parameters.getValue("camera_id")).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) }.getOrNull()
            if(flash == false) {
                val result=vm.engine.result(d, DiagnosticStatus.NOT_SUPPORTED, emptyMap(), "This camera has no flash")
                saveObserved(id,listOf(result)) { onCompleted(listOf(result)) };return
            }
        }
        val missing = definitions.flatMap { DiagnosticRegistry.permissions(it) }.distinct().filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) { pending=request;permissions.launch(missing.toTypedArray()) } else execute(request)
    }
    fun openWorkflow(stage:InspectionWorkflow) { selected?.id?.let { workflow=it to stage } }
    fun start(d:DiagnosticDefinition) {
        val stage=InspectionWorkflows.manual(vm.definitions()).firstOrNull { w -> w.definitions.any { it.id==d.id } }
        if(stage!=null)openWorkflow(stage) else startBatch(listOf(d)) {}
    }
    fun pair() {
        if(vm.api.vault.snapshot().tokens == null) { tab = "Account"; vm.notice.value = "Sign in to pair your scanner."; return }
        val id = vm.selected.value?.id ?: return
        vm.action("Preparing pairing code") {
            val scoped = Api(activity, AuthenticatedRequestScope(vm.api.vault.snapshot().generation, vm.api.baseUrl))
            withContext(Dispatchers.IO) { SyncWorker.synchronize(activity) }.requireInspectionReady(id)
            val value = withContext(Dispatchers.IO) { scoped.call("inspections/$id/pairing-token", "POST") }
            scoped.requireCurrentSession(); pairing = value
        }
    }
    fun finalizeReport() {
        if(vm.api.vault.snapshot().tokens == null) { tab = "Account"; vm.notice.value = "Sign in to create your report."; return }
        val id = vm.selected.value?.id ?: return
        selected?.cloudVerificationUrl?.let { reportUrl=it;return }
        vm.action("Creating report") {
            vm.reports.save(id)
            val scoped = Api(activity, AuthenticatedRequestScope(vm.api.vault.snapshot().generation, vm.api.baseUrl))
            withContext(Dispatchers.IO) { SyncWorker.synchronize(activity) }.requireInspectionReady(id,photos=true)
            val result = withContext(Dispatchers.IO) { JSONObject(scoped.call("inspections/$id/finalize", "POST")) }
            scoped.requireCurrentSession()
            val url=result.getString("verification_url")
            vm.db.dao().markFinalized(id,result.getString("id"),url)
            vm.reports.cacheCloud(id,result.toString());reportUrl=url
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(if(scanner) "Scanner" else "PhoneProof", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { if(scanner || (tab == "Inspect" && selected != null)) IconButton(onClick = {
                if(scanner) scanner = false else if(details) details=false else { vm.stop(); vm.selected.value = null }
            }) { AppIcon(AppSymbol.Back, "Back") } },
            actions = {
                if(!scanner && tab == "Inspect" && selected != null) {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { AppIcon(AppSymbol.More, "Inspection options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Sync results") }, { menu = false; vm.sync() }, enabled = busy == null)
                        DropdownMenuItem({ Text("Retry uploads") }, { menu = false; vm.retrySync() }, enabled = busy == null)
                        DropdownMenuItem({ Text("Read device info") }, { menu = false; vm.runAutomatic() }, enabled = busy == null)
                        DropdownMenuItem({ Text("Pair scanner") }, { menu = false; pair() }, enabled = busy == null)
                        DropdownMenuItem({ Text("New inspection") }, { menu = false; vm.startInspection() }, enabled = busy == null)
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = { if(!scanner) Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf("Inspect" to AppSymbol.Inspect, "Reports" to AppSymbol.Reports, "Account" to AppSymbol.Account).forEach { (name, symbol) ->
                    NavigationBarItem(tab == name, { tab = name }, icon = { AppIcon(symbol) }, label = { Text(name) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                }
            }
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(busy!!, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if(running) TextButton(onClick = vm::stop) { Text("Stop") }
                }
            }
            when {
                scanner -> ScannerScreen(vm, activity, onSignIn = { scanner = false; tab = "Account" })
                tab == "Reports" -> LocalReportsScreen(vm,activity,onResume={ vm.select(it);details=false;tab="Inspect" }) { ReportsScreen(vm, activity, onSignIn = { tab = "Account" }) }
                tab == "Account" -> AccountScreen(vm)
                selected != null && vm.canView(selected!!) && details -> InspectionResults(vm, selected!!, rows, busy != null, ::start, ::pair, ::finalizeReport)
                selected != null && vm.canView(selected!!) -> InspectionStages(vm,selected!!,rows,busy!=null,::openWorkflow,{ details=true },::pair,::finalizeReport)
                else -> InspectHome(inspections.filter(vm::canView), busy != null, { details=false;vm.startInspection() }, { details=false;vm.select(it) }, { scanner = true }, { history = true })
            }
        }
    }
    workflow?.let { (id,stage) ->
        val latest=DiagnosticAttempts.latest(rows.map { DiagnosticRegistry.json.decodeFromString<DiagnosticResult>(it.payload) })
        key(id,stage.id) {
            when(stage.kind) {
                "DISPLAY" -> DisplayInspection(stage.definitions,vm.engine,{ saveObserved(id,it) { workflow=null } },{ if(vm.busy.value==null&&unsaved==null)workflow=null })
                "TOUCH" -> TouchInspection(stage.definitions,vm.engine,{ saveObserved(id,it) { workflow=null } },{ if(vm.busy.value==null&&unsaved==null)workflow=null })
                else -> HardwareInspection(stage,latest,busy!=null,::startBatch,{ workflow=null })
            }
        }
    }
    guided?.let { request ->
        key(request.inspectionId,request.definitions.first().id) {
            GuidedTest(request.definitions.first(),vm.engine,onResult={ result ->
                saveObserved(request.inspectionId,listOf(result)) { request.onCompleted(listOf(result)) }
            },onClose={ guided=null })
        }
    }
    unsaved?.let { observation -> AlertDialog(onDismissRequest={},title={Text("Observations aren't saved yet")},
        text={Text(observation.error)},confirmButton={TextButton(enabled=busy==null,onClick={saveObserved(observation.inspectionId,observation.results,observation.onSaved)}) { Text("Retry saving") }},
        dismissButton={TextButton(enabled=busy==null,onClick={unsaved=null;workflow=null;guided=null}) { Text("Discard these observations") }}) }
    if(history) ModalBottomSheet(onDismissRequest = { history = false }) {
        Text("Inspections", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineMedium)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp)) {
            items(inspections.filter(vm::canView), key = { it.id }) { i -> InspectionSummary(i) { history = false;details=false;vm.select(i) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    pairing?.let { value ->
        var manual by remember(value) { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { pairing = null }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Pair the other phone", style = MaterialTheme.typography.headlineMedium)
                Text("Open Scanner and scan this code.", style = MaterialTheme.typography.bodyMedium)
                QrImage(value, 244)
                Text("Expires in 5 minutes", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { manual = !manual }) { Text(if(manual) "Hide code" else "Use a code instead") }
                if(manual) SelectionText(value)
                Button(onClick = { pairing = null }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
            }
        }
    }
    reportUrl?.let { url -> ModalBottomSheet(onDismissRequest = { reportUrl = null }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIcon(AppSymbol.Check, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Report ready", style = MaterialTheme.typography.headlineMedium); QrImage(url, 220)
            Button(onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); reportUrl = null }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Open report") }
            TextButton(onClick = { reportUrl = null; tab = "Reports" }) { Text("View all reports") }
        }
    } }
}

@Composable
private fun InspectHome(inspections: List<LocalInspection>, busy: Boolean, onNew: () -> Unit, onSelect: (LocalInspection) -> Unit, onScanner: () -> Unit, onHistory: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("THIS PHONE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Build.MODEL, style = MaterialTheme.typography.headlineLarge)
                Text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} · Android ${Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon(AppSymbol.Phone, modifier = Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
        } }
        item { Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Check this phone", style = MaterialTheme.typography.titleLarge)
                    Text("Screen, cameras, sound & more", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onNew, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Start inspection", Modifier.weight(1f)); AppIcon(AppSymbol.Next) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AppIcon(AppSymbol.Cloud, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Saved as you go. Works offline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        item { Surface(onClick = onScanner, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppIcon(AppSymbol.Scan, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Scan another phone", style = MaterialTheme.typography.titleMedium)
                    Text("Photos & cosmetic review", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppIcon(AppSymbol.Next, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent inspections", style = MaterialTheme.typography.titleMedium)
            if(inspections.size > 4) TextButton(onClick = onHistory) { Text("See all") }
        } }
        if(inspections.isEmpty()) item { Text("Your inspections will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(inspections.take(4), key = { it.id }) { i -> InspectionSummary(i) { onSelect(i) } }
    }
}

@Composable
private fun InspectionSummary(item: LocalInspection, onClick: () -> Unit) {
    val profile = remember(item.profile) { DiagnosticRegistry.json.decodeFromString<CapabilityProfile>(item.profile) }
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon(AppSymbol.Phone, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.device["model"] ?: "Phone", style = MaterialTheme.typography.titleMedium)
                Text(formatInspectionTime(item.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon(AppSymbol.Next, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InspectionResults(vm: InspectionViewModel, inspection: LocalInspection, rows: List<LocalResult>, busy: Boolean,
    onRun: (DiagnosticDefinition) -> Unit, onPair: () -> Unit, onFinalize: () -> Unit) {
    val definitions = remember(inspection.profile) { vm.definitions() }
    val attempts = remember(rows) { rows.map { DiagnosticRegistry.json.decodeFromString<DiagnosticResult>(it.payload) } }
    val results = remember(attempts) { DiagnosticAttempts.latest(attempts) }
    val rule = vm.scoringRules.rules.firstOrNull { it.version == inspection.scoreRuleVersion }
    val progress = remember(definitions, attempts, rule) { InspectionProgress.from(definitions, attempts, rule) }
    val score = remember(definitions, attempts, rule) { rule?.let { ScoringPreview.calculate(definitions, attempts, it) } }
    var deviceInfo by rememberSaveable(inspection.id) { mutableStateOf(false) }
    var filter by rememberSaveable(inspection.id) { mutableStateOf("All") }
    var category by rememberSaveable(inspection.id) { mutableStateOf("All") }
    var detail by remember { mutableStateOf<DiagnosticDefinition?>(null) }
    var explain by remember { mutableStateOf(false) }
    val candidates = definitions.filter { it.implementation == "ENABLED" && if(deviceInfo) it.weight == 0.0 else it.weight > 0 }
    val categories = candidates.map { it.category }.distinct()
    val filtered = candidates.filter { d -> (category == "All" || d.category == category) && when(filter) {
        "To do" -> results[d.id] == null || d in progress.required
        "Issues" -> results[d.id]?.status in setOf(DiagnosticStatus.FAIL, DiagnosticStatus.INCONCLUSIVE, DiagnosticStatus.NOT_ACCESSIBLE)
        else -> true
    } }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("inspection-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text(vm.profile()?.device?.get("model") ?: "Phone", style = MaterialTheme.typography.headlineMedium)
                Text(formatInspectionTime(inspection.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if(progress.ready) "Checks recorded" else "In progress", style = MaterialTheme.typography.titleMedium)
                        Text("${progress.recorded} / ${progress.checks.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    LinearProgressIndicator(progress = { if(progress.checks.isEmpty()) 0f else progress.recorded.toFloat()/progress.checks.size }, modifier = Modifier.fillMaxWidth().height(5.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("passed", progress.passed, MaterialTheme.colorScheme.primary)
                        Metric("problems", progress.failed, if(progress.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Metric("unavailable", progress.unavailable, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { explain = true }, verticalAlignment = Alignment.CenterVertically) {
                        Text(if(progress.ready && score?.score != null) "Functional score  ${"%.0f".format(score.score)} / 100" else "${score?.coveragePercent?.let { "%.0f".format(it) } ?: "0"}% tested · Score pending",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AppIcon(AppSymbol.Info, "Score details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } }
            item {
                ExpertAnalysisPanel(attempts)
                SecondaryTabRow(selectedTabIndex = if(deviceInfo) 1 else 0, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(!deviceInfo, { deviceInfo = false; category = "All"; filter = "All" }, text = { Text("Checks") })
                    Tab(deviceInfo, { deviceInfo = true; category = "All"; filter = "All" }, text = { Text("Device info") })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    (listOf("All") + categories).forEach { c -> FilterChip(category == c, { category = c }, label = { Text(if(c == "All") "All categories" else categoryLabel(c)) }) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf("All", "To do", "Issues").forEach { f -> TextButton(onClick = { filter = f }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(f, color = if(filter == f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if(filter == f) FontWeight.SemiBold else FontWeight.Normal)
                    } }
                }
            }
            if(filtered.isEmpty()) item { Text(if(filter == "Issues") "No problems in this view." else "Nothing left in this view.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(filtered, key = { it.id }) { d ->
                val result = results[d.id]
                Surface(onClick = { detail = d }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val color = statusColor(result?.status)
                        AppIcon(when(result?.status) { DiagnosticStatus.PASS -> AppSymbol.Check; DiagnosticStatus.FAIL -> AppSymbol.Alert; null -> AppSymbol.Play; else -> AppSymbol.Info }, tint = color)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(statusLabel(result?.status), style = MaterialTheme.typography.bodySmall, color = color)
                        }
                        AppIcon(AppSymbol.Next, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                val rejected = rows.count { it.syncError != null }; val uploads = rows.count { !it.synced }
                Text(if(rejected > 0) "$rejected uploads need attention" else if(uploads > 0) "Saved on phone · $uploads awaiting sync" else "All results synced",
                    style = MaterialTheme.typography.bodySmall, color = if(rejected > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                progress.next?.let { Text("Next: ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = !busy && (progress.next != null || progress.ready), onClick = { progress.next?.let(onRun) ?: onFinalize() }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(if(progress.ready) "Create report" else if(progress.next != null) "Continue inspection" else "No measured checks", Modifier.weight(1f)); AppIcon(AppSymbol.Next)
                    }
                    OutlinedIconButton(onClick = onPair, enabled = !busy, modifier = Modifier.size(52.dp), shape = MaterialTheme.shapes.medium) { AppIcon(AppSymbol.Scan, "Pair scanner for photos") }
                }
            }
        }
    }
    detail?.let { d -> CheckDetails(d, results[d.id], rows.lastOrNull { it.checkId == d.id }?.syncError, busy,
        { detail = null }, { detail = null; onRun(d) }, { detail = null; vm.skip(d) }) }
    if(explain) ModalBottomSheet(onDismissRequest = { explain = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Score details", style = MaterialTheme.typography.headlineMedium)
            Text("Functional preview", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(score?.score?.let { "%.2f / 100".format(it) } ?: "Not measured", style = MaterialTheme.typography.headlineLarge)
            Text("Coverage ${score?.coveragePercent?.let { "%.0f%%".format(it) } ?: "—"} · Rule ${inspection.scoreRuleVersion}")
            score?.categories?.filter { it.applicableWeight > 0 }?.forEach { c -> Row(Modifier.fillMaxWidth()) {
                Text(c.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(c.score?.let { "%.0f / 100".format(it) } ?: "Not measured", style = MaterialTheme.typography.labelLarge)
            } }
            score?.caps?.forEach { Text("${it.reason} · maximum ${it.maximumScore}", color = MaterialTheme.colorScheme.error) }
            Text("Cosmetic and final grades appear in the report.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Inspection ${inspection.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { explain = false }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
        }
    }
}

@Composable
private fun Metric(label: String, number: Int, color: Color) {
    Text("$number $label", style = MaterialTheme.typography.labelMedium, color = color)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckDetails(d: DiagnosticDefinition, result: DiagnosticResult?, syncError: String?, busy: Boolean, onDismiss: () -> Unit, onRun: () -> Unit, onSkip: () -> Unit) {
    var technical by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(d.title, style = MaterialTheme.typography.headlineMedium)
            Text(statusLabel(result?.status), color = statusColor(result?.status), style = MaterialTheme.typography.titleMedium)
            if(result != null) Text(formatInspectionTime(result.completedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(syncError != null) Text("Upload needs attention. Retry from inspection options.", color = MaterialTheme.colorScheme.error)
            Button(enabled = !busy, onClick = onRun, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if(result == null) "Start check" else "Run again") }
            TextButton(enabled = !busy, onClick = onSkip) { Text("Skip for now") }
            HorizontalDivider()
            TextButton(onClick = { technical = !technical }) { Text(if(technical) "Hide evidence" else "View evidence & test details") }
            if(technical) {
                Text(d.description, style = MaterialTheme.typography.bodyMedium)
                Text("Expected: ${d.passCriteria}", style = MaterialTheme.typography.bodySmall)
                Text("${d.mode.replace('_', ' ')} · v${d.version}", style = MaterialTheme.typography.bodySmall)
                result?.let { SelectionText("${it.message}\n\nMeasured: ${it.measuredValue}\nEvidence: ${it.evidence}\nStarted: ${it.startedAt}\nCompleted: ${it.completedAt}\nApp: ${it.appVersion}") }
                syncError?.let { SelectionText(it) }
            }
        }
    }
}

@Composable
private fun statusColor(status: DiagnosticStatus?): Color = when(status) {
    DiagnosticStatus.PASS -> MaterialTheme.colorScheme.primary
    DiagnosticStatus.FAIL -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
private fun statusLabel(status: DiagnosticStatus?): String = when(status) {
    DiagnosticStatus.PASS -> "Passed"
    DiagnosticStatus.FAIL -> "Problem found"
    DiagnosticStatus.NOT_SUPPORTED -> "Not on this phone"
    DiagnosticStatus.NOT_ACCESSIBLE -> "Access unavailable"
    DiagnosticStatus.SKIPPED_BY_USER -> "Skipped"
    DiagnosticStatus.INCONCLUSIVE -> "Needs another try"
    null -> "Not checked"
}
private fun categoryLabel(value: String): String = when(value) {
    "All" -> "All"; "display" -> "Screen"; "touch" -> "Touch"; "sensors" -> "Sensors"; "biometrics" -> "Face & fingerprint"
    else -> value.replaceFirstChar { it.uppercase() }
}
private fun formatInspectionTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM · h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value.take(16))

@Composable
fun SelectionText(value: String) { androidx.compose.foundation.text.selection.SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) } }
