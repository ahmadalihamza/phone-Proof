package pk.phoneproof.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID
import pk.phoneproof.app.InspectionViewModel
import pk.phoneproof.app.data.*

val captureViews=listOf("front_off","front_on","back","left","right","top","bottom","top_left","top_right","bottom_left","bottom_right","camera_module")
val defectClasses=listOf("front_glass_crack","back_glass_crack","scratch","deep_scratch","dent","chipped_corner","frame_deformation","paint_wear","camera_glass_damage","camera_ring_damage","back_panel_damage","screen_edge_damage")

@Composable
fun QrImage(value:String,size:Int=230) {
    val bitmap=remember(value) {
        val matrix=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,600,600)
        Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply {
            for(y in 0 until 600)for(x in 0 until 600)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    Image(bitmap.asImageBitmap(),"QR code",Modifier.size(size.dp))
}

private data class ScannerCameraRequest(val mode:String,val session:ScreenSession,val capture:ScannerCaptureBinding?=null)

@Composable
fun ScannerScreen(vm:InspectionViewModel,activity:Activity,onSignIn:()->Unit={}) {
    val session=rememberScreenSession(vm)
    // Discard every visible field and open camera when the account or origin changes.
    key(session) { ScannerContent(vm,activity,session,onSignIn) }
}

@Composable
private fun ScannerContent(vm:InspectionViewModel,activity:Activity,session:ScreenSession,onSignIn:()->Unit) {
    val api=remember(session) { session.api(activity) }
    val drafts=remember { ScannerDraftStore(activity) }
    val busy by vm.busy.collectAsState()
    val captures by vm.db.dao().observeCaptures().collectAsState(initial=emptyList())
    var ownerId by remember { mutableStateOf<String?>(null) }
    var accountLoading by remember { mutableStateOf(session.signedIn) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pairing by remember { mutableStateOf<ScannerPairing?>(null) }
    var pairingText by remember { mutableStateOf("") }
    var pairingOpen by remember { mutableStateOf(false) }
    var viewsOpen by remember { mutableStateOf(false) }
    var reviewOpen by remember { mutableStateOf(false) }
    var identityOpen by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(captureViews.first()) }
    var camera by remember { mutableStateOf<ScannerCameraRequest?>(null) }
    var permissionRequest by remember { mutableStateOf<ScannerCameraRequest?>(null) }
    var images by remember { mutableStateOf<JSONArray?>(null) }
    var reviewed by remember { mutableStateOf(setOf<String>()) }
    var reviewImage by remember { mutableStateOf<JSONObject?>(null) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var findings by remember { mutableStateOf(JSONArray()) }
    var imei by remember { mutableStateOf("") }
    var pta by remember { mutableStateOf("NOT_VERIFIED") }
    var framingConfirmed by remember { mutableStateOf(false) }
    var restoredKey by remember { mutableStateOf<String?>(null) }
    var cosmeticSaved by remember { mutableStateOf(false) }

    fun requireSession()=session.requireCurrent(vm)
    fun isCurrent(request:ScannerCameraRequest):Boolean = runCatching {
        request.session.requireCurrent(vm)
        val captured=request.capture
        captured==null||captured.isCurrent(vm.api.vault.snapshot(),vm.api.baseUrl,ownerId,pairing?.inspectionId,pairing?.nonce)
    }.getOrDefault(false)
    fun binding():ScannerCaptureBinding {
        requireSession()
        val current=pairing?:error("Pair a target phone first")
        check(current.ownerId==ownerId&&current.origin==session.origin) { "Sign in again to use this scanner" }
        return ScannerCaptureBinding(session.generation,current.origin,current.ownerId,current.inspectionId,current.nonce,current.scannerId,view)
    }
    fun clearReview() {
        images=null;reviewed=emptySet();findings=JSONArray();framingConfirmed=false;restoredKey=null
        reviewImage=null;photo=null;cosmeticSaved=false;imei="";pta="NOT_VERIFIED";view=captureViews.first()
    }
    LaunchedEffect(session,reload) {
        if(!session.signedIn)return@LaunchedEffect
        accountLoading=true;accountError=null
        try {
            val user=withContext(Dispatchers.IO) { JSONObject(api.call("auth/me")) }
            requireSession()
            val owner=user.getString("id")
            val saved=withContext(Dispatchers.IO) { drafts.pairing(session.origin,owner) }
            // The server may have paired another scanner since this draft was saved.
            val restored=saved?.let {
                val status=withContext(Dispatchers.IO) { JSONObject(api.call("inspections/${it.inspectionId}/pairing-status")) }
                requireSession()
                if(status.optBoolean("paired")&&status.optString("scanner_device_id")==it.scannerId)it else null
            }
            requireSession();ownerId=owner;pairing=restored
            if(saved!=null&&restored==null)drafts.forgetPairing(session.origin,owner)
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { ownerId=null;pairing=null;accountError="Reconnect to confirm your account and scanner pairing." }
        finally { accountLoading=false }
    }
    val draftKey=pairing?.let { drafts.key(it.origin,it.ownerId,it.inspectionId) }
    LaunchedEffect(draftKey) {
        clearReview()
        if(draftKey!=null) {
            val saved=withContext(Dispatchers.IO) { drafts.read(draftKey) }
            requireSession()
            images=saved?.optJSONArray("images")
            findings=saved?.optJSONArray("findings")?:JSONArray()
            val checked=saved?.optJSONArray("reviewed")?:JSONArray()
            reviewed=(0 until checked.length()).map { checked.getString(it) }.toSet()
            framingConfirmed=saved?.optBoolean("framing",false)?:false
            view=saved?.optString("view")?.takeIf { it in captureViews }?:captureViews.first()
            restoredKey=draftKey
        }
    }
    val draftBody=JSONObject().put("images",images).put("findings",findings).put("reviewed",JSONArray(reviewed.toList()))
        .put("framing",framingConfirmed).put("view",view).toString()
    LaunchedEffect(draftKey,restoredKey,draftBody) {
        if(draftKey!=null&&restoredKey==draftKey&&runCatching { requireSession() }.isSuccess)
            withContext(Dispatchers.IO) { drafts.save(draftKey,JSONObject(draftBody)) }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request=permissionRequest;permissionRequest=null
        if(granted&&request!=null&&isCurrent(request))camera=request
        else if(!granted)vm.notice.value="Camera access is needed to capture scanner evidence."
    }
    fun openCamera(mode:String) {
        val request=runCatching { requireSession();ScannerCameraRequest(mode,session,if(mode=="qr")null else binding()) }
            .getOrElse { vm.notice.value=it.message;return }
        if(activity.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)camera=request
        else { permissionRequest=request;permission.launch(Manifest.permission.CAMERA) }
    }
    fun join() {
        val text=pairingText
        val owner=ownerId?:return
        vm.action("Pairing scanner") {
            requireSession()
            val qr=JSONObject(text)
            require(qr.optString("type")=="phoneproof_pair") { "Use a PhoneProof pairing code" }
            val scannerId=drafts.scannerId(session.origin,owner)
            val joined=withContext(Dispatchers.IO) { JSONObject(api.call("pairing/join","POST",JSONObject()
                .put("inspection_id",qr.getString("inspection_id")).put("token",qr.getString("token")).put("scanner_device_id",scannerId))) }
            requireSession()
            val next=ScannerPairing(session.origin,owner,joined.getString("inspection_id"),joined.getString("capture_nonce"),scannerId)
            drafts.savePairing(next);clearReview();pairing=next;pairingText="";pairingOpen=false
            vm.notice.value="Scanner paired. Start with the front of the target phone."
        }
    }
    fun loadReview() {
        val current=pairing?:return
        vm.action("Syncing photos") {
            requireSession()
            val sync=withContext(Dispatchers.IO) { SyncWorker.synchronize(activity) }
            check(sync.state !in setOf(pk.phoneproof.app.data.SyncState.OFFLINE,pk.phoneproof.app.data.SyncState.SIGN_IN_REQUIRED,pk.phoneproof.app.data.SyncState.LOCAL_ONLY)) {
                sync.message?:"Photos are still saved on this phone. Reconnect and try again."
            }
            sync.inspections[current.inspectionId]?.let { queue ->
                check(!queue.hasPending&&!queue.hasRejected&&queue.errorMessage==null) {
                    queue.errorMessage?:"Some photos haven't been acknowledged. Retry the pending views before review."
                }
            }
            requireSession()
            val currentImages=withContext(Dispatchers.IO) { JSONArray(api.call("inspections/${current.inspectionId}/images")) }
            requireSession();check(pairing==current) { "Pairing changed. Reload the photos." }
            val ids=(0 until currentImages.length()).map { currentImages.getJSONObject(it).getString("id") }.toSet()
            val oldIds=images?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }?:emptySet()
            images=currentImages;reviewed=reviewed.intersect(ids)
            findings=JSONArray().also { kept -> for(index in 0 until findings.length()) {
                val finding=findings.getJSONObject(index);if(finding.getString("image_id") in ids)kept.put(finding)
            } }
            if(ids!=oldIds) { framingConfirmed=false;cosmeticSaved=false }
            reviewOpen=true
        }
    }
    val current=pairing
    val scopedCaptures=captures.filter { it.ownerId==ownerId&&it.serverUrl==session.origin&&it.inspectionId==current?.inspectionId }
    val latest=scopedCaptures.distinctBy { it.view }
    val imageViews=images?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("view") }.toSet() }?:emptySet()
    val completed=(latest.filter { it.error==null }.map { it.view }.toSet()+imageViews)

    ScreenColumn {
        ScreenHeading("Photos",if(current==null)"Connect the target phone." else null)
        when {
            !session.signedIn -> QuietCard {
                Text("Use the same account on both phones.")
                Button(onClick=onSignIn,modifier=Modifier.fillMaxWidth().height(52.dp)) { Text("Sign in") }
            }
            accountLoading -> { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Checking account…",style=MaterialTheme.typography.bodyMedium) }
            accountError!=null -> QuietCard { Text(accountError!!);Button(onClick={reload++}) { Text("Try again") } }
            current==null -> QuietCard {
                Text("Pair the target phone",style=MaterialTheme.typography.titleLarge)
                Text("Open an inspection on the target phone and choose Pair scanner.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled=busy==null,onClick={openCamera("qr")},modifier=Modifier.fillMaxWidth()) { Text("Scan pairing code") }
                TextButton(enabled=busy==null,onClick={pairingOpen=true}) { Text("Paste pairing code") }
            }
            else -> {
                QuietCard {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Exterior photos",style=MaterialTheme.typography.titleMedium)
                        Text("${completed.size} / 12",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                    }
                    LinearProgressIndicator(progress={completed.size/12f},modifier=Modifier.fillMaxWidth())
                    Text(readableLabel(view),style=MaterialTheme.typography.headlineSmall)
                    Text(if(view=="front_off")"Screen off. Keep the whole phone in the guide." else if(view=="front_on")"Screen on. Keep the whole phone in the guide." else "Keep this side sharp, well lit and free of glare.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled=busy==null&&restoredKey==draftKey,onClick={openCamera("capture")},modifier=Modifier.fillMaxWidth()) {
                        Text(if(view in completed)"Retake photo" else "Capture photo")
                    }
                    TextButton(onClick={viewsOpen=true},modifier=Modifier.fillMaxWidth()) { Text("Choose view or check uploads") }
                }
                if(latest.any { it.error!=null })Text("Some photos need another capture. Check uploads for details.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                QuietCard {
                    DetailRow("Review photos",if(cosmeticSaved)"Review saved" else "${reviewed.size} of 12 reviewed",busy==null) { loadReview() }
                    HorizontalDivider()
                    DetailRow("IMEI & PTA","Optional identity evidence",busy==null) { identityOpen=true }
                }
                TextButton(enabled=busy==null,onClick={pairingOpen=true}) { Text("Pair another inspection") }
            }
        }
    }
    if(pairingOpen)DetailSheet("Pair target phone",{pairingOpen=false}) {
        Text("Use the pairing code from the target phone.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled=busy==null,onClick={pairingOpen=false;openCamera("qr")},modifier=Modifier.fillMaxWidth()) { Text("Scan code") }
        OutlinedTextField(pairingText,{pairingText=it},label={Text("Pairing code")},modifier=Modifier.fillMaxWidth(),minLines=3)
        OutlinedButton(enabled=pairingText.isNotBlank()&&busy==null&&ownerId!=null,onClick={join()},modifier=Modifier.fillMaxWidth()) { Text("Pair scanner") }
    }
    if(viewsOpen)DetailSheet("Photo checklist",{viewsOpen=false}) {
        captureViews.forEachIndexed { index,value ->
            val capture=latest.firstOrNull { it.view==value }
            val status=when { capture?.error!=null -> "Retake: ${capture.error}";capture?.synced==true||value in imageViews -> "Uploaded";capture!=null -> "Saved · waiting for upload";else -> "Not captured" }
            DetailRow("${index+1}. ${readableLabel(value)}",status) { view=value;viewsOpen=false }
        }
        OutlinedButton(enabled=busy==null,onClick={viewsOpen=false;loadReview()},modifier=Modifier.fillMaxWidth()) { Text("Sync photos") }
    }
    if(reviewOpen&&current!=null)DetailSheet("Review photos",{reviewOpen=false}) {
        val array=images
        if(array==null||array.length()==0)Text("Capture the twelve views, then sync to begin review.")
        else {
            Text("${reviewed.size} of 12 reviewed · ${findings.length()} defects",style=MaterialTheme.typography.titleMedium)
            Text("Open each photo. Mark visible defects or confirm no defects.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            for(index in 0 until array.length()) {
                val item=array.getJSONObject(index)
                DetailRow(readableLabel(item.getString("view")),if(item.getString("id") in reviewed)"Reviewed" else "Needs review",busy==null) {
                    vm.action("Opening photo") {
                        requireSession()
                        val bitmap=withContext(Dispatchers.IO) { decodeReviewBitmap(api.bytes("images/${item.getString("id")}")) }
                        requireSession();check(pairing==current) { "Pairing changed" }
                        photo=bitmap;reviewImage=item
                    }
                }
            }
            if(findings.length()>0) {
                HorizontalDivider();Text("Annotated defects",style=MaterialTheme.typography.titleSmall)
                for(index in 0 until findings.length()) {
                    val finding=findings.getJSONObject(index)
                    Row(Modifier.fillMaxWidth()) {
                        Text("${readableLabel(finding.getString("severity"))} · ${readableLabel(finding.getString("defect"))}",Modifier.weight(1f).padding(top=12.dp),style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={findings=JSONArray().also { kept -> for(i in 0 until findings.length())if(i!=index)kept.put(findings.getJSONObject(i)) };cosmeticSaved=false}) { Text("Remove") }
                    }
                }
            }
            Row { Checkbox(framingConfirmed,{framingConfirmed=it;cosmeticSaved=false});Text("Every photo shows the correct view and the whole target area.",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall) }
            Button(enabled=array.length()==12&&reviewed.size==12&&framingConfirmed&&busy==null,onClick={
                val submitted=JSONArray(findings.toString())
                vm.action("Saving photo review") {
                    requireSession();check(pairing==current) { "Pairing changed" }
                    val ids=JSONArray();for(index in 0 until array.length())ids.put(array.getJSONObject(index).getString("id"))
                    val body=JSONObject().put("image_ids",ids).put("framing_and_angles_confirmed",true).put("all_views_reviewed",true).put("findings",submitted).put("note","Twelve-view manual review completed in scanner app")
                    withContext(Dispatchers.IO) { api.call("inspections/${current.inspectionId}/cosmetic/review","POST",body) }
                    requireSession();cosmeticSaved=true;reviewOpen=false;vm.notice.value="Photo review saved. Finalize the report on the target phone."
                }
            },modifier=Modifier.fillMaxWidth()) { Text("Save review") }
            Text("Manual review. Automatic defect detection is unavailable.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(identityOpen&&current!=null)DetailSheet("IMEI & PTA",{identityOpen=false}) {
        OutlinedTextField(imei,{imei=it.filter { c -> c in '0'..'9' }.take(15)},label={Text("IMEI")},supportingText={Text("15 digits")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
        TextButton(enabled=busy==null,onClick={identityOpen=false;openCamera("imei")}) { Text("Read IMEI with camera") }
        Text("Check every digit before saving. Only a masked suffix and keyed hash are retained.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("NOT_VERIFIED" to "PTA not verified","INSPECTOR_CONFIRMED_APPROVED" to "Inspector confirmed approval","INSPECTOR_CONFIRMED_NOT_APPROVED" to "Inspector confirmed no approval").forEach { (value,label) ->
            Row { RadioButton(pta==value,{pta=value});Text(label,Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodyMedium) }
        }
        Text("PTA status is an inspector statement. No live PTA query is made.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled=imei.length==15&&busy==null,onClick={
            val digits=imei;val status=pta
            vm.action("Saving identity evidence") {
                requireSession();withContext(Dispatchers.IO) { api.call("inspections/${current.inspectionId}/identity","POST",JSONObject().put("imei",digits).put("pta_status",status)) }
                requireSession();imei="";identityOpen=false;vm.notice.value="Identity evidence saved."
            }
        },modifier=Modifier.fillMaxWidth()) { Text("Save identity evidence") }
    }
    camera?.let { request ->
        Dialog(onDismissRequest={camera=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(when(request.mode) { "qr" -> "Scan pairing code";"imei" -> "Read displayed IMEI";else -> readableLabel(request.capture!!.view) },style=MaterialTheme.typography.headlineSmall)
                    Box(Modifier.weight(1f)) { CameraPanel(onCapture={file ->
                        camera=null
                        if(!isCurrent(request)) { file.delete();vm.notice.value="Account or pairing changed. Capture the photo again." }
                        else when(request.mode) {
                            "qr" -> {
                                val scanner=BarcodeScanning.getClient()
                                scanner.process(InputImage.fromFilePath(activity,android.net.Uri.fromFile(file))).addOnSuccessListener { barcodes ->
                                    if(isCurrent(request)) {
                                        val found=barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf { value -> runCatching { JSONObject(value).optString("type")=="phoneproof_pair" }.getOrDefault(false) } }
                                        if(found==null)vm.notice.value="No pairing code found. Keep the code in focus and retry."
                                        else { pairingText=found;pairingOpen=true }
                                    }
                                }.addOnFailureListener { if(isCurrent(request))vm.notice.value="The code could not be read. Try again." }
                                    .addOnCompleteListener { file.delete();scanner.close() }
                            }
                            "imei" -> {
                                val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                recognizer.process(InputImage.fromFilePath(activity,android.net.Uri.fromFile(file))).addOnSuccessListener { text ->
                                    if(isCurrent(request)) {
                                        val candidate=Regex("(?<![0-9])[0-9]{15}(?![0-9])").find(text.text.replace(" ",""))?.value
                                        if(candidate==null)vm.notice.value="No 15-digit IMEI found. Enter it manually."
                                        else { imei=candidate;identityOpen=true;vm.notice.value="Check the detected IMEI before saving." }
                                    }
                                }.addOnFailureListener { if(isCurrent(request))vm.notice.value="IMEI could not be read. Enter it manually." }
                                    .addOnCompleteListener { file.delete();recognizer.close() }
                            }
                            else -> vm.action("Saving photo") {
                                val captured=request.capture!!
                                var stored=false
                                try {
                                    check(isCurrent(request)) { "Account or pairing changed. Capture the photo again." }
                                    val options=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.absolutePath,options)
                                    require(minOf(options.outWidth,options.outHeight)>=720) { "Photo is too small. Try a higher-resolution camera." }
                                    check(isCurrent(request)) { "Account or pairing changed. Capture the photo again." }
                                    vm.db.dao().capture(LocalCapture(UUID.randomUUID().toString(),captured.inspectionId,captured.view,file.absolutePath,Instant.now().toString(),captured.scannerId,captured.nonce,captured.ownerId,serverUrl=captured.origin))
                                    stored=true
                                    SyncWorker.enqueue(activity)
                                    if(isCurrent(request)) {
                                        val next=captureViews.indexOf(captured.view)+1
                                        if(next<captureViews.size)view=captureViews[next]
                                        framingConfirmed=false;cosmeticSaved=false
                                    }
                                } catch(e:Exception) { if(!stored)file.delete();throw e }
                            }
                        }
                    }) }
                    TextButton(onClick={camera=null},modifier=Modifier.fillMaxWidth()) { Text("Close camera") }
                }
            }
        }
    }
    reviewImage?.let { img -> photo?.let { bitmap ->
        ReviewPhoto(bitmap,img.getString("view"),onClose={reviewImage=null;photo=null},onReviewed={
            if(runCatching { requireSession() }.isSuccess) { reviewed=reviewed+img.getString("id");cosmeticSaved=false }
            reviewImage=null;photo=null
        },onDefect={defect,severity,bbox ->
            if(runCatching { requireSession() }.isSuccess) {
                findings=JSONArray(findings.toString()).put(JSONObject().put("image_id",img.getString("id")).put("defect",defect).put("severity",severity).put("bbox",JSONArray(bbox)).put("note","Inspector annotated region"));cosmeticSaved=false
            }
        })
    } }
}

@Composable
private fun ReviewPhoto(bitmap:Bitmap,view:String,onClose:()->Unit,onReviewed:()->Unit,onDefect:(String,String,List<Float>)->Unit) {
    var start by remember { mutableStateOf(Offset.Zero) }
    var end by remember { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(Size(1f,1f)) }
    var defect by remember { mutableStateOf(defectClasses.first()) }
    var severity by remember { mutableStateOf("minor") }
    var added by remember { mutableIntStateOf(0) }
    var defectMenu by remember { mutableStateOf(false) }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                ScreenHeading(readableLabel(view),"Drag a box over a defect, or mark this photo reviewed.")
                Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height)) {
                    Image(bitmap.asImageBitmap(),"Exterior evidence",Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)
                    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures(onDragStart={start=it;end=it},onDrag={change,_ -> change.consume();end=change.position})
                    }) {
                        bounds=size
                        drawRect(Color(0xFFB7443B),Offset(minOf(start.x,end.x),minOf(start.y,end.y)),Size(kotlin.math.abs(end.x-start.x),kotlin.math.abs(end.y-start.y)),style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    }
                }
                Box {
                    OutlinedButton(onClick={defectMenu=true},modifier=Modifier.fillMaxWidth()) { Text(readableLabel(defect)) }
                    DropdownMenu(defectMenu,{defectMenu=false}) { defectClasses.forEach { value ->
                        DropdownMenuItem(text={Text(readableLabel(value))},onClick={defect=value;defectMenu=false})
                    } }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("minor","moderate","severe").forEach { value ->
                    FilterChip(severity==value,{severity=value},label={Text(readableLabel(value))})
                } }
                OutlinedButton(enabled=kotlin.math.abs(end.x-start.x)>8&&kotlin.math.abs(end.y-start.y)>8,onClick={
                    val box=listOf(minOf(start.x,end.x)/bounds.width,minOf(start.y,end.y)/bounds.height,maxOf(start.x,end.x)/bounds.width,maxOf(start.y,end.y)/bounds.height).map { it.coerceIn(0f,1f) }
                    onDefect(defect,severity,box);added++;start=Offset.Zero;end=Offset.Zero
                },modifier=Modifier.fillMaxWidth()) { Text(if(added==0)"Add defect" else "Add defect · $added added") }
                Button(onClick=onReviewed,modifier=Modifier.fillMaxWidth()) { Text("Mark reviewed") }
                TextButton(onClick=onClose,modifier=Modifier.fillMaxWidth()) { Text("Close photo") }
            }
        }
    }
}
