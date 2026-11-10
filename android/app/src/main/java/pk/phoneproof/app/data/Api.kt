package pk.phoneproof.app.data

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import pk.phoneproof.app.BuildConfig

class ApiException(val status:Int,val detail:String,val requestId:String?=null):IOException(apiErrorMessage(status,detail))

class Api(val context:Context,private val requestScope:AuthenticatedRequestScope?=null) {
    private companion object {
        val refreshCoordinator=SessionRefreshCoordinator()
        val httpClient=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build()
        val authenticationClient=httpClient.newBuilder().callTimeout(20,TimeUnit.SECONDS).build()
        val probeClient=httpClient.newBuilder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).build()
        const val MAX_RESPONSE_BYTES=32*1024*1024
    }
    val vault=TokenVault(context)
    private val prefs=context.getSharedPreferences("connection",Context.MODE_PRIVATE)
    var baseUrl:String
        get()=prefs.getString("base",BuildConfig.API_BASE_URL).orEmpty()
        set(value) {
            val normalized=value.trim().trimEnd('/')+"/"
            val url=runCatching { normalized.toHttpUrl() }.getOrElse { throw IllegalArgumentException("Enter a valid server URL ending in /api/v1/.") }
            require(url.scheme=="https"||BuildConfig.DEBUG) { "Release builds require HTTPS" }
            require(url.username.isEmpty()&&url.password.isEmpty()&&url.query==null&&url.fragment==null&&url.encodedPath.endsWith("/api/v1/")) { "Use a server URL ending in /api/v1/." }
            check(vault.snapshot().tokens==null) { "Sign out before changing the server." }
            if(url.toString()!=baseUrl) {
                vault.clear()
                check(prefs.edit().putString("base",url.toString()).commit()) { "Connection couldn't be saved. Try again." }
            }
        }
    fun requireConfiguredOrigin():String {
        val value=baseUrl
        if(value.isBlank())throw ApiConnectionException(ConnectionFailure.NOT_CONFIGURED,"No server is configured. Open Connection to set up PhoneProof.")
        val url=runCatching { value.toHttpUrl() }.getOrNull()
        if(url==null||(!BuildConfig.DEBUG&&url.scheme!="https")||url.username.isNotEmpty()||url.password.isNotEmpty()||url.query!=null||url.fragment!=null||!url.encodedPath.endsWith("/api/v1/"))
            throw ApiConnectionException(ConnectionFailure.NOT_CONFIGURED,"Check the server URL in Connection. It must end in /api/v1/.")
        return value
    }
    private fun refresh(stale:SessionSnapshot,serverUrl:String):SessionTokens?=refreshCoordinator.refresh(stale,vault) { refreshToken ->
        val response=raw(serverUrl,"auth/refresh","POST",JSONObject().put("refresh_token",refreshToken).toString().toRequestBody("application/json".toMediaType()),null)
        if(response.code==401||response.code==403) { response.close();null }
        else {
            val tokens=JSONObject(consume(response).bytes.toString(Charsets.UTF_8))
            val access=tokens.getString("access_token");val refresh=tokens.getString("refresh_token")
            if(access.isBlank()||refresh.length !in 32..200)throw ApiConnectionException(ConnectionFailure.INCOMPATIBLE,"The server returned an invalid session response. Try signing in again.")
            SessionTokens(access,refresh,tokens.toString())
        }
    }
    private fun requestId(response:Response)=response.header("X-Request-ID")?.takeIf { it.length<=100&&it.matches(Regex("[A-Za-z0-9._-]+")) }
    private fun routeCategory(path:String)=when(path.substringBefore('/')) { "auth"->"auth/"+path.substringAfter('/').substringBefore('/');"inspections"->"inspections";"reports"->"reports";"images"->"images";else->"api" }
    private fun raw(serverUrl:String,path:String,method:String,body:RequestBody?,token:String?,client:OkHttpClient=httpClient):Response {
        val builder=Request.Builder().url(serverUrl+path).method(method,body)
        if(!token.isNullOrBlank())builder.header("Authorization","Bearer $token")
        try {
            return client.newCall(builder.build()).execute().also {
                Log.i("PhoneProofHttp","method=$method route=${routeCategory(path)} status=${it.code} request_id=${requestId(it)?:"unavailable"}")
            }
        } catch(e:IOException) {
            val failure=when(e) {
                is UnknownHostException -> ApiConnectionException(ConnectionFailure.DNS,"The server address wasn't found. Check Connection settings.",e)
                is SSLException -> ApiConnectionException(ConnectionFailure.TLS,"The server's secure connection couldn't be verified. Check the URL and device date.",e)
                is SocketTimeoutException -> ApiConnectionException(ConnectionFailure.TIMEOUT,"The server took too long to respond. Try again; your inspections are saved.",e)
                is ConnectException -> ApiConnectionException(ConnectionFailure.UNREACHABLE,"Can't reach the server. Check your connection or server settings.",e)
                else -> ApiConnectionException(ConnectionFailure.TRANSFER,"The connection was interrupted. Try again; your inspections are saved.",e)
            }
            Log.w("PhoneProofHttp","method=$method route=${routeCategory(path)} failure=${failure.category}")
            throw failure
        }
    }
    private data class Payload(val bytes:ByteArray,val requestId:String?)
    private fun readBounded(body:ResponseBody?,limit:Int,truncate:Boolean=false):ByteArray {
        if(body==null)return byteArrayOf()
        val output=ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer=ByteArray(8192)
            while(true) {
                val count=input.read(buffer,0,minOf(buffer.size,limit+1-output.size()))
                if(count<0)break
                output.write(buffer,0,count)
                if(output.size()>limit) {
                    if(truncate)return output.toByteArray().copyOf(limit)
                    throw ApiConnectionException(ConnectionFailure.TRANSFER,"The server response is too large to open safely.")
                }
            }
        }
        return output.toByteArray()
    }
    private fun redactedError(bytes:ByteArray):String {
        fun clean(value:Any?):Any? = when(value) {
            is JSONObject -> JSONObject().also { result -> value.keys().forEach { key ->
                if(key.lowercase() !in setOf("input","ctx","password","access_token","refresh_token"))result.put(key,clean(value.opt(key)))
            } }
            is JSONArray -> JSONArray().also { result -> for(i in 0 until value.length())result.put(clean(value.opt(i))) }
            else -> value
        }
        return runCatching { clean(JSONObject(bytes.toString(Charsets.UTF_8))).toString() }.getOrDefault("")
    }
    private fun consume(response:Response):Payload=response.use {
        val id=requestId(it)
        if(!it.isSuccessful)throw ApiException(it.code,redactedError(readBounded(it.body,8192,true)),id)
        Payload(readBounded(it.body,MAX_RESPONSE_BYTES),id)
    }
    private fun checkedSession(session:SessionSnapshot,serverUrl:String):SessionSnapshot {
        try { return requestScope?.requireSession(session,serverUrl)?:session }
        catch(failure:SessionScopeChangedException) { throw ApiException(401,failure.message!!) }
    }
    fun requireCurrentSession() {
        if(requestScope!=null)checkedSession(vault.snapshot(),baseUrl)
    }
    fun bytes(path:String,method:String="GET",body:RequestBody?=null,authenticated:Boolean=true):ByteArray {
        val currentOrigin=requireConfiguredOrigin()
        val serverUrl=requestScope?.origin?:currentOrigin
        val session=if(authenticated)checkedSession(vault.snapshot(),currentOrigin) else null
        val token=session?.tokens?.accessToken
        if(authenticated&&token==null)throw ApiException(401,"Authentication required")
        var response=raw(serverUrl,path,method,body,token)
        if(response.code==401&&authenticated&&session!=null) {
            response.close();requireCurrentSession()
            val refreshed=refresh(session,serverUrl)
            val current=checkedSession(vault.snapshot(),baseUrl)
            if(refreshed==null||current.generation!=session.generation)throw ApiException(401,"Sign in to synchronize this inspection")
            response=raw(serverUrl,path,method,body,refreshed.accessToken)
        }
        return consume(response).bytes
    }
    fun call(path:String,method:String="GET",json:JSONObject?=null,authenticated:Boolean=true):String = bytes(path,method,if(method=="GET")null else (json?:JSONObject()).toString().toRequestBody("application/json".toMediaType()),authenticated).toString(Charsets.UTF_8)
    internal fun publicJsonAt(origin:String,path:String,body:JSONObject):JSONObject {
        if(requireConfiguredOrigin()!=origin)throw SessionScopeChangedException()
        return JSONObject(consume(raw(origin,path,"POST",body.toString().toRequestBody("application/json".toMediaType()),null,authenticationClient)).bytes.toString(Charsets.UTF_8))
    }
    internal fun identityWithToken(origin:String,accessToken:String):AccountIdentity {
        val payload=consume(raw(origin,"auth/me","GET",null,accessToken,authenticationClient))
        return try { AccountIdentity.fromServer(JSONObject(payload.bytes.toString(Charsets.UTF_8)),origin) }
        catch(e:Exception) { throw ApiConnectionException(ConnectionFailure.INCOMPATIBLE,"The server returned an invalid account response. Check Connection settings.",e) }
    }
    fun refreshIdentity():AccountIdentity {
        val snapshot=vault.snapshot();val origin=requireConfiguredOrigin()
        val identity=AccountIdentity.fromServer(JSONObject(call("auth/me")),origin)
        val old=vault.identity(origin)
        if(old!=null&&old.id!=identity.id) {
            vault.replaceIfCurrent(vault.snapshot(),null)
            throw ApiException(401,"Authentication required")
        }
        if(!vault.updateIdentity(snapshot.generation,identity))throw SessionScopeChangedException()
        return identity
    }
    fun probeConnection():ConnectionCheck {
        var id:String?=null
        return try {
            val origin=requireConfiguredOrigin()
            val payload=consume(raw(origin,"health","GET",null,null,probeClient));id=payload.requestId
            val value=JSONObject(payload.bytes.toString(Charsets.UTF_8))
            if(value.optString("status")!="ok"||value.optString("database")!="reachable")
                throw ApiConnectionException(ConnectionFailure.INCOMPATIBLE,"This address didn't return a compatible PhoneProof health response.")
            val available=runCatching {
                val capabilities=consume(raw(origin,"auth/capabilities","GET",null,null,probeClient))
                JSONObject(capabilities.bytes.toString(Charsets.UTF_8)).getBoolean("email_recovery_available")
            }.getOrNull()
            ConnectionCheck(true,"Connected",available,System.currentTimeMillis(),id)
        } catch(e:Exception) {
            ConnectionCheck(false,e.message?:"Can't check the connection.",null,System.currentTimeMillis(),(e as? ApiException)?.requestId?:id)
        }
    }
    fun upload(inspectionId:String,file:File,view:String,scannerId:String,nonce:String,capturedAt:String):String {
        val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("view",view).addFormDataPart("scanner_device_id",scannerId).addFormDataPart("capture_nonce",nonce).addFormDataPart("captured_at",capturedAt).addFormDataPart("file",file.name,file.asRequestBody("image/jpeg".toMediaType())).build()
        return bytes("inspections/$inspectionId/images","POST",body).toString(Charsets.UTF_8)
    }
}
