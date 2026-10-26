package com.moge.app.ui.capture

import com.moge.app.R

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moge.app.ui.localization.rememberAppStrings
import java.io.File

/**
 * 取景框的控制句柄：快门、闪光灯，以及相机是否就绪 / 出错。
 * 出错（部分 ROM 预览黑屏、没有后置摄像头）时界面降级到系统相机。
 */
@Stable
class ViewfinderController {
    internal var imageCapture: ImageCapture? = null
    var ready by mutableStateOf(false)
        internal set
    var failed by mutableStateOf(false)
        internal set
    var torchOn by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    /**
     * CameraX 实际生效的目标方向（[Preview] 与 [ImageCapture] 同源，含屏幕锁定时的
     * 传感器补偿）。取景页用它驱动操作图标的平滑转动，保证图标和照片方向一致。
     */
    var targetRotation by mutableIntStateOf(Surface.ROTATION_0)
        internal set

    fun setFlash(on: Boolean) {
        torchOn = on
        imageCapture?.flashMode = if (on) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    /** 拍一张写到 [target]；连拍时上一张没写完不接受下一次快门。 */
    fun takePicture(context: android.content.Context, target: File, onSaved: (File) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture ?: return onError(context.getString(R.string.ui_camera_is_not_ready_1ade9))
        if (busy) return
        busy = true
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    busy = false
                    onSaved(target)
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    target.delete()
                    Log.w(TAG, "takePicture failed", exception)
                    onError(context.getString(R.string.ui_could_not_take_photo_1_s_03a31, exception.message ?: exception.imageCaptureError))
                }
            },
        )
    }

    private companion object {
        const val TAG = "Viewfinder"
    }
}

/** 把设备方向转成 CameraX 的目标方向；左右横屏分别为 270 / 90，不能仅看 Configuration。 */
internal fun cameraTargetRotation(orientation: Int): Int? = when (orientation) {
    in 0..44, in 315..359 -> Surface.ROTATION_0
    in 45..134 -> Surface.ROTATION_270
    in 135..224 -> Surface.ROTATION_180
    in 225..314 -> Surface.ROTATION_90
    else -> null // 平放 / 传感器尚无有效值时保留上一次方向。
}

/** CameraX 预览。只在已授予相机权限时调用；方向更新不重新绑定用例。 */
@Composable
fun CameraViewfinder(controller: ViewfinderController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val metering = remember(previewView) { CameraTapMetering(ContextCompat.getMainExecutor(context)) }
    val zoom = remember(previewView) { CameraPreviewZoom(ContextCompat.getMainExecutor(context)) }
    val strings = rememberAppStrings()
    val focusLabel = strings.text(R.string.ui_camera_focus_and_meter)

    DisposableEffect(lifecycleOwner, previewView, controller) {
        val future = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var capture: ImageCapture? = null
        var camera: Camera? = null
        var sensorRotation: Int? = null
        var targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        controller.targetRotation = targetRotation
        controller.ready = false

        fun updateRotation(rotation: Int) {
            if (disposed || rotation == targetRotation) return
            metering.reset()
            targetRotation = rotation
            controller.targetRotation = rotation
            preview?.targetRotation = rotation
            capture?.targetRotation = rotation
        }
        fun updateDisplayRotation() {
            // 有传感器读数时也支持锁定方向 / 180° 翻转；无传感器时跟随当前窗口的显示。
            updateRotation(sensorRotation ?: previewView.display?.rotation ?: targetRotation)
        }
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = stableCameraTargetRotation(orientation, targetRotation) ?: return
                sensorRotation = rotation
                updateRotation(rotation)
            }
        }
        // 方向传感器只在页面前台时监听；重复的 resume / 旋转不会重复注册。
        var listening = false
        fun startOrientation() {
            if (!listening && orientationListener.canDetectOrientation()) {
                orientationListener.enable()
                listening = true
            }
        }
        fun stopOrientation() {
            if (listening) {
                orientationListener.disable()
                listening = false
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    updateDisplayRotation()
                    startOrientation()
                }
                Lifecycle.Event.ON_PAUSE -> { stopOrientation(); metering.reset(); zoom.reset() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startOrientation()
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (previewView.display?.displayId == displayId) updateDisplayRotation()
            }
        }
        displayManager.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = updateDisplayRotation()
            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        previewView.addOnAttachStateChangeListener(attachListener)

        future.addListener({
            // Provider 初始化是异步的；页面已退出时绝不能重新打开相机。
            if (disposed || lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
            runCatching {
                val cameraProvider = future.get().also { provider = it }
                updateDisplayRotation()
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val boundPreview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(targetRotation)
                    .build().also { it.surfaceProvider = previewView.surfaceProvider; preview = it }
                // 相同传感器比例；照片写 EXIF，裁剪器 decodeUprightPhoto 转正后计算坐标。
                val boundCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(targetRotation)
                    .setFlashMode(if (controller.torchOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .build().also { capture = it }
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, boundPreview, boundCapture)
                    .also(metering::attach)
                    .also(zoom::attach)
                controller.imageCapture = boundCapture
                controller.ready = true
            }.onFailure {
                Log.w("Viewfinder", "bind camera failed", it)
                controller.failed = true
            }
        }, executor)
        onDispose {
            disposed = true
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            stopOrientation()
            displayManager.unregisterDisplayListener(displayListener)
            previewView.removeOnAttachStateChangeListener(attachListener)
            metering.detach(camera)
            zoom.detach(camera)
            if (controller.imageCapture === capture) {
                controller.imageCapture = null
                controller.ready = false
            }
            // 仅解绑本预览持有的用例，避免影响随后打开的新相机路由。
            runCatching { provider?.unbind(*listOfNotNull<UseCase>(preview, capture).toTypedArray()) }
        }
    }

    DisposableEffect(previewView, metering, zoom, focusLabel, lifecycleOwner, controller) {
        fun canControlCamera() = controller.ready && !controller.busy && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val uninstall = installCameraPreviewGestures(previewView, focusLabel,
            onTap = { position ->
                if (canControlCamera()) metering.focusAt(previewView.meteringPointFactory, position)
            },
            onZoomStart = {
                if (canControlCamera()) { metering.reset(); zoom.beginGesture() }
            },
            onZoom = { factor -> if (canControlCamera()) zoom.scaleBy(factor) },
        )
        onDispose { uninstall() }
    }
    Box(modifier.clipToBounds().onSizeChanged { metering.reset() }) {
        AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize().testTag("camera-preview"))
        CameraFocusIndicator(metering.target, metering::dismiss, Modifier.matchParentSize())
    }
}
