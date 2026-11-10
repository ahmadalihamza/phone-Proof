package pk.phoneproof.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import pk.phoneproof.app.data.*
import pk.phoneproof.diagnostics.*
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Runs only against an explicitly supplied loopback backend in the isolated QA app. */
@RunWith(AndroidJUnit4::class)
class AuthBackendAcceptanceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()

    @Test fun realBackendJourney()=runBlocking {
        val configuredOrigin=args.getString("phoneproof_test_origin")
        assumeTrue("Use tools/verify_android_backend.py for the live backend test.",configuredOrigin!=null)
        check(context.packageName.endsWith(".verification")) { "Authentication acceptance requires the isolated verification application." }
        val origin=requireNotNull(configuredOrigin)
        val uri=URI(origin)
        check(uri.scheme=="http"&&uri.host=="127.0.0.1"&&uri.port in 1024..65535&&uri.path=="/api/v1/"&&uri.userInfo==null&&uri.query==null&&uri.fragment==null) { "Only an explicit loopback backend is allowed." }
        val prefs=context.getSharedPreferences("auth-backend-acceptance",Context.MODE_PRIVATE)
        val api=Api(context)
        if(args.getString("phoneproof_test_phase")=="restore") {
            assertEquals(origin,api.baseUrl)
            val expected=requireNotNull(prefs.getString("account_id",null)) { "Run journey before restore." }
            assertEquals(expected,api.vault.identity(origin)?.id)
            assertNotNull("Encrypted credentials must survive process restart",api.vault.snapshot().tokens)
            assertEquals(expected,api.refreshIdentity().id)
            val id=requireNotNull(prefs.getString("inspection_id",null))
            assertTrue(PhoneProofDatabase.get(context).dao().inspection(id)?.serverCreated==true)
            assertTrue(PhoneProofDatabase.get(context).dao().pending(id).isEmpty())
            SyncWorker.synchronize(context).requireInspectionReady(id)
            val refresh=requireNotNull(api.vault.snapshot().tokens).refreshToken
            api.call("auth/logout","POST",JSONObject().put("refresh_token",refresh))
            api.vault.clear()
            check(context.getSharedPreferences("connection",Context.MODE_PRIVATE).edit().remove("base").commit())
            assertNull(api.vault.snapshot().tokens)
            return@runBlocking
        }
        api.vault.clear();api.baseUrl=origin
        val connection=api.probeConnection()
        assertTrue("A real PhoneProof backend must answer health",connection.connected)
        val db=PhoneProofDatabase.get(context);val dao=db.dao()
        val profile=CapabilityScanner(context).scan()
        val definitions=DiagnosticRegistry.expanded(context,profile)
        val storage=definitions.single { it.id=="storage.checksum" }
        val observation=DiagnosticsEngine(context).execute(storage,profile)
        assertEquals("Actual app-private checksum observation must complete",DiagnosticStatus.PASS,observation.status)
        val id=UUID.randomUUID().toString();val resultId=UUID.randomUUID().toString()
        dao.create(LocalInspection(id,Instant.now().toString(),DiagnosticRegistry.json.encodeToString(profile),
            definitionSnapshot=DiagnosticRegistry.json.encodeToString(definitions)))
        dao.record(LocalResult(resultId,id,observation.checkId,observation.completedAt,DiagnosticRegistry.json.encodeToString(observation)))
        val guest=SyncWorker.synchronize(context)
        assertEquals(SyncState.LOCAL_ONLY,guest.state)
        assertEquals(1,dao.pending(id).size)
        assertFalse(requireNotNull(dao.inspection(id)).serverCreated)

        val email="qa-${UUID.randomUUID()}@example.com"
        val password="PhoneProof-QA-${UUID.randomUUID()}"
        val identity=AuthClient(context).register(email,password)
        assertEquals(email,identity.email)
        assertEquals(identity.id,api.vault.identity(origin)?.id)
        assertEquals("Account created. You're signed in.",api.vault.authenticationMessage(origin))
        api.vault.clear()
        try { AuthClient(context).register(email,password);fail("Duplicate registration must fail") }
        catch(e:ApiException) { assertEquals(409,e.status) }
        assertNull(api.vault.snapshot().tokens)
        try { AuthClient(context).login(email,"This-password-is-incorrect");fail("Incorrect credentials must fail") }
        catch(e:ApiException) { assertEquals(401,e.status) }
        assertNull(api.vault.snapshot().tokens)
        assertEquals(identity.id,AuthClient(context).login(email,password).id)

        // An expired/invalid access token must use the real rotating refresh endpoint.
        val before=api.vault.snapshot();val tokens=requireNotNull(before.tokens)
        val invalid=JSONObject(tokens.serialized).put("access_token","invalid-access-token")
        assertTrue(api.vault.replaceIfCurrent(before,SessionTokens("invalid-access-token",tokens.refreshToken,invalid.toString())))
        assertEquals(identity.id,api.refreshIdentity().id)
        assertNotEquals(tokens.refreshToken,api.vault.snapshot().tokens?.refreshToken)

        val synced=SyncWorker.synchronize(context)
        synced.requireInspectionReady(id)
        assertTrue(dao.pending(id).isEmpty())
        assertTrue(dao.results(id).single().synced)
        assertEquals(identity.id,dao.inspection(id)?.ownerId)
        assertEquals(origin,dao.inspection(id)?.serverUrl)
        val remote=JSONArray(api.call("inspections"))
        assertEquals(1,(0 until remote.length()).count { remote.getJSONObject(it).getString("id")==id })
        val rows=JSONArray(api.call("inspections/$id/results"))
        assertEquals(1,rows.length())
        assertEquals("storage.checksum",rows.getJSONObject(0).getString("check_id"))
        SyncWorker.synchronize(context).requireInspectionReady(id)
        assertEquals(1,JSONArray(api.call("inspections/$id/results")).length())
        assertTrue(prefs.edit().putString("account_id",identity.id).putString("inspection_id",id).commit())
        // Passwords and tokens are never written to the fixture preferences or test output.
    }
}
