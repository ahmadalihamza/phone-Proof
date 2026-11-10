package pk.phoneproof.app.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class SyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        if(!PhoneProofDatabase.get(applicationContext).dao().hasPendingSync())return@withContext Result.success()
        val outcome=synchronize(applicationContext)
        when(outcome.state) {
            SyncState.OFFLINE -> Result.retry()
            SyncState.SIGN_IN_REQUIRED -> Result.failure(workDataOf("reason" to "Sign in again"))
            else -> Result.success()
        }
    }
    companion object {
        private val synchronizationMutex=Mutex()
        private fun constraints()=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun initialize(context:Context) {
            val recovery=PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("inspection-sync-recovery",ExistingPeriodicWorkPolicy.KEEP,recovery)
            enqueue(context)
        }
        fun enqueue(context:Context) {
            val request=OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("inspection-sync",ExistingWorkPolicy.KEEP,request)
        }
        suspend fun synchronize(context:Context):SyncOutcome=synchronizationMutex.withLock { withContext(Dispatchers.IO) {
            val connection=Api(context);val origin=connection.baseUrl
            val db=PhoneProofDatabase.get(context);val dao=db.dao()
            val initial=connection.vault.snapshot()
            var identity=connection.vault.identity(origin)
            var state=SyncState.SYNCED;var error:String?=null;var requestId:String?=null
            var resultAcks=0;var photoAcks=0
            fun belongs(item:LocalInspection)=if(identity==null)item.ownerId==null&&item.serverUrl==null else
                (item.ownerId==null&&item.serverUrl==null)||(item.ownerId==identity!!.id&&item.serverUrl==origin)
            suspend fun outcome():SyncOutcome {
                val captures=dao.captures()
                val summaries=dao.inspections().filter(::belongs).associate { item ->
                    val rows=dao.results(item.id).filter { !it.synced }
                    val photos=captures.filter { it.inspectionId==item.id&&!it.synced&&it.serverUrl==origin&&it.ownerId==identity?.id }
                    item.id to InspectionSyncSummary(item.id,item.serverCreated,item.serverFinalized,
                        rows.count { it.syncError==null },rows.count { it.syncError!=null },
                        photos.count { it.error==null },photos.count { it.error!=null },item.syncError)
                }.toMutableMap()
                // The companion scanner can queue photos for an inspection created
                // on the inspected phone. It has no LocalInspection row of its own.
                captures.filter { it.serverUrl==origin&&it.ownerId==identity?.id }.groupBy { it.inspectionId }.forEach { (id,photos) ->
                    if(id !in summaries)summaries[id]=InspectionSyncSummary(id,photos.any { it.synced },false,0,0,
                        photos.count { !it.synced&&it.error==null },photos.count { !it.synced&&it.error!=null },
                        photos.firstOrNull { !it.synced&&it.error!=null }?.error)
                }
                if(state==SyncState.SYNCED&&summaries.values.any { !it.serverCreated||it.hasPending||it.hasRejected||it.errorMessage!=null })state=SyncState.NEEDS_ATTENTION
                val message=error?:when(state) {
                    SyncState.LOCAL_ONLY -> "Saved on this phone. Sign in to sync."
                    SyncState.SIGN_IN_REQUIRED -> "Sign in again to sync. Your inspections are saved on this phone."
                    SyncState.OFFLINE -> "Couldn't reach the server. Your inspections are saved on this phone."
                    SyncState.NEEDS_ATTENTION -> "Some items need attention. Your local results are saved."
                    SyncState.SYNCED -> "All results acknowledged by the server."
                }
                return SyncOutcome(state,identity?.id,origin,summaries,resultAcks,photoAcks,message,requestId)
            }
            if(initial.tokens==null||identity==null) {
                state=if(connection.vault.sessionIssue()!=null)SyncState.SIGN_IN_REQUIRED else SyncState.LOCAL_ONLY
                return@withContext outcome()
            }
            val scope=AuthenticatedRequestScope(initial.generation,origin);val api=Api(context,scope)
            try {
                val verified=api.refreshIdentity()
                check(verified.id==identity!!.id) { "Account changed. Sign in again; your inspections are saved." }
                identity=verified
                for(original in dao.inspections().filter(::belongs)) {
                    api.requireCurrentSession()
                    var item=original
                    try {
                        if(item.ownerId==null) { item=item.copy(ownerId=verified.id,serverUrl=origin);dao.update(item) }
                        if(!item.serverCreated) {
                            val profile=JSONObject(item.profile)
                            val created=JSONObject(api.call("inspections","POST",JSONObject().put("id",item.id).put("device_profile",profile.getJSONObject("device"))))
                            val bound=JSONObject(api.call("inspections/${item.id}/capabilities","POST",profile))
                            item=item.copy(serverCreated=true,scoreRuleVersion=bound.optInt("score_rule_version",created.optInt("score_rule_version",1)))
                            dao.update(item)
                        }
                        val remote=JSONObject(api.call("inspections/${item.id}"))
                        if(remote.optString("status")=="FINALIZED") {
                            item=item.copy(serverFinalized=true);dao.update(item)
                            val cloud=JSONArray(api.call("reports"))
                            for(index in 0 until cloud.length()) {
                                val report=cloud.getJSONObject(index)
                                if(report.optString("inspection_id")==item.id) {
                                    dao.markFinalized(item.id,report.getString("id"),report.getString("verification_url"))
                                    LocalReportRepository(context,db).cacheCloud(item.id,report.toString());break
                                }
                            }
                        }
                        while(true) {
                            val pending=dao.pending(item.id);if(pending.isEmpty())break
                            val array=JSONArray();pending.forEach { array.put(JSONObject(it.payload).put("id",it.id)) }
                            try {
                                val response=JSONObject(api.call("inspections/${item.id}/results/batch","POST",JSONObject().put("results",array)))
                                val accepted=response.getJSONArray("accepted_ids");val ids=(0 until accepted.length()).map { accepted.getString(it) }
                                check(ids.size==pending.size&&ids.toSet()==pending.map { it.id }.toSet()) { "Server acknowledgement was incomplete. Results remain saved for retry." }
                                api.requireCurrentSession();dao.acknowledge(ids);resultAcks+=ids.size
                            } catch(e:ApiException) {
                                if(e.status in listOf(400,403,404,409,422)) { dao.reject(pending.map { it.id },e.message?:"Server rejected these results.");break } else throw e
                            }
                        }
                        val current=dao.inspection(item.id)?:item
                        if(current.syncError!=null)dao.update(current.copy(syncError=null))
                    } catch(e:CancellationException) { throw e }
                    catch(e:ApiException) {
                        if(e.status==401||e.status>=500||e.status==429)throw e
                        dao.inspection(item.id)?.let { dao.update(it.copy(syncError=e.message)) }
                        state=SyncState.NEEDS_ATTENTION;requestId=e.requestId
                    } catch(e:IllegalStateException) {
                        dao.inspection(item.id)?.let { dao.update(it.copy(syncError=e.message)) }
                        state=SyncState.NEEDS_ATTENTION
                    }
                }
                for(capture in dao.pendingCaptures(verified.id)) {
                    api.requireCurrentSession();if(capture.serverUrl!=origin)continue
                    try {
                        val file=java.io.File(capture.path)
                        check(file.isFile) { "The saved photo file is missing. Retake this view." }
                        val response=JSONObject(api.upload(capture.inspectionId,file,capture.view,capture.scannerId,capture.nonce,capture.capturedAt))
                        check(response.optString("id").isNotBlank()) { "The server didn't acknowledge the photo. It remains saved for retry." }
                        api.requireCurrentSession();dao.captureStatus(capture.id,true,null);photoAcks++
                        file.delete()
                    } catch(e:ApiException) {
                        if(e.status in listOf(400,403,404,409,422))dao.captureStatus(capture.id,false,e.message) else throw e
                    } catch(e:IllegalStateException) { dao.captureStatus(capture.id,false,e.message) }
                }
            } catch(e:CancellationException) { throw e }
            catch(e:ApiException) {
                state=if(e.status==401)SyncState.SIGN_IN_REQUIRED else if(e.status>=500||e.status==429)SyncState.OFFLINE else SyncState.NEEDS_ATTENTION
                error=e.message;requestId=e.requestId
            } catch(e:java.io.IOException) { state=SyncState.OFFLINE;error=e.message }
            catch(e:Exception) { state=SyncState.NEEDS_ATTENTION;error=e.message?:"Sync couldn't finish. Your local data is saved." }
            outcome()
        } }
    }
}
