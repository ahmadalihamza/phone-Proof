package pk.phoneproof.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import pk.phoneproof.app.InspectionViewModel
import pk.phoneproof.app.BuildConfig
import pk.phoneproof.app.SelectionText
import pk.phoneproof.app.data.*

@Composable
fun AccountScreen(vm:InspectionViewModel) {
    val session=rememberScreenSession(vm)
    key(session) { AccountContent(vm,session) }
}

@Composable
private fun AccountContent(vm:InspectionViewModel,session:ScreenSession) {
    val context=LocalContext.current
    val api=remember(session) { session.api(context) }
    val busy by vm.busy.collectAsState()
    var email by remember { mutableStateOf("") }
    var accountEmail by remember { mutableStateOf(vm.api.vault.identity(session.origin)?.email) }
    var authError by remember { mutableStateOf<String?>(null) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var connectionCheck by remember { mutableStateOf<ConnectionCheck?>(null) }
    var checkingConnection by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var loginOtp by remember { mutableStateOf("") }
    var administratorSignIn by remember { mutableStateOf(false) }
    var registering by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(session.origin) }
    var sheet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        if(session.signedIn)try {
            val account=withContext(Dispatchers.IO) { api.refreshIdentity() }
            session.requireCurrent(vm);accountEmail=account.email
        } catch(e:CancellationException) { throw e } catch(e:Exception) { accountError=e.message?:"Couldn't refresh account status." }
    }
    fun authenticate() {
        val enteredEmail=email.trim();val enteredPassword=password;val enteredOtp=loginOtp
        val register=registering
        authError=null
        vm.action(if(register)"Creating account" else "Signing in") {
            try {
                session.requireCurrent(vm)
                withContext(Dispatchers.IO) {
                    val client=AuthClient(context)
                    if(register)client.register(enteredEmail,enteredPassword) else client.login(enteredEmail,enteredPassword,enteredOtp.takeIf { administratorSignIn })
                }
                password="";loginOtp=""
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                if(e is ApiException&&e.detail.contains("authenticator",ignoreCase=true))administratorSignIn=true
                authError=e.message?:"Couldn't sign in. Try again."
            }
        }
    }
    ScreenColumn {
        ScreenHeading("Account",if(session.signedIn)accountEmail?:"Signed in" else null)
        if(session.signedIn)vm.api.vault.authenticationMessage(session.origin)?.let { Text(it,color=MaterialTheme.colorScheme.primary) }
        accountError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(!session.signedIn)vm.api.vault.sessionIssue()?.let { Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(!session.signedIn) {
            QuietCard {
                Text(if(registering)"Create an account" else "Sign in",style=MaterialTheme.typography.titleLarge)
                authError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                OutlinedTextField(email,{email=it},enabled=busy==null,label={Text("Email")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(password,{password=it},enabled=busy==null,label={Text("Password")},supportingText={Text("12-128 characters")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
                if(administratorSignIn&&!registering)OutlinedTextField(loginOtp,{loginOtp=it.filter { char -> char in '0'..'9' }.take(6)},enabled=busy==null,label={Text("Authenticator code")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                Button(enabled=busy==null&&email.isNotBlank()&&password.length in 12..128&&(registering||!administratorSignIn||loginOtp.length==6),onClick={authenticate()},modifier=Modifier.fillMaxWidth()) { Text(if(registering)"Create account" else "Sign in") }
                TextButton(enabled=busy==null,onClick={registering=!registering;loginOtp="";administratorSignIn=false;authError=null},modifier=Modifier.fillMaxWidth()) { Text(if(registering)"Already have an account? Sign in" else "Create an account") }
            }
            QuietCard {
                DetailRow("Forgot password?",enabled=busy==null) { sheet="recovery" }
            }
        } else {
            QuietCard {
                DetailRow("Billing & plans","Report access and payment history",busy==null) { sheet="billing" }
                HorizontalDivider()
                DetailRow("Email verification","Manage account verification",busy==null) { sheet="security" }
            }
            TextButton(enabled=busy==null,onClick={vm.action("Signing out") {
                val token=vm.api.vault.read()?.optString("refresh_token")
                try { if(token!=null)withContext(Dispatchers.IO) { api.call("auth/logout","POST",JSONObject().put("refresh_token",token)) } }
                finally { if(vm.api.vault.snapshot().generation==session.generation)vm.api.vault.clear() }
            }}) { Text("Sign out") }
        }
        QuietCard { DetailRow("Connection",Uri.parse(session.origin).host?:"Set up server") { sheet="connection" } }
    }
    when(sheet) {
        "billing" -> DetailSheet("Billing & plans",{sheet=null}) { BillingPanel(vm) }
        "security" -> DetailSheet("Account verification",{sheet=null}) { AccountSecurity(vm,true,session.origin,email,onPasswordReset={password="";loginOtp=""}) }
        "recovery" -> DetailSheet("Password recovery",{sheet=null}) { AccountSecurity(vm,false,session.origin,email,onPasswordReset={password="";loginOtp=""}) }
        "connection" -> DetailSheet("Connection",{sheet=null}) {
            OutlinedTextField(endpoint,{endpoint=it},enabled=busy==null&&!session.signedIn,label={Text("Server URL")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text(if(session.signedIn)"Sign out to change the server." else "Use an API address ending in /api/v1/.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled=busy==null&&!session.signedIn,onClick={
                runCatching { vm.api.baseUrl=endpoint }.onSuccess { password="";loginOtp="";sheet=null;vm.notice.value="Connection saved." }.onFailure { vm.notice.value=it.message }
            },modifier=Modifier.fillMaxWidth()) { Text("Save connection") }
            OutlinedButton(enabled=busy==null&&!checkingConnection,onClick={
                checkingConnection=true
                scope.launch {
                    try { connectionCheck=withContext(Dispatchers.IO) { api.probeConnection() } }
                    finally { checkingConnection=false }
                }
            },modifier=Modifier.fillMaxWidth()) { Text(if(checkingConnection)"Checking…" else "Check saved connection") }
            connectionCheck?.let { check ->
                Text(check.message,color=if(check.connected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if(check.connected&&check.recoveryAvailable==false)Text("Password recovery email is unavailable.",style=MaterialTheme.typography.bodySmall)
                check.requestId?.let { Text("Reference: $it",style=MaterialTheme.typography.bodySmall) }
            }
            Text(if(BuildConfig.DEBUG)"Local HTTP is available in this debug build." else "HTTPS is required.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Security codes stay in memory only and are discarded when the account or server changes. */
@Composable
private fun AccountSecurity(vm:InspectionViewModel,signedIn:Boolean,serverUrl:String,email:String,onPasswordReset:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val session=rememberScreenSession(vm)
    val api=remember(session) { session.api(context) }
    val busy by vm.busy.collectAsState()
    var capabilities by remember(serverUrl,signedIn) { mutableStateOf<JSONObject?>(null) }
    var account by remember(serverUrl,signedIn) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(serverUrl,signedIn) { mutableStateOf(true) }
    var serviceError by remember(serverUrl,signedIn) { mutableStateOf<String?>(null) }
    var accountError by remember(serverUrl,signedIn) { mutableStateOf<String?>(null) }
    var reload by remember(serverUrl,signedIn) { mutableIntStateOf(0) }
    var recoveryOpen by remember(serverUrl,signedIn) { mutableStateOf(!signedIn) }
    var recoveryEmail by remember(serverUrl,signedIn) { mutableStateOf(email) }
    var token by remember(serverUrl,signedIn) { mutableStateOf("") }
    var newPassword by remember(serverUrl,signedIn) { mutableStateOf("") }
    var confirmation by remember(serverUrl,signedIn) { mutableStateOf("") }
    var otp by remember(serverUrl,signedIn) { mutableStateOf("") }
    LaunchedEffect(serverUrl,signedIn,reload) {
        loading=true;serviceError=null;accountError=null
        try {
            capabilities=withContext(Dispatchers.IO) { JSONObject(api.call("auth/capabilities",authenticated=false)) }
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { capabilities=null;serviceError="Email service availability could not be checked. Reconnect and refresh to request a new code." }
        if(signedIn) {
            try { account=withContext(Dispatchers.IO) { JSONObject(api.call("auth/me")) } }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { account=null;accountError="Account verification status could not be loaded. Reconnect or sign in again." }
        }
        loading=false
    }
    fun clearCodes() { token="";newPassword="";confirmation="";otp="" }
    if(!signedIn) {
        if(recoveryOpen)OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Recover your account",style=MaterialTheme.typography.titleMedium)
                Text("Codes last 30 minutes. Resend after one minute.",style=MaterialTheme.typography.bodySmall)
                if(loading)Text("Checking email service...",style=MaterialTheme.typography.bodySmall)
                serviceError?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                if(capabilities?.optBoolean("email_recovery_available")==false)Text("Email unavailable. An existing code can still be used.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(recoveryEmail,{recoveryEmail=it},enabled=busy==null,label={Text("Account email")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())
                OutlinedButton(enabled=busy==null&&!loading&&capabilities?.optBoolean("email_recovery_available")==true&&recoveryEmail.isNotBlank(),onClick={
                    vm.action("Requesting recovery email") {
                        val response=withContext(Dispatchers.IO) { JSONObject(api.call("auth/recovery/request","POST",JSONObject().put("email",recoveryEmail.trim()),authenticated=false)) }
                        token="";otp=""
                        vm.notice.value=response.getString("message")
                    }
                }) { Text("Request recovery email") }
                OutlinedTextField(token,{token=it.trim()},enabled=busy==null,label={Text("Code from recovery email")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(newPassword,{newPassword=it},enabled=busy==null,label={Text("New password (12-128 characters)")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(confirmation,{confirmation=it},enabled=busy==null,label={Text("Confirm new password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),isError=confirmation.isNotEmpty()&&confirmation!=newPassword,modifier=Modifier.fillMaxWidth())
                if(confirmation.isNotEmpty()&&confirmation!=newPassword)Text("Passwords do not match.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(otp,{otp=it.filter { char -> char in '0'..'9' }.take(6)},enabled=busy==null,label={Text("Administrator authenticator code (if applicable)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                Text("All devices will need to sign in again.",style=MaterialTheme.typography.bodySmall)
                Button(enabled=busy==null&&token.length in 40..100&&newPassword.length in 12..128&&newPassword==confirmation&&(otp.isEmpty()||otp.length==6),onClick={
                    vm.action("Updating password") {
                        val body=JSONObject().put("token",token).put("password",newPassword)
                        if(otp.isNotEmpty())body.put("otp",otp)
                        val response=withContext(Dispatchers.IO) { JSONObject(api.call("auth/recovery/complete","POST",body,authenticated=false)) }
                        clearCodes();recoveryOpen=false;onPasswordReset();vm.notice.value=response.getString("message")
                    }
                }) { Text("Reset password") }
                TextButton(enabled=busy==null&&!loading,onClick={reload++}) { Text("Refresh email availability") }
            }
        }
    } else OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Email verification",style=MaterialTheme.typography.titleMedium)
            account?.optString("email")?.takeIf { it.isNotBlank() }?.let { Text(it) }
            if(loading)Text("Loading verification status...",style=MaterialTheme.typography.bodySmall)
            accountError?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            val verified=account?.takeIf { it.has("email_verified") }?.getBoolean("email_verified")
            if(verified==true)Text("Email verified")
            else if(verified==false) {
                Text("Email not yet verified",style=MaterialTheme.typography.bodyMedium)
                Text("Email codes expire in 30 minutes. Allow one minute between requests.",style=MaterialTheme.typography.bodySmall)
                serviceError?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                if(capabilities?.optBoolean("email_verification_available")==false)Text("Email unavailable. An existing code can still be used.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled=busy==null&&!loading&&capabilities?.optBoolean("email_verification_available")==true,onClick={
                    vm.action("Requesting verification email") {
                        val response=withContext(Dispatchers.IO) { JSONObject(api.call("auth/email/request-verification","POST")) }
                        token=""
                        vm.notice.value=response.getString("message")
                    }
                }) { Text("Request verification email") }
                OutlinedTextField(token,{token=it.trim()},enabled=busy==null,label={Text("Code from verification email")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                Button(enabled=busy==null&&token.length in 40..100,onClick={
                    vm.action("Verifying email") {
                        val response=withContext(Dispatchers.IO) { JSONObject(api.call("auth/email/verify","POST",JSONObject().put("token",token))) }
                        check(response.getBoolean("email_verified")) { "The server did not confirm email verification" }
                        account=JSONObject(account!!.toString()).put("email_verified",true);clearCodes()
                        vm.notice.value="Email address verified."
                    }
                }) { Text("Verify email") }
            } else if(!loading&&accountError==null)Text("This server does not expose email verification status.",style=MaterialTheme.typography.bodySmall)
            TextButton(enabled=busy==null&&!loading,onClick={reload++}) { Text("Refresh verification status") }
        }
    }
}

@Composable
fun ReportsScreen(vm:InspectionViewModel,activity:Activity,onSignIn:()->Unit={}) {
    val session=rememberScreenSession(vm)
    key(session) { ReportsContent(vm,activity,session,onSignIn) }
}

@Composable
private fun ReportsContent(vm:InspectionViewModel,activity:Activity,session:ScreenSession,onSignIn:()->Unit) {
    val api=remember(session) { session.api(activity) }
    val busy by vm.busy.collectAsState()
    var reports by remember { mutableStateOf<JSONArray?>(null) }
    var report by remember { mutableStateOf<JSONObject?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(session.signedIn) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session,refresh) {
        if(!session.signedIn)return@LaunchedEffect
        loading=true;error=null
        try {
            val response=withContext(Dispatchers.IO) { JSONArray(api.call("reports")) }
            session.requireCurrent(vm);reports=response
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { error="Reports could not be loaded. Check your connection and retry." }
        finally { loading=false }
    }
    fun download(item:JSONObject) {
        val id=item.getString("id")
        vm.action("Downloading report") {
            session.requireCurrent(vm)
            val bytes=withContext(Dispatchers.IO) { api.bytes("reports/$id/pdf") }
            session.requireCurrent(vm)
            val file=withContext(Dispatchers.IO) { File(File(activity.cacheDir,"reports").apply { mkdirs() },"PhoneProof-$id.pdf").apply { writeBytes(bytes) } }
            session.requireCurrent(vm)
            val uri=FileProvider.getUriForFile(activity,activity.packageName+".files",file)
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share report PDF"))
        }
    }
    ScreenColumn {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { ScreenHeading("Reports","Your finalized inspection records.") }
            if(session.signedIn)TextButton(enabled=busy==null&&!loading,onClick={refresh++}) { Text("Refresh") }
        }
        when {
            !session.signedIn -> QuietCard { Text("Sign in to view your reports.",style=MaterialTheme.typography.titleMedium);Button(onClick=onSignIn,modifier=Modifier.fillMaxWidth().height(52.dp)) { Text("Sign in") } }
            loading -> { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading reports...",style=MaterialTheme.typography.bodyMedium) }
            error!=null -> QuietCard { Text(error!!);Button(onClick={refresh++}) { Text("Try again") } }
            reports?.length()==0 -> QuietCard { Text("No reports yet",style=MaterialTheme.typography.titleLarge);Text("Finish an inspection and its photo review to create a report.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> reports?.let { array ->
                for(index in 0 until array.length()) {
                    val item=array.getJSONObject(index);val payload=item.getJSONObject("payload")
                    val score=payload.optJSONObject("overall")?.takeUnless { it.isNull("score") }?:payload.getJSONObject("score")
                    QuietCard {
                        Text(payload.getJSONObject("device").optString("model","Phone"),style=MaterialTheme.typography.titleLarge)
                        Text(payload.getString("finalized_at").take(10),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Grade ${score.optString("grade","—")}",style=MaterialTheme.typography.headlineSmall)
                            Text("${score.opt("score")?:score.opt("functional_score")}/100",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                        }
                        OutlinedButton(enabled=busy==null,onClick={report=item},modifier=Modifier.fillMaxWidth()) { Text("View report") }
                    }
                }
            }
        }
    }
    report?.let { item -> DetailSheet("Inspection report",{report=null}) {
        val payload=item.getJSONObject("payload")
        val functional=payload.getJSONObject("score")
        val cosmetic=payload.getJSONObject("cosmetic")
        Text(payload.getJSONObject("device").optString("model","Phone"),style=MaterialTheme.typography.headlineSmall)
        Text("${readableLabel(item.getString("status"))} Â· ${payload.getString("finalized_at").take(10)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        QuietCard {
            Text("Functional",style=MaterialTheme.typography.titleSmall)
            Text("${functional.opt("functional_score")}/100 Â· Grade ${functional.optString("grade")}",style=MaterialTheme.typography.titleLarge)
            Text("${functional.opt("coverage_percent")}% conclusive coverage Â· Rule ${functional.optInt("rule_version",1)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("Cosmetic",style=MaterialTheme.typography.titleSmall)
            Text(if(cosmetic.isNull("score"))"Unavailable" else "${cosmetic.opt("score")}/100 Â· Grade ${cosmetic.optString("grade")}",style=MaterialTheme.typography.titleLarge)
            Text(readableLabel(cosmetic.optString("method")),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            payload.optJSONObject("overall")?.takeUnless { it.isNull("score") }?.let { overall ->
                HorizontalDivider();Text("Overall",style=MaterialTheme.typography.titleSmall)
                Text("${overall.opt("score")}/100 Â· Grade ${overall.optString("grade")}",style=MaterialTheme.typography.titleLarge)
                Text(overall.optString("method"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(enabled=busy==null,onClick={download(item)},modifier=Modifier.fillMaxWidth()) { Text("Download PDF") }
        OutlinedButton(enabled=busy==null,onClick={session.requireCurrent(vm);activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(item.getString("verification_url"))))},modifier=Modifier.fillMaxWidth()) { Text("Verify online") }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) { QrImage(item.getString("verification_url"),180) }
        Text("Reports are signed when finalized. They remain available after a subscription expires.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}
