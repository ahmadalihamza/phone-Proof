package pk.phoneproof.app.ui

import android.app.Activity
import android.content.Context
import android.media.*
import android.os.*
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import pk.phoneproof.diagnostics.*
import java.time.Instant
import kotlin.math.*

@Composable
fun GuidedTest(d:DiagnosticDefinition,engine:DiagnosticsEngine,onResult:(DiagnosticResult)->Unit,onClose:()->Unit) {
    val started=remember { Instant.now().toString() }
    val context=LocalContext.current
    val activity=context as Activity
    var confirmed by remember { mutableStateOf(false) }
    var evidence by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var message by remember { mutableStateOf("") }
    var operationReady by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val focus=remember { FocusRequester() }
    fun finish(status:DiagnosticStatus,note:String) {
        if(finished)return
        finished=true;onResult(engine.result(d,status,evidence,note,started));onClose()
    }
    Dialog(onDismissRequest={finish(DiagnosticStatus.INCONCLUSIVE,"Guided test closed before completion")},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val dialogWindow=(LocalView.current.parent as? DialogWindowProvider)?.window ?: activity.window
        Surface(Modifier.fillMaxSize().testTag("guided-test").focusRequester(focus).onPreviewKeyEvent { event ->
            val key=event.nativeKeyEvent
            val test=d.parameters["test"]
            if(d.handler=="buttons"&&key.action==android.view.KeyEvent.ACTION_DOWN&&((test=="volume_up"&&key.keyCode==android.view.KeyEvent.KEYCODE_VOLUME_UP)||(test=="volume_down"&&key.keyCode==android.view.KeyEvent.KEYCODE_VOLUME_DOWN))) {
                operationReady=true;evidence=mapOf("key_code" to key.keyCode.toString(),"source" to "KeyEvent.ACTION_DOWN");true
            } else false
        }.focusable()) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start=20.dp,end=8.dp,top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(guidedTitle(d),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
                    TextButton(onClick={showDetails=true}) { Text("Info") }
                    TextButton(onClick={finish(DiagnosticStatus.INCONCLUSIVE,"Guided test closed before completion")}) { Text("Close") }
                }
                Text(guidedInstruction(d),Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.bodyLarge)
                if(d.handler=="display_guided") {
                    val pattern=d.parameters["pattern"]?:"white"
                    DisposableEffect(pattern,dialogWindow) {
                        val old=dialogWindow.attributes.screenBrightness
                        if(pattern.startsWith("brightness"))dialogWindow.attributes=dialogWindow.attributes.apply { screenBrightness=if(pattern.endsWith("min"))0.01f else 1f }
                        onDispose { dialogWindow.attributes=dialogWindow.attributes.apply { screenBrightness=old } }
                    }
                    val colors=when(pattern) {"red"->listOf(Color.Red,Color.Red);"green"->listOf(Color.Green,Color.Green);"blue"->listOf(Color.Blue,Color.Blue);"black"->listOf(Color.Black,Color.Black);"gray","burn_in"->listOf(Color.Gray,Color.Gray);"gradient"->listOf(Color.Red,Color.Green,Color.Blue);"grayscale"->listOf(Color.Black,Color.White);else->listOf(Color.White,Color.White)}
                    Box(Modifier.fillMaxWidth().weight(1f).background(Brush.horizontalGradient(colors)))
                    LaunchedEffect(Unit) { operationReady=true;evidence=mapOf("pattern" to pattern,"confirmation_method" to "USER_CONFIRMED") }
                } else if(d.handler=="touch") {
                    TouchSurface(d,Modifier.weight(1f),onEvidence={values,passed -> evidence=values;operationReady=passed })
                } else if(d.handler in listOf("camera_capture","torch")) {
                    Box(Modifier.weight(1f).padding(12.dp)) {
                        CameraPanel(d.parameters["camera_id"],onCapture={file ->
                            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true };android.graphics.BitmapFactory.decodeFile(file.absolutePath,bounds)
                            evidence=mapOf("file_bytes" to file.length().toString(),"width" to bounds.outWidth.toString(),"height" to bounds.outHeight.toString(),"camera_id" to (d.parameters["camera_id"]?:"unknown"))
                            if(d.handler=="camera_capture"&&d.parameters["test"]=="capture")operationReady=bounds.outWidth>0&&bounds.outHeight>0
                            file.delete()
                        },onPreview={streaming -> if(d.parameters["test"]=="preview") { operationReady=streaming;if(streaming)evidence=mapOf("preview_stream" to "STREAMING","camera_id" to d.parameters.getValue("camera_id")) } },onError={message=it;operationReady=false},onTorch=if(d.handler=="torch")({enabled -> if(enabled) { evidence=mapOf("torch_state" to "ON","camera_id" to d.parameters.getValue("camera_id"),"confirmation_method" to "USER_CONFIRMED");operationReady=true } })else null,showCapture=d.parameters["test"]=="capture",onTorchUnavailable={finish(DiagnosticStatus.NOT_SUPPORTED,"This lens has no flash unit")})
                    }
                } else if(d.handler=="speaker") {
                    SpeakerPanel(d,Modifier.weight(1f),onReady={operationReady=it},onEvidence={evidence=it},onMessage={message=it})
                } else if(d.handler=="buttons") {
                    val test=d.parameters.getValue("test")
                    BackHandler(enabled=test=="back") { operationReady=true;evidence=mapOf("back_callback" to "observed") }
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Text(when(test) {"volume_up"->"＋";"volume_down"->"−";"back"->"←";"power"->"⏻";else->"≋"},fontSize=88.sp,color=MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(24.dp))
                        if(test=="vibration")Button(onClick={
                            val vibrator=if(Build.VERSION.SDK_INT>=31)context.getSystemService(VibratorManager::class.java).defaultVibrator else context.getSystemService(Vibrator::class.java)
                            if(!vibrator.hasVibrator()) { finish(DiagnosticStatus.NOT_SUPPORTED,"No vibrator is exposed") } else { vibrator.vibrate(VibrationEffect.createOneShot(500,VibrationEffect.DEFAULT_AMPLITUDE));operationReady=true;evidence=mapOf("vibration_requested_ms" to "500","confirmation_method" to "USER_CONFIRMED") }
                        },modifier=Modifier.heightIn(min=56.dp)) { Text("Start vibration") }
                        if(test=="power")Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=confirmed,onCheckedChange={confirmed=it;operationReady=it;evidence=mapOf("power_button" to "inspector_confirmed","confirmation_method" to "USER_CONFIRMED")})
                            Text("Phone locked and woke")
                        }
                        if(operationReady&&test !in listOf("power","vibration"))Text("Button detected",style=MaterialTheme.typography.titleMedium)
                    }
                }
                if(message.isNotEmpty())Text(message,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(enabled=operationReady,onClick={finish(DiagnosticStatus.PASS,if(d.mode=="USER_CONFIRMED")"Inspector confirmed the expected behavior" else "Required operation observed")},modifier=Modifier.weight(1f).heightIn(min=56.dp)) { Text("Pass") }
                    OutlinedButton(enabled=d.handler!="speaker"||operationReady,onClick={evidence=evidence+mapOf("confirmation_method" to "USER_CONFIRMED","defect_observed" to "true");finish(DiagnosticStatus.FAIL,"Inspector confirmed a functional defect during the guided check")},modifier=Modifier.weight(1f).heightIn(min=56.dp)) { Text("Problem") }
                    TextButton(onClick={finish(DiagnosticStatus.SKIPPED_BY_USER,"Inspector intentionally skipped the guided test")},modifier=Modifier.weight(1f).heightIn(min=56.dp)) { Text("Skip") }
                }
            }
        }
    }
    if(showDetails)AlertDialog(onDismissRequest={showDetails=false},title={Text(d.title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(d.description)
            Text("Pass: ${d.passCriteria}")
            if(evidence.isNotEmpty())Text(evidence.entries.joinToString("\n") { "${it.key}: ${it.value}" },style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={showDetails=false}) { Text("Done") }})
}

private fun guidedTitle(d:DiagnosticDefinition):String=when(d.handler) {
    "camera_capture"->if(d.parameters["test"]=="capture")"Camera photo" else "Camera preview"
    "torch"->"Camera flash"
    "speaker"->when(d.parameters["frequency"]) {"200"->"Low speaker tone";"4000"->"High speaker tone";else->"Speaker tone"}
    "buttons"->when(d.parameters["test"]) {"power"->"Power button";"back"->"Back button";"volume_up"->"Volume up";"volume_down"->"Volume down";else->"Vibration"}
    "touch"->when(d.parameters["test"]) {"grid"->"Touch grid";"edges"->"Screen edges";"corners"->"Screen corners";"multitouch"->"Two-finger touch";"three_finger"->"Three-finger touch";"interval"->"Touch response";"jitter"->"Steady touch";else->d.title}
    else->d.title.substringBefore(" / ")
}

private fun guidedInstruction(d:DiagnosticDefinition):String=when(d.handler) {
    "display_guided"->when(d.parameters["pattern"]) {"brightness_min"->"Check that the screen dims evenly.";"brightness_max"->"Check that the screen brightens evenly.";"burn_in"->"Look for faint shapes left on the screen.";"flicker"->"Look for visible flashing or flickering.";else->"Look for spots, lines or uneven color."}
    "camera_capture"->if(d.parameters["test"]=="capture")"Take a photo and check that the camera works." else "Move the phone and check that the picture stays live."
    "torch"->"Switch on the light and check that it shines."
    "speaker"->"Listen for a clear tone at a comfortable volume."
    "buttons"->when(d.parameters["test"]) {"volume_up"->"Press the volume-up button.";"volume_down"->"Press the volume-down button.";"back"->"Use the system Back button or gesture.";"power"->"Lock the phone, then wake it with the power button.";else->"Start the vibration and check that you feel it."}
    "touch"->when(d.parameters["test"]) {"single"->"Tap anywhere in the test area.";"grid"->"Drag through every square until the grid is filled.";"edges"->"Trace the squares around the outside edge.";"corners"->"Touch each of the four corners.";"multitouch"->"Place two fingers on the area together.";"three_finger"->"Place three fingers on the area together.";"long_press"->"Hold one spot for a second.";"rapid_tap"->"Tap the area five times.";"horizontal"->"Swipe from one side to the other.";"vertical"->"Swipe from top to bottom.";"diagonal"->"Swipe from one corner to the opposite corner.";"jitter"->"Keep one finger still on the area.";else->"Drag a finger slowly across the area."}
    else->"Try the check, then choose the result."
}

@Composable
private fun SpeakerPanel(d:DiagnosticDefinition,modifier:Modifier,onReady:(Boolean)->Unit,onEvidence:(Map<String,String>)->Unit,onMessage:(String)->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf<AudioTrack?>(null) }
    var playbackJob by remember { mutableStateOf<Job?>(null) }
    val ready by rememberUpdatedState(onReady)
    val saveEvidence by rememberUpdatedState(onEvidence)
    val showMessage by rememberUpdatedState(onMessage)
    fun stopPlayback(message:String) {
        if(playing) { ready(false);completed=false;showMessage(message) }
        playbackJob?.cancel();playbackJob=null
        runCatching { track?.stop() }
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_PAUSE)stopPlayback("Tone paused. Play it again to continue.") }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);stopPlayback("Tone stopped before finishing.") }
    }
    Column(modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        Text(if(playing)"♫" else "♪",fontSize=100.sp,color=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(if(playing)"Playing tone" else if(completed)"Tone finished" else "Ready to play",style=MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Button(enabled=!playing,onClick={playbackJob=scope.launch {
            playing=true;completed=false;ready(false);showMessage("");saveEvidence(emptyMap())
            var audio:AudioTrack?=null
            var listener:AudioRouting.OnRoutingChangedListener?=null
            val route=BuiltInAudioRouteCheck(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            var values:Map<String,String> = emptyMap()
            try {
                val speaker=context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if(speaker==null) { showMessage("Phone speaker unavailable. Disconnect external audio and retry.");return@launch }
                val frequency=d.parameters.getValue("frequency").toDouble();val rate=44100
                // A short lead-in lets Android expose the real route before the two-second tone.
                val lead=rate/2
                val data=ShortArray(lead+rate*2) { if(it<lead)0 else (sin(2*PI*(it-lead)*frequency/rate)*4000).toInt().toShort() }
                val output=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(data.size*2).build()
                audio=output;track=output
                val preferred=output.setPreferredDevice(speaker)
                val written=output.write(data,0,data.size)
                values=mapOf("tone_hz" to frequency.toString(),"pcm_samples_written" to written.toString(),"audio_target" to "BUILTIN_SPEAKER","preferred_route_requested" to preferred.toString(),"confirmation_method" to "USER_CONFIRMED")
                if(written!=data.size) { showMessage("Tone couldn't finish. Try again.");return@launch }
                listener=AudioRouting.OnRoutingChangedListener { routing ->
                    route.observe(runCatching { observedAudioRoutes(routing) }.getOrDefault(emptyList()))
                    saveEvidence(values+route.evidence())
                    if(route.invalid())stopPlayback("Audio changed. Disconnect external audio and try again.")
                }
                output.addOnRoutingChangedListener(listener,Handler(Looper.getMainLooper()))
                output.play()
                val routeDeadline=SystemClock.elapsedRealtime()+400
                while(!route.observe(observedAudioRoutes(output))&&!route.invalid()&&SystemClock.elapsedRealtime()<routeDeadline)delay(20)
                saveEvidence(values+route.evidence())
                if(!route.verified()) { showMessage("Use the phone speaker. Disconnect headphones or Bluetooth audio.");return@launch }
                val deadline=SystemClock.elapsedRealtime()+3500
                while(output.playbackHeadPosition<data.size&&SystemClock.elapsedRealtime()<deadline) {
                    ensureActive()
                    if(!route.observe(observedAudioRoutes(output))) { showMessage("Audio changed. Disconnect external audio and try again.");return@launch }
                    delay(20)
                }
                route.observe(observedAudioRoutes(output))
                completed=route.verified()&&output.playbackHeadPosition>=data.size
                ready(completed)
                if(!completed)showMessage("Tone didn't finish. Try again.")
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { ready(false);showMessage("Couldn't play the tone. Try again.") }
            finally {
                saveEvidence(values+route.evidence()+mapOf("pcm_frames_played" to runCatching { audio?.playbackHeadPosition?.toString() ?: "0" }.getOrDefault("unavailable")))
                listener?.let { callback -> runCatching { audio?.removeOnRoutingChangedListener(callback) } }
                runCatching { audio?.stop() };runCatching { audio?.release() }
                track=null;playing=false;playbackJob=null
            }
        }},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(if(playing)"Playing…" else if(completed)"Play again" else "Play tone") }
        if(playing)TextButton(onClick={stopPlayback("Tone stopped. Play it again to continue.")},modifier=Modifier.heightIn(min=48.dp)) { Text("Stop tone") }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TouchSurface(d:DiagnosticDefinition,modifier:Modifier,onEvidence:(Map<String,String>,Boolean)->Unit) {
    var cells by remember { mutableStateOf(setOf<Int>()) };var pointers by remember { mutableIntStateOf(0) }
    var taps by remember { mutableIntStateOf(0) };var moves by remember { mutableIntStateOf(0) }
    var start by remember { mutableStateOf(Offset.Zero) };var last by remember { mutableStateOf(Offset.Zero) }
    var downTime by remember { mutableLongStateOf(0L) };var held by remember { mutableLongStateOf(0L) }
    var intervals by remember { mutableStateOf(listOf<Long>()) };var previous by remember { mutableLongStateOf(0L) }
    var dimensions by remember { mutableStateOf(Offset(1f,1f)) }
    var positions by remember { mutableStateOf(listOf<Offset>()) }
    val test=d.parameters.getValue("test")
    Canvas(modifier.fillMaxWidth().background(Color(0xFF102A25)).pointerInteropFilter { event ->
        pointers=maxOf(pointers,event.pointerCount)
        if(previous>0&&event.eventTime>previous)intervals=(intervals+(event.eventTime-previous)).takeLast(300)
        previous=event.eventTime
        for(index in 0 until event.pointerCount) {
            val x=(event.getX(index)/dimensions.x*6).toInt().coerceIn(0,5);val y=(event.getY(index)/dimensions.y*8).toInt().coerceIn(0,7)
            cells=cells+(y*6+x)
        }
        last=Offset(event.x,event.y)
        positions=(positions+last).takeLast(150)
        if(event.actionMasked==MotionEvent.ACTION_DOWN) { start=last;downTime=event.eventTime;taps++ }
        if(event.actionMasked==MotionEvent.ACTION_MOVE)moves++
        if(event.actionMasked==MotionEvent.ACTION_UP)held=maxOf(held,event.eventTime-downTime)
        val dx=abs(last.x-start.x)/dimensions.x;val dy=abs(last.y-start.y)/dimensions.y
        val edge=(0 until 48).filter { it%6==0||it%6==5||it/6==0||it/6==7 }
        val passed=when(test) {"single"->taps>0;"grid"->cells.size==48;"edges"->cells.containsAll(edge);"corners"->cells.containsAll(listOf(0,5,42,47));"multitouch"->pointers>=2;"three_finger"->pointers>=3;"horizontal"->dx>0.7;"vertical"->dy>0.7;"diagonal"->dx>0.6&&dy>0.6;"long_press"->held>=700;"rapid_tap"->taps>=5;"drag"->moves>=15&&(dx>0.6||dy>0.6);"interval"->intervals.size>=15;"jitter"->moves>=15;else->false}
        val meanX=positions.map { it.x }.average();val meanY=positions.map { it.y }.average()
        val rms=sqrt(positions.map { (it.x-meanX).pow(2)+(it.y-meanY).pow(2) }.average())
        onEvidence(mapOf("covered_cells" to cells.size.toString(),"total_cells" to "48","coverage_percent" to (cells.size*100.0/48).toString(),"max_observed_pointers" to pointers.toString(),"tap_count" to taps.toString(),"move_events" to moves.toString(),"longest_press_ms" to held.toString(),"observed_event_interval_ms" to (if(intervals.isEmpty())"unavailable" else intervals.average().toString()),"observed_position_rms_px" to rms.toString(),"position_variation_limit" to "Includes intentional finger movement; not calibrated hardware jitter","test_area" to "App interaction surface; system gesture inset excluded"),passed)
        true
    }) {
        dimensions=Offset(size.width,size.height)
        val w=size.width/6;val h=size.height/8
        for(i in 0 until 48)drawRect(if(i in cells)Color(0xFF6AD8AE) else Color(0xFF26483F),topLeft=Offset(i%6*w+2,i/6*h+2),size=androidx.compose.ui.geometry.Size(w-4,h-4))
        drawCircle(Color.White,12f,last)
    }
    Text(when(test) {"multitouch","three_finger"->"$pointers fingers detected";"grid"->"${cells.size} of 48 squares filled";"rapid_tap"->"$taps of 5 taps";else->"Touch the area above"},Modifier.padding(12.dp))
}

fun authenticateBiometric(activity:FragmentActivity,d:DiagnosticDefinition,engine:DiagnosticsEngine,onResult:(DiagnosticResult)->Unit) {
    val allowed=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    val availability=BiometricManager.from(activity).canAuthenticate(allowed)
    if(availability!=BiometricManager.BIOMETRIC_SUCCESS) {
        onResult(engine.result(d,if(availability==BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)DiagnosticStatus.NOT_SUPPORTED else DiagnosticStatus.NOT_ACCESSIBLE,emptyMap(),"Biometric unavailable or not enrolled (code $availability)"));return
    }
    val started=Instant.now().toString()
    val prompt=BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),object:BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) { onResult(engine.result(d,DiagnosticStatus.PASS,mapOf("authentication_type" to result.authenticationType.toString()),"Real BiometricPrompt authentication succeeded",started)) }
        override fun onAuthenticationError(code:Int,text:CharSequence) { onResult(engine.result(d,if(code==BiometricPrompt.ERROR_NEGATIVE_BUTTON||code==BiometricPrompt.ERROR_USER_CANCELED)DiagnosticStatus.SKIPPED_BY_USER else DiagnosticStatus.INCONCLUSIVE,emptyMap(),"Biometric authentication ended: $text",started)) }
    })
    prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("PhoneProof biometric check").setSubtitle("Authenticate to test the enrolled biometric").setAllowedAuthenticators(allowed).setNegativeButtonText("Skip").build())
}
