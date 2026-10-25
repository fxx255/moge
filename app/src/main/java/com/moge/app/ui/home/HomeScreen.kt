package com.moge.app.ui.home

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.ui.capture.CameraViewfinder
import com.moge.app.ui.capture.CaptureBatch
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.ui.capture.ConfirmSheet
import com.moge.app.ui.capture.LevelIndicator
import com.moge.app.ui.capture.ViewfinderController
import com.moge.app.ui.capture.cameraIconRotation
import com.moge.app.ui.capture.rememberCameraIconRotation
import com.moge.app.ui.capture.rememberDisplayRotation
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.components.paperCard
import com.moge.app.ui.photo.PhotoCropDialog
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.viewer.PhotoViewer
import kotlinx.coroutines.delay
import java.io.File

/**
 * 独立拍照路由：拍照 / 选图 → 逐张裁剪 → 使用照片，通过 [onStartSolve] 交回调用方。
 * [onAskByText] 用作返回对话；保留其余导航参数以兼容现有路由。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HomeScreen(
    onOpenNotebook: () -> Unit,
    onOpenSettings: () -> Unit,
    onAskByText: () -> Unit,
    onStartSolve: (CaptureBatch) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val strings = rememberAppStrings()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val controller = remember { ViewfinderController() }
    var viewer by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    val cameraPrefs = remember(context) { context.getSharedPreferences("capture_permissions", android.content.Context.MODE_PRIVATE) }
    var askedOnce by rememberSaveable { mutableStateOf(cameraPrefs.getBoolean("camera_requested", false)) }
    var levelEnabled by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        cameraGranted = hasCameraPermission(context)
        vm.refreshModels()
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        askedOnce = true
        cameraPrefs.edit().putBoolean("camera_requested", true).apply()
    }
    fun grantCamera() {
        if (askedOnce) openAppSettings(context) else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // 系统相机兜底；目标路径跨 Activity 重建保存，返回后仍进入原裁剪队列。
    var pendingSystemShot by rememberSaveable { mutableStateOf<String?>(null) }
    val systemCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingSystemShot
        pendingSystemShot = null
        if (path == null) return@rememberLauncherForActivityResult
        val file = File(path)
        if (ok && file.length() > 0) vm.onCaptured(path) else file.delete()
    }
    fun launchSystemCamera() {
        if (!state.canAddMore) {
            vm.showMessage(strings.text(R.string.ui_up_to_1_s_photos_84e2a, CaptureStore.MAX_PHOTOS))
            return
        }
        val target = vm.newCameraFile()
        pendingSystemShot = target.absolutePath
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            systemCamera.launch(uri)
        }.onFailure {
            pendingSystemShot = null
            target.delete()
            vm.showMessage(strings.text(R.string.ui_no_camera_app_available_f648a))
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(CaptureStore.MAX_PHOTOS),
    ) { uris -> vm.onPicked(uris) }
    fun openGallery() {
        if (!state.canAddMore) {
            vm.showMessage(strings.text(R.string.ui_up_to_1_s_photos_84e2a, CaptureStore.MAX_PHOTOS))
            return
        }
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun shoot() {
        when {
            !state.canAddMore -> vm.showMessage(strings.text(R.string.ui_up_to_1_s_photos_84e2a, CaptureStore.MAX_PHOTOS))
            !cameraGranted -> grantCamera()
            controller.ready -> controller.takePicture(
                context,
                vm.newCameraFile(),
                onSaved = { vm.onCaptured(it.absolutePath) },
                onError = vm::showMessage,
            )
            controller.failed -> launchSystemCamera()
        }
    }

    val cropping = state.cropQueue.isNotEmpty() || editingPhoto?.let { it in state.photos } == true
    val useViewfinder = cameraGranted && !controller.failed
    val cameraActive = useViewfinder && !cropping
    val backdrop = if (useViewfinder) Color.Black else MaterialTheme.colorScheme.background
    CameraLayout(
        modifier = Modifier.fillMaxSize().background(backdrop).safeDrawingPadding(),
        preview = {
            if (useViewfinder) {
                // 裁剪时移除预览：DisposableEffect 解绑 CameraX 并停止方向监听，底层只留黑色背景。
                if (cameraActive) {
                    CameraViewfinder(controller, Modifier.fillMaxSize())
                    if (levelEnabled) LevelIndicator(Modifier.align(Alignment.Center))
                }
            } else {
                GridPaper(Modifier.fillMaxSize()) { }
                Box(Modifier.fillMaxSize().padding(top = 56.dp, bottom = 32.dp), contentAlignment = Alignment.Center) {
                    CameraUnavailable(
                        cameraFailed = cameraGranted && controller.failed,
                        onGrant = ::grantCamera,
                        onSystemCamera = ::launchSystemCamera,
                    )
                }
            }
            IconButton(
                onClick = onAskByText,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                    .background(if (useViewfinder) Color.Black.copy(alpha = 0.35f) else Color.Transparent, CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, strings.text(R.string.ui_back_to_conversation_185d6), tint = if (useViewfinder) Color.White else MaterialTheme.colorScheme.onBackground)
            }
            if (useViewfinder && state.message == null) {
                Text(
                    strings.text(R.string.ui_take_a_photo_fd362),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            state.message?.let {
                Box(Modifier.align(Alignment.BottomCenter)) { Notice(it, useViewfinder, vm::dismissMessage) }
            }
        },
        controls = { sideControls ->
            CameraControls(
                count = state.photos.size,
                controller = controller,
                onDark = useViewfinder,
                sideControls = sideControls,
                levelEnabled = levelEnabled,
                onLevel = { levelEnabled = it },
                shutterEnabled = !cropping && !controller.busy && (!cameraGranted || controller.ready || controller.failed),
                onGallery = ::openGallery,
                onShutter = ::shoot,
                onOpenStack = vm::openConfirm,
                cameraActive = cameraActive,
            )
        },
    )

    state.cropQueue.firstOrNull()?.let { path ->
        PhotoCropDialog(
            path = path,
            onCropped = vm::onCropDone,
            onDismiss = vm::onRetake,
            dismissLabel = strings.text(R.string.ui_retake_41c37),
            onCropStarted = { vm.onPhotoCropStarted(path) },
            onCropClosed = { vm.onPhotoCropClosed(path) },
            saveCrop = { vm.savePhotoCrop(path, it) },
        )
    }
    if (state.confirmOpen && state.cropQueue.isEmpty() && state.photos.isNotEmpty() && editingPhoto == null) {
        ConfirmSheet(
            photos = state.photos,
            note = state.note,
            canAddMore = state.canAddMore,
            onNoteChange = vm::setNote,
            onRemove = vm::removePhoto,
            onOpenPhoto = { viewer = it },
            onCropPhoto = { editingPhoto = state.photos.getOrNull(it) },
            onAddMore = vm::closeConfirm,
            onStart = { vm.takeBatch()?.let(onStartSolve) },
            onDismiss = vm::closeConfirm,
        )
    }
    editingPhoto?.takeIf { it in state.photos }?.let { path ->
        PhotoCropDialog(
            path = path,
            onCropped = { vm.onPhotoRecropped(path); editingPhoto = null },
            onDismiss = { editingPhoto = null },
            onCropStarted = { vm.onPhotoCropStarted(path) },
            onCropClosed = { vm.onPhotoCropClosed(path) },
            saveCrop = { vm.savePhotoCrop(path, it) },
        )
    }
    viewer?.let { index ->
        PhotoViewer(paths = state.photos, initialIndex = index, onDismiss = { viewer = null }, preferProcessed = false)
    }
}

/** 按实际可用窗口排布；同一个预览节点只改变尺寸，竖横切换不卸载 CameraX。 */
@Composable
internal fun CameraLayout(
    modifier: Modifier = Modifier,
    preview: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    controls: @Composable (sideControls: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val sideControls = maxWidth > maxHeight || (maxHeight < 480.dp && maxWidth >= 360.dp)
        Layout(
            content = {
                Box(Modifier.fillMaxSize(), content = preview)
                Box { controls(sideControls) }
            },
            modifier = Modifier.fillMaxSize(),
        ) { children, constraints ->
            val controlConstraints = if (sideControls) {
                Constraints.fixed(minOf(120.dp.roundToPx(), constraints.maxWidth / 3), constraints.maxHeight)
            } else {
                Constraints(
                    minWidth = constraints.maxWidth,
                    maxWidth = constraints.maxWidth,
                    maxHeight = constraints.maxHeight / 2,
                )
            }
            val panel = children[1].measure(controlConstraints)
            val viewfinder = children[0].measure(Constraints.fixed(
                constraints.maxWidth - if (sideControls) panel.width else 0,
                constraints.maxHeight - if (sideControls) 0 else panel.height,
            ))
            layout(constraints.maxWidth, constraints.maxHeight) {
                viewfinder.place(0, 0)
                panel.place(if (sideControls) viewfinder.width else 0, if (sideControls) 0 else viewfinder.height)
            }
        }
    }
}

private fun hasCameraPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 未授权或预览失败时仍可选图、授权、使用系统相机并返回对话。 */
@Composable
internal fun CameraUnavailable(cameraFailed: Boolean, onGrant: () -> Unit, onSystemCamera: () -> Unit) {
    val strings = rememberAppStrings()
    Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .paperCard(MaterialTheme.colorScheme.surface, MogeTheme.paper.cardStroke).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (cameraFailed) strings.text(R.string.ui_could_not_open_viewfinder_704fd) else strings.text(R.string.ui_camera_permission_needed_30a59), style = MaterialTheme.typography.titleMedium)
        Text(
            if (cameraFailed) strings.text(R.string.ui_use_the_system_camera_or_choose_photos_from_your_gallery_41176) else strings.text(R.string.ui_allow_camera_access_to_take_photos_or_choose_from_your_galle_b743d),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!cameraFailed) {
            OutlinedButton(onClick = onGrant, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(R.string.ui_allow_camera_1a535)) }
        }
        TextButton(onClick = onSystemCamera, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(strings.text(R.string.ui_use_system_camera_cf162))
        }
    }
}

@Composable
private fun Notice(text: String, onDark: Boolean, onTimeout: () -> Unit) {
    LaunchedEffect(text) {
        delay(3_500)
        onTimeout()
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (onDark) Color.White else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
internal fun CameraControls(
    count: Int,
    controller: ViewfinderController,
    onDark: Boolean,
    sideControls: Boolean,
    levelEnabled: Boolean,
    onLevel: (Boolean) -> Unit,
    shutterEnabled: Boolean,
    onGallery: () -> Unit,
    onShutter: () -> Unit,
    onOpenStack: () -> Unit,
    cameraActive: Boolean = true,
) {
    val strings = rememberAppStrings()
    val tint = if (onDark) Color.White else MaterialTheme.colorScheme.onBackground
    // Animate the actual camera target; compensate canvas changes when the window rotates too.
    val displayRotation = rememberDisplayRotation()
    val iconRotation = rememberCameraIconRotation(
        targetRotation = controller.targetRotation,
        displayRotation = displayRotation,
        enabled = cameraActive,
    )
    val flashAndLevel: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.Center) {
            IconButton(enabled = controller.ready, onClick = { controller.setFlash(!controller.torchOn) }) {
                Icon(if (controller.torchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    if (controller.torchOn) strings.text(R.string.ui_turn_flash_off_e5910) else strings.text(R.string.ui_turn_flash_on_56bb6), tint = tint,
                    modifier = Modifier.cameraIconRotation(iconRotation))
            }
            IconToggleButton(checked = levelEnabled, onCheckedChange = onLevel) {
                Icon(Icons.Outlined.Straighten, if (levelEnabled) strings.text(R.string.ui_hide_level_4f356) else strings.text(R.string.ui_show_level_068df),
                    tint = if (levelEnabled) Color(0xFFFFE066) else tint,
                    modifier = Modifier.cameraIconRotation(iconRotation))
            }
        }
    }
    val gallery: @Composable () -> Unit = {
        IconButton(onClick = onGallery, modifier = Modifier.size(if (sideControls) 48.dp else 56.dp)) {
            Icon(Icons.Outlined.PhotoLibrary, strings.text(R.string.ui_gallery_c657c), tint = tint,
                modifier = Modifier.cameraIconRotation(iconRotation))
        }
    }
    val stack: @Composable () -> Unit = {
        BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
            TextButton(onClick = onOpenStack, enabled = count > 0, modifier = Modifier.heightIn(min = 56.dp).widthIn(max = 56.dp)
                .semantics { contentDescription = strings.text(R.string.ui_view_selected_photos_4ba4f) }) {
                Text(strings.text(R.string.ui_confirm_b56d9), color = tint.copy(alpha = if (count > 0) 1f else 0.4f))
            }
        }
    }
    if (sideControls) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactControls = maxHeight < 320.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(8.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                flashAndLevel()
                CameraShutter(shutterEnabled, tint, onShutter)
                if (compactControls) {
                    Row(verticalAlignment = Alignment.CenterVertically) { gallery(); stack() }
                } else {
                    gallery()
                    stack()
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            flashAndLevel()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                gallery()
                CameraShutter(shutterEnabled, tint, onShutter)
                stack()
            }
        }
    }
}

/** 外环 + 实心圆；无文字快门，80dp 触控区与独立无障碍描述。 */
@Composable
internal fun CameraShutter(enabled: Boolean, tint: Color, onClick: () -> Unit) {
    val strings = rememberAppStrings()
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(80.dp).semantics { contentDescription = strings.text(R.string.ui_take_photo_3da4b) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val color = tint.copy(alpha = if (enabled) 1f else 0.4f)
            val stroke = 3.dp.toPx()
            val radius = size.minDimension / 2f - stroke
            drawCircle(color, radius, style = Stroke(stroke))
            drawCircle(color, radius - 8.dp.toPx())
        }
    }
}
