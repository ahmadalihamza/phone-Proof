package pk.phoneproof.app.data

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class ScannerPairing(val origin:String,val ownerId:String,val inspectionId:String,val nonce:String,val scannerId:String)

/** App-private recovery state, isolated by backend, account and inspection. */
class ScannerDraftStore(context:Context) {
    private val preferences=context.applicationContext.getSharedPreferences("scanner_review_drafts",Context.MODE_PRIVATE)
    fun key(origin:String,owner:String,inspection:String):String =
        MessageDigest.getInstance("SHA-256").digest("$origin\n$owner\n$inspection".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun read(key:String):JSONObject? = preferences.getString(key,null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun save(key:String,body:JSONObject) {
        // apply updates memory immediately and serializes disk writes in order.
        // Android drains pending preference writes before a normal lifecycle stop.
        preferences.edit().putString(key,body.toString()).apply()
    }
    private fun ownerKey(origin:String,owner:String)=key(origin,owner,"active_scanner_pairing")
    fun scannerId(origin:String,owner:String):String {
        val key="device_"+ownerKey(origin,owner)
        return preferences.getString(key,null)?:UUID.randomUUID().toString().also { preferences.edit().putString(key,it).commit() }
    }
    fun pairing(origin:String,owner:String):ScannerPairing? {
        val value=read("pairing_"+ownerKey(origin,owner))?:return null
        return runCatching {
            require(value.getString("origin")==origin&&value.getString("owner")==owner)
            ScannerPairing(origin,owner,value.getString("inspection"),value.getString("nonce"),value.getString("scanner"))
                .also { require(it.inspectionId.isNotBlank()&&it.nonce.isNotBlank()&&it.scannerId.isNotBlank()) }
        }.getOrNull()
    }
    fun savePairing(pairing:ScannerPairing) {
        save("pairing_"+ownerKey(pairing.origin,pairing.ownerId),JSONObject().put("origin",pairing.origin).put("owner",pairing.ownerId)
            .put("inspection",pairing.inspectionId).put("nonce",pairing.nonce).put("scanner",pairing.scannerId))
    }
    fun forgetPairing(origin:String,owner:String) {
        preferences.edit().remove("pairing_"+ownerKey(origin,owner)).apply()
    }
}
