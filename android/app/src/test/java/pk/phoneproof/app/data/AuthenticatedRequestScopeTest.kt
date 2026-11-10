package pk.phoneproof.app.data

import org.junit.Assert.*
import org.junit.Test

class AuthenticatedRequestScopeTest {
    private val origin="https://first.example/api/v1/"
    private val original=SessionSnapshot(4,SessionTokens("first-access","first-refresh","first-session"))
    private val scope=AuthenticatedRequestScope(original.generation,origin)

    private fun assertChanged(current:SessionSnapshot,currentOrigin:String=origin) {
        try { scope.requireSession(current,currentOrigin);fail("A changed workflow session must be rejected") }
        catch(_:SessionScopeChangedException) { }
    }

    @Test fun aTokenRotationKeepsTheSameWorkflow() {
        val refreshed=SessionSnapshot(original.generation,SessionTokens("rotated-access","rotated-refresh","rotated-session"))
        assertEquals(refreshed,scope.requireSession(refreshed,origin))
    }

    @Test fun switchingAccountsCannotUploadAnEarlierUsersInspection() {
        val sent=mutableListOf<Pair<String,String>>()
        fun request(current:SessionSnapshot,path:String) {
            val authorized=scope.requireSession(current,origin)
            sent.add(path to authorized.tokens!!.accessToken)
        }
        request(original,"auth/me")
        val otherUser=SessionSnapshot(original.generation+1,SessionTokens("other-access","other-refresh","other-session"))
        try { request(otherUser,"inspections");fail("Must stop before creating the inspection under another account") }
        catch(_:SessionScopeChangedException) { }
        assertEquals(listOf("auth/me" to "first-access"),sent)
    }

    @Test fun logoutStopsTheWorkflow() { assertChanged(SessionSnapshot(original.generation+1,null)) }
    @Test fun signingBackIntoTheSameAccountStartsANewWorkflow() { assertChanged(original.copy(generation=original.generation+1)) }
    @Test fun changingServersStopsTheWorkflowEvenWithIdenticalCredentials() { assertChanged(original,"https://second.example/api/v1/") }
    @Test fun missingTokensCannotContinueTheWorkflow() { assertChanged(SessionSnapshot(original.generation,null)) }

    @Test fun alreadyCapturedCredentialsKeepTheirOriginalAccountAndOrigin() {
        val captured=scope.requireSession(original,origin)
        assertChanged(SessionSnapshot(original.generation+1,SessionTokens("new-access","new-refresh","new-session")))
        assertEquals("first-access",captured.tokens!!.accessToken)
        assertEquals(origin,scope.origin)
    }
}
