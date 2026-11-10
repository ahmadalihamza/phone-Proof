package pk.phoneproof.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pk.phoneproof.app.data.ScannerDraftStore
import pk.phoneproof.app.data.ScannerPairing
import java.util.UUID

class ScannerDraftStoreTest {
    @Test fun pairingAndReviewRecoveryAreIsolatedByServerAndOwner() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val drafts=ScannerDraftStore(context)
        val owner="test-"+UUID.randomUUID()
        val origin="https://one.example/api/"
        val inspection=UUID.randomUUID().toString()
        val scanner=drafts.scannerId(origin,owner)
        val pairing=ScannerPairing(origin,owner,inspection,"private-nonce",scanner)
        val key=drafts.key(origin,owner,inspection)
        try {
            drafts.savePairing(pairing)
            drafts.save(key,JSONObject().put("view","back"))
            val reopened=ScannerDraftStore(context)
            assertEquals(pairing,reopened.pairing(origin,owner))
            assertEquals("back",reopened.read(key)?.getString("view"))
            assertEquals(scanner,reopened.scannerId(origin,owner))
            assertNull(reopened.pairing(origin,"another-$owner"))
            assertNull(reopened.pairing("https://two.example/api/",owner))
            assertNull(reopened.read(reopened.key(origin,"another-$owner",inspection)))
            assertNull(reopened.read(reopened.key("https://two.example/api/",owner,inspection)))
        } finally {
            drafts.forgetPairing(origin,owner)
            context.getSharedPreferences("scanner_review_drafts",0).edit().remove(key).apply()
        }
    }
}
