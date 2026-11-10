package pk.phoneproof.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pk.phoneproof.app.BuildConfig
import pk.phoneproof.app.InspectionViewModel
import pk.phoneproof.app.SelectionText
import java.io.File
import java.util.UUID

private data class BillingData(val entitlements:JSONObject,val plans:JSONObject,val transactions:JSONArray,
    val subscription:JSONObject?,val history:JSONArray)

@Composable
fun BillingPanel(vm:InspectionViewModel) {
    val session=rememberScreenSession(vm)
    key(session) { BillingContent(vm,session) }
}

@Composable
private fun BillingContent(vm:InspectionViewModel,session:ScreenSession) {
    val context=LocalContext.current
    val api=remember(session) { session.api(context) }
    val busy by vm.busy.collectAsState()
    val selected by vm.selected.collectAsState()
    var data by remember { mutableStateOf<BillingData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var section by remember { mutableIntStateOf(0) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var promo by remember { mutableStateOf("") }
    var promoOpen by remember { mutableStateOf(false) }
    var cancelOpen by remember { mutableStateOf(false) }
    var renewalKey by rememberSaveable(session.generation,session.origin) { mutableStateOf(UUID.randomUUID().toString()) }
    LaunchedEffect(session,refresh) {
        loading=true;error=null
        try {
            val result=withContext(Dispatchers.IO) {
                val entitlement=JSONObject(api.call("billing/entitlements"))
                val plans=JSONObject(api.call("plans"))
                val transactions=JSONArray(api.call("transactions"))
                val subscription=api.call("subscriptions/current")
                val history=JSONArray(api.call("subscriptions/history"))
                BillingData(entitlement,plans,transactions,if(subscription=="null")null else JSONObject(subscription),history)
            }
            session.requireCurrent(vm);data=result
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { error="Billing could not be loaded. Check your connection and retry." }
        finally { loading=false }
    }
    TabRow(selectedTabIndex=section,containerColor=MaterialTheme.colorScheme.background) {
        listOf("Access","Plans","Payments").forEachIndexed { index,label -> Tab(selected=section==index,onClick={section=index},text={Text(label)}) }
    }
    if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    data?.let { billing ->
        val e=billing.entitlements
        val providers=billing.plans.optJSONArray("providers")?:JSONArray()
        val development=BuildConfig.DEBUG&&(0 until providers.length()).any { providers.getString(it)=="development" }
        val current=billing.subscription
        when(section) {
            0 -> {
                QuietCard {
                    Text("Reports remaining",style=MaterialTheme.typography.titleSmall)
                    Text(e.optInt("remaining").toString(),style=MaterialTheme.typography.displaySmall,color=MaterialTheme.colorScheme.primary)
                    Text(readableLabel(e.optString("source")),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(e.has("trial_expires_at")&&!e.isNull("trial_expires_at"))Text("Trial ends ${e.getString("trial_expires_at").take(10)}",style=MaterialTheme.typography.bodySmall)
                    Button(enabled=busy==null,onClick={section=1},modifier=Modifier.fillMaxWidth()) { Text("View plans") }
                }
                current?.let { subscription ->
                    QuietCard {
                        Text(readableLabel(subscription.getString("plan_id")),style=MaterialTheme.typography.titleMedium)
                        Text(readableLabel(subscription.getString("status")),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Text("${subscription.getString("current_period_start").take(10)} – ${subscription.getString("current_period_end").take(10)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(!subscription.isNull("next_plan_id"))Text("Next cycle: ${readableLabel(subscription.getString("next_plan_id"))}. Payment required.",style=MaterialTheme.typography.bodySmall)
                        if(subscription.optBoolean("cancel_at_period_end"))Text("Ends after this period. Existing reports remain available.",style=MaterialTheme.typography.bodySmall)
                        else TextButton(enabled=busy==null,onClick={cancelOpen=true}) { Text("Cancel subscription") }
                    }
                }
                if(current==null&&billing.history.length()>0) {
                    val previous=billing.history.getJSONObject(0)
                    QuietCard {
                        Text("Previous subscription",style=MaterialTheme.typography.titleSmall)
                        Text(readableLabel(previous.getString("status")))
                        if(development&&previous.getString("status") in listOf("EXPIRED","CANCELLED"))OutlinedButton(enabled=busy==null,onClick={vm.action("Creating test renewal") {
                            withContext(Dispatchers.IO) { api.call("subscriptions/${previous.getString("id")}/renew","POST",JSONObject().put("idempotency_key",renewalKey).put("provider","development")) }
                            session.requireCurrent(vm);renewalKey=UUID.randomUUID().toString();refresh++;section=2
                            vm.notice.value="Test renewal created. Open the payment to simulate its outcome."
                        }}) { Text("Test renewal") }
                    }
                }
                TextButton(enabled=busy==null,onClick={promoOpen=!promoOpen}) { Text(if(promoOpen)"Hide promotion code" else "Have a promotion code?") }
                if(promoOpen)QuietCard {
                    OutlinedTextField(promo,{promo=it.uppercase().filter { c -> c.isLetterOrDigit()||c=='_'||c=='-' }.take(40)},enabled=busy==null,label={Text("Promotion code")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedButton(enabled=busy==null&&promo.length>=3,onClick={vm.action("Redeeming promotion") {
                        withContext(Dispatchers.IO) { api.call("promo/redeem","POST",JSONObject().put("code",promo.trim())) }
                        session.requireCurrent(vm);promo="";refresh++;vm.notice.value="Promotion redeemed. One report added."
                    }},modifier=Modifier.fillMaxWidth()) { Text("Redeem code") }
                }
            }
            1 -> {
                billing.plans.optString("payment_notice").takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                val plans=billing.plans.getJSONArray("plans")
                for(index in 0 until plans.length()) {
                    val plan=plans.getJSONObject(index)
                    key(session,plan.getString("id")) { PlanCard(vm,plan,selected?.id,current,development,busy==null) { refresh++;section=2 } }
                }
            }
            2 -> {
                if(billing.transactions.length()==0)QuietCard { Text("No payments yet",style=MaterialTheme.typography.titleMedium) }
                for(index in 0 until billing.transactions.length()) {
                    val transaction=billing.transactions.getJSONObject(index)
                    QuietCard {
                        Text(transaction.optJSONObject("plan_snapshot")?.optString("name")?:readableLabel(transaction.getString("kind")),style=MaterialTheme.typography.titleMedium)
                        Text("${transaction.getString("currency")} ${transaction.getString("amount")}",style=MaterialTheme.typography.headlineSmall)
                        Text("${readableLabel(transaction.getString("status"))} · ${transaction.getString("created_at").take(10)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(transaction.optString("fulfillment_status")=="REVIEW_REQUIRED")Text("Payment received. Report access needs support review.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        TextButton(enabled=busy==null,onClick={detailId=transaction.getString("id")}) { Text("Payment details") }
                    }
                }
            }
        }
        if(cancelOpen&&current!=null)AlertDialog(onDismissRequest={cancelOpen=false},title={Text("Cancel subscription?")},text={Text("Access continues until the end of this period. Existing reports remain available.")},confirmButton={TextButton(enabled=busy==null,onClick={vm.action("Scheduling cancellation") {
            withContext(Dispatchers.IO) { api.call("subscriptions/${current.getString("id")}/cancel","POST") }
            session.requireCurrent(vm);cancelOpen=false;refresh++;vm.notice.value="Cancellation scheduled for the end of this period."
        }}) { Text("Cancel subscription") }},dismissButton={TextButton(onClick={cancelOpen=false}) { Text("Keep subscription") }})
    }
    TextButton(enabled=busy==null&&!loading,onClick={refresh++}) { Text("Refresh billing") }
    detailId?.let { id -> TransactionDialog(vm,id,onDismiss={detailId=null},onChanged={refresh++}) }
}

@Composable
private fun PlanCard(vm:InspectionViewModel,plan:JSONObject,inspectionId:String?,subscription:JSONObject?,development:Boolean,enabled:Boolean,onChanged:()->Unit) {
    val context=LocalContext.current
    val session=rememberScreenSession(vm)
    val api=remember(session) { session.api(context) }
    var checkoutOpen by remember { mutableStateOf(false) }
    var checkoutKey by rememberSaveable(session.generation,session.origin,plan.getString("id"),inspectionId) { mutableStateOf(UUID.randomUUID().toString()) }
    val reports=plan.getInt("report_limit")
    val days=plan.getInt("period_days")
    QuietCard { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(plan.getString("name"),style=MaterialTheme.typography.titleMedium);Text("${plan.getString("currency")} ${plan.getString("price")} · $reports reports${if(days>0)" / $days days" else ""}")
        if(subscription!=null&&days>0&&subscription.getString("plan_id")!=plan.getString("id")&&!subscription.optBoolean("cancel_at_period_end"))TextButton(enabled=enabled,onClick={vm.action("Scheduling plan change") {
            withContext(Dispatchers.IO) { api.call("subscriptions/${subscription.getString("id")}/change-plan","POST",JSONObject().put("plan_id",plan.getString("id"))) }
            session.requireCurrent(vm)
            onChanged();vm.notice.value="Plan change scheduled. Current access is retained; payment is required for the next cycle."
        }}) { Text("Choose for next cycle") }
        if(development&&(days==0||subscription==null))OutlinedButton(enabled=enabled&&(days>0||inspectionId!=null),onClick={checkoutOpen=true}) { Text("Test checkout") }
        if(days==0&&inspectionId==null)Text("Select an inspection to purchase its report.",style=MaterialTheme.typography.bodySmall)
    } }
    if(checkoutOpen)AlertDialog(onDismissRequest={checkoutOpen=false},title={Text("Review test checkout")},text={
        Text("${plan.getString("name")}\n${plan.getString("currency")} ${plan.getString("price")}\n$reports reports${if(days>0)" for $days days" else "\nInspection: $inspectionId"}\n\nDEVELOPMENT PAYMENT — NOT REAL MONEY. Terms and amount are confirmed by the server.")
    },confirmButton={TextButton(enabled=enabled,onClick={vm.action("Creating development checkout") {
        val body=JSONObject().put("idempotency_key",checkoutKey).put("plan_id",plan.getString("id")).put("provider","development")
        if(days==0)body.put("inspection_id",inspectionId)
        withContext(Dispatchers.IO) { api.call("payments/create","POST",body) }
        session.requireCurrent(vm)
        checkoutKey=UUID.randomUUID().toString();checkoutOpen=false;onChanged()
        vm.notice.value="Development transaction created. Open its details to simulate settlement."
    }}) { Text("Create test payment") }},dismissButton={TextButton(onClick={checkoutOpen=false}) { Text("Close") }})
}

@Composable
private fun TransactionDialog(vm:InspectionViewModel,id:String,onDismiss:()->Unit,onChanged:()->Unit) {
    val context=LocalContext.current
    val session=rememberScreenSession(vm)
    val api=remember(session) { session.api(context) }
    val busy by vm.busy.collectAsState()
    var detail by remember(id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var refresh by remember(id) { mutableIntStateOf(0) }
    var reason by remember(id) { mutableStateOf("") }
    LaunchedEffect(id,refresh) {
        try {
            val response=withContext(Dispatchers.IO) { JSONObject(api.call("transactions/$id")) }
            session.requireCurrent(vm);detail=response;error=null
        }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { error="Transaction details unavailable. Close and retry." }
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Transaction")},text={
        Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(detail==null&&error==null)Text("Loading…")
            detail?.let { t ->
                SelectionText(id)
                Text("${t.getString("currency")} ${t.getString("amount")} · ${t.getString("status")}")
                Text("Method: ${t.getString("provider")}\nCreated: ${t.getString("created_at")}")
                if(!t.isNull("provider_reference"))SelectionText("Provider reference: ${t.getString("provider_reference")}")
                if(!t.isNull("inspection_id"))SelectionText("Inspection: ${t.getString("inspection_id")}")
                t.optJSONObject("refund")?.let { refund -> Text("Refund: ${refund.getString("status")}\n${refund.getString("reason")}") }
                if(t.optBoolean("receipt_available"))OutlinedButton(enabled=busy==null,onClick={vm.action("Downloading receipt") {
                    val bytes=withContext(Dispatchers.IO) { api.bytes("transactions/$id/receipt/pdf") }
                    session.requireCurrent(vm)
                    val file=withContext(Dispatchers.IO) {
                        File(File(context.cacheDir,"reports").apply { mkdirs() },"PhoneProof-receipt-$id.pdf").apply { writeBytes(bytes) }
                    }
                    session.requireCurrent(vm)
                    val uri=FileProvider.getUriForFile(context,context.packageName+".files",file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share receipt PDF"))
                }}) { Text("Download receipt") }
                if(t.getString("status")=="PAID"&&t.isNull("refund")) {
                    OutlinedTextField(reason,{reason=it.take(500)},enabled=busy==null,label={Text("Refund reason (5–500 characters)")})
                    TextButton(enabled=busy==null&&reason.trim().length>=5,onClick={vm.action("Requesting refund") {
                        withContext(Dispatchers.IO) { api.call("transactions/$id/refund","POST",JSONObject().put("reason",reason.trim())) }
                        session.requireCurrent(vm)
                        reason="";refresh++;onChanged();vm.notice.value="Refund requested for review. Money has not been refunded yet."
                    }}) { Text("Request refund review") }
                }
                if(t.getString("status")=="PENDING") {
                    TextButton(enabled=busy==null,onClick={vm.action("Checking payment status") {
                        withContext(Dispatchers.IO) { api.call("payments/$id/reconcile","POST") };session.requireCurrent(vm);refresh++;onChanged()
                    }}) { Text("Check payment") }
                    if(BuildConfig.DEBUG&&t.getString("provider")=="development") {
                        Text("DEVELOPMENT PAYMENT — NOT REAL MONEY",color=MaterialTheme.colorScheme.error)
                        listOf("PAID" to "Simulate paid","FAILED" to "Simulate failed").forEach { (outcome,label) ->
                            TextButton(enabled=busy==null,onClick={vm.action(label) {
                                withContext(Dispatchers.IO) { api.call("payments/$id/development-settle","POST",JSONObject().put("outcome",outcome)) }
                                session.requireCurrent(vm)
                                refresh++;onChanged();vm.notice.value="Development outcome saved. No real money moved."
                            }}) { Text(label) }
                        }
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick=onDismiss) { Text("Close") }})
}
