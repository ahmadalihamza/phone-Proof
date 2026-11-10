package pk.phoneproof.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.UUID
import pk.phoneproof.app.data.*
import pk.phoneproof.diagnostics.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InspectionViewModel(application:Application):AndroidViewModel(application) {
    val db=PhoneProofDatabase.get(application)
    val api=Api(application)
    val engine=DiagnosticsEngine(application)
    val scoringRules=ScoringRulebook.load(application)
    val inspections=db.dao().observeInspections().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val selected=MutableStateFlow<LocalInspection?>(null)
    val rows=selected.flatMapLatest { if(it==null) flowOf(emptyList()) else db.dao().observeResults(it.id) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val busy=MutableStateFlow<String?>(null)
    val testRunning=MutableStateFlow(false)
    val notice=MutableStateFlow<String?>(null)
    val syncOutcome=MutableStateFlow<SyncOutcome?>(null)
    val localReports=db.dao().observeReports().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val reports=LocalReportRepository(application,db)
    private var testJob:Job?=null
    init {
        viewModelScope.launch {
            db.dao().observeInspections().collect { items ->
                val current=selected.value
                if(current!=null)items.firstOrNull { it.id==current.id }?.let { updated ->
                    if(!canView(updated)) { stop();selected.value=null }
                    else if(updated!=current)selected.value=updated
                }
            }
        }
    }
    fun profile():CapabilityProfile?=selected.value?.let { DiagnosticRegistry.json.decodeFromString(it.profile) }
    fun definitions():List<DiagnosticDefinition> = selected.value?.let { item ->
        item.definitionSnapshot?.let { DiagnosticRegistry.json.decodeFromString<List<DiagnosticDefinition>>(it) }
            ?:profile()?.let { DiagnosticRegistry.expanded(getApplication(),it) }
    }?:emptyList()
    fun canView(item:LocalInspection):Boolean {
        if(item.ownerId==null&&item.serverUrl==null)return true
        val origin=api.baseUrl
        val owner=api.vault.identity(origin)?.id?:return false
        return item.ownerId==owner&&item.serverUrl==origin
    }
    fun startInspection() = launchTest("Reading this phone") {
        val p=withContext(Dispatchers.IO) { CapabilityScanner(getApplication()).scan() }
        val definitions=DiagnosticRegistry.expanded(getApplication(),p)
        val i=LocalInspection(UUID.randomUUID().toString(),Instant.now().toString(),DiagnosticRegistry.json.encodeToString(p),scoreRuleVersion=scoringRules.currentVersion,
            definitionSnapshot=DiagnosticRegistry.json.encodeToString(definitions))
        db.dao().create(i);selected.value=i
        for(d in definitions.filter { it.implementation=="ENABLED" }) {
            val gate=EligibilityFilter.evaluate(d.copy(permissions=emptyList()),p.api,p.features,emptySet())
            if(!gate.eligible)db.dao().record(LocalResult(UUID.randomUUID().toString(),i.id,d.id,Instant.now().toString(),DiagnosticRegistry.json.encodeToString(engine.result(d,gate.status?:DiagnosticStatus.NOT_ACCESSIBLE,emptyMap(),gate.reason))))
        }
        SyncWorker.enqueue(getApplication())
        readAutomatic(p,i.id,definitions)
    }
    fun select(i:LocalInspection) { if(canView(i)) { stop();selected.value=i } else notice.value="Sign in to the original account and server to open this inspection." }
    suspend fun persist(d:DiagnosticDefinition,r:DiagnosticResult,inspectionId:String?=selected.value?.id) {
        val id=inspectionId?:return
        db.withTransaction {
            val item=requireNotNull(db.dao().inspection(id)) { "Inspection is unavailable." }
            check(canView(item)) { "Sign in to the original account and server to save this observation." }
            if(item.serverFinalized)throw IllegalStateException("This cloud report is finalized. Start a new inspection to record more checks.")
            db.dao().record(LocalResult(UUID.randomUUID().toString(),id,d.id,r.completedAt,DiagnosticRegistry.json.encodeToString(r)))
        }
        runCatching { SyncWorker.enqueue(getApplication()) }
    }
    fun save(d:DiagnosticDefinition,r:DiagnosticResult) { selected.value?.id?.let { saveFor(it,d,r) } }
    fun saveFor(inspectionId:String,d:DiagnosticDefinition,r:DiagnosticResult) { viewModelScope.launch {
        try { persist(d,r,inspectionId) } catch(e:CancellationException) { throw e } catch(e:Exception) { notice.value=e.message?:"The result could not be saved." }
    } }
    fun saveResults(inspectionId:String,results:List<DiagnosticResult>,onError:(String)->Unit={},onSaved:()->Unit={}) {
        if(busy.value!=null) { onError("Another operation is running. Retry saving when it finishes.");return }
        action("Saving observations") {
            try { persistResults(inspectionId,results) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { onError(e.message?:"Observations could not be saved. Retry.");return@action }
            onSaved()
        }
    }
    private suspend fun persistResults(inspectionId:String,results:List<DiagnosticResult>) {
        db.withTransaction {
            val item=requireNotNull(db.dao().inspection(inspectionId)) { "Inspection is unavailable." }
            check(canView(item)) { "Sign in to the original account and server to save this observation." }
            check(!item.serverFinalized) { "This cloud report is finalized. Start a new inspection to record more checks." }
            results.forEach { r -> db.dao().record(LocalResult(UUID.randomUUID().toString(),inspectionId,r.checkId,r.completedAt,DiagnosticRegistry.json.encodeToString(r))) }
        }
        runCatching { SyncWorker.enqueue(getApplication()) }
    }
    fun skip(d:DiagnosticDefinition) { save(d,engine.result(d,DiagnosticStatus.SKIPPED_BY_USER,emptyMap(),"Inspector intentionally skipped this check")) }
    fun run(d:DiagnosticDefinition) {
        val p=profile()?:return
        val inspectionId=selected.value?.id?:return
        launchTest(d.title) {
            try { persist(d,engine.execute(d,p),inspectionId) }
            catch(e:CancellationException) { withContext(NonCancellable) { persist(d,engine.result(d,DiagnosticStatus.INCONCLUSIVE,emptyMap(),"Test interrupted; retry after resuming"),inspectionId) };throw e }
        }
    }
    fun runBatch(definitions:List<DiagnosticDefinition>,inspectionId:String?=selected.value?.id,onCompleted:(List<DiagnosticResult>)->Unit) {
        val p=profile()?:return
        val inspectionId=inspectionId?:return
        if(selected.value?.id!=inspectionId)return
        if(selected.value?.serverFinalized==true) { notice.value="This cloud report is finalized. Start a new inspection.";return }
        launchTest("Observing ${definitions.firstOrNull()?.category?:"phone"}") {
            try {
                val results=engine.executeBatch(ProbeBatch("workflow",definitions),p)
                persistResults(inspectionId,results);onCompleted(results)
            } catch(e:CancellationException) {
                withContext(NonCancellable) { persistResults(inspectionId,definitions.map { engine.result(it,DiagnosticStatus.INCONCLUSIVE,emptyMap(),"Observation interrupted; retry after resuming") }) }
                throw e
            }
        }
    }
    fun runAutomatic() {
        val p=profile()?:return
        val inspectionId=selected.value?.id?:return
        launchTest("Reading device info") { readAutomatic(p,inspectionId,definitions(),retryUnverified=true) }
    }
    private suspend fun readAutomatic(p:CapabilityProfile,inspectionId:String,definitions:List<DiagnosticDefinition>,retryUnverified:Boolean=false) {
        val recorded=DiagnosticAttempts.latest(db.dao().results(inspectionId).map { DiagnosticRegistry.json.decodeFromString<DiagnosticResult>(it.payload) })
        val retryable=setOf(DiagnosticStatus.FAIL,DiagnosticStatus.INCONCLUSIVE,DiagnosticStatus.NOT_ACCESSIBLE,DiagnosticStatus.SKIPPED_BY_USER)
        val defs=InspectionWorkflows.automatic(definitions).filter { (recorded[it.id]==null||(retryUnverified&&recorded[it.id]?.status in retryable))&&engine.canRun(it) }
        busy.value="Collecting device information and self-checks"
        for(batch in DiagnosticProbePlanner.batches(defs)) {
            persistResults(inspectionId,engine.executeBatch(batch,p));yield()
        }
    }
    private fun launchTest(label:String,block:suspend ()->Unit) {
        if(busy.value!=null)return
        busy.value=label;testRunning.value=true
        testJob=viewModelScope.launch {
            try { block() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { notice.value=e.message?:"Couldn't complete this check. Try again." }
            finally { busy.value=null;testRunning.value=false }
        }
    }
    fun stop() { testJob?.cancel() }
    fun sync() {
        if(busy.value!=null) { SyncWorker.enqueue(getApplication());return }
        action("Syncing results") {
            val outcome=withContext(Dispatchers.IO) { SyncWorker.synchronize(getApplication()) }
            syncOutcome.value=outcome
            notice.value=outcome.message?:"Synchronization checked."
        }
    }
    fun retrySync() {
        val id=selected.value?.id?:return
        action("Retrying uploads") {
            db.dao().retry(id)
            val outcome=withContext(Dispatchers.IO) { SyncWorker.synchronize(getApplication()) }
            syncOutcome.value=outcome;notice.value=outcome.message?:"Synchronization checked."
        }
    }
    fun saveLocalReport(id:String?=selected.value?.id,onSaved:(LocalReport)->Unit={}) {
        val inspectionId=id?:return
        action("Saving local report") {
            val item=requireNotNull(db.dao().inspection(inspectionId))
            check(canView(item)) { "Sign in to the original account and server to open this inspection." }
            val report=reports.save(inspectionId);notice.value="Report saved on this phone.";onSaved(report)
        }
    }
    fun action(label:String,block:suspend ()->Unit) {
        if(busy.value!=null)return
        busy.value=label
        viewModelScope.launch {
            try { block() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { notice.value=e.message?:"Couldn't finish. Your local data is saved." }
            finally { busy.value=null }
        }
    }
}
