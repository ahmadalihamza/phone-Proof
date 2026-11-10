package pk.phoneproof.app.data

enum class SyncState { LOCAL_ONLY, SIGN_IN_REQUIRED, OFFLINE, SYNCED, NEEDS_ATTENTION }

data class InspectionSyncSummary(
    val inspectionId:String,val serverCreated:Boolean,val serverFinalized:Boolean,
    val pendingResults:Int,val rejectedResults:Int,val pendingPhotos:Int,val rejectedPhotos:Int,
    val errorMessage:String?=null,
) {
    val hasPending get()=pendingResults>0||pendingPhotos>0
    val hasRejected get()=rejectedResults>0||rejectedPhotos>0
}

/** A successful HTTP call alone does not mean the local queue was acknowledged. */
data class SyncOutcome(
    val state:SyncState,val accountId:String?,val origin:String,
    val inspections:Map<String,InspectionSyncSummary>,val acknowledgedResults:Int=0,val acknowledgedPhotos:Int=0,
    val message:String?=null,val requestId:String?=null,
) {
    fun requireInspectionReady(id:String,photos:Boolean=false) {
        val item=inspections[id]
        check(state !in setOf(SyncState.LOCAL_ONLY,SyncState.SIGN_IN_REQUIRED,SyncState.OFFLINE)&&item!=null&&item.serverCreated&&
            item.pendingResults==0&&item.rejectedResults==0&&item.errorMessage==null&&
            (!photos||(item.pendingPhotos==0&&item.rejectedPhotos==0))) {
            item?.errorMessage?:message?:"This inspection hasn't finished syncing. Its local data is saved."
        }
    }
}
