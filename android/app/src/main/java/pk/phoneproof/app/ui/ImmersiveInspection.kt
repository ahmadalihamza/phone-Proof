package pk.phoneproof.app.ui

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A dedicated window means inspection flags never leak into the normal app window. */
@Composable
internal fun ImmersiveInspection(
    tag: String,
    brightness: Float? = null,
    onBack: () -> Unit,
    onInterrupted: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val currentBack by rememberUpdatedState(onBack)
    val currentInterrupted by rememberUpdatedState(onInterrupted)
    Dialog(onDismissRequest = { currentBack() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        val view = LocalView.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val window = (view.parent as DialogWindowProvider).window
        val desiredBrightness by rememberUpdatedState(brightness)
        DisposableEffect(window, view, lifecycle) {
            val controller = WindowCompat.getInsetsController(window, view)
            val originalBrightness = window.attributes.screenBrightness
            val originalCutout = window.attributes.layoutInDisplayCutoutMode
            val originalKeepOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            val originalBehavior = controller.systemBarsBehavior
            val insets = ViewCompat.getRootWindowInsets(view)
            val statusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
            val navigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
            fun restore() {
                window.attributes = window.attributes.apply {
                    screenBrightness = originalBrightness
                    layoutInDisplayCutoutMode = originalCutout
                }
                if(!originalKeepOn)window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller.systemBarsBehavior = originalBehavior
                if(statusVisible)controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
                if(navigationVisible)controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
            }
            fun enter() {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = if(Build.VERSION.SDK_INT >= 30)WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    screenBrightness = desiredBrightness ?: originalBrightness
                }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                ViewCompat.requestApplyInsets(view)
            }
            val observer = LifecycleEventObserver { _, event ->
                when(event) {
                    Lifecycle.Event.ON_RESUME -> enter()
                    Lifecycle.Event.ON_PAUSE -> { restore(); currentInterrupted() }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            enter()
            onDispose { lifecycle.removeObserver(observer); restore() }
        }
        SideEffect {
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                window.attributes = window.attributes.apply { screenBrightness = brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
        BackHandler { currentBack() }
        Box(Modifier.fillMaxSize().testTag(tag), content = content)
    }
}
