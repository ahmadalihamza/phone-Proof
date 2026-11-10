package pk.phoneproof.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import pk.phoneproof.app.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens and server-verified identity share one encrypted, origin-bound envelope. */
class TokenVault(context:Context):SessionStore {
    private companion object { val lock=Any() }
    private val prefs=context.getSharedPreferences("auth",Context.MODE_PRIVATE)
    private val connection=context.getSharedPreferences("connection",Context.MODE_PRIVATE)
    private val alias="phoneproof-auth-v1"
    private fun configuredOrigin()=connection.getString("base",BuildConfig.API_BASE_URL).orEmpty()
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        return (store.getKey(alias,null) as? SecretKey)?:KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    private fun envelopeLocked():JSONObject? {
        val value=prefs.getString("tokens",null)?:return null
        val envelope=runCatching {
            val bytes=Base64.decode(value,Base64.NO_WRAP);require(bytes.size>28)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            val decoded=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
            require(decoded.optInt("version")==2)
            val origin=decoded.getString("origin");require(origin.isNotBlank()&&origin==configuredOrigin())
            val identity=AccountIdentity.fromStored(decoded.getJSONObject("identity"));require(identity.origin==origin)
            val tokens=decoded.getJSONObject("tokens")
            require(tokens.getString("access_token").isNotBlank()&&tokens.getString("refresh_token").length in 32..200)
            decoded
        }.getOrNull()
        if(envelope==null)clearLocked("Sign in again to reconnect this device. Your inspections are still saved.")
        return envelope
    }
    fun read():JSONObject?=synchronized(lock) { envelopeLocked()?.getJSONObject("tokens") }
    fun identity(origin:String):AccountIdentity?=synchronized(lock) {
        envelopeLocked()?.takeIf { it.getString("origin")==origin }?.let { AccountIdentity.fromStored(it.getJSONObject("identity")) }
    }
    fun authenticationMessage(origin:String):String?=synchronized(lock) {
        envelopeLocked()?.takeIf { it.getString("origin")==origin }?.let {
            if(it.optString("action")=="created")"Account created. You're signed in." else "You're signed in."
        }
    }
    fun sessionIssue():String?=synchronized(lock) { prefs.getString("session_issue",null) }
    private fun writeLocked(envelope:JSONObject,generation:Long) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val value=cipher.iv+cipher.doFinal(envelope.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("tokens",Base64.encodeToString(value,Base64.NO_WRAP))
            .putLong("session_generation",generation).remove("session_issue").commit()) { "Sign-in couldn't be saved. Try again." }
    }
    fun saveVerified(expected:SessionSnapshot,tokens:SessionTokens,identity:AccountIdentity,action:String):Boolean=synchronized(lock) {
        if(snapshot()!=expected||configuredOrigin()!=identity.origin)return@synchronized false
        val envelope=JSONObject().put("version",2).put("origin",identity.origin).put("tokens",JSONObject(tokens.serialized))
            .put("identity",identity.toJson()).put("action",action)
        writeLocked(envelope,expected.generation+1);true
    }
    fun updateIdentity(expectedGeneration:Long,identity:AccountIdentity):Boolean=synchronized(lock) {
        val envelope=envelopeLocked()?:return@synchronized false
        if(prefs.getLong("session_generation",0)!=expectedGeneration||envelope.getString("origin")!=identity.origin)return@synchronized false
        writeLocked(envelope.put("identity",identity.toJson()),expectedGeneration);true
    }
    private fun clearLocked(issue:String?=null) {
        val editor=prefs.edit().remove("tokens").putLong("session_generation",prefs.getLong("session_generation",0)+1)
        if(issue==null)editor.remove("session_issue") else editor.putString("session_issue",issue)
        check(editor.commit())
    }
    fun clear()=synchronized(lock) { clearLocked() }
    override fun snapshot():SessionSnapshot=synchronized(lock) {
        val tokens=envelopeLocked()?.getJSONObject("tokens")?.let { SessionTokens(it.getString("access_token"),it.getString("refresh_token"),it.toString()) }
        SessionSnapshot(prefs.getLong("session_generation",0),tokens)
    }
    override fun replaceIfCurrent(expected:SessionSnapshot,replacement:SessionTokens?):Boolean=synchronized(lock) {
        if(snapshot()!=expected)return@synchronized false
        if(replacement==null)clearLocked("Your session expired. Sign in again; your inspections are saved.")
        else {
            val envelope=envelopeLocked()?:return@synchronized false
            writeLocked(envelope.put("tokens",JSONObject(replacement.serialized)),expected.generation)
        }
        true
    }
}
