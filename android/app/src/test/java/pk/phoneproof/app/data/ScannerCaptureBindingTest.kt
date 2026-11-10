package pk.phoneproof.app.data

import org.junit.Assert.*
import org.junit.Test

class ScannerCaptureBindingTest {
    private val binding=ScannerCaptureBinding(7,"https://one/api/","alice","inspection-a","nonce-a","scanner-a","back")
    private fun session(generation:Long=7,signedIn:Boolean=true)=SessionSnapshot(generation,
        if(signedIn)SessionTokens("rotated-access","rotated-refresh","{}") else null)

    @Test fun tokenRotationAllowsOriginalCapture() {
        assertTrue(binding.isCurrent(session(),"https://one/api/","alice","inspection-a","nonce-a"))
        assertEquals("back",binding.view)
    }
    @Test fun loginLogoutServerOwnerAndPairingChangesRejectDelayedCapture() {
        assertFalse(binding.isCurrent(session(8),"https://one/api/","alice","inspection-a","nonce-a"))
        assertFalse(binding.isCurrent(session(signedIn=false),"https://one/api/","alice","inspection-a","nonce-a"))
        assertFalse(binding.isCurrent(session(),"https://two/api/","alice","inspection-a","nonce-a"))
        assertFalse(binding.isCurrent(session(),"https://one/api/","bob","inspection-a","nonce-a"))
        assertFalse(binding.isCurrent(session(),"https://one/api/","alice","inspection-b","nonce-a"))
        assertFalse(binding.isCurrent(session(),"https://one/api/","alice","inspection-a","nonce-b"))
    }
}
