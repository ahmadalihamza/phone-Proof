package pk.phoneproof.app.ui

import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@Composable
fun CameraPanel(cameraId:String?=null,onCapture:(File)->Unit,onPreview:(Boolean)->Unit={},onError:(String)->Unit={},onTorch:((Boolean)->Unit)?=null,showCapture:Boolean=true,onTorchUnavailable:()->Unit={}) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    val executor=ContextCompat.getMainExecutor(context)
    val previewView=remember { PreviewView(context).apply { scaleType=PreviewView.ScaleType.FIT_CENTER } }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }
    val currentPreview by rememberUpdatedState(onPreview)
    val currentError by rememberUpdatedState(onError)
    val currentTorch by rememberUpdatedState(onTorch)
    val currentUnsupported by rememberUpdatedState(onTorchUnavailable)
    DisposableEffect(cameraId,lifecycle) {
        val future=ProcessCameraProvider.getInstance(context)
        var bound:ProcessCameraProvider?=null
        var disposed=false
        var ownedPreview:Preview?=null
        var ownedCapture:ImageCapture?=null
        var ownedCamera:Camera?=null
        capture=null;camera=null;error=null;torch=false
        val observer=androidx.lifecycle.Observer<PreviewView.StreamState> { currentPreview(it==PreviewView.StreamState.STREAMING) }
        val torchObserver=androidx.lifecycle.Observer<Int> { state -> torch=state==TorchState.ON;currentTorch?.invoke(torch) }
        previewView.previewStreamState.observe(lifecycle,observer)
        future.addListener({
            if(!disposed)try {
                val provider=future.get();bound=provider
                val selector=if(cameraId==null)CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.Builder().addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId==cameraId } }.build()
                val preview=Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val photo=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                ownedPreview=preview;ownedCapture=photo
                camera=provider.bindToLifecycle(lifecycle,selector,preview,photo);ownedCamera=camera;capture=photo
                camera?.cameraInfo?.torchState?.observe(lifecycle,torchObserver)
                if(currentTorch!=null&&camera?.cameraInfo?.hasFlashUnit()==false)currentUnsupported()
            } catch(e:Exception) { error="Can't open this camera. Close and try again.";currentError(error!!) }
        },executor)
        onDispose {
            disposed=true
            ownedCamera?.cameraInfo?.torchState?.removeObserver(torchObserver)
            ownedCamera?.cameraControl?.enableTorch(false)
            val useCases=listOfNotNull(ownedPreview,ownedCapture).toTypedArray()
            if(useCases.isNotEmpty())bound?.unbind(*useCases)
            previewView.previewStreamState.removeObserver(observer)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).heightIn(min=160.dp)) {
            AndroidView(factory={previewView},modifier=Modifier.fillMaxSize())
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(32.dp)) {
                drawRect(androidx.compose.ui.graphics.Color.White.copy(alpha=0.7f),style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(showCapture)Button(enabled=capture!=null&&!taking,onClick={
                val photo=capture?:return@Button
                val directory=File(context.filesDir,"captures").apply { mkdirs() }
                val file=File(directory,"${UUID.randomUUID()}.jpg")
                taking=true
                photo.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),executor,object:ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output:ImageCapture.OutputFileResults) { taking=false;onCapture(file) }
                    override fun onError(exception:ImageCaptureException) { taking=false;file.delete();error="Photo failed. Try again.";currentError(error!!) }
                })
            },modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text(if(taking)"Taking photo…" else "Take photo") }
            if(onTorch!=null)OutlinedButton(enabled=camera?.cameraInfo?.hasFlashUnit()==true,onClick={
                val next=!torch
                val command=camera?.cameraControl?.enableTorch(next)
                command?.addListener({ try { command.get() } catch(e:Exception) { error="Flash didn't switch. Try again.";currentError(error!!) } },executor)
            },modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text(if(torch)"Light off" else "Light on") }
        }
    }
}
