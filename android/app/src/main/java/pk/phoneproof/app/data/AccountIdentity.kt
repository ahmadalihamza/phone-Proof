package pk.phoneproof.app.data

import org.json.JSONObject
import java.util.UUID

/** Identity comes from the authenticated server, never decoded JWT claims. */
data class AccountIdentity(val id:String,val email:String,val role:String,val emailVerified:Boolean,val origin:String) {
    fun toJson():JSONObject = JSONObject().put("id",id).put("email",email).put("role",role)
        .put("email_verified",emailVerified).put("origin",origin)
    companion object {
        fun fromServer(value:JSONObject,origin:String):AccountIdentity {
            val id=value.getString("id");UUID.fromString(id)
            val email=value.getString("email");val role=value.getString("role")
            require(email.isNotBlank()&&email.length<=254&&role.isNotBlank()&&role.length<=40) { "Unexpected account response. Try again." }
            return AccountIdentity(id,email,role,value.getBoolean("email_verified"),origin)
        }
        internal fun fromStored(value:JSONObject):AccountIdentity = fromServer(value,value.getString("origin"))
    }
}

enum class ConnectionFailure { NOT_CONFIGURED, DNS, TLS, TIMEOUT, UNREACHABLE, TRANSFER, INCOMPATIBLE }
class ApiConnectionException(val category:ConnectionFailure,message:String,cause:Throwable?=null):java.io.IOException(message,cause)
data class ConnectionCheck(val connected:Boolean,val message:String,val recoveryAvailable:Boolean?,val checkedAt:Long,val requestId:String?=null)
