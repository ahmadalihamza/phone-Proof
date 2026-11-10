package pk.phoneproof.app

import org.junit.Assert.*
import org.junit.Test
import pk.phoneproof.app.data.*

class SyncOutcomeTest {
    private fun outcome(state:SyncState=SyncState.SYNCED,pending:Int=0,rejected:Int=0,photos:Int=0,error:String?=null)=SyncOutcome(
        state,"account","https://example.invalid/api/v1/",mapOf("inspection" to InspectionSyncSummary("inspection",true,false,pending,rejected,photos,0,error)))
    @Test fun acknowledgedResultsPermitNextStep() { outcome().requireInspectionReady("inspection") }
    @Test fun pendingOrRejectedRowsDoNotPretendToBeSynced() {
        for(item in listOf(outcome(pending=1),outcome(rejected=1),outcome(error="Needs retry"))) {
            assertThrows(IllegalStateException::class.java) { item.requireInspectionReady("inspection") }
        }
    }
    @Test fun signedOutOfflineAndUnknownInspectionCannotProceed() {
        for(state in listOf(SyncState.LOCAL_ONLY,SyncState.SIGN_IN_REQUIRED,SyncState.OFFLINE))
            assertThrows(IllegalStateException::class.java) { outcome(state).requireInspectionReady("inspection") }
        assertThrows(IllegalStateException::class.java) { outcome().requireInspectionReady("other") }
    }
    @Test fun photoAcknowledgementsAreRequiredForPhotoDependentActions() {
        val value=outcome(photos=1)
        value.requireInspectionReady("inspection")
        assertThrows(IllegalStateException::class.java) { value.requireInspectionReady("inspection",photos=true) }
    }
    @Test fun anotherInspectionFailureDoesNotBlockAnAcknowledgedInspection() {
        outcome(SyncState.NEEDS_ATTENTION).requireInspectionReady("inspection")
    }
}
