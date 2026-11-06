package com.moge.app.ui.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings
import java.io.File

internal class SystemCameraLauncher(
    val cameraGranted: Boolean,
    val pending: Boolean,
    val requestPermission: () -> Unit,
    val launch: () -> Unit,
)

/** Activity results and permission handling only; the caller owns photos and the surrounding UI. */
@Composable
internal fun rememberSystemCameraLauncher(
    createFile: () -> File,
    onCaptured: (String) -> Unit,
    onMessage: (String) -> Unit,
    onCancelled: () -> Unit = {},
    canLaunch: () -> Boolean = { true },
): SystemCameraLauncher {
    val context = LocalContext.current
    val strings = rememberAppStrings()
    val currentCreateFile by rememberUpdatedState(createFile)
    val currentCaptured by rememberUpdatedState(onCaptured)
    val currentMessage by rememberUpdatedState(onMessage)
    val currentCancelled by rememberUpdatedState(onCancelled)
    val currentCanLaunch by rememberUpdatedState(canLaunch)
    val preferences = remember(context) { context.getSharedPreferences("capture_permissions", Context.MODE_PRIVATE) }
    var askedOnce by rememberSaveable { mutableStateOf(preferences.getBoolean("camera_requested", false)) }
    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var awaitingPermission by rememberSaveable { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { cameraGranted = hasCameraPermission(context) }

    fun permissionResult(granted: Boolean) {
        cameraGranted = granted
        if (!granted && awaitingPermission) {
            awaitingPermission = false
            currentMessage(strings.text(R.string.ui_camera_permission_needed_30a59))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedOnce = true
        preferences.edit().putBoolean("camera_requested", true).apply()
        permissionResult(granted)
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionResult(hasCameraPermission(context))
    }
    fun requestPermission() {
        runCatching {
            if (askedOnce) settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
            else permission.launch(Manifest.permission.CAMERA)
        }.onFailure {
            awaitingPermission = false
            currentMessage(strings.text(R.string.ui_camera_permission_needed_30a59))
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPath
        pendingPath = null
        if (path == null) return@rememberLauncherForActivityResult
        val file = File(path)
        if (ok && file.length() > 0) currentCaptured(path)
        else {
            file.delete()
            currentCancelled()
        }
    }
    fun launch() {
        if (pendingPath != null || awaitingPermission || !currentCanLaunch()) return
        // Declaring CAMERA for the embedded viewfinder also requires its grant for IMAGE_CAPTURE.
        if (!cameraGranted) {
            awaitingPermission = true
            requestPermission()
            return
        }
        val file = currentCreateFile()
        pendingPath = file.absolutePath
        runCatching {
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
        }.onFailure {
            pendingPath = null
            file.delete()
            currentMessage(strings.text(R.string.ui_no_camera_app_available_f648a))
        }
    }
    LaunchedEffect(cameraGranted, awaitingPermission) {
        if (cameraGranted && awaitingPermission) {
            awaitingPermission = false
            launch()
        }
    }
    return SystemCameraLauncher(cameraGranted, pendingPath != null || awaitingPermission, ::requestPermission, ::launch)
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
