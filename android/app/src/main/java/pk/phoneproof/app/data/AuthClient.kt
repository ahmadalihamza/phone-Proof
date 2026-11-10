package pk.phoneproof.app.data

import android.content.Context
import org.json.JSONObject

/** Real HTTP credentials are committed only after the candidate token passes /auth/me. */
class AuthClient(context:Context) {
    private val api=Api(context)
    fun register(email:String,password:String):AccountIdentity = authenticate("register",email,password,null)
    fun login(email:String,password:String,otp:String?=null):AccountIdentity = authenticate("login",email,password,otp)
    private fun authenticate(action:String,email:String,password:String,otp:String?):AccountIdentity {
        val origin=api.requireConfiguredOrigin();val initial=api.vault.snapshot()
        check(initial.tokens==null) { "Sign out before signing in to another account." }
        val body=JSONObject().put("email",email.trim()).put("password",password)
        if(!otp.isNullOrBlank())body.put("otp",otp)
        val value=api.publicJsonAt(origin,"auth/$action",body)
        val access=value.optString("access_token");val refresh=value.optString("refresh_token")
        if(access.isBlank()||refresh.length !in 32..200||!value.optString("token_type").equals("bearer",true)||value.optInt("expires_in",0)<=0)
            throw ApiConnectionException(ConnectionFailure.INCOMPATIBLE,"The server returned an invalid sign-in response. Check Connection settings.")
        val identity=api.identityWithToken(origin,access)
        if(api.baseUrl!=origin||!api.vault.saveVerified(initial,SessionTokens(access,refresh,value.toString()),identity,if(action=="register")"created" else "signed_in"))
            throw SessionScopeChangedException()
        SyncWorker.enqueue(api.context)
        return identity
    }
}
